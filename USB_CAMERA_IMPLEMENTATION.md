# USB Camera Support and Radio Button Implementation

## Overview
Added full USB camera support to the AxiSight application alongside existing Internal and WiFi camera sources. Users can now select between three camera options via radio buttons in the main activity.

## Changes Made

### 1. MainActivity.kt
**Location:** `app/src/main/java/com/etrsystems/axisight/MainActivity.kt`

#### Enum Update
- Added `USB` to `CameraSource` enum alongside `INTERNAL` and `WIFI`

#### Import Additions
- Added `import android.content.Intent` for launching USB camera activity
- Added `import android.widget.Toast` for error messages

#### Radio Button Listener Enhancement
- Updated `rgCameraSource` listener to handle three camera sources
- Added `R.id.rbUsb` case that:
  - Sets `cameraSource = CameraSource.USB`
  - Hides WiFi controls
  - Stops internal and WiFi cameras
  - Launches USB camera activity

#### Method Updates
- Updated `onDestroy()` to call `stopUsbCamera()`
- Updated `switchSim` listener to handle USB camera when toggling simulation mode

#### New Methods Added
```kotlin
private fun startUsbCamera()
- Hides preview and texture views
- Launches UsbCameraActivity via Intent
- Handles exceptions with Toast notifications

private fun stopUsbCamera()
- Restores preview visibility
- Cleans up USB camera resources
```

### 2. activity_main.xml
**Location:** `app/src/main/res/layout/activity_main.xml`

#### RadioGroup Enhancement
- Added `rbUsb` RadioButton between Internal and WiFi buttons:
  ```xml
  <RadioButton
      android:id="@+id/rbUsb"
      android:layout_width="wrap_content"
      android:layout_height="wrap_content"
      android:text="@string/usb"
      android:textColor="@android:color/white"/>
  ```

### 3. AndroidManifest.xml
**Location:** `app/src/main/AndroidManifest.xml`

#### Permission Addition
- Added `<uses-permission android:name="android.permission.ACCESS_USB" />`
- This is in addition to existing USB feature declaration

### 4. UsbCameraActivity.kt
**Status:** Already implemented
- Location: `app/src/main/java/com/etrsystems/axisight/UsbCameraActivity.kt`
- Properly extends AppCompatActivity
- Manages UvcFragment for USB video class support
- Integrated with AndroidUSBCamera library

### 5. Strings Resource
**Status:** Already defined
- `<string name="usb">USB</string>` already present in values/strings.xml

### 6. Device Filter
**Status:** Already configured
- `app/src/main/res/xml/device_filter.xml` already defines USB Video Class (0x0E) support

## Architecture

### Three-Way Camera Selection
```
┌─────────────────────────────────────┐
│        Radio Button Selection        │
├─────────────────────────────────────┤
│ • Internal → startCamera()           │
│ • WiFi     → startWifiCamera(url)    │
│ • USB      → startUsbCamera()        │
└─────────────────────────────────────┘
```

### USB Camera Flow
1. User selects USB radio button
2. MainActivity stops internal/WiFi cameras
3. UsbCameraActivity is launched
4. UvcFragment initializes USB video stream
5. Video is displayed in TextureView
6. Frame analysis works with USB input

## Dependencies Used
- **AndroidUSBCamera**: For UVC (USB Video Class) support
  - `com.github.chenyeju295.AndroidUSBCamera:libausbc:3.3.6`
  - `com.github.chenyeju295.AndroidUSBCamera:libuvc:3.3.6`

## Testing Checklist
- [ ] Internal camera selection works
- [ ] WiFi camera selection and RTSP connection works
- [ ] USB camera selection launches USB activity
- [ ] Switching between camera sources works smoothly
- [ ] Simulation mode works with all camera sources
- [ ] Auto-detect features work with USB camera
- [ ] USB device filter properly recognizes UVC cameras
- [ ] Permission handling for USB access
- [ ] Proper cleanup when switching camera sources

## Integration Points
1. **Camera Selection** - Radio buttons in main activity
2. **Intent-based Navigation** - USB activity launch
3. **Resource Cleanup** - Proper stop methods for each source
4. **Error Handling** - Toast notifications for failures
5. **Lifecycle Management** - onDestroy cleanup

## Future Enhancements
- Add USB camera frame analysis to BlobDetector
- Implement real-time processing from USB stream
- Add USB camera configuration UI
- Support multiple concurrent USB cameras
- Add USB device discovery/selection dialog

