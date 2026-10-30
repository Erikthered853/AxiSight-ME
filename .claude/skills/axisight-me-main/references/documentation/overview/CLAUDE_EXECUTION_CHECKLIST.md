# AxiSight-ME Claude Execution Checklist

Use this file when handing the project to Claude so the work happens in a clean order.

## Recommended execution order

### Option 1: USB camera reliability first

1. Open Claude Code in the `AxiSight-ME` project root.

2. Run:

`/plan "Make the USB camera path production-ready in AxiSight-ME, focusing on permissions, reconnect, timeout handling, and preview recovery"`

3. Review the generated plan.

4. Then run:

`/build-fix "Fix USB camera startup and permission handling in UsbCameraActivity.kt and UvcFragment.kt"`

5. After implementation, run:

`/verify "Check the USB camera flow in UsbCameraActivity.kt and UvcFragment.kt for permission, reconnect, timeout, and lifecycle edge cases"`

6. Then run:

`/code-review`

7. Finish with:

`/update-docs "Update the AxiSight-ME docs to match the latest camera, overlay, and alignment behavior"`

## Option 2: Overlay accuracy next

1. Run:

`/plan "Make the alignment overlay accurate and easy to tune in AxiSight-ME, including aspect-ratio safety and operator adjustment controls"`

2. Then run:

`/tdd "Add tests for OverlayView and SafeAspectRatioTextureView so preview scaling and overlay placement stay correct across device rotations and preview sizes"`

3. After code changes, run:

`/verify "Review overlay rendering, coordinate mapping, preview scaling, and operator usability for the alignment UI"`

4. Then run:

`/code-review`

## Option 3: Detection and centerline accuracy

1. Run:

`/plan "Make AxiSight-ME's centerline detection more robust with better blob detection, noise tolerance, and circle-fit stability"`

2. Then run:

`/tdd "Add tests for BlobDetector.kt and CircleFit.kt using noisy and partial circle data"`

3. After implementation, run:

`/verify "Review BlobDetector.kt, CircleFit.kt, and detector configuration for false positives, unstable center estimates, and recovery behavior when tracking drops out"`

4. Then run:

`/quality-gate "Review this AxiSight-ME branch for correctness, reliability, maintainability, and production readiness"`

## Best overall handoff sequence

If you want the safest full-project order, do this:

1. USB camera reliability pass
2. Overlay accuracy pass
3. Detection accuracy pass
4. Final full-app verification
5. Documentation update

Use these final commands at the end:

`/verify "Review the whole app for Android runtime issues, broken build assumptions, and anything that could prevent reliable spindle alignment in production"`

`/quality-gate "Review this AxiSight-ME branch for correctness, reliability, maintainability, and production readiness"`

`/update-docs "Document how camera alignment, centerline detection, and overlay calibration work in AxiSight-ME"`

## Handoff notes for whoever runs Claude

- Run commands from the `AxiSight-ME` root folder.
- Let Claude finish one area before starting the next.
- Prefer `/tdd` for math and UI correctness work.
- Prefer `/build-fix` for Android runtime or Gradle issues.
- Always run `/verify` after implementation.
- Use `/code-review` or `/quality-gate` before accepting the result.

## Related file

For more prompt choices, see:

`CLAUDE_HANDOFF_PROMPTS.md`
