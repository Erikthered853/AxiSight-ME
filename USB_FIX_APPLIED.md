# USB Camera Fix Report

## Issue Description
The user reported that the USB camera was having a hard time connecting and was not detecting anything reliably.

## Root Cause Analysis
1.  **Missing Camera Configuration**: The `UvcFragment.kt` file had the `getCameraRequest()` method commented out due to an "Unresolved reference" error. This method is critical for the `AndroidUSBCamera` library to know how to configure the camera (resolution, format, etc.). Without it, the library likely failed to open the camera or used an invalid default configuration.
2.  **Inefficient Processing**: The image processing loop in `UvcFragment` was spawning a new `Thread` for every frame. This can lead to high memory churn and performance issues, potentially affecting the camera stream stability.
3.  **Lack of Feedback**: When selecting the "USB" option, the app did not check if a USB device was actually connected, leading to a silent failure or a generic error if no camera was attached.

## Changes Applied

### 1. Fixed `UvcFragment.kt`
*   **Added Import**: Added `import com.jiangdg.ausbc.camera.bean.CameraRequest` to resolve the build error.
*   **Implemented `getCameraRequest`**:
    ```kotlin
    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(640)
            .setPreviewHeight(480)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultCameraId(0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .create()
    }
    ```
*   **Optimized Processing**: Replaced the ad-hoc thread creation with a dedicated `HandlerThread` ("FrameProcessingThread") to handle image analysis sequentially and efficiently.
*   **Lifecycle Management**: improved `startProcessing` and `stopProcessing` to correctly manage the background thread.

### 2. Improved `MainActivity.kt`
*   **Device Check**: Added a check in `startUsbCamera()` to verify if any USB devices are connected.
    ```kotlin
    val usbManager = getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
    if (usbManager.deviceList.isEmpty()) {
        Toast.makeText(this, "No USB devices detected. Please check connection.", Toast.LENGTH_LONG).show()
    }
    ```
    This provides immediate feedback to the user if the camera is not plugged in or not recognized by the OS.

## Next Steps for User
1.  **Build the App**: Run `./gradlew assembleDebug` to ensure the build passes with the new import.
2.  **Test Connectivity**:
    *   Plug in a USB camera.
    *   Open the app and select "USB".
    *   Verify that the preview appears and the "USB Camera Connected" toast is shown.
    *   If "No USB devices detected" appears, check the cable/OTG adapter.
3.  **Test Detection**:
    *   Ensure the camera is pointed at a target (dark dot).
    *   Verify that the detection overlay works smoothly without lagging the UI.

## Troubleshooting
If the build still fails on `CameraRequest`, ensure the dependency `com.github.chenyeju295.AndroidUSBCamera:libausbc:3.3.6` is correctly downloaded. If the class path is different in that specific fork, check the library documentation.
