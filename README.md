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

`gradle.properties` pins `org.gradle.java.home` to Android Studio's bundled JDK on Windows. Override it (or set `JAVA_HOME`) on other machines.

### Install on a phone

```bash
# USB or wireless ADB (Developer options → Wireless debugging → Pair device with pairing code)
adb pair <phone-ip>:<pair-port> <code>
adb connect <phone-ip>:<connect-port>

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.etrsystems.axisight/.MainActivity
```

If `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the phone has a build signed with a different debug key; `adb uninstall com.etrsystems.axisight` first.

### Repository layout

```
app/src/main/java/com/etrsystems/axisight/   Kotlin sources (see Key Source Files)
app/src/main/res/                            Layouts, drawables, strings, themes
app/src/test/                                Robolectric unit tests
scripts/verify_alignment.{sh,bat}            Check 16 KB page alignment of native libs in a release APK
```

---

## Firebase Setup

1. Create a Firebase project and add the Android app (`com.etrsystems.axisight`)
2. Download `google-services.json` → place in `app/` (it is git-ignored and **not** committed; the build fails without it)
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
  · Pass 1: luminance histogram → threshold = mean − kStd × σ, + sharpness (Laplacian variance)
  · Pass 2: weighted centroid of pixels below threshold + contrast-ratio check
  · Pass 3: 2nd-moment circularity check
  · Pass 4: boundary refinement — radial edge-crossing points → CircleFit → sub-pixel center
        │
        ▼
  DetectionFilter  (EMA smoothing, alpha = 0.35)
        │
        ▼
  CoordinateMapper.imageToView()
  · Accounts for FIT_CENTER letterboxing + sensor rotation
        │
        ▼
  OverlayView.addPoint()  →  CircleFit  →  runout readout + quality readout (Fit/Sharp/Signal)
```

### Key tuning parameters (Detection Settings panel, ⚙ button)

| Parameter | Default | Effect |
|-----------|---------|--------|
| **kStd** (Noise Rejection) | 1.0 | Higher → tighter threshold → only very dark pixels |
| **Min Circularity** | 0.5 | Higher → rejects non-circular blobs (shadows, screws) |
| **Contrast Sensitivity** | 0.12 | Minimum bore-vs-background contrast ratio required to accept a detection |
| **Target Radius** | overlay | Restricts detection to the draggable target circle |
| Nudge buttons (▲▼◀▶ / ± ) | — | Fine-tune target circle position and radius |

All three sliders drive `DetectorConfig` for the Internal/WiFi sources and are also mirrored onto the USB (UVC) fragment's own independent detector config, since USB frames are processed on their own thread with their own `DetectorConfig` instance.

---

## Overlay & Calibration

1. **Target circle** — drag to place over the tool; edge-drag to resize; nudge buttons for fine adjustment
2. **Cal Wizard** (Cal button) — three modes:
   - **Set Center** — tap the true center directly (or use **Auto**, which averages a held-still tool over several frames)
   - **Set Scale & Direction** — tap +Y direction, then two known-distance points
   - **Find Center by Rotation** — rotate the spindle by hand while the tool trace is captured; a least-squares circle fit ([`CircleFit`](app/src/main/java/com/etrsystems/axisight/CircleFit.kt)) derives the true centerline, gated on angular coverage (~270°+) and fit RMS
3. **Delta readout** — shows dX / dY offset in inches once calibrated
4. **Quality readout** — color-coded Fit (circle-fit RMS)/Sharp (focus proxy)/Signal (circularity+contrast confidence) segments next to the delta, so you can tell whether to trust the current reading

Calibration data (center, up-vector, in/px scale) is persisted in `SharedPreferences` and restored on launch.

---

## Digital Zoom

`1x`/`2x`/`5x` buttons (top-right) apply a pivot-centered `scaleX`/`scaleY` transform to the active preview view and the overlay together, so the crosshair/target/points stay pixel-aligned with the zoomed image. This is purely a rendering transform — Android delivers touch events to a scaled view in that view's own unscaled local coordinates, so calibration taps, target-circle dragging, and all detection math are unaffected by zoom level. Zoom resets to 1x on camera-source switch.

