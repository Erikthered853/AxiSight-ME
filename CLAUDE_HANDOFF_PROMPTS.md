# AxiSight-ME Claude Handoff Prompts

Use these inside Claude Code from the `AxiSight-ME` project root.

Recommended order:

1. `/plan ...`
2. `/tdd ...` or `/build-fix ...`
3. `/verify ...`
4. `/code-review`
5. `/update-docs ...`

## Full-app prompts

### Calibration and workflow

`/plan "Add a calibration wizard for the USB spindle camera and save profiles per machine"`

`/plan "Design a safer alignment workflow for AxiSight-ME so the operator can capture a 360-degree rotation, confirm the detected centerline, and save the result for repeat use"`

### Build and platform stability

`/build-fix "Fix the Android build and 16KB page-size compatibility issues in this app"`

`/verify "Review the whole app for Android runtime issues, broken build assumptions, and anything that could prevent reliable spindle alignment in production"`

### Documentation

`/update-docs "Document how camera alignment, centerline detection, and overlay calibration work in AxiSight-ME"`

## USB camera prompts

### Planning

`/plan "Improve USB camera reconnect flow when the Teslong camera is unplugged and replugged during use"`

`/plan "Improve the USB camera startup flow so AxiSight-ME handles permission prompts, delayed camera readiness, and preview recovery without confusing the user"`

### Implementation and debugging

`/build-fix "Fix USB camera startup and permission handling in UsbCameraActivity.kt and UvcFragment.kt"`

`/build-fix "Investigate why the camera preview can stay black or fail after reconnect, then implement the safest fix in UsbCameraActivity.kt and UvcFragment.kt"`

### Verification

`/verify "Review the USB camera path for missing permission checks, retry loops, black preview states, and timeout bugs"`

`/verify "Check the USB camera flow in UsbCameraActivity.kt and UvcFragment.kt for permission, reconnect, timeout, and lifecycle edge cases"`

## Overlay UI prompts

### Planning

`/plan "Add UI controls to adjust overlay size, opacity, and center offset while aligning a spindle"`

`/plan "Improve the alignment overlay UX so operators can clearly see the reference circle, live target, and fine adjustment controls during setup"`

### Test-driven work

`/tdd "Add tests for overlay coordinate mapping so OverlayView stays aligned with the camera preview at different aspect ratios"`

`/tdd "Add tests for OverlayView and SafeAspectRatioTextureView so preview scaling and overlay placement stay correct across device rotations and preview sizes"`

### Review and verification

`/code-review`

Use this after editing `OverlayView.kt`, `SafeAspectRatioTextureView.kt`, or related layout files.

`/verify "Review overlay rendering, coordinate mapping, preview scaling, and operator usability for the alignment UI"`

## Alignment math prompts

### Planning

`/plan "Improve centerline calculation from 360-degree rotation samples with better outlier rejection"`

`/plan "Improve the blob detection and circle fitting pipeline so AxiSight-ME stays accurate when samples are noisy, incomplete, or slightly off-center"`

### Test-driven work

`/tdd "Add tests for CircleFit.kt so noisy rotation samples still produce a stable centerline"`

`/tdd "Add tests for BlobDetector.kt and CircleFit.kt using noisy and partial circle data"`

### Verification

`/verify "Check alignment math accuracy, drift handling, and edge cases when the detected blob is lost mid-rotation"`

`/verify "Review BlobDetector.kt, CircleFit.kt, and detector configuration for false positives, unstable center estimates, and recovery behavior when tracking drops out"`

## Suggested focused handoffs

If you want Claude to work on one area at a time, start with one of these:

### USB camera reliability pass

`/plan "Make the USB camera path production-ready in AxiSight-ME, focusing on permissions, reconnect, timeout handling, and preview recovery"`

### Overlay accuracy pass

`/plan "Make the alignment overlay accurate and easy to tune in AxiSight-ME, including aspect-ratio safety and operator adjustment controls"`

### Detection accuracy pass

`/plan "Make AxiSight-ME's centerline detection more robust with better blob detection, noise tolerance, and circle-fit stability"`

## Final polish prompts

After Claude completes a feature or fix, run:

`/verify "Check this AxiSight-ME change for regressions, missing tests, Android lifecycle issues, and edge cases specific to USB camera alignment"`

`/quality-gate "Review this AxiSight-ME branch for correctness, reliability, maintainability, and production readiness"`

`/update-docs "Update the AxiSight-ME docs to match the latest camera, overlay, and alignment behavior"`
