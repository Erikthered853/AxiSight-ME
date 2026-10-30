package com.etrsystems.axisight.camera

import androidx.media3.common.util.UnstableApi

/**
 * Facade over the three camera sources. Policy (which source to run, permission
 * prompts, UI visibility) stays with the host activity; this class owns the
 * source instances, the active-type state, and teardown.
 */
@UnstableApi
class CameraController(
    val internal: InternalCameraSource,
    val wifi: WifiCameraSource,
    val usb: UsbCameraController
) {
    var activeType: CameraSourceType = CameraSourceType.INTERNAL

    fun stopAll() {
        internal.stop()
        wifi.stop()
        usb.stop()
    }

    fun dispose() {
        stopAll()
        internal.dispose()
        usb.dispose()
    }
}
