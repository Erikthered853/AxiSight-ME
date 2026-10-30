# AxiSight

CNC spindle-alignment app for Android. Mount a camera in the spindle, rotate 360°, and the app calculates the true centerline and overlays a digital reference so tools can be aligned faster and more accurately than with dial indicators or lasers.

---

## Requirements

| Item | Value |
|------|-------|
| Android | minSdk 26 (Android 8.0), targetSdk 36 / compileSdk 36 |
| Architecture | arm64-v8a only; all native libraries are 16 KB page-size aligned |
| Build tool | Gradle 8.13, AGP 8.13, Kotlin 2.2, JDK 17+ |
| Firebase project | Required (Crashlytics, Analytics, Auth) |

---

## Build

```bash
# Set JDK 17+ (gradle.properties points at Android Studio's bundled JDK by default)
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
| **Internal** | CameraX `ImageAnalysis` on the device's back camera (YUV `ImageProxy`) |
| **USB** | AUSBC `UvcFragment` via USB-OTG UVC camera; detection runs on the raw NV21 frames libuvc delivers (`IPreviewDataCallBack`), not on screenshots of the preview |
| **WiFi** | ExoPlayer RTSP stream (`rtsp://...`) rendered to `TextureView`; detection on the rendered bitmap |

All three paths feed the same `FrameAnalyzer` → `BlobDetector` → `DetectionFilter` → `CoordinateMapper` → `OverlayView` pipeline, so tuning sliders, the target circle, lock, temporal filtering and image-space calibration behave identically whichever source is active.

### 16 KB page-size compliance

The USB stack is `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0` (JitPack), a maintained fork of jiangdg/AndroidUSBCamera built with NDK r28. Its arm64-v8a `.so` files have 16 KB-aligned LOAD segments (verified with an ELF program-header check), and `packaging.jniLibs.useLegacyPackaging = false` keeps them uncompressed and page-aligned in the APK. The original 3.3.x artifacts ship 4 KB-aligned libraries and must not be reintroduced. The fork's 32-bit (armeabi-v7a / x86) libraries are still 4 KB-aligned, which is irrelevant here because the app ships arm64-v8a only.

---

## Detection Pipeline

```
Camera frame (ImageProxy / Bitmap / NV21 byte[])
        │
        ▼
  FrameAnalyzer
  · CoordinateMapper.update(image size, rotation, preview-view size + offset)
  · operator's view-space target circle → image space (per frame, per source)
        │
        ▼
  BlobDetector.detectDarkDotCenter()
  · Pass 1: luminance histogram → threshold = mean − kStd × σ, + sharpness (Laplacian variance)
  · Pass 2: connected components of pixels below threshold; any component above the area cap fails TOO_LARGE (no target placed), otherwise best weight × circularity² wins + contrast-ratio check
  · Pass 3: 2nd-moment circularity check
  · Pass 4: boundary refinement — radial edge-crossing points → CircleFit → sub-pixel center
        │
        ▼
  DetectionFilter  (jump gate, confirmation streak, adaptive EMA + dead-band)
  · jump gate releases on re-acquisition (N agreeing far frames) or after a tracking loss
        │
        ▼
  CoordinateMapper.imageToView()
  · Accounts for FIT_CENTER letterboxing, sensor rotation, and the USB preview's on-screen offset
        │
        ▼
  OverlayView.addPoint()  →  CircleFit  →  runout readout + quality readout (Fit/Sharp/Signal)
```

### Key tuning parameters (Detection Parameters panel, tune button top-right)

| Parameter | Default | Effect |
|-----------|---------|--------|
| **kStd** (Noise Rejection) | 1.5 | Higher → tighter threshold → only very dark pixels |
| **Min Circularity** | 0.65 | Higher → rejects non-circular blobs (shadows, screws) |
| **Contrast Sensitivity** | 0.12 | Minimum bore-vs-background contrast ratio required to accept a detection |
| **Target Radius** | overlay | Restricts detection to the draggable target circle. A blob that fits inside the circle is accepted even if it exceeds the fixed `maxAreaPx`, so large bores work without retuning |
| Nudge pad (arrows / − +) | — | Fine-tune target circle position and radius |

The target circle is kept in view space and converted to image space on every frame through the live `CoordinateMapper`, so detection always masks the right pixels for whichever source is running. It is persisted in two forms: a view-space copy (an immediate placeholder on the next launch) and an image-space copy tagged with the camera source. After a launch, a source switch, or an overlay resize (rotation), the image-space copy is re-projected through the mapper on the first frame, exactly like the calibration crosshair, so the circle lands back on the same part of the image. The overlay's own proportional rescale only bridges the gap until that frame arrives. Saves are debounced so dragging the circle does not rewrite preferences per touch event.

### Temporal filtering and tracking loss

`DetectionFilter` rejects any detection that jumps more than `maxJumpPx` (60 px) from the last accepted position, and requires `consecutiveFramesRequired` (3) successive hits before publishing. Two rules keep that gate from locking tracking out permanently:

