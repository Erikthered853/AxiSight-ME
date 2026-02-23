package com.etrsystems.axisight

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class AxisightApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installUsbMonitorCrashGuard()
    }

    private fun installUsbMonitorCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isKnownUsbMonitorPermissionRace(thread, throwable)) {
                Log.e(TAG, "Suppressed known USBMonitor permission race crash", throwable)
                Handler(Looper.getMainLooper()).post {
                    sendBroadcast(
                        android.content.Intent(ACTION_USB_MONITOR_RACE).setPackage(packageName)
                    )
                    Toast.makeText(
                        this,
                        "USB camera disconnected unexpectedly. Reconnect and tap USB again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@setDefaultUncaughtExceptionHandler
            }

            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun isKnownUsbMonitorPermissionRace(thread: Thread, throwable: Throwable): Boolean {
        if (!thread.name.contains("USBMonitor", ignoreCase = true)) return false
        if (!hasUsbMonitorStackFrame(throwable)) return false

        var current: Throwable? = throwable
        while (current != null) {
            if (current is SecurityException) {
                val message = current.message.orEmpty()
                if (
                    message.contains("permission to access device", ignoreCase = true) ||
                    message.contains("User has not given", ignoreCase = true)
                ) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    private fun hasUsbMonitorStackFrame(throwable: Throwable): Boolean {
        return throwable.stackTrace.any { it.className.startsWith("com.jiangdg.usb.USBMonitor") }
    }

    companion object {
        private const val TAG = "AxisightApp"
        const val ACTION_USB_MONITOR_RACE = "com.etrsystems.axisight.action.USB_MONITOR_RACE"
    }
}
