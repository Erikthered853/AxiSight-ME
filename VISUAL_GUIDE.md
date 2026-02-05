# USB Camera Implementation - Visual Guide

## 🎯 What Was Added

### Three-Way Camera Selection
```
┌─────────────────────────────────────────────────────────┐
│                                                         │
│                   Radio Button Group                    │
│                                                         │
│     ⦿ Internal      ○ USB      ○ WiFi                  │
│     (Default)      (NEW)      (Existing)              │
│                                                         │
└─────────────────────────────────────────────────────────┘
         ↓                ↓               ↓
    CameraX         UsbCameraActivity   ExoPlayer
   (Built-in)        (USB Device)      (RTSP Stream)
```

---

## 📊 Code Flow Diagram

### User Selection Flow
```
User selects USB radio button
         ↓
MainActivity.onCheckedChangeListener triggered
         ↓
┌─────────────────────────────────────────┐
│ case R.id.rbUsb:                        │
│   - cameraSource = USB                  │
│   - stopCamera() [internal]             │
│   - stopWifiCamera() [RTSP]             │
│   - startUsbCamera() [NEW]              │ ← NEW METHOD
└─────────────────────────────────────────┘
         ↓
startUsbCamera() {
    previewView.visibility = GONE
    textureView.visibility = GONE
    
    intent = Intent(this, UsbCameraActivity)
    startActivity(intent)  ← Launches USB camera activity
}
         ↓
UsbCameraActivity.onCreate()
    ↓
UvcFragment.getRootView()
    ↓
AndroidUSBCamera library
    ↓
USB Video Class (UVC) Camera
    ↓
Video Stream → TextureView → Display
```

---

## 🔄 State Machine Diagram

### Camera Source Management
```
                    ┌─────────────┐
                    │  INTERNAL   │ ← Default state
                    │  Camera     │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │  Switch to  │
                    │    USB      │
                    └──────┬──────┘
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
    stopCamera()    stopWifiCamera()    startUsbCamera()
         │                 │                 │
         └─────────────────┼─────────────────┘
                           │
                    ┌──────▼──────┐
                    │   USB       │
                    │  Camera     │
                    │ (Activity)  │
                    └──────┬──────┘
                           │
                   ┌────────▼────────┐
                   │  Switch to WiFi │
                   └────────┬────────┘
                           │
         ┌─────────────────┼──────────────┐
         │                 │              │
    stopCamera()  stopUsbCamera()  startWifiCamera(url)
         │                 │              │
         └─────────────────┼──────────────┘
                           │
                    ┌──────▼──────┐
                    │   WiFi      │
                    │  (RTSP)     │
                    └─────────────┘
```

---

## 📁 File Structure Changes

```
axisight-3/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/etrsystems/axisight/
│   │   │   │   ├── MainActivity.kt ✏️ MODIFIED
│   │   │   │   │   ├─ Enum: INTERNAL, WIFI, USB ← Added USB
│   │   │   │   │   ├─ Import: Intent, Toast ← Added imports
│   │   │   │   │   ├─ Method: startUsbCamera() ← NEW
│   │   │   │   │   ├─ Method: stopUsbCamera() ← NEW
│   │   │   │   │   └─ Listeners: Updated for USB ← Modified
│   │   │   │   ├── UsbCameraActivity.kt ✓ EXISTING
│   │   │   │   └── ui/
│   │   │   │       └── UvcFragment.kt ✓ EXISTING
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   └── activity_main.xml ✏️ MODIFIED
│   │   │   │   │       └─ RadioButton: rbUsb ← Added
│   │   │   │   ├── xml/
│   │   │   │   │   └── device_filter.xml ✓ EXISTING
│   │   │   │   └── values/
│   │   │   │       └── strings.xml ✓ EXISTING
│   │   │   │           └─ android:text="@string/usb"
│   │   │   └── AndroidManifest.xml ✏️ MODIFIED
│   │   │       ├─ Permission: ACCESS_USB ← Added
│   │   │       └─ Feature/Activity: Already present
│   │   └── build.gradle ✓ EXISTING (No changes needed)
│   └── local.properties ✏️ MODIFIED
│       └─ SDK path formatting fix
│
├── USB_CAMERA_IMPLEMENTATION.md ← NEW DOCUMENTATION
├── USB_CAMERA_COMPLETE_SUMMARY.md ← NEW DOCUMENTATION
├── QUICK_REFERENCE.md ← NEW DOCUMENTATION
├── VERIFICATION_REPORT.md ← NEW DOCUMENTATION
└── README.md ✓ EXISTING

Legend:
  ✏️  = Modified
  ✓   = Existing & verified
  ← = Change/Addition
```

