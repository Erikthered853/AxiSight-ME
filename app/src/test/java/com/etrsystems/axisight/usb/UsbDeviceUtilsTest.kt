package com.etrsystems.axisight.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure attach/detach relevance rules used by the USB camera controller's receivers. */
class UsbDeviceUtilsTest {

    @Test
    fun `non-UVC devices never concern the camera`() {
        assertFalse(UsbDeviceUtils.concernsActiveCamera(isUvc = false, deviceName = "/dev/bus/usb/001/005", activeDeviceName = null))
        assertFalse(UsbDeviceUtils.concernsActiveCamera(isUvc = false, deviceName = "/dev/bus/usb/001/005", activeDeviceName = "/dev/bus/usb/001/005"))
    }

    @Test
    fun `a UVC device concerns the camera when none is active`() {
        assertTrue(UsbDeviceUtils.concernsActiveCamera(isUvc = true, deviceName = "/dev/bus/usb/001/005", activeDeviceName = null))
    }

    @Test
    fun `only the active camera itself concerns the pipeline while one is running`() {
        val active = "/dev/bus/usb/001/005"
        assertTrue(UsbDeviceUtils.concernsActiveCamera(isUvc = true, deviceName = active, activeDeviceName = active))
        assertFalse(UsbDeviceUtils.concernsActiveCamera(isUvc = true, deviceName = "/dev/bus/usb/001/009", activeDeviceName = active))
    }

    @Test
    fun `missing device information is treated as relevant`() {
        assertTrue(UsbDeviceUtils.concernsActiveCamera(isUvc = null, deviceName = null, activeDeviceName = "/dev/bus/usb/001/005"))
        assertTrue(UsbDeviceUtils.concernsActiveCamera(isUvc = true, deviceName = null, activeDeviceName = "/dev/bus/usb/001/005"))
    }
}
