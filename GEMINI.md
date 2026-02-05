# Gemini Project Analysis: AxiSight

## Project Overview

This repository contains two main components:

1.  **AxiSight Android App:** An Android application named "AxiSight". Based on the `AndroidManifest.xml` and `app/build.gradle` files, this app's primary function involves using the device's camera. It requests camera permissions and includes the Android CameraX library. The application is written in Kotlin.

2.  **CNC Alignment Tool:** A standalone Python script (`cnc_alignment_app.py`) that provides a user interface for CNC machine alignment. This tool uses OpenCV for computer vision tasks and can optionally use PyQt5 for a more advanced GUI. It is a separate utility and not part of the Android application build.

## Building and Running

### AxiSight Android App

This is a standard Android Gradle project.

**Build:**

To build the application from the command line, use the Gradle wrapper:

```bash
./gradlew build
```

**Assemble a Debug APK:**

```bash
./gradlew assembleDebug
```

The output APK can be found in `app/build/outputs/apk/debug/`.

**Run:**

The application can be run directly from Android Studio or installed on a device/emulator using `adb`:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### CNC Alignment Tool (Python)

The Python script can be run directly if the required dependencies are installed.

**Dependencies:**

*   `opencv-python`
*   `numpy`
*   `PyQt5` (optional, for the GUI)

**Running the script:**

```bash
python cnc_alignment_app.py
```

You can run it with the `--help` flag to see available options:

```bash
python cnc_alignment_app.py --help
```

## Development Conventions

### Android

*   **Language:** Kotlin
*   **Java Version:** 17
*   **Build System:** Gradle
*   **Min SDK:** 26
*   **Target SDK:** 35

### USB Camera Support

To use a USB camera with this app, you will need to stream the camera feed over the network using a separate process (e.g., using RTSP). The Android app can then connect to this stream and display it using the "WiFi" camera source option.

A Raspberry Pi is a good choice for this task, as it has excellent support for USB cameras and there are many open-source tools available for streaming video.

### Python

*   The script is self-contained.
*   It uses `argparse` for command-line argument parsing.
*   It has a clear separation of concerns for camera management, detector configuration, and UI.
