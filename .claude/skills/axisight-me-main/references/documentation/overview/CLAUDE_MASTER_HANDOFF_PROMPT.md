# AxiSight-ME Master Handoff Prompt

Paste the prompt below into Claude Code when you want Claude to take over this project in a structured way.

---

You are taking over work on the `AxiSight-ME` Android app.

Project context:
- This app helps align CNC machine tooling using a USB camera in the spindle.
- Key areas are USB camera reliability, overlay UI accuracy, and centerline detection math.
- Important files likely include:
  - `app/src/main/java/com/etrsystems/axisight/UsbCameraActivity.kt`
  - `app/src/main/java/com/etrsystems/axisight/ui/UvcFragment.kt`
  - `app/src/main/java/com/etrsystems/axisight/OverlayView.kt`
  - `app/src/main/java/com/etrsystems/axisight/ui/SafeAspectRatioTextureView.kt`
  - `app/src/main/java/com/etrsystems/axisight/BlobDetector.kt`
  - `app/src/main/java/com/etrsystems/axisight/CircleFit.kt`

Your goals:
1. Make the USB camera flow production-ready, especially permissions, reconnect behavior, timeout handling, and preview recovery.
2. Make the overlay accurate and easy to tune, especially coordinate mapping and aspect-ratio safety.
3. Make the blob detection and circle fitting pipeline more robust against noise, partial data, and tracking loss.

Execution requirements:
- Start by analyzing the existing codebase and current docs before changing code.
- Create a clear plan first.
- Prefer test-driven work for alignment math and UI correctness.
- Prefer build/debug-driven work for Android runtime, USB camera, and Gradle issues.
- After each implementation step, verify behavior and review for regressions.
- Update documentation to match any behavior changes.
- Do not stop after analysis only; carry work through implementation and verification unless blocked.

Work in this order:

Step 1:
`/plan "Make the USB camera path production-ready in AxiSight-ME, focusing on permissions, reconnect, timeout handling, and preview recovery"`

Step 2:
Implement the USB camera fixes.

Step 3:
`/verify "Check the USB camera flow in UsbCameraActivity.kt and UvcFragment.kt for permission, reconnect, timeout, and lifecycle edge cases"`

Step 4:
`/plan "Make the alignment overlay accurate and easy to tune in AxiSight-ME, including aspect-ratio safety and operator adjustment controls"`

Step 5:
Implement overlay improvements and add or update tests where appropriate.

Step 6:
`/verify "Review overlay rendering, coordinate mapping, preview scaling, and operator usability for the alignment UI"`

Step 7:
`/plan "Make AxiSight-ME's centerline detection more robust with better blob detection, noise tolerance, and circle-fit stability"`

Step 8:
Implement detection and math improvements with tests.

Step 9:
`/verify "Review BlobDetector.kt, CircleFit.kt, and detector configuration for false positives, unstable center estimates, and recovery behavior when tracking drops out"`

Step 10:
Run a final project-wide quality pass:
`/quality-gate "Review this AxiSight-ME branch for correctness, reliability, maintainability, and production readiness"`

Step 11:
Update docs:
`/update-docs "Document how camera alignment, centerline detection, and overlay calibration work in AxiSight-ME"`

Deliverables expected from you:
- working code changes
- tests where appropriate
- verification results
- a short summary of what changed, what remains risky, and what should be tested on-device

If you need to narrow scope, prioritize in this order:
1. USB camera reliability
2. Overlay correctness
3. Alignment math robustness

---