- **Re-acquisition** — if the far-away position is seen on `consecutiveFramesRequired` consecutive frames (minimum 2) and those frames agree with each other within `maxJumpPx`, the tool has genuinely moved (or the camera was repositioned) and tracking snaps there without blending with the stale position.
- **Loss timeout** — after `trackingLossFrames` (15) consecutive misses the tool is considered lost; wherever it reappears is accepted after the normal confirmation streak, with no jump check against the stale position.

Scattered far detections that disagree with each other are still rejected as noise.

---

## Overlay & Calibration

1. **Target circle** — drag to place over the tool; edge-drag to resize; nudge buttons for fine adjustment
2. **Calibration** (Calibrate button) — three modes:
   - **Set Center** — tap the true center directly (or use **Auto**, which averages a held-still tool over several frames)
   - **Set Scale & Direction** — tap +Y direction, then two known-distance points
   - **Find Center by Rotation** — rotate the spindle by hand while the tool trace is captured; a least-squares circle fit ([`CircleFit`](app/src/main/java/com/etrsystems/axisight/CircleFit.kt)) derives the true centerline, gated on angular coverage (~270°+) and fit RMS. The fit is robust: samples more than 2.5 robust standard deviations off the circle (the detector briefly latching onto a scratch or reflection) are discarded iteratively, never more than half of them, and coverage/RMS are reported over the surviving inliers
3. **Delta readout** — shows dX / dY offset in inches once calibrated
4. **Quality readout** — color-coded Fit (circle-fit RMS)/Sharp (focus proxy)/Signal (circularity+contrast confidence) segments next to the delta, so you can tell whether to trust the current reading

Calibration data (center, up-vector, in/px scale) is persisted in camera image space in `SharedPreferences`, tagged with the camera source it was captured on, and restored on launch. Calibration is per source: a stored calibration is hidden on other sources, and capturing a new center on a different camera drops the old camera's scale/direction rather than re-tagging them. See [CALIBRATION_GUIDE.md](CALIBRATION_GUIDE.md) for the operator walkthrough (also available in-app under **Cal → How to Calibrate**).

---

## Digital Zoom

`1x`/`2x`/`5x` segmented control (bottom-right) apply a pivot-centered `scaleX`/`scaleY` transform to the active preview view and the overlay together, so the crosshair/target/points stay pixel-aligned with the zoomed image. This is purely a rendering transform — Android delivers touch events to a scaled view in that view's own unscaled local coordinates, so calibration taps, target-circle dragging, and all detection math are unaffected by zoom level. Zoom resets to 1x on camera-source switch.

---

## USB (UVC) Reliability

- **Raw-frame detection** — `UvcFragment` requests `setRawPreviewData(true)` and registers an `IPreviewDataCallBack`; each NV21 frame is handed to a dedicated processing thread with keep-latest semantics (a frame arriving while the previous one is still being analyzed is dropped), so detection never backs up the camera.
- **Stall watchdog** — if the camera is open but no frame arrives for `FRAME_STALL_THRESHOLD_MS` (3 s), the fragment reports an error which triggers a backoff reconnect (`scheduleFragmentRetry`, up to `MAX_USB_FRAGMENT_RETRIES` = 6 attempts, delays 0.6 s → 4 s). The attempt counter only resets when a camera actually opens or a camera is freshly plugged in, so a camera that keeps failing cannot loop forever. After retries are exhausted, a Snackbar with a **Retry** action lets the operator retry immediately without replugging.
- **Open watchdog** — if the camera never reaches the OPENED state within `OPEN_TIMEOUT_MS` (10 s) of the fragment resuming (black preview, no state callback at all), the same reconnect path runs.
- **Background/resume** — AUSBC's fragment keeps the camera open across pause/resume (it only closes it when the view is destroyed), so no OPENED callback arrives on resume. `UvcFragment` tracks the open state itself and restarts frame processing in `onResume`; without this, detection silently stopped after the app was backgrounded.
- **Permission flow** — the permission request uses a mutable, package-scoped `PendingIntent`. If the operator denies permission, or the system dialog is dismissed without an answer within `USB_PERMISSION_TIMEOUT_MS` (30 s), the app does not nag with automatic re-prompts: it shows a Snackbar with a **Retry** action instead, so the USB source is never silently stuck.
- **Cable-bump recovery** — `ACTION_USB_DEVICE_ATTACHED`/`DETACHED` broadcasts drive automatic reconnect/teardown, but only for UVC-class devices and, while a camera is open, only for that camera (`UsbDeviceUtils.concernsActiveCamera`). Unplugging a keyboard or flash drive leaves the camera alone. Several restart triggers arriving together are coalesced into one. A separate receiver recovers from a known `USBMonitor` permission-race crash.
- **Intentional teardown** — when the controller stops or replaces a fragment it detaches that fragment's state listener first, so the CLOSED it reports while being destroyed is not mistaken for a failure.
- **Non-UVC devices** — if no UVC-class device is present (device class or any interface class 0x0E), the user gets an explicit "No USB camera detected" message rather than a silent failure.

---

## Alignment History