---

## USB (UVC) Reliability

- **Stall watchdog** — if no frame arrives for `FRAME_STALL_THRESHOLD_MS`, the fragment reports an error which triggers an exponential-backoff reconnect (`scheduleUsbFragmentRetry`, up to `MAX_USB_FRAGMENT_RETRIES` attempts). After retries are exhausted, a Snackbar with a **Retry** action lets the operator retry immediately without replugging.
- **Cable-bump recovery** — `ACTION_USB_DEVICE_ATTACHED`/`DETACHED` broadcasts drive automatic reconnect/teardown, and a separate receiver recovers from a known `USBMonitor` permission-race crash.
- **Non-UVC devices** — if no UVC-class device is present, the user gets an explicit "No USB camera detected" message rather than a silent failure.

---

## Alignment History

The **CSV** button now opens a menu with three actions:
- **Export Points CSV** — the existing per-session runout-point export.
- **Log Alignment** — prompts for a tool/machine label and appends the current dX/dY reading plus the current circle-fit RMS to an append-only `alignment_history.csv` (`AlignmentHistoryStore`), so drift for a specific tool can be reviewed over time.
- **Export Alignment History** — reports the record count and file path of that history log.

---

## Authentication (dormant)

Firebase Auth code lives in `auth/` (`LoginActivity`, `AuthManager`, `BiometricGate`) but is currently **bypassed**: `MainActivity` is the launcher and nothing starts `LoginActivity`. The Firebase dependencies and `google-services.json` are still required because `AxisightApp` initialises Crashlytics/Analytics. To re-enable the gate, make `LoginActivity` the launcher again and route it to `MainActivity` after sign-in.

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
| `CalibrationData.kt` | Calibration model + `CalibrationStore` SharedPreferences persistence |
| `AlignmentHistoryStore.kt` | Append-only `alignment_history.csv` log of dX/dY readings per tool |
| `CsvLogger.kt` | Per-session runout-point CSV export |
| `AxisightApp.kt` | Application class: Firebase init, global crash handler |
| `UvcFragment.kt` | USB camera (AUSBC), frame processing, stall watchdog |
| `SafeAspectRatioTextureView.kt` | SurfaceTexture wrapper with 500ms availability polling |
| `UsbDeviceUtils.kt` | Shared USB device utilities (isUvc, findFirst, getExtra) |
| `auth/*` | Firebase Auth login, sign-in state, biometric gate (dormant, see above) |

---

## Tests

```
app/src/test/java/com/etrsystems/axisight/
├── BlobDetectorTest.kt          26 tests — detection on synthetic bitmaps, kStd threshold,
│                                 area gating, circularity, target mask, locked threshold, EMA filter
├── CircleFitTest.kt              5 tests — least-squares circle fit accuracy and degenerate input
├── CoordinateMapperTest.kt       7 tests — image ↔ view mapping with rotation + letterbox
└── AlignmentHistoryStoreTest.kt  4 tests — CSV append/read round-trip
```

All 42 run under Robolectric with `./gradlew testDebugUnitTest`; no device needed.

---

## Known Limitations

- **CalibrationData** is stored in camera image-space pixels (converted via `CoordinateMapper`) for the Internal and WiFi camera sources, so it survives screen resolution/orientation changes on those sources. The **USB (UVC)** source does not yet feed `CoordinateMapper` — its detection callback reports pre-offset coordinates directly — so calibration captured while on USB is still effectively view-space and should be redone if the screen resolution changes
- **Google Sign-In** requires a real `default_web_client_id` from Firebase Console (placeholder ships in the repo)
- **USB camera** tested on UVC-class cameras (e.g. Teslong borescopes); non-UVC USB cameras are ignored
- **Zoom buttons** (`1x`/`2x`/`5x`) render without visible labels on some devices; functionality is unaffected
- **Google Sign-In** uses the deprecated `GoogleSignIn` API (compiler warnings); migrate to Credential Manager if auth is re-enabled
