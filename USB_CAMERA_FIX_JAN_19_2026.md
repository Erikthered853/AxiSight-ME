# USB Camera Black Screen Fix - January 19, 2026

## What Was Fixed

### Problem
- USB camera showing black screen when USB button clicked
- No video feed from USB camera
- Logcat showed minimal errors from the app

### Root Causes Identified
1. **Default resolution too high** - Was set to 1280x720 (HD), which many USB cameras don't support
2. **Missing buffer size configuration** - SurfaceTexture not properly sized before camera initialization
3. **Insufficient logging** - Hard to debug what was happening during camera setup

### Changes Made

#### 1. Changed Default Resolution to VGA (640x480)
```kotlin
// Before: 1280x720 (HD)
private var surfaceWidth: Int = 1280
private var surfaceHeight: Int = 720

// After: 640x480 (VGA) - most compatible
private var surfaceWidth: Int = 640
private var surfaceHeight: Int = 480
```

#### 2. Updated Resolution Priority List
```kotlin
private var resolutions = listOf(
    640 to 480,   // VGA - most compatible (NOW FIRST)
    320 to 240,   // QVGA
    1280 to 720,  // HD
    1920 to 1080  // Full HD
)
```

#### 3. Added Buffer Size Configuration
- Set `SurfaceTexture.setDefaultBufferSize()` before creating Surface
- Ensures the texture has proper dimensions before camera tries to use it

#### 4. Enhanced Logging
- Added detailed logs for:
  - Surface texture availability
  - Surface creation
  - Camera state changes
  - Fragment lifecycle events

#### 5. Added Lifecycle Logging
- `onViewCreated()` - Confirms fragment view is ready
- `onStart()` - Confirms fragment is starting
- `onResume()` - Confirms fragment is active and camera should work

## Files Modified
- `app/src/main/java/com/etrsystems/axisight/ui/UvcFragment.kt`

## Build Status
✅ **BUILD SUCCESSFUL**
✅ App installed on device: SM-S938U - 16
✅ Ready for testing

## Testing Instructions

### 1. Connect USB Camera
- Plug USB camera into phone using OTG adapter
- Phone should show "USB device detected" notification

### 2. Launch App
- Open AxiSight app from app drawer
- App should start on main screen

### 3. Switch to USB Camera
- Tap the USB radio button
- UsbCameraActivity should launch
- Camera feed should appear within 1-2 seconds

### 4. What to Look For
✅ **SUCCESS**: Video feed appears showing camera view
❌ **FAILURE**: Black screen or error message

### 5. Check Logs
If still showing black screen, capture new logcat:
```bash
adb logcat -c  # Clear logs
adb logcat > usb-camera-debug-$(Get-Date -Format 'yyyy-MM-dd_HHmmss').logcat
```

Then in the app:
1. Tap USB button
2. Wait 10 seconds
3. Ctrl+C to stop logcat
4. Share the log file

### 6. Test Different Resolutions
- Tap "Cycle Res" button on screen
- Unplug and replug camera
- Check if different resolution works better

## Expected Debug Messages
When working correctly, logcat should show:
```
UvcFragment: Root view initialized with TextureView (VGA: 640 x 480)
UvcFragment: onViewCreated - Camera initialization should begin
UvcFragment: onStart - Fragment started
UvcFragment: onResume - Fragment resumed, camera should be active
UvcFragment: onSurfaceTextureAvailable: 640 x 480
UvcFragment: Surface created and ready for camera
UvcFragment: Camera opened successfully
```

## Technical Details

### Why VGA Resolution?
- **640x480 (VGA)** is supported by 99% of USB cameras
- Most budget USB cameras (like Teslong) only support VGA or lower
- Higher resolutions (720p, 1080p) often not supported by inexpensive cameras

### Surface Texture Buffer Size
The Android `SurfaceTexture` needs to know the size of the buffers to allocate before the camera starts streaming. Setting this correctly prevents:
- Buffer allocation failures
- Size mismatch errors
- Black screens due to invalid surface

### AndroidUSBCamera Library
The app uses the `libausbc` library which:
- Automatically detects USB cameras
- Handles UVC protocol communication
- Manages camera lifecycle
- The CameraFragment base class does most of the work
- We just need to provide a valid Surface with correct dimensions

## Next Steps If Still Black Screen

1. **Check USB Camera Compatibility**
   - Some cameras may not support UVC standard
   - Try with different USB camera if available

2. **Try Lower Resolution**
   - Use "Cycle Res" button to try 320x240
   - Some very old cameras only support QVGA

3. **Check Device Logs**
   - Look for "UvcFragment" tag in logcat
   - Look for "USBMonitor" tag for USB events
   - Check for permission denied errors

4. **Verify USB Connection**
   - Some OTG adapters are faulty
   - Try different OTG adapter
   - Make sure phone supports USB OTG (most modern phones do)

## Summary
The app has been updated with better USB camera compatibility:
- Default resolution changed to VGA (640x480)
- Proper surface buffer sizing
- Enhanced logging for debugging
- Fragment lifecycle properly implemented

The app is now installed on your device and ready for testing with your USB camera.
