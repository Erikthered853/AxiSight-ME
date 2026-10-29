# AxiSight Calibration Guide

AxiSight measures how far the detected tool tip sits from the spindle centerline and
reports the offset in inches. For that it needs two calibrations:

| Calibration | What it provides | Set by |
|---|---|---|
| **Center** | Where the spindle centerline is in the camera image | *Set Center* or *Find Center by Rotation* |
| **Scale & Direction** | How many inches one pixel spans, and which way is machine +Y | *Set Scale & Direction* |

Until both are set, the Delta readout shows `calibrate to get inch offsets`.
All three flows live under the **Cal** button. A condensed version of this guide is
available in the app itself: **Cal → How to Calibrate**.

## Before you start

- Point the camera at the bore and confirm the tool point is actually being detected:
  the Quality readout should show live `Fit / Sharp / Signal` values and the overlay
  should be plotting points. Calibrating against a wandering or absent detection wastes
  the effort.
- If detection is flaky, open the settings panel (gear) and adjust circularity,
  noise rejection (kStd), and contrast, or use **Lock** to freeze the detection
  threshold on the current lighting.
- Size the target circle so the dark bore fills well under half of it. The detection
  threshold is `mean − kStd × σ` over the pixels inside the circle, which assumes the
  bore is the minority; a tight circle around a large bore pushes the threshold below
  the bore itself and nothing is detected. Enlarge the circle or lower kStd if that
  happens. Anything that fits inside the circle passes the area gate, so a big circle
  does not reject a big bore.
- Zoom does not affect calibration — taps are recorded in unzoomed view coordinates —
  but feel free to zoom for visual confirmation.

## 1. Set Center

Moves the crosshair to the spindle centerline. Scale and direction are untouched.

1. **Cal → Set Center.**
2. Tap the exact center point on the video. The tap shows up as a pending marker and
   `Captured: x=… y=…` in the panel.
3. Press **Confirm**. Done — `Center saved.`

**Auto button:** instead of tapping, press **Auto** to average the next 30 frames of the
detected tool point. This is steadier than a fingertip and is the right choice when the
tool tip itself marks the centerline (e.g. a pin in the spindle bore).

## 2. Find Center by Rotation (most accurate)

The operator rotates the spindle by hand while the app traces the detected tool tip.
The traced arc is circle-fitted ([least-squares](app/src/main/java/com/etrsystems/axisight/CircleFit.kt));
the fitted center is the true centerline, independent of tool runout.

1. **Cal → Find Center by Rotation.**
2. Press **Start Rotation Capture**.
3. Rotate the spindle by hand — slowly and steadily, at least ¾ of a full turn.
   The panel counts samples and angular coverage live (`Rotating… N samples, P% coverage`).
4. Press **Finish Rotation Capture** (capture also auto-finishes at 600 samples).
5. Check the fit line (`Fit: N pts, P% coverage, rms=…px`), then press **Confirm**.

**Quality gate** — the fitted center is only accepted when all of these hold:

| Requirement | Threshold |
|---|---|
| Samples collected | ≥ 40 |
| Angular coverage | ≥ 75 % (≈ 270°) |
| Circle-fit RMS error | ≤ 3.0 px |

If the gate fails (`Trace too short or noisy`), rotate further and steadier, keep the
tool tip detected the whole way around, and try again.

## 3. Set Scale & Direction

Requires a saved center. Converts pixels to inches and defines the machine +Y axis.

1. **Cal → Set Scale & Direction.** (If you get `Set center first`, do flow 1 or 2 first.)
2. **Step 1 of 3 — direction:** tap a point *straight up* (machine +Y) from the center.
   Distance matters: the angular error shrinks with the length of this vector.
   Minimum 40 px; aim for the far edge of the frame.
3. **Step 2 & 3 — scale:** tap the two ends of a feature of *known length* — a gauge
   pin, a ruler graduation pair, anything measured. Use the longest span that fits.
   Minimum 80 px apart.
4. Type the feature's real length into the **Known in** field (default `0.1181` in
   = 3 mm) *before* confirming the final tap. The panel previews the quality estimate
   live on the last step.
5. Press **Confirm** on the last point. `Calibration saved.`

**Quality gate** — the wizard rejects the result (`Quality too low`) unless:

| Requirement | Threshold |
|---|---|
| Up-vector length | ≥ 40 px |
| Scale span | ≥ 80 px |
| Estimated combined error | ≤ 0.020 in |

The error estimate assumes ±1 px of tap uncertainty on each point, so longer spans
directly buy you accuracy. If rejected, press **Retry** and re-tap with longer spans.

## Wizard controls

| Button | Effect |
|---|---|
| **Confirm** | Accept the pending point and advance to the next step |
| **Retry** | Clear only the current step's point (and any capture in progress) |
| **Cancel** | Abandon the wizard entirely; nothing is saved |

## What is stored, and when to recalibrate

Calibration is persisted in **camera image space** (SharedPreferences), so it survives:

- app restarts,
- screen rotation,
- digital zoom,
- preview resolution / letterboxing changes.

It does **not** survive physically moving the camera or changing its optics — redo
*Center* (and *Scale* if the working distance changed) after any camera adjustment.
Switching camera sources (Internal / WiFi / USB) changes the image geometry, so
recalibrate per source.

## After calibrating

- The **Delta** readout shows `dX / dY` in inches between the detected tool point and
  the calibrated center, using the calibrated +Y direction.
- The **in/px** field shows (and lets you hand-edit) the view-space inches-per-pixel.
- **CSV → Log Alignment** records a labeled `dX/dY` snapshot; **Export Alignment
  History** writes the accumulated records to a CSV file.

## Troubleshooting

| Message | Cause | Remedy |
|---|---|---|
| `No detection — point camera at the bore first.` | Auto center pressed with no live tool point | Get detection working first (see *Before you start*) |
| `Set center first before setting scale.` | Scale wizard started with no saved center | Run *Set Center* or *Find Center by Rotation* |
| `Quality too low. Use longer spans…` | Up/scale taps too close together | Re-tap with longer spans |
| `Trace too short or noisy — rotate further/steadier…` | Rotation capture failed the gate | Rotate ≥ ¾ turn, keep detection continuous |
| `Calibration failed. Retry wizard.` | Internal guard tripped (e.g. zero-length up vector, invalid known length) | Restart the wizard; check the *Known in* value |
| Delta stuck on `waiting for tool detection` | Calibrated, but no current detection | Re-aim / re-tune detection |
| `Quality: no detection` with a tight target circle | Bore fills most of the circle, so the adaptive threshold collapses | Enlarge the target circle or lower kStd |
