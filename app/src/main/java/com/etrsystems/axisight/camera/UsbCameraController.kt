package com.etrsystems.axisight.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.etrsystems.axisight.AxisightApp
import com.etrsystems.axisight.BuildConfig
import com.etrsystems.axisight.ui.UvcFragment
import com.etrsystems.axisight.usb.UsbDeviceUtils

/**
 * Owns the USB/UVC camera pipeline: device discovery, the runtime USB
 * permission flow (with its broadcast receiver), attach/detach handling, the
 * USBMonitor crash-race recovery signal from [AxisightApp], fragment hosting,
 * and the bounded reconnect/retry state machine.
 *
 * Recovery model:
 *  - Camera-side failures reported by [UvcFragment] (closed unexpectedly, error,
 *    stalled preview, never opened) go through [scheduleFragmentRetry]: exponential
 *    backoff, at most [MAX_USB_FRAGMENT_RETRIES] attempts, then a persistent Retry
 *    affordance via [Host.showRecoveryAction]. The attempt counter only resets when a
 *    camera actually opens, so a camera that keeps failing cannot loop forever.
 *  - Permission denied, or a permission dialog that is never answered within
 *    [USB_PERMISSION_TIMEOUT_MS], does not auto-retry (the operator said no, or is not
 *    there): it surfaces the same Retry affordance so the app is never silently stuck.
 *  - Attach/detach broadcasts only act on UVC devices, and only on the active camera
 *    while one is open (see [UsbDeviceUtils.concernsActiveCamera]).
 *
 * The host activity keeps policy (which source is selected, fragment
 * detection wiring, user-facing UI) behind the [Host] interface.
 */