---

## 🔌 Integration Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        MAIN ACTIVITY                             │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐    │
│  │              Radio Button Group                         │    │
│  │  [●Internal]  [○USB]  [○WiFi]                          │    │
│  │      │          │        │                              │    │
│  │      └──────────┼────────┘                              │    │
│  │                 │                                        │    │
│  │          onCheckedChangeListener                        │    │
│  └────────────────┬────────────────────────────────────────┘    │
│                   │                                              │
│        ┌──────────┼──────────┐                                  │
│        │          │          │                                  │
│        ↓          ↓          ↓                                  │
│    ┌────────┐ ┌────────┐ ┌────────┐                           │
│    │Internal│ │  USB   │ │ WiFi   │                           │
│    │Camera  │ │Camera  │ │Camera  │                           │
│    │(CameraX)││(Intent)│ │(ExoP)  │                           │
│    └────────┘ └────────┘ └────────┘                           │
│        │          │          │                                  │
│        └──────────┴──────────┘                                  │
│                   │                                              │
│         ┌─────────▼──────────┐                                  │
│         │  Frame Analysis    │                                  │
│         │  BlobDetector      │                                  │
│         └────────┬───────────┘                                  │
│                  │                                              │
│         ┌────────▼──────────┐                                  │
│         │  OverlayView      │                                  │
│         │  (Visualization)  │                                  │
│         └───────────────────┘                                  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 🌊 Activity Lifecycle - USB Camera

```
MainActivity
    │
    ├─ onCreate()
    │  └─ Setup radio button listeners ✓
    │     └─ Handles rbInternal, rbUsb, rbWifi ← USB added
    │
    ├─ onResume()
    │  └─ Camera initialization
    │
    ├─ User selects USB
    │  └─ startUsbCamera()
    │     └─ Intent → UsbCameraActivity
    │
    └─ onDestroy()
       └─ stopUsbCamera() ← NEW cleanup
       └─ stopCamera()
       └─ stopWifiCamera()
```

---

## 📋 Method Call Sequence

### USB Camera Selection Sequence
```
1. User taps USB radio button
   ↓
2. rgCameraSource.setOnCheckedChangeListener triggered
   ↓
3. R.id.rbUsb case executed
   ├─ cameraSource = CameraSource.USB
   ├─ b.wifiGroup.visibility = View.GONE
   ├─ stopCamera()           [if internal was active]
   ├─ stopWifiCamera()       [if WiFi was active]
   └─ startUsbCamera()       ← NEW METHOD CALLED
     ↓
4. startUsbCamera() execution:
   ├─ b.previewView.visibility = View.GONE
   ├─ b.textureView.visibility = View.GONE
   ├─ Create Intent(MainActivity, UsbCameraActivity.class)
   └─ startActivity(intent)
     ↓
5. UsbCameraActivity.onCreate()
   ├─ setContentView(R.layout.activity_usb_camera)
   └─ fragmentManager.beginTransaction()
      └─ replace(container, UvcFragment)
        ↓
6. UvcFragment.getRootView()
   ├─ Create TextureView
   ├─ Set SurfaceTextureListener
   └─ Add to FrameLayout container
     ↓
7. AndroidUSBCamera Library
   ├─ Initialize UVC device
   ├─ Get video stream
   └─ Render to TextureView
```

---

## 🔐 Permission & Feature Requirements

