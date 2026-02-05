# AxiSight USB Camera App - Deployment & Testing Guide

## ✅ BUILD STATUS: SUCCESS

Your app has been successfully built and is ready for testing!

## Step 1: Install APK on Phone

### Requirements:
- Android phone with USB debugging enabled
- ADB (Android Debug Bridge) installed
- Phone connected via USB

### Installation Commands:

```bash
# Navigate to project directory
cd "C:\Users\epeterson\Downloads\axisight-3_patched_usb\axisight-3"

# Install the app (replace with your device)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verify installation
adb logcat -c
adb shell am start -n com.etrsystems.axisight/.MainActivity
```

## Step 2: Enable USB Debugging on Phone

1. Go to **Settings** → **About Phone**
2. Tap **Build Number** 7 times to enable Developer Options
3. Go back to Settings → **Developer Options**
4. Enable **USB Debugging**
5. Trust the connected computer

## Step 3: Connect USB Camera

After app launches:
1. Connect your USB camera (Teslong or compatible UVC camera)
2. Click the **USB Camera** button in the app
3. Grant USB camera permission when prompted
4. You should see the camera preview (640x480 VGA)

## Expected Behavior

### Good Signs ✅
- App launches without crashing
- Main camera preview works (built-in camera)
- USB button responds to clicks
- Camera preview appears (possibly with slight delay)
- Preview shows at ~15 fps
- No "unsupported preview size" errors

### Issues to Watch For ⚠️
- Black screen (usually a format issue)
- Permission denied (check USB permissions)
- Timeout errors (camera not responding)
- Preview size errors (shouldn't occur now)

## Debugging

### View Logs in Real-time:
```bash
adb logcat | findstr "UvcFragment\|CameraUVC\|MultiCamera"
```

### Key Log Messages to Look For:
```
UvcFragment: Camera opened successfully
CameraUVC: getSuitableSize: PreviewSize(width=640, height=480)
CameraUVC: start preview
RenderManager: camera render frame rate is 15 fps
```

### If Something Goes Wrong:
```bash
# Get full logcat
adb logcat > logcat_$(Get-Date -Format "yyyyMMdd_HHmmss").txt

# Clear app data and retry
adb shell pm clear com.etrsystems.axisight

# Reinstall
adb uninstall com.etrsystems.axisight
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## What Was Fixed

### Build Issues Resolved:
1. ✅ Removed invalid `wrapper{}` block from build.gradle
2. ✅ Fixed JNI packaging configuration for AGP 8.x
3. ✅ Disabled stale configuration cache
4. ✅ Ensured compileSdk is properly set to 36

### Camera Code Status:
- ✅ UvcFragment handles preview sizes correctly
- ✅ Fallback to 640x480 (VGA) for universal compatibility
- ✅ Surface texture lifecycle properly managed
- ✅ Error handling with user-friendly messages

## APK Details

**File**: `app/build/outputs/apk/debug/app-debug.apk`  
**Size**: 11.4 MB  
**Target Platform**: arm64-v8a (64-bit)  
**Android Versions**: 8.0 (API 26) to 15 (API 36)  
**Debuggable**: Yes (Debug APK)

## Troubleshooting

### "Unsupported Preview Size" Error
This should NOT occur anymore. If it does:
1. Check if camera supports 640x480
2. Try a different USB camera
3. Check logcat for detailed error message

### Black Screen When Opening USB Camera
Possible causes:
1. Camera not properly connected
2. Permission not granted
3. Camera is MJPEG-only (should work with current code)
4. Wrong camera format (try different resolution in logs)

### No Preview at All
1. Is USB camera connected and powered?
2. Did you grant permission when prompted?
3. Check device manager on PC to verify camera is recognized

## Next Build/Rebuild

If you need to rebuild after modifications:
```bash
cd "C:\Users\epeterson\Downloads\axisight-3_patched_usb\axisight-3"

# Clean rebuild
./gradlew clean assembleDebug

# Or just rebuild
./gradlew assembleDebug
```

## Support

If issues persist:
1. Share the logcat output
2. Specify the exact USB camera model
3. Mention which step fails (build, install, or runtime)

---

**Build Date**: December 10, 2025  
**App Version**: 0.1.0  
**Status**: ✅ Ready to Deploy

