---
name: axisight-me-main
description: Local codebase analysis for AxiSight-ME-main - a CNC spindle-alignment Android app using camera-based centerline detection
doc_version: 1.0
---

# AxiSight-ME-main Codebase

## Description

**AxiSight** is a CNC spindle-alignment Android app written in Kotlin. An operator mounts a camera in the spindle, rotates 360°, and the app calculates the true centerline and overlays a digital reference so tools can be aligned faster and more accurately than with dial indicators or lasers.

**Path:** `D:\AxiSight\AxiSight-ME-main`
**Files Analyzed:** 34 (100% Kotlin)
**Min SDK:** 26 (Android 8.0) | **Target/Compile SDK:** 36 | **Kotlin:** 2.2 | **JDK:** 17+
**Architecture:** arm64-v8a only (16 KB page-size aligned native libs)

## When to Use This Skill

Use this skill when you need to:

- **Fix the USB camera flow** — permissions, reconnect, timeout, preview recovery (`UsbCameraController.kt`, `UvcFragment.kt`)
- **Work on overlay accuracy** — coordinate mapping, aspect-ratio safety, zoom (`CoordinateMapper.kt`, `OverlayView.kt`)
- **Improve detection math** — blob detection, circle fitting, noise tolerance (`BlobDetector.kt`, `CircleFit.kt`, `DetectionFilter.kt`)
- **Understand the calibration workflow** — Set Center, Set Scale & Direction, Find Center by Rotation
- **Navigate the detection pipeline** — how frames flow from camera to overlay
- **Review API signatures** for any of the 34 Kotlin classes
- **Hand the project off to Claude Code** — see the handoff prompts in `references/documentation/overview/`

## 🏗️ Architecture Overview

**Pattern:** MVVM (ViewModel + StateFlow effects for the calibration wizard)

### The Detection Pipeline (core concept)

All three camera sources feed the **same** pipeline:

```
Camera frame (ImageProxy / Bitmap / NV21 byte[])
        ▼
  FrameAnalyzer          · updates CoordinateMapper, converts target circle view→image space
        ▼
  BlobDetector           · luminance threshold → centroid → circularity → boundary refine
        ▼
  DetectionFilter        · jump gate, confirmation streak, adaptive EMA
        ▼
  CoordinateMapper       · image→view (handles letterbox, rotation, preview offset)
        ▼
  OverlayView → CircleFit → runout + quality readout
```

**Camera sources:**
| Mode | Mechanism |
|------|-----------|
| **Internal** | CameraX `ImageAnalysis` (YUV `ImageProxy`) |
| **USB** | AUSBC `UvcFragment` via USB-OTG UVC; detection on raw NV21 frames |
| **WiFi** | ExoPlayer RTSP → `TextureView`; detection on rendered bitmap |

## ⚡ Quick Reference

### Detect the dark-dot center of a bore (core detection)

```kotlin
// BlobDetector offers 3 overloads for the 3 frame types
val detector = BlobDetector()
val cfg = DetectorConfig()

val result: DetectionResult = detector.detectDarkDotCenter(bitmap, cfg)
// Also: detectDarkDotCenter(image: ImageProxy, cfg)
//       detectDarkDotCenter(nv21: ByteArray, width, height, cfg)
```

### Key tuning parameters (DetectorConfig)

```kotlin
// From Detection Settings panel (⚙ button). Defaults:
//   kStd (Noise Rejection)   = 1.5   higher → only very dark pixels pass
//   minCirc (Min Circularity) = 0.65  higher → rejects non-circular blobs
//   minContrast (Sensitivity) = 0.12  min bore-vs-background contrast ratio
//   maxArea / target radius   — blob fitting inside target circle is accepted
```

### Temporal filtering of detections

```kotlin
val filter = DetectionFilter()
val stable = filter.filter(result, cfg)   // jump gate + confirmation streak + EMA
filter.reset()                            // call when tracking is intentionally dropped
```

### Least-squares circle fit (centerline derivation)

```kotlin
// Feed detected tool-tip points from a 360° rotation
val points: List<Pair<Double, Double>> = /* traced arc */
val fit = CircleFit.fit(points)   // → Result? (center + radius), null if degenerate
```

### Coordinate mapping (image ↔ view space)

```kotlin
val mapper = CoordinateMapper()
mapper.update(imageWidth, imageHeight, rotationDegrees,
              viewWidth, viewHeight, viewOffsetX = 0f, viewOffsetY = 0f)

val (vx, vy) = mapper.imageToView(ix, iy)   // detection → screen
val (ix, iy) = mapper.viewToImage(vx, vy)   // tap → detection space
```

### Convert a tool offset to inches (calibration result)

```kotlin
val cal: CalibrationData? = CalibrationStore(context).load()
val (dxInches, dyInches) = cal!!.toolOffsetInches(toolX, toolY)

// Build calibration from center + up-vector + scale:
CalibrationData.fromCenterUpAndScale(
    centerX, centerY, upPointX, upPointY, inchesPerPixel
)   // → CalibrationData? (null if invalid)
```

### Gate rotation-based center calibration

```kotlin
// RotationFitEvaluator rejects a trace unless it passes quality gates:
//   samples ≥ 40,  angular coverage ≥ 75% (~270°),  circle-fit RMS ≤ 3.0 px
val quality: RotationFitQuality = RotationFitEvaluator.evaluate(samples)
```

### Persist / load calibration (survives restart, rotation, zoom)