```
┌───────────────────────────────────────────────────────┐
│            AndroidManifest.xml                        │
├───────────────────────────────────────────────────────┤
│                                                       │
│ Features (Required Hardware):                        │
│ ├─ android.hardware.camera          ✓ Existing      │
│ └─ android.hardware.usb.host        ✓ Existing      │
│                                                       │
│ Permissions:                                         │
│ ├─ android.permission.CAMERA        ✓ Existing      │
│ ├─ android.permission.INTERNET      ✓ Existing      │
│ ├─ android.permission.RECORD_AUDIO  ✓ Existing      │
│ ├─ android.permission.READ...       ✓ Existing      │
│ ├─ android.permission.WRITE...      ✓ Existing      │
│ └─ android.permission.ACCESS_USB    ✏️ ADDED        │
│                                                       │
│ Activities:                                          │
│ ├─ MainActivity                     ✓ Existing      │
│ └─ UsbCameraActivity                ✓ Existing      │
│    └─ intent-filter USB_DEVICE_...  ✓ Present       │
│       └─ device_filter.xml (UVC)    ✓ Present       │
│                                                       │
└───────────────────────────────────────────────────────┘
```

---

## 💾 Resource Map

```
Activity Main XML Layout Structure:
┌─ FrameLayout (root)
│  ├─ PreviewView (internal camera)
│  ├─ TextureView (WiFi camera)
│  ├─ OverlayView (blob detection)
│  │
│  ├─ LinearLayout (TOP CONTROLS)
│  │  ├─ RadioGroup (Camera Selection) ← USB BUTTON HERE
│  │  │  ├─ RadioButton (Internal)
│  │  │  ├─ RadioButton (USB) ← NEW
│  │  │  └─ RadioButton (WiFi)
│  │  │
│  │  ├─ LinearLayout (WiFi Controls)
│  │  │  ├─ EditText (URL)
│  │  │  └─ Button (Connect)
│  │  │
│  │  └─ LinearLayout (Tools)
│  │     ├─ Switch (Simulate)
│  │     ├─ Switch (Auto-Detect)
│  │     ├─ Button (Calibrate)
│  │     └─ Button (Export CSV)
│  │
│  └─ LinearLayout (BOTTOM TUNING)
│     ├─ SeekBars (Parameter tuning)
│     └─ EditTexts (Manual values)
```

---

## ✨ Features Summary

```
┌─────────────────────────────────────────────────────────┐
│           CAMERA SOURCE SELECTION                       │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  INTERNAL CAMERA                                       │
│  └─ Source: Device built-in camera                     │
│  └─ Library: AndroidX Camera (CameraX)                 │
│  └─ Method: startCamera()                              │
│  └─ Display: PreviewView                               │
│  └─ Feature: Real-time blob detection                  │
│  └─ Status: ✓ Working                                  │
│                                                         │
│  USB CAMERA ← NEW                                       │
│  └─ Source: USB Video Class (UVC) device               │
│  └─ Library: AndroidUSBCamera                          │
│  └─ Method: startUsbCamera() ← NEW                     │
│  └─ Display: UsbCameraActivity + TextureView           │
│  └─ Feature: USB device auto-detection                 │
│  └─ Status: ✓ Implemented & Ready                      │
│                                                         │
│  WIFI CAMERA                                           │
│  └─ Source: Remote RTSP stream                         │
│  └─ Library: Media3 ExoPlayer                          │
│  └─ Method: startWifiCamera(url)                       │
│  └─ Display: TextureView                               │
│  └─ Feature: Live streaming support                    │
│  └─ Status: ✓ Working                                  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 🎨 UI Before & After

### BEFORE (2 options)
```
┌─────────────────────────────┐
│ ⦿ Internal    ○ WiFi       │
└─────────────────────────────┘
```

### AFTER (3 options)
```
┌─────────────────────────────┐
│ ⦿ Internal  ○ USB  ○ WiFi   │ ← USB ADDED
└─────────────────────────────┘
```

---

## 🚀 Deployment Diagram

```
Source Code
    ↓
    ├─ MainActivity.kt (modified)
    ├─ activity_main.xml (modified)
    ├─ AndroidManifest.xml (modified)
    └─ local.properties (fixed)
    
    ↓
Gradle Build (./gradlew assembleDebug)
    ↓
✅ BUILD SUCCESSFUL (No errors)
    ↓
APK Generated
    ├─ app/build/outputs/apk/debug/app-debug.apk
    ↓
Ready for Installation/Deployment
```

---

**Version:** 1.0
**Date:** December 9, 2025
**Status:** ✅ Complete

