# ⚡ Quick Build Fix Checklist

## 🔴 Current Build Error

```
SDK location not found. Define a valid SDK location with an ANDROID_HOME 
environment variable or by setting the sdk.dir path in your project's local 
properties file.
```

## ✅ Quick Fixes (Try in Order)

### Fix 1: Update local.properties (if SDK exists elsewhere)
```bash
# Edit this file:
local.properties

# Set to your Android SDK location:
sdk.dir=C:/path/to/Android/Sdk

# Note: Use forward slashes (/) not backslashes (\)
```

### Fix 2: Set ANDROID_HOME Environment Variable
**Windows Command Prompt:**
```batch
setx ANDROID_HOME "C:\Users\YourUsername\AppData\Local\Android\Sdk"
setx JAVA_HOME "C:\Program Files\Java\jdk-17"
```

**Windows PowerShell:**
```powershell
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Users\YourUsername\AppData\Local\Android\Sdk", "User")
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Java\jdk-17", "User")
```

### Fix 3: Verify SDK Installation
```bash
# Check if SDK exists
dir "C:\Users\YourUsername\AppData\Local\Android\Sdk"

# Should show folders like: platforms, build-tools, tools, etc.
```

### Fix 4: Install Android SDK

**Option A - Using Android Studio (Easiest)**
1. Download: https://developer.android.com/studio
2. Install Android Studio
3. Android Studio will auto-install SDK to: `C:\Users\YourUsername\AppData\Local\Android\Sdk`
4. SDK Manager will show installed platforms

**Option B - Using Command Line**
```bash
# Download Android SDK Command-line Tools from:
# https://developer.android.com/studio

# Create directories
mkdir C:\Android\cmdline-tools
mkdir C:\Android\platforms
mkdir C:\Android\build-tools

# Extract downloaded tools to C:\Android\cmdline-tools

# Set environment variable
setx ANDROID_HOME "C:\Android"

# Install SDK packages
C:\Android\cmdline-tools\bin\sdkmanager.bat --sdk_root=C:\Android "platforms;android-36" "build-tools;36.0.0"

# Accept licenses
C:\Android\cmdline-tools\bin\sdkmanager.bat --sdk_root=C:\Android --licenses
```

## 🧪 Test Build

After fixing, test the build:

```bash
cd C:\Users\epeterson\Downloads\axisight-3_patched_usb\axisight-3

# Clean build
.\gradlew clean build

# If that works, build APK
.\gradlew assembleDebug
```

## ✅ Success Indicators

Build should output:
```
✅ BUILD SUCCESSFUL
   Time: X seconds
   APK: app/build/outputs/apk/debug/app-debug.apk
```

## 🚨 Still Not Working?

### Debug Steps
```bash
# Show environment variables
echo %ANDROID_HOME%
echo %JAVA_HOME%

# Test Gradle
.\gradlew --version

# Run with debug info
.\gradlew build --stacktrace

# Clear cache and try again
.\gradlew clean --no-build-cache
.\gradlew build
```

### Check Java Version
```bash
java -version
```
Should be Java 17 or higher. If not:
- Download Java 17: https://adoptium.net/
- Set JAVA_HOME environment variable

### Check File Permissions
```bash
# Ensure files are readable
icacls "C:\Users\epeterson\Downloads\axisight-3_patched_usb\axisight-3" /grant:r %USERNAME%:F /T
```

## 📋 Summary of Required Setup

| Item | Status | Action |
|------|--------|--------|
| Java 17+ | ❓ | Install if missing |
| Android SDK API 36 | ❌ | **INSTALL REQUIRED** |
| Android Build Tools 36 | ❌ | **INSTALL REQUIRED** |
| local.properties | ✅ | Points to SDK location |
| ANDROID_HOME | ❌ | **SET if SDK installed** |
| Gradle | ✅ | Included (wrapper) |

## 🎯 Next Steps

1. **Install Android SDK** (if not installed)
   - Use Android Studio (recommended)
   - Or command-line tools

2. **Update local.properties**
   - Point `sdk.dir` to your SDK location

3. **Test Build**
   ```bash
   .\gradlew clean build
   ```

4. **If Successful**
   - Build APK: `.\gradlew assembleDebug`
   - Install on device: `.\gradlew installDebug`

---

**Need more details?** See `BUILD_SETUP_GUIDE.md` for comprehensive instructions.

