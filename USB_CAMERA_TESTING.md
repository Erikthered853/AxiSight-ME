# USB Camera Testing Guide

## Quick Start

### Installation
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Testing Steps
1. **Connect USB Camera**: Use USB OTG adapter if needed
2. **Open App**: Launch Axisight on your phone
3. **Select USB Mode**: Click the USB radio button
4. **View Feed**: Camera feed should appear in the preview area

## Troubleshooting

### Issue: Black Screen
**Solution**: 
- Check USB camera is powered/recognized
- Try different USB cable
- Verify USB OTG is enabled in Settings
- Check logcat for detailed errors

### Issue: Camera Not Detected
**Solution**:
- Verify USB camera works on PC/Mac
- Check Android phone supports USB OTG
- Try different USB port
- Grant camera permissions if prompted

### Issue: Low Frame Rate / Lag
**Solution**:
- Lower resolution if camera supports it (auto-selected to VGA)
- Close other apps using camera
- Reduce image processing load

## Logging

### Enable Detailed Logs
```bash
adb logcat | grep UvcFragment
```

### Key Log Messages
- `Surface texture available: WxH` - TextureView initialized
- `Camera opened successfully` - Camera connected
- `Preview size successfully set to WxH` - Ready for streaming
- `Camera error:` - Error occurred

## Supported Cameras
- ✅ Most UVC standard cameras
- ✅ Logitech C270, C310, etc.
- ✅ Generic USB webcams
- ✅ Mobile phone cameras (via USB)

## Known Limitations
- Only supports UVC (USB Video Class) cameras
- Requires USB OTG support on phone
- Max resolution depends on camera (defaults to VGA 640x480)

## Build Info
- **Version**: 0.1.0
- **Build Type**: Debug
- **Gradle**: 8.13
- **JVM Target**: 17

---

**Date**: December 9, 2025
**Status**: Ready for Testing ✅