The **Export** button opens a menu with three actions:
- **Export Points CSV** — the per-session runout-point export.
- **Log Alignment** — prompts for a tool/machine label and appends the current dX/dY reading plus the current circle-fit RMS to an append-only `alignment_history.csv` (`AlignmentHistoryStore`), so drift for a specific tool can be reviewed over time.
- **Export Alignment History** — reports the record count and file path of that history log.

---

## Authentication

- `MainActivity` is the launcher; the Firebase login gate (`auth/LoginActivity`, `AuthManager`, `BiometricGate`) is present but currently bypassed
- Email/password + optional Google Sign-In via Firebase Auth
- Biometric re-authentication after 5 minutes of idle (`BiometricGate`)

---

## Key Source Files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Main UI, camera orchestration, edge-to-edge insets, calibration panel rendering |
| `camera/FrameAnalyzer.kt` | Shared per-frame pipeline (mapper update → target conversion → detect → filter → view space) for all sources |
| `camera/InternalCameraSource.kt` | CameraX preview + analysis |
| `camera/WifiCameraSource.kt` | ExoPlayer RTSP into a `TextureView` |
| `camera/UsbCameraController.kt` | USB device discovery, permission flow, attach/detach, retry state machine, fragment hosting |
| `ui/UvcFragment.kt` | AUSBC `CameraFragment` subclass: NV21 frame callback, processing thread, stall watchdog |
| `ui/SafeAspectRatioTextureView.kt` | Aspect-fitted `TextureView` that never reports a zero surface size to AUSBC |
| `BlobDetector.kt` | Dark-dot detection (YUV `ImageProxy`, `Bitmap`, and NV21 `ByteArray` overloads) |
| `DetectionFilter.kt` | Temporal filtering (jump gate with re-acquisition and loss timeout, confirmation streak, adaptive EMA) |
| `CoordinateMapper.kt` | Image ↔ view transform (rotation, letterbox, preview-view offset) |
| `OverlayView.kt` | Overlay rendering, target circle (rescaled on view resize), CircleFit display |
| `CircleFit.kt` | Centred algebraic least-squares circle fit, plus `fitRobust` with iterative outlier rejection |
| `DetectorConfig.kt` | All detection tuning parameters |
| `CalibrationData.kt` + `CalibrationStore.kt` | Calibration model + SharedPreferences persistence |
| `presentation/CalibrationViewModel.kt` | Calibration wizard state machine (StateFlow + one-shot effects) |
| `domain/CalibrationQuality.kt`, `domain/RotationFit.kt` | Pure quality gates for manual and rotation calibration |
| `usb/UsbDeviceUtils.kt` | Shared USB device utilities (isUvc, findFirst, getExtra, attach/detach relevance) |
| `auth/*` | Firebase Auth login screen, sign-in state, biometric gate |

---

## Tests

```
app/src/test/java/com/etrsystems/axisight/
├── BlobDetectorTest.kt              — detection accuracy, gating, target mask, NV21 overload, sensor noise, clipped blobs
├── DetectionFilterTest.kt           — tracking loss: re-acquisition, loss timeout, scattered-noise rejection
├── CircleFitTest.kt                 — exact/jittered fits, far-from-origin conditioning, robust outlier rejection
├── CoordinateMapperTest.kt          — round trips, rotation, letterbox, preview-view offset
├── OverlayViewTest.kt               — target placement, proportional rescale on resize, clamping
├── CalibrationStoreTest.kt
├── AlignmentHistoryStoreTest.kt
├── camera/FrameAnalyzerTest.kt      — NV21 path: image→view mapping, target conversion, tracking gate
├── domain/CalibrationQualityEvaluatorTest.kt
├── domain/RotationFitEvaluatorTest.kt — coverage/RMS gates, glitch samples rejected, garbage traces still fail
├── presentation/CalibrationViewModelTest.kt
├── ui/SafeAspectRatioTextureViewTest.kt — aspect-fit measurement, non-zero surface size fallback
└── usb/UsbDeviceUtilsTest.kt        — attach/detach relevance rules
```

Run with `./gradlew testDebugUnitTest` (Robolectric, no device needed).

---

## Known Limitations

- **Google Sign-In** requires a real `default_web_client_id` from Firebase Console (placeholder ships in the repo), and the `GoogleSignIn` API it uses is deprecated in favour of Credential Manager
- **USB camera** tested on UVC-class cameras (e.g. Teslong borescopes); non-UVC USB cameras are ignored. The preview is requested at 640×480 MJPEG; AUSBC negotiates the nearest supported size
- **USB recovery paths are not unit-testable** — the AUSBC fragment needs native libuvc and a real `UsbManager`, so the reconnect/permission/resume behaviour above is verified by build + on-device testing, not Robolectric. Only the pure decision helpers are unit tested
- **Target circle placed before the first frame** — until a frame has refreshed the `CoordinateMapper`, the circle only exists in view space; its image-space copy is written on the first frame after placement
- **WiFi source** still analyzes a bitmap grabbed from the `TextureView` on the main thread on every rendered frame
