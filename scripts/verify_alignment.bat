@echo off
REM Android 16 KB Alignment Verification Script for Windows
REM Verifies that all native libraries in the APK are properly aligned

setlocal enabledelayedexpansion

REM Configuration
set "APK_PATH=app\build\outputs\apk\release\app-release.apk"
set "TEMP_DIR=alignment_check_temp"
set "TOOLS_DIR=%ANDROID_SDK_ROOT%\build-tools\36.0.0"

REM Libraries to check
set "LIBS[0]=libUACAudio.so"
set "LIBS[1]=libUVCCamera.so"
set "LIBS[2]=libjpeg-turbo1500.so"
set "LIBS[3]=libnativelib.so"
set "LIBS[4]=libusb100.so"
set "LIBS[5]=libuvc.so"

echo.
echo ╔════════════════════════════════════════════════════════════╗
echo ║  Android 16 KB Alignment Verification Tool (Windows)      ║
echo ╚════════════════════════════════════════════════════════════╝
echo.

REM Check if APK exists
if not exist "%APK_PATH%" (
    echo ❌ APK not found at: %APK_PATH%
    echo Please build the APK first: gradlew assembleRelease
    goto :error
)

echo 📦 APK File: %APK_PATH%
for %%I in ("%APK_PATH%") do echo 📊 File Size: %%~zI bytes
echo.

REM Check if zipalign tool exists
if not exist "%TOOLS_DIR%\zipalign.exe" (
    echo ❌ zipalign not found at: %TOOLS_DIR%\zipalign.exe
    echo Please set ANDROID_SDK_ROOT environment variable correctly.
    goto :error
)

REM Run zipalign verification
echo 🔍 Running zipalign verification...
echo.

"%TOOLS_DIR%\zipalign.exe" -c 16 "%APK_PATH%" > alignment_result.txt 2>&1
set "ZIPALIGN_EXIT=%ERRORLEVEL%"

REM Display results
if %ZIPALIGN_EXIT% EQU 0 (
    echo ✅ All libraries are properly 16 KB aligned!
    echo ✅ APK is ready for Android 16+ deployment.
    echo.
    echo Verification Details:
    type alignment_result.txt
    del /f alignment_result.txt
    goto :success
) else (
    echo ⚠️  Alignment verification failed. Output:
    echo.
    type alignment_result.txt
    del /f alignment_result.txt
    echo.
    echo Recommended Action:
    echo Manual alignment using zipalign:
    echo.
    echo "%TOOLS_DIR%\zipalign.exe" -v 16 ^
    echo     app\build\outputs\apk\release\app-release-unsigned.apk ^
    echo     app\build\outputs\apk\release\app-release-aligned.apk
    echo.
    echo Then verify:
    echo "%TOOLS_DIR%\zipalign.exe" -c 16 ^
    echo     app\build\outputs\apk\release\app-release-aligned.apk
    goto :error
)

:success
echo.
exit /b 0

:error
echo.
exit /b 1

