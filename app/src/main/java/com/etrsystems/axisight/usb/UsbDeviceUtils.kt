package com.etrsystems.axisight.usb

import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

object UsbDeviceUtils {

    fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
    }

    fun findFirstUvcDevice(usbManager: UsbManager): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { isUvcDevice(it) }

    /**
     * Whether a USB attach/detach broadcast should drive the camera pipeline.
     *
     * @param isUvc            Whether the broadcast's device is a UVC camera, or null when the
     *                         broadcast carried no device extra (treated as relevant, since we
     *                         cannot tell).
     * @param deviceName       The broadcast device's name, if any.
     * @param activeDeviceName Name of the camera currently in use, or null when none is open.
     *
     * A keyboard or flash drive being (un)plugged must not restart or tear down the camera,
     * and while one camera is running, a second camera coming and going is ignored too.
     */
    fun concernsActiveCamera(isUvc: Boolean?, deviceName: String?, activeDeviceName: String?): Boolean {
        if (isUvc == null) return true
        if (!isUvc) return false
        return activeDeviceName == null || deviceName == null || deviceName == activeDeviceName
    }

    fun getUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
