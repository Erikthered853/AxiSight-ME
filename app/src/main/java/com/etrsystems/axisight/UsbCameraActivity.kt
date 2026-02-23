package com.etrsystems.axisight

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.etrsystems.axisight.ui.UvcFragment

class UsbCameraActivity : AppCompatActivity() {

    private var usbPermissionReceiverRegistered = false
    private var usbRecoveryReceiverRegistered = false
    private var usbAttachReceiverRegistered = false
    private var pendingUsbDeviceName: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbPermissionAction by lazy { "${BuildConfig.APPLICATION_ID}.USB_PERMISSION" }

    private var cameraOpened = false
    private var openRetryCount = 0
    private var openWatchdog: Runnable? = null
    private var pendingRetry: Runnable? = null
    private var activeSessionId = 0L
    private var retryGeneration = 0L
    private var permissionRequestInFlight = false
    private var lastPermissionRequestMs = 0L
    private var permissionWatchdog: Runnable? = null
    private var permissionCooldownRetry: Runnable? = null
    private var attachDebounce: Runnable? = null
    private lateinit var statusOverlay: View
    private lateinit var statusText: TextView
    private lateinit var statusProgress: ProgressBar

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != usbPermissionAction) return

            clearPermissionWatchdog()
            permissionRequestInFlight = false
            val device = getUsbDeviceExtra(intent)
            val expected = pendingUsbDeviceName
            if (expected != null && device?.deviceName != expected) {
                Log.w(
                    TAG,
                    "Ignoring stale USB permission callback. expected=$expected actual=${device?.deviceName}"
                )
                pendingUsbDeviceName = null
                showStatus("USB device changed. Reconnecting...")
                mainHandler.postDelayed(
                    { startUsbFlow(force = true) },
                    USB_ATTACH_SETTLE_MS
                )
                return
            }
            pendingUsbDeviceName = null
            clearPermissionCooldownRetry()

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted || device == null) {
                Log.w(TAG, "USB permission denied or device missing")
                showStatus("USB permission denied. Retrying...")
                Toast.makeText(
                    this@UsbCameraActivity,
                    "USB permission denied. Retrying...",
                    Toast.LENGTH_SHORT
                ).show()
                scheduleRetry("Permission denied")
                return
            }

            Log.i(TAG, "USB permission granted; waiting for device stabilization")
            showStatus("USB permission granted. Opening camera...")
            mainHandler.postDelayed(
                { startUsbFlow(force = true) },
                PERMISSION_POST_GRANT_SETTLE_MS
            )
        }
    }

    private val usbMonitorRecoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AxisightApp.ACTION_USB_MONITOR_RACE) return
            if (permissionRequestInFlight) {
                Log.w(TAG, "USB monitor race while permission prompt is active; waiting for result")
                return
            }
            Log.w(TAG, "Received USB monitor race broadcast")
            scheduleRetry("USB monitor race broadcast")
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (permissionRequestInFlight) {
                        Log.i(TAG, "USB attach observed while waiting for permission callback")
                        showStatus("USB camera reconnected. Confirm USB permission prompt...")
                        return
                    }
                    Log.i(TAG, "USB device attached; restarting USB flow")
                    showStatus("USB camera attached. Reconnecting...")
                    openRetryCount = 0
                    attachDebounce?.let { mainHandler.removeCallbacks(it) }
                    val restart = Runnable {
                        attachDebounce = null
                        if (!isFinishing && !isDestroyed) {
                            startUsbFlow(force = true)
                        }
                    }
                    attachDebounce = restart
                    mainHandler.postDelayed(restart, USB_ATTACH_SETTLE_MS)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.w(TAG, "USB device detached")
                    showStatus("USB camera disconnected. Waiting for reconnect...")
                    cameraOpened = false
                    clearPermissionRequestState()
                    clearPermissionCooldownRetry()
                    cancelOpenWatchdog()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usb_camera)
        statusOverlay = findViewById(R.id.usb_status_overlay)
        statusText = findViewById(R.id.usb_status_text)
        statusProgress = findViewById(R.id.usb_status_progress)
        showStatus("Connecting USB camera...")
        registerUsbMonitorRecoveryReceiverIfNeeded()
        registerUsbAttachReceiverIfNeeded()
        startUsbFlow()
    }

    override fun onDestroy() {
        cancelOpenWatchdog()
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingRetry = null
        attachDebounce?.let { mainHandler.removeCallbacks(it) }
        attachDebounce = null
        clearPermissionRequestState()
        clearPermissionCooldownRetry()
        unregisterUsbPermissionReceiverIfNeeded()
        unregisterUsbMonitorRecoveryReceiverIfNeeded()
        unregisterUsbAttachReceiverIfNeeded()
        super.onDestroy()
    }

    private fun startUsbFlow(force: Boolean = false) {
        if (isFinishing || isDestroyed) return
        if (!force && cameraOpened) {
            Log.d(TAG, "Camera already open; skipping USB flow restart")
            return
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val uvcDevice = usbManager.deviceList.values.firstOrNull { isUvcDevice(it) }
        if (uvcDevice == null) {
            Log.w(TAG, "No USB camera detected")
            showStatus("No USB camera detected. Waiting for reconnect...")
            scheduleRetry("No USB camera detected")
            return
        }

        if (!usbManager.hasPermission(uvcDevice)) {
            showStatus("Waiting for USB permission...")
            val now = SystemClock.elapsedRealtime()
            if (permissionRequestInFlight) {
                Log.i(TAG, "USB permission request already in flight; waiting for callback")
                return
            }
            val elapsed = now - lastPermissionRequestMs
            if (elapsed < USB_PERMISSION_REQUEST_COOLDOWN_MS) {
                Log.i(TAG, "Deferring USB permission re-request to avoid prompt spam")
                val waitMs = USB_PERMISSION_REQUEST_COOLDOWN_MS - elapsed
                schedulePermissionCooldownRetry(waitMs)
                return
            }
            clearPermissionCooldownRetry()
            Log.i(TAG, "Requesting USB permission for ${uvcDevice.deviceName}")
            requestUsbPermission(usbManager, uvcDevice)
            Toast.makeText(this, "Grant USB permission to continue.", Toast.LENGTH_SHORT).show()
            return
        }

        showUvcFragment(uvcDevice)
    }

    private fun showUvcFragment(device: UsbDevice) {
        if (isFinishing || isDestroyed) return

        activeSessionId += 1
        val sessionId = activeSessionId
        cameraOpened = false
        cancelOpenWatchdog()
        showStatus("Opening USB camera...")

        val fragment = UvcFragment.newInstance(
            vendorId = device.vendorId,
            productId = device.productId,
            deviceName = device.deviceName
        )
        fragment.setCameraStateListener(object : UvcFragment.CameraStateListener {
            override fun onUsbCameraOpened() {
                if (sessionId != activeSessionId) return
                cameraOpened = true
                openRetryCount = 0
                retryGeneration += 1
                cancelOpenWatchdog()
                pendingRetry?.let { mainHandler.removeCallbacks(it) }
                pendingRetry = null
                Log.i(TAG, "USB camera opened")
                hideStatus()
            }

            override fun onUsbCameraClosed() {
                if (sessionId != activeSessionId) return
                cameraOpened = false
                showStatus("USB camera closed. Reconnecting...")
                if (!isFinishing && !isDestroyed) {
                    Log.w(TAG, "USB camera closed before activity finish")
                    scheduleRetry("Camera closed unexpectedly")
                }
            }

            override fun onUsbCameraError(message: String?) {
                if (sessionId != activeSessionId) return
                cameraOpened = false
                Log.e(TAG, "USB camera error callback: $message")
                showStatus("USB camera error. Reconnecting...")
                scheduleRetry("Camera error callback: $message")
            }
        })

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commitAllowingStateLoss()

        scheduleOpenWatchdog(sessionId)
    }

    private fun scheduleOpenWatchdog(sessionId: Long) {
        cancelOpenWatchdog()
        val watchdog = Runnable {
            if (isFinishing || isDestroyed || cameraOpened || sessionId != activeSessionId) {
                return@Runnable
            }
            Log.w(TAG, "USB camera did not reach OPENED in time")
            scheduleRetry("Open timeout")
        }
        openWatchdog = watchdog
        mainHandler.postDelayed(watchdog, CAMERA_OPEN_TIMEOUT_MS)
    }

    private fun cancelOpenWatchdog() {
        openWatchdog?.let { mainHandler.removeCallbacks(it) }
        openWatchdog = null
    }

    private fun scheduleRetry(reason: String, immediate: Boolean = false, ignoreLimit: Boolean = false) {
        if (isFinishing || isDestroyed) return
        if (pendingRetry != null) {
            Log.d(TAG, "Retry already scheduled; skipping duplicate. reason=$reason")
            return
        }

        if (!ignoreLimit && openRetryCount >= MAX_USB_OPEN_RETRIES) {
            Log.e(TAG, "USB retries exhausted. reason=$reason")
            showStatus("USB camera failed. Returning to main screen...", spinner = false)
            Toast.makeText(
                this,
                "USB camera could not connect. Replug USB camera and try again.",
                Toast.LENGTH_LONG
            ).show()
            mainHandler.postDelayed({
                setResult(RESULT_CANCELED)
                finish()
            }, 1200L)
            return
        }

        if (!ignoreLimit) {
            openRetryCount += 1
        }
        cancelOpenWatchdog()
        activeSessionId += 1
        cameraOpened = false

        supportFragmentManager.findFragmentById(R.id.fragment_container)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
        }

        val delay = if (immediate) {
            0L
        } else {
            (RETRY_DELAY_MS * openRetryCount.coerceAtLeast(1)).coerceAtMost(RETRY_MAX_DELAY_MS)
        }
        showStatus("Reconnecting USB camera...")
        Log.w(TAG, "Scheduling USB retry #$openRetryCount in ${delay}ms. reason=$reason")
        val generation = retryGeneration

        val retry = Runnable {
            pendingRetry = null
            if (generation != retryGeneration) {
                Log.d(TAG, "Skipping stale retry runnable. reason=$reason")
                return@Runnable
            }
            if (cameraOpened) {
                Log.d(TAG, "Skipping retry because camera is already open. reason=$reason")
                return@Runnable
            }
            startUsbFlow()
        }
        pendingRetry = retry
        mainHandler.postDelayed(retry, delay)
    }

    private fun showStatus(message: String, spinner: Boolean = true) {
        statusText.text = message
        statusOverlay.visibility = View.VISIBLE
        statusProgress.visibility = if (spinner) View.VISIBLE else View.GONE
    }

    private fun hideStatus() {
        statusOverlay.visibility = View.GONE
    }

    private fun schedulePermissionWatchdog(expectedDeviceName: String) {
        clearPermissionWatchdog()
        val watchdog = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            if (!permissionRequestInFlight) return@Runnable
            if (pendingUsbDeviceName != expectedDeviceName) return@Runnable
            Log.w(TAG, "USB permission callback timed out for $expectedDeviceName")
            clearPermissionRequestState()
            lastPermissionRequestMs = 0L
            showStatus("USB permission timed out. Retrying...")
            scheduleRetry("Permission callback timeout", immediate = true, ignoreLimit = true)
        }
        permissionWatchdog = watchdog
        mainHandler.postDelayed(watchdog, USB_PERMISSION_RESULT_TIMEOUT_MS)
    }

    private fun clearPermissionWatchdog() {
        permissionWatchdog?.let { mainHandler.removeCallbacks(it) }
        permissionWatchdog = null
    }

    private fun clearPermissionRequestState() {
        permissionRequestInFlight = false
        pendingUsbDeviceName = null
        clearPermissionWatchdog()
    }

    private fun schedulePermissionCooldownRetry(delayMs: Long) {
        if (isFinishing || isDestroyed) return
        if (permissionCooldownRetry != null) return
        val waitMs = delayMs.coerceAtLeast(200L)
        val retry = Runnable {
            permissionCooldownRetry = null
            if (isFinishing || isDestroyed || cameraOpened) return@Runnable
            startUsbFlow(force = true)
        }
        permissionCooldownRetry = retry
        mainHandler.postDelayed(retry, waitMs)
    }

    private fun clearPermissionCooldownRetry() {
        permissionCooldownRetry?.let { mainHandler.removeCallbacks(it) }
        permissionCooldownRetry = null
    }

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        registerUsbPermissionReceiverIfNeeded()
        pendingUsbDeviceName = device.deviceName
        permissionRequestInFlight = true
        lastPermissionRequestMs = SystemClock.elapsedRealtime()
        schedulePermissionWatchdog(device.deviceName)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            1002,
            Intent(usbPermissionAction).setPackage(packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun registerUsbPermissionReceiverIfNeeded() {
        if (usbPermissionReceiverRegistered) return
        val filter = IntentFilter(usbPermissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
        usbPermissionReceiverRegistered = true
    }

    private fun unregisterUsbPermissionReceiverIfNeeded() {
        if (!usbPermissionReceiverRegistered) return
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered by lifecycle races.
        } finally {
            usbPermissionReceiverRegistered = false
            pendingUsbDeviceName = null
        }
    }

    private fun registerUsbMonitorRecoveryReceiverIfNeeded() {
        if (usbRecoveryReceiverRegistered) return
        val filter = IntentFilter(AxisightApp.ACTION_USB_MONITOR_RACE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbMonitorRecoveryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbMonitorRecoveryReceiver, filter)
        }
        usbRecoveryReceiverRegistered = true
    }

    private fun unregisterUsbMonitorRecoveryReceiverIfNeeded() {
        if (!usbRecoveryReceiverRegistered) return
        try {
            unregisterReceiver(usbMonitorRecoveryReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered by lifecycle races.
        } finally {
            usbRecoveryReceiverRegistered = false
        }
    }

    private fun registerUsbAttachReceiverIfNeeded() {
        if (usbAttachReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbAttachReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbAttachReceiver, filter)
        }
        usbAttachReceiverRegistered = true
    }

    private fun unregisterUsbAttachReceiverIfNeeded() {
        if (!usbAttachReceiverRegistered) return
        try {
            unregisterReceiver(usbAttachReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered by lifecycle races.
        } finally {
            usbAttachReceiverRegistered = false
        }
    }

    private fun getUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "UsbCameraActivity"
        private const val CAMERA_OPEN_TIMEOUT_MS = 5500L
        private const val RETRY_DELAY_MS = 800L
        private const val RETRY_MAX_DELAY_MS = 4000L
        private const val MAX_USB_OPEN_RETRIES = 6
        private const val USB_ATTACH_SETTLE_MS = 700L
        private const val PERMISSION_POST_GRANT_SETTLE_MS = 500L
        private const val USB_PERMISSION_REQUEST_COOLDOWN_MS = 2500L
        private const val USB_PERMISSION_RESULT_TIMEOUT_MS = 12000L
    }
}
