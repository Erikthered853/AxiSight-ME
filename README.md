# AxiSight

CNC spindle-alignment app for Android. Mount a camera in the spindle, rotate 360°, and the app calculates the true centerline and overlays a digital reference so tools can be aligned faster and more accurately than with dial indicators or lasers.

---

## Requirements

| Item | Value |
|------|-------|
| Android | minSdk 26 (Android 8.0), targetSdk 34 |
| Architecture | arm64-v8a |
| Build tool | Gradle 8, JDK 17 |
| Firebase project | Required (Crashlytics, Analytics, Auth) |

---

## Build

```bash
# Set JDK 17
export JAVA_HOME="/path/to/jdk-17"

# Debug APK
./gradlew assembleDebug

# Unit tests (Robolectric, no device needed)
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug
```

---

## Firebase Setup

1. Create a Firebase project and add the Android app (`com.etrsystems.axisight`)
2. Download `google-services.json` → place in `app/`
3. Enable **Email/Password** authentication in Firebase Console
4. *(Optional)* Enable **Google Sign-In** → copy the Web Client ID into `app/src/main/res/values/strings.xml`:
   ```xml
   <string name="default_web_client_id">YOUR_WEB_CLIENT_ID_HERE</string>
   ```
   Until this is set, Google Sign-In shows a "not configured" toast and gracefully falls back to email login.

---

## Camera Sources

| Mode | How it works |
|------|-------------|
| **Internal** | CameraX `ImageAnalysis` on the device's back camera |
| **USB** | AUSBC library (`UvcFragment`) via USB-OTG UVC camera |
| **WiFi** | ExoPlayer RTSP stream (`rtsp://...`) rendered to `TextureView` |

All three paths feed into the same `BlobDetector` + `CoordinateMapper` + `OverlayView` pipeline.

---

## Detection Pipeline

```
Camera frame (ImageProxy / Bitmap)
        │
        ▼
  BlobDetector.detectDarkDotCenter()
  · Pass 1: luminance histogram → threshold = mean − kStd × σ
  · Pass 2: centroid of pixels below threshold
  · Pass 3: 2nd-moment circularity check
        │
        ▼
  DetectionFilter  (EMA smoothing, alpha = 0.35)
        │
        ▼
  CoordinateMapper.imageToView()
  · Accounts for FIT_CENTER letterboxing + sensor rotation
        │
        ▼
  OverlayView.addPoint()  →  CircleFit  →  runout readout
```

### Key tuning parameters (Detection Settings panel, ⚙ button)

| Parameter | Default | Effect |
|-----------|---------|--------|
| **kStd** | 1.0 | Higher → tighter threshold → only very dark pixels |
| **Min Circularity** | 0.5 | Higher → rejects non-circular blobs (shadows, screws) |
| **Target Radius** | overlay | Restricts detection to the draggable target circle |
| Nudge buttons (▲▼◀▶ / ± ) | — | Fine-tune target circle position and radius |

---

## Overlay & Calibration

1. **Target circle** — drag to place over the tool; edge-drag to resize; nudge buttons for fine adjustment
2. **Cal Wizard** (Cal button) — tap true center → tap +Y direction → tap two known-distance points
3. **Delta readout** — shows dX / dY offset in inches once calibrated

Calibration data (center, up-vector, in/px scale) is persisted in `SharedPreferences` and restored on launch.

---

## Authentication

- Login screen (`LoginActivity`) is the launcher; `MainActivity` is not exported
- Email/password + optional Google Sign-In via Firebase Auth
- Biometric re-authentication after 5 minutes of idle (`BiometricGate`)
- Sign-out clears the Firebase session and returns to `LoginActivity`

---

## Key Source Files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Main UI, camera orchestration, calibration wizard |
| `BlobDetector.kt` | Dark-dot detection (YUV ImageProxy + Bitmap overloads) |
| `DetectionFilter.kt` | EMA temporal smoothing |
| `CoordinateMapper.kt` | Image ↔ view coordinate transform (rotation + letterbox) |
| `OverlayView.kt` | Overlay rendering, target circle, CircleFit display |
| `CircleFit.kt` | Algebraic least-squares circle fit for runout readout |
| `DetectorConfig.kt` | All detection tuning parameters |
| `CalibrationData.kt` + `CalibrationStore.kt` | Calibration model + SharedPreferences persistence |
| `UvcFragment.kt` | USB camera (AUSBC), frame processing, stall watchdog |
| `SafeAspectRatioTextureView.kt` | SurfaceTexture wrapper with 500ms availability polling |
| `UsbDeviceUtils.kt` | Shared USB device utilities (isUvc, findFirst, getExtra) |
| `auth/LoginActivity.kt` | Firebase Auth login screen |
| `auth/AuthManager.kt` | Sign-in state + sign-out helper |
| `auth/BiometricGate.kt` | Biometric re-auth after idle timeout |

---

## Tests

```
app/src/test/java/com/etrsystems/axisight/
└── BlobDetectorTest.kt   — 15 unit tests (Robolectric, no device needed)
    · Detection accuracy on synthetic bitmaps
    · kStd threshold behaviour
    · Area gating (TOO_SMALL / TOO_LARGE)
    · Circularity rejection
    · Target mask, locked threshold
    · DetectionFilter EMA smoothing
```

---

## Known Limitations

- **CalibrationData** is stored in view-space pixels — calibration must be redone if the app is used on a different device or screen resolution
- **Google Sign-In** requires a real `default_web_client_id` from Firebase Console (placeholder ships in the repo)
- **USB camera** tested on UVC-class cameras (e.g. Teslong borescopes); non-UVC USB cameras are ignored
