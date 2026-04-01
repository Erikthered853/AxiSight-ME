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

    fun getUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