class UsbCameraController(
    private val activity: AppCompatActivity,
    private val containerView: View,
    private val containerId: Int,
    private val host: Host
) {
    interface Host {
        /** True while USB is the selected source and simulation is off. */
        val isActiveSource: Boolean

        /** Attach the shared FrameAnalyzer + listener to a freshly created fragment. */
        fun configureFragment(fragment: UvcFragment)

        fun showToast(message: String)

        /** Automatic recovery has stopped; surface a persistent affordance that calls [retry]. */
        fun showRecoveryAction(message: String, retry: () -> Unit)

        /** Called after the USB pipeline stops so the host can restore preview surfaces. */
        fun onStopped()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbPermissionAction = "${BuildConfig.APPLICATION_ID}.USB_PERMISSION"

    private var permissionReceiverRegistered = false
    private var recoveryReceiverRegistered = false
    private var attachReceiverRegistered = false
    private var pendingUsbDeviceName: String? = null
    private var permissionRequestInFlight = false
    private var lastPermissionRequestMs = 0L
    private var permissionTimeoutRunnable: Runnable? = null

    /** deviceName of the camera the current fragment was created for; null when stopped. */
    private var activeDeviceName: String? = null

    // Limits automatic reconnect attempts to prevent infinite loops. Reset only when a
    // camera actually opens.
    private var fragmentRetryCount = 0
    private var pendingRetryRunnable: Runnable? = null
    private var pendingRestartRunnable: Runnable? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!host.isActiveSource) return
            if (intent?.action != usbPermissionAction) return

            cancelPermissionTimeout()
            val device = UsbDeviceUtils.getUsbDeviceExtra(intent)
            val expected = pendingUsbDeviceName
            permissionRequestInFlight = false
            if (expected != null && device?.deviceName != expected) {
                pendingUsbDeviceName = null
                Log.w(TAG, "USB permission callback device changed. expected=$expected actual=${device?.deviceName}")
                restartAfterDelay(USB_RECONNECT_DELAY_MS)
                return
            }
            pendingUsbDeviceName = null

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted) {
                // The operator declined: re-prompting automatically would just nag (and the
                // previous auto-retry was silently swallowed by the request cooldown anyway).
                Log.w(TAG, "USB permission denied")
                containerView.visibility = View.GONE
                host.showRecoveryAction("USB permission was denied. Tap Retry to ask again.") {
                    if (host.isActiveSource) start()
                }
                return
            }

            val opened = openPermittedCamera(preferredDevice = device)
            if (!opened) {
                Log.w(TAG, "USB permission granted but no permitted UVC device was immediately available")
                restartAfterDelay(USB_RECONNECT_DELAY_MS)
            }
        }
    }

    private val monitorRecoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AxisightApp.ACTION_USB_MONITOR_RACE) return
            if (!host.isActiveSource) return
            if (permissionRequestInFlight) return

            stop()
            restartAfterDelay(MONITOR_RACE_RESTART_DELAY_MS)
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!host.isActiveSource) return
            val device = UsbDeviceUtils.getUsbDeviceExtra(intent ?: return)
            val relevant = UsbDeviceUtils.concernsActiveCamera(
                isUvc = device?.let { UsbDeviceUtils.isUvcDevice(it) },
                deviceName = device?.deviceName,
                activeDeviceName = activeDeviceName
            )
            if (!relevant) {
                Log.d(TAG, "Ignoring ${intent.action} for unrelated device ${device?.deviceName}")
                return
            }
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null && device.deviceName == activeDeviceName) {
                        // Already opening/running this very device (the launch intent or
                        // onNewIntent switched to USB a moment ago): restarting would only
                        // tear down a camera that is about to open.
                        Log.d(TAG, "Ignoring attach for already-active device ${device.deviceName}")
                        return
                    }
                    Log.i(TAG, "UVC camera attached: ${device?.deviceName}")
                    fragmentRetryCount = 0 // a fresh plug-in deserves a fresh retry budget
                    restartAfterDelay(USB_RECONNECT_DELAY_MS)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "UVC camera detached: ${device?.deviceName}")
                    stop()
                    host.showToast("USB camera disconnected.")
                }
            }
        }
    }

    init {
        registerRecoveryReceiverIfNeeded()
        registerAttachReceiverIfNeeded()
    }

    fun start() {
        try {
            containerView.visibility = View.VISIBLE

            val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
            val uvcDevice = UsbDeviceUtils.findFirstUvcDevice(usbManager)
            if (uvcDevice == null) {
                containerView.visibility = View.GONE
                host.showToast("No USB camera detected.")
                return
            }

            if (!usbManager.hasPermission(uvcDevice)) {
                if (permissionRequestInFlight) {
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastPermissionRequestMs < USB_PERMISSION_RETRY_COOLDOWN_MS) {
                    return
                }
                containerView.visibility = View.GONE
                permissionRequestInFlight = true
                lastPermissionRequestMs = now
                requestPermission(usbManager, uvcDevice)
                host.showToast("Grant USB permission to continue.")
                return
            }

            permissionRequestInFlight = false
            cancelPermissionTimeout()
            showFragment(uvcDevice)
        } catch (e: Exception) {
            permissionRequestInFlight = false
            cancelPermissionTimeout()
            host.showToast("USB camera error: ${e.message}")
            Log.e(TAG, "USB camera start failed", e)
            stop()
        }
    }

    fun stop() {
        try {
            cancelPendingRestart()
            cancelPendingRetry()
            cancelPermissionTimeout()
            permissionRequestInFlight = false
            pendingUsbDeviceName = null
            activeDeviceName = null
            containerView.visibility = View.GONE
            val currentFrag = activity.supportFragmentManager.findFragmentById(containerId)
            if (currentFrag != null) {
                // Intentional teardown: the CLOSED this fragment is about to report must
                // not be mistaken for a failure and schedule a reconnect.
                (currentFrag as? UvcFragment)?.setCameraStateListener(null)
                activity.supportFragmentManager.beginTransaction()
                    .remove(currentFrag)
                    .commitAllowingStateLoss()
            }
            host.onStopped()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping USB camera", e)
        }
    }

    /** Unregisters receivers and cancels pending retries/restarts. Call from onDestroy. */
    fun dispose() {
        unregisterPermissionReceiverIfNeeded()
        unregisterRecoveryReceiverIfNeeded()
        unregisterAttachReceiverIfNeeded()
        mainHandler.removeCallbacksAndMessages(null)
        pendingRetryRunnable = null
        pendingRestartRunnable = null
        permissionTimeoutRunnable = null
    }

    fun currentFragment(): UvcFragment? =
        activity.supportFragmentManager.findFragmentById(containerId) as? UvcFragment

    /** Samples the center-pixel luminance on the next USB frame (see [UvcFragment.requestLock]). */
    fun requestLock() {
        currentFragment()?.requestLock()
    }

    /** Cancels a pending [requestLock] that no frame has consumed yet. */
    fun cancelLockRequest() {
        currentFragment()?.cancelLockRequest()
    }

    /**
     * Schedules a single [start] after [delayMs]. Several triggers can fire close
     * together (attach broadcast + permission callback + monitor-race recovery); only
     * the latest one runs so the fragment is not created and torn down repeatedly.
     */
    private fun restartAfterDelay(delayMs: Long) {
        cancelPendingRestart()
        val runnable = Runnable {
            pendingRestartRunnable = null
            if (host.isActiveSource && !activity.isFinishing && !activity.isDestroyed) {
                start()
            }
        }
        pendingRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingRestart() {
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRestartRunnable = null
    }

    private fun cancelPendingRetry() {
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
    }

    private fun openPermittedCamera(preferredDevice: UsbDevice?): Boolean {
        val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val chosen = sequenceOf(preferredDevice)
            .filterNotNull()
            .firstOrNull { UsbDeviceUtils.isUvcDevice(it) && usbManager.hasPermission(it) }
            ?: usbManager.deviceList.values.firstOrNull { UsbDeviceUtils.isUvcDevice(it) && usbManager.hasPermission(it) }

        if (chosen == null) {
            return false
        }
        showFragment(chosen)
        return true
    }

    private fun showFragment(device: UsbDevice) {
        containerView.visibility = View.VISIBLE

        // A fragment being replaced will report CLOSED as it is destroyed; that is not a
        // failure of the new one, so detach its listener first.
        currentFragment()?.setCameraStateListener(null)

        val uvcFragment = UvcFragment.newInstance(
            vendorId = device.vendorId,
            productId = device.productId,
            deviceName = device.deviceName
        )
        host.configureFragment(uvcFragment)
        activeDeviceName = device.deviceName
        uvcFragment.setCameraStateListener(object : UvcFragment.CameraStateListener {
            override fun onUsbCameraOpened() {
                fragmentRetryCount = 0
                Log.i(TAG, "USB camera opened")
            }

            override fun onUsbCameraClosed() {
                Log.w(TAG, "USB camera closed (retry $fragmentRetryCount)")
                scheduleFragmentRetry("camera closed")
            }

            override fun onUsbCameraError(message: String?) {
                Log.e(TAG, "USB camera error: $message (retry $fragmentRetryCount)")
                scheduleFragmentRetry("camera error: $message")
            }
        })

        activity.supportFragmentManager.beginTransaction()
            .replace(containerId, uvcFragment)
            .commit()
    }

    private fun requestPermission(usbManager: UsbManager, device: UsbDevice) {
        registerPermissionReceiverIfNeeded()
        pendingUsbDeviceName = device.deviceName

        // Must be MUTABLE: UsbManager fills in EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED on
        // delivery, which an immutable PendingIntent silently drops (the callback then
        // arrives with device=null, granted=false). The intent is explicit via
        // setPackage, as Android 14+ requires for mutable PendingIntents.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            activity,
            PERMISSION_REQUEST_CODE,
            Intent(usbPermissionAction).setPackage(activity.packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
        schedulePermissionTimeout()
    }

    /**
     * The system permission dialog can be dismissed without any broadcast (activity goes
     * to the background, dialog swiped away). Without this, [permissionRequestInFlight]
     * would stay set and every later [start] would return silently.
     */
    private fun schedulePermissionTimeout() {
        cancelPermissionTimeout()
        val runnable = Runnable {
            permissionTimeoutRunnable = null
            if (!permissionRequestInFlight) return@Runnable
            permissionRequestInFlight = false
            pendingUsbDeviceName = null
            if (!host.isActiveSource || activity.isFinishing || activity.isDestroyed) return@Runnable

            // Permission may have been granted through the system "open with" chooser
            // without our broadcast firing; start() detects that and just opens.
            val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = UsbDeviceUtils.findFirstUvcDevice(usbManager)
            if (device != null && usbManager.hasPermission(device)) {
                start()
                return@Runnable
            }
            Log.w(TAG, "USB permission request timed out without a response")
            host.showRecoveryAction("USB permission was not granted. Tap Retry to ask again.") {
                if (host.isActiveSource) start()
            }
        }
        permissionTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, USB_PERMISSION_TIMEOUT_MS)
    }

    private fun cancelPermissionTimeout() {
        permissionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        permissionTimeoutRunnable = null
    }

    private fun scheduleFragmentRetry(reason: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!host.isActiveSource) return
        if (pendingRetryRunnable != null) return // one retry at a time
        if (fragmentRetryCount >= MAX_USB_FRAGMENT_RETRIES) {
            Log.e(TAG, "USB fragment retries exhausted. reason=$reason")
            fragmentRetryCount = 0
            host.showRecoveryAction("USB camera could not reconnect. Replug it, or tap Retry.") {
                if (host.isActiveSource) start()
            }
            return
        }
        fragmentRetryCount++
        val delay = retryDelayMs(fragmentRetryCount)
        Log.i(TAG, "Scheduling USB reconnect #$fragmentRetryCount in ${delay}ms ($reason)")
        val runnable = Runnable {
            pendingRetryRunnable = null
            if (host.isActiveSource && !activity.isFinishing && !activity.isDestroyed) {
                start()
            }
        }
        pendingRetryRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun registerPermissionReceiverIfNeeded() {
        if (permissionReceiverRegistered) return
        val filter = IntentFilter(usbPermissionAction)
        ContextCompat.registerReceiver(
            activity, permissionReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        permissionReceiverRegistered = true
    }

    private fun registerRecoveryReceiverIfNeeded() {
        if (recoveryReceiverRegistered) return
        val filter = IntentFilter(AxisightApp.ACTION_USB_MONITOR_RACE)
        ContextCompat.registerReceiver(
            activity, monitorRecoveryReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        recoveryReceiverRegistered = true
    }

    private fun registerAttachReceiverIfNeeded() {
        if (attachReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            activity, attachReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        attachReceiverRegistered = true
    }

    private fun unregisterPermissionReceiverIfNeeded() {
        if (!permissionReceiverRegistered) return
        try {
            activity.unregisterReceiver(permissionReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered by lifecycle races.
        } finally {
            permissionReceiverRegistered = false
            pendingUsbDeviceName = null
        }
    }

    private fun unregisterRecoveryReceiverIfNeeded() {
        if (!recoveryReceiverRegistered) return
        try {
            activity.unregisterReceiver(monitorRecoveryReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered by lifecycle races.
        } finally {
            recoveryReceiverRegistered = false
        }
    }

    private fun unregisterAttachReceiverIfNeeded() {
        if (!attachReceiverRegistered) return
        try {
            activity.unregisterReceiver(attachReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered by lifecycle races.
        } finally {
            attachReceiverRegistered = false
        }
    }

    companion object {
        private const val TAG = "UsbCameraController"
        private const val USB_RECONNECT_DELAY_MS = 600L
        private const val USB_PERMISSION_RETRY_COOLDOWN_MS = 1500L
        private const val USB_PERMISSION_TIMEOUT_MS = 30_000L
        private const val MONITOR_RACE_RESTART_DELAY_MS = 500L
        private const val MAX_USB_FRAGMENT_RETRIES = 6
        private const val MAX_RETRY_DELAY_MS = 4000L
        private const val PERMISSION_REQUEST_CODE = 1001

        /** Linear backoff for reconnect attempt [attempt] (1-based), capped at [MAX_RETRY_DELAY_MS]. */
        fun retryDelayMs(attempt: Int): Long =
            (USB_RECONNECT_DELAY_MS * attempt.coerceAtLeast(1)).coerceAtMost(MAX_RETRY_DELAY_MS)
    }
}
