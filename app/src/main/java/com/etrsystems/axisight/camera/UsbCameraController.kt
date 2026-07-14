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

        /** Apply app-specific fragment config (target circle, tuning, detection callback). */
        fun configureFragment(fragment: UvcFragment)

        fun showToast(message: String)

        /** Retry budget exhausted; surface a persistent affordance that calls [retry]. */
        fun showRetriesExhausted(retry: () -> Unit)

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
    // Limits automatic reconnect attempts to prevent infinite loops.
    private var fragmentRetryCount = 0
    private var pendingRetryRunnable: Runnable? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!host.isActiveSource) return
            if (intent?.action != usbPermissionAction) return

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
                Log.w(TAG, "USB permission denied; scheduling retry")
                host.showToast("USB permission denied. Retrying...")
                scheduleFragmentRetry("permission denied")
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
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> restartAfterDelay(USB_RECONNECT_DELAY_MS)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> stop()
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
            showFragment(uvcDevice)
        } catch (e: Exception) {
            permissionRequestInFlight = false
            host.showToast("USB camera error: ${e.message}")
            Log.e(TAG, "USB camera start failed", e)
            stop()
        }
    }

    fun stop() {
        try {
            permissionRequestInFlight = false
            pendingUsbDeviceName = null
            containerView.visibility = View.GONE
            val currentFrag = activity.supportFragmentManager.findFragmentById(containerId)
            if (currentFrag != null) {
                activity.supportFragmentManager.beginTransaction()
                    .remove(currentFrag)
                    .commitAllowingStateLoss()
            }
            host.onStopped()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping USB camera", e)
        }
    }

    /** Unregisters receivers and cancels pending retries. Call from onDestroy. */
    fun dispose() {
        unregisterPermissionReceiverIfNeeded()
        unregisterRecoveryReceiverIfNeeded()
        unregisterAttachReceiverIfNeeded()
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
    }

    fun currentFragment(): UvcFragment? =
        activity.supportFragmentManager.findFragmentById(containerId) as? UvcFragment

    fun setTargetCircle(x: Float, y: Float, radiusPx: Float) {
        currentFragment()?.setTargetCircle(x, y, radiusPx)
    }

    fun setDetectorTuning(minCircularity: Double, kStd: Double, minContrastRatio: Double) {
        currentFragment()?.setDetectorTuning(minCircularity, kStd, minContrastRatio)
    }

    /** Toggles target locking on the live fragment. @return locked threshold, or null. */
    fun lockTarget(active: Boolean): Int? = currentFragment()?.lockTarget(active)

    private fun restartAfterDelay(delayMs: Long) {
        mainHandler.postDelayed({
            if (host.isActiveSource) {
                start()
            }
        }, delayMs)
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

        val uvcFragment = UvcFragment.newInstance(
            vendorId = device.vendorId,
            productId = device.productId,
            deviceName = device.deviceName
        )
        host.configureFragment(uvcFragment)
        fragmentRetryCount = 0
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

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            activity,
            PERMISSION_REQUEST_CODE,
            Intent(usbPermissionAction).setPackage(activity.packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun scheduleFragmentRetry(reason: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!host.isActiveSource) return
        if (fragmentRetryCount >= MAX_USB_FRAGMENT_RETRIES) {
            Log.e(TAG, "USB fragment retries exhausted. reason=$reason")
            fragmentRetryCount = 0
            host.showRetriesExhausted {
                if (host.isActiveSource) start()
            }
            return
        }
        fragmentRetryCount++
        val delay = (USB_RECONNECT_DELAY_MS * fragmentRetryCount).coerceAtMost(MAX_RETRY_DELAY_MS)
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
        private const val MONITOR_RACE_RESTART_DELAY_MS = 500L
        private const val MAX_USB_FRAGMENT_RETRIES = 6
        private const val MAX_RETRY_DELAY_MS = 4000L
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