```kotlin
val store = CalibrationStore(context)
store.save(calibrationData)
store.saveCenter(x, y)                        // center only
store.saveTarget(x, y, radiusPx)             // target circle
store.loadCenterOrNull()                      // → Pair<Float,Float>?
store.loadTargetOrNull()                      // → Triple<Float,Float,Float>?
```

### Detect UVC (USB) cameras

```kotlin
UsbDeviceUtils.isUvcDevice(device)                  // → Boolean
UsbDeviceUtils.findFirstUvcDevice(usbManager)       // → UsbDevice?
UsbDeviceUtils.getUsbDeviceExtra(intent)            // → UsbDevice? (from attach intent)
```

### Log and export alignment history

```kotlin
val store = AlignmentHistoryStore(context)
store.append(record)                 // append-only CSV
store.readAll()                      // → List<AlignmentRecord>
store.exportPath()                   // → String (file path)
```

## 📚 Reference Files

### `references/api_reference/` — Complete API Documentation
Per-class Kotlin API docs (methods, parameters, return types). Key files:

| File | What it covers |
|------|----------------|
| `BlobDetector.md` | Dark-dot detection core — 3 `detectDarkDotCenter` overloads, internal passes |
| `CircleFit.md` | `fit(points)` least-squares circle fit, `solve3x3` |
| `CoordinateMapper.md` | `imageToView`/`viewToImage`, radius conversions, logical mapping |
| `DetectionFilter.md` | `filter()` temporal smoothing, `reset()` |
| `CalibrationData.md` | `CalibrationData` + `CalibrationStore` (save/load center, target, source) |
| `CalibrationQuality.md` / `RotationFit.md` | Pure quality-gate evaluators |
| `CalibrationViewModel.md` | MVVM wizard state machine (Step enum, Factory) |
| `MainActivity.md` | Main UI orchestration, camera switching, calibration panel |
| `UsbCameraController.md` | USB discovery, permission flow, attach/detach, retry state machine |
| `UvcFragment.md` | AUSBC fragment: NV21 callback, processing thread, stall watchdog |
| `InternalCameraSource.md` / `WifiCameraSource.md` | CameraX and RTSP sources |
| `UsbDeviceUtils.md` | UVC detection helpers |
| `AuthManager.md` / `LoginActivity.md` | Firebase Auth (currently bypassed) |
| `*Test.md` | Test classes with synthetic-frame helpers (good usage examples) |

### `references/documentation/overview/` — Project Documentation
- **README.md** — ⭐ **Start here.** Full architecture, pipeline diagram, camera sources, tuning parameters, source-file map, tests, known limitations
- **CALIBRATION_GUIDE.md** — Operator walkthrough of all 3 calibration flows + quality gates + troubleshooting table
- **GEMINI.md** — Project overview, build commands, dev conventions
- **CLAUDE_EXECUTION_CHECKLIST.md** / **CLAUDE_HANDOFF_PROMPTS.md** / **CLAUDE_MASTER_HANDOFF_PROMPT.md** — Structured prompts for handing work to Claude Code (USB → overlay → detection → verify → docs)

### `references/config_patterns/` — Configuration
12 config files analyzed (gradle.properties, google-services.json, jitpack.yml, VS Code/Claude settings). 200 total settings.

### `references/patterns/` — Design Patterns
5 high-confidence patterns: Observer (3), Builder (1), Factory (1).

### `references/architecture/` & `references/dependencies/`
Architectural analysis (MVVM) and dependency graph.

## 🛠️ Working with This Skill

**To fix a bug or add a feature:**
1. Read `references/documentation/overview/README.md` for the architecture and the source-file map
2. Locate the relevant class in `references/api_reference/` for exact signatures
3. Check the corresponding `*Test.md` for usage patterns and synthetic-data helpers

**Common task → file mapping:**
- USB reliability → `UsbCameraController.md`, `UvcFragment.md`, `UsbDeviceUtils.md`
- Overlay/coordinate bugs → `CoordinateMapper.md`, `MainActivity.md`
- Detection accuracy → `BlobDetector.md`, `DetectionFilter.md`, `CircleFit.md`
- Calibration logic → `CalibrationData.md`, `CalibrationViewModel.md`, `CalibrationQuality.md`, `RotationFit.md`

**Build & test commands (from project root):**
```bash
./gradlew assembleDebug         # Debug APK → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest     # Unit tests (Robolectric, no device)
./gradlew lintDebug
```

## 🔑 Key Concepts

- **View space vs. image space** — The target circle and taps live in *view* coordinates; detection runs in *image* coordinates. `CoordinateMapper` bridges them every frame, accounting for letterboxing, sensor rotation, and USB preview offset. Calibration is persisted in **image space** so it survives rotation, zoom, and resolution changes.
- **Adaptive threshold** — Detection threshold = `mean − kStd × σ` over pixels inside the target circle. This assumes the bore is the *minority* of pixels, so a tight circle around a large bore can collapse the threshold (enlarge circle or lower kStd).
- **Quality readout (Fit/Sharp/Signal)** — Fit = circle-fit RMS, Sharp = focus proxy (Laplacian variance), Signal = circularity+contrast confidence.
- **Keep-latest frame semantics** — USB NV21 frames are dropped if a previous frame is still processing, so detection never backs up the camera.
- **16 KB page-size compliance** — Uses the `AndroidUSBCamera:libausbc:3.6.0` JitPack fork (NDK r28, 16 KB-aligned). Do **not** reintroduce 3.3.x artifacts.

---

**Generated by Skill Seeker** | Codebase Analyzer with C3.x Analysis