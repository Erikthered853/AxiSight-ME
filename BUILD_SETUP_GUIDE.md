# 🔧 Build Setup Guide - AxiSight USB Camera Edition

## Build Problem Resolution

### Issue: SDK Location Not Found

**Problem:**
```
Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'.
> SDK location not found. Define a valid SDK location with an ANDROID_HOME 
  environment variable or by setting the sdk.dir path in your project's local 
  properties file.
```

**Cause:** The Android SDK is not installed on your development machine.

### Solution: Install Android SDK

#### Option 1: Using Android Studio (Recommended)
1. Download Android Studio from https://developer.android.com/studio
2. Install Android Studio
3. Open Android Studio
4. Go to **Tools** → **SDK Manager**
5. Install the following:
   - SDK Platforms (API level 36 minimum, as per project requirement)
   - SDK Tools (latest)
6. Android Studio will automatically set `ANDROID_HOME` environment variable
7. SDK will be installed to: `C:\Users\YourUsername\AppData\Local\Android\Sdk`

#### Option 2: Command Line SDK Setup
1. Download Android SDK Command Line Tools from https://developer.android.com/studio
2. Extract to `C:\Android\cmdline-tools`
3. Run:
```batch
cd C:\Android\cmdline-tools\bin
sdkmanager --sdk_root=C:\Android "platforms;android-36" "build-tools;36.0.0" "system-images;android-36;default;x86_64"
```
4. Set environment variable `ANDROID_HOME=C:\Android`

#### Option 3: Update local.properties
If you already have Android SDK installed elsewhere:
1. Find your Android SDK location
2. Edit `local.properties` in the project root
3. Update the path:
```ini
sdk.dir=C:/path/to/your/Android/Sdk
```

### Verify Installation

1. Check ANDROID_HOME environment variable:
```bash
echo %ANDROID_HOME%
```

Should output something like: `C:\Users\YourUsername\AppData\Local\Android\Sdk`

2. Verify SDK tools exist:
```bash
ls %ANDROID_HOME%/platforms
ls %ANDROID_HOME%/build-tools
```

### Build After Installation

Once Android SDK is installed and `local.properties` is configured:

```bash
cd C:\Users\epeterson\Downloads\axisight-3_patched_usb\axisight-3

# Clean build
.\gradlew clean build

# Build debug APK
.\gradlew assembleDebug

# Build and install on device
.\gradlew installDebug

# Run tests
.\gradlew test
```

---

## Build Requirements

### Minimum Requirements
- **Gradle:** 8.13 (included via wrapper)
- **Java:** OpenJDK 17 or higher
- **Android SDK:** API level 36
- **Android Build Tools:** 36.0.0
- **Kotlin:** 2.2.21
- **Android Gradle Plugin:** 8.13.1

### Project Configuration
```gradle
minSdk = 26
targetSdk = 36
compileSdk = 36
jvmTarget = "17"
```

---

## local.properties Configuration

### Current State
```ini
sdk.dir=C:/Users/epeterson/AppData/Local/Android/Sdk
```

### How to Update
Edit `local.properties` and set `sdk.dir` to your Android SDK location.

**Windows Example:**
```ini
sdk.dir=C:/Users/YourUsername/AppData/Local/Android/Sdk
```

**macOS Example:**
```ini
sdk.dir=/Users/YourUsername/Library/Android/sdk
```

**Linux Example:**
```ini
sdk.dir=/home/YourUsername/Android/Sdk
```

### Important Notes
- Use forward slashes (`/`), not backslashes (`\`)
- Do NOT include the drive letter colon on Windows (use `C:/` not `C:\`)
- This file should NOT be committed to version control (it's in .gitignore)
- Each developer must set their own SDK path

---

## Build Troubleshooting

### Issue 1: "SDK location not found"
**Solution:** 
- Install Android SDK
- Update `local.properties` with correct path
- Verify path exists and contains `platforms` and `build-tools` directories

### Issue 2: "Failed to install the following Android SDK packages"
**Solution:**
- Open Android Studio
- Go to Tools → SDK Manager
- Accept all license agreements
- Install required SDK packages

### Issue 3: "Gradle daemon is busy"
**Solution:**
```bash
.\gradlew --stop
.\gradlew clean build
```

### Issue 4: "Out of memory" error
**Solution:**
Add to `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4096m
```

### Issue 5: Build cache issues
**Solution:**
```bash
.\gradlew clean
rm -r .gradle/
.\gradlew build
```

---

## Gradle Build Commands Reference

### Basic Commands
```bash
# List all available tasks
.\gradlew tasks

# Build the project
.\gradlew build

# Build debug APK only
.\gradlew assembleDebug

# Build release APK only
.\gradlew assembleRelease

# Install on connected device
.\gradlew installDebug

# Run tests
.\gradlew test

# Check code style
.\gradlew lint

# Clean build artifacts
.\gradlew clean
```

### Useful Options
```bash
# Skip tests
.\gradlew build -x test

# Skip lint
.\gradlew build -x lint

# Parallel build
.\gradlew build --parallel

# No daemon (more stable)
.\gradlew build --no-daemon

# Verbose output
.\gradlew build --debug

# Don't use cache
.\gradlew build --no-build-cache
```

---

## environment.gradle Configuration

If you need to customize builds further, create `gradle.properties` in the project root:

```properties
# JVM Arguments
org.gradle.jvmargs=-Xmx4096m

# Parallel builds
org.gradle.parallel=true

# Offline mode (use cached dependencies)
org.gradle.offline=false

# Build cache
org.gradle.build.cache=true

# Configure on demand
org.gradle.configureondemand=true
```

---

## Continuous Integration (CI) Setup

### GitHub Actions Example
```yaml
name: Build

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
      - name: Build with Gradle
        run: ./gradlew build
```

### Local CI Testing
```bash
# Simulate CI environment
.\gradlew clean build --no-daemon --offline
```

---

## Post-Build Artifacts

After successful build, find outputs in:

### APKs
```
app/build/outputs/apk/debug/app-debug.apk       (Debug APK)
app/build/outputs/apk/release/app-release.apk   (Release APK - if built)
```

### Reports
```
app/build/reports/lint-results-debug.html       (Lint report)
app/build/reports/tests/debug/index.html        (Test report)
build/reports/configuration-cache/              (Config cache report)
```

### Generated Files
```
app/build/generated/                            (Generated sources)
app/build/intermediates/                        (Build intermediates)
```

---

## Next Steps After Setup

1. **Install Android SDK**
   - Download Android Studio or command-line tools
   - Install required SDK packages (API 36)

2. **Update local.properties**
   - Set `sdk.dir` to your SDK location
   - Verify path is correct

3. **Verify Java Installation**
   ```bash
   java -version
   ```
   Should be Java 17 or higher

4. **Test Build**
   ```bash
   .\gradlew clean build
   ```

5. **Connect Device** (if testing on device)
   - Enable USB debugging
   - Connect via USB
   - Run: `.\gradlew installDebug`

---

## Helpful Resources

- **Android SDK Installation:** https://developer.android.com/studio/install
- **Gradle Documentation:** https://gradle.org/releases/
- **Android Gradle Plugin:** https://developer.android.com/reference/tools/gradle-api
- **Kotlin Compiler:** https://kotlinlang.org/
- **AndroidX Libraries:** https://developer.android.com/jetpack/androidx

---

## Support

### If Build Still Fails

1. Run with full debug output:
```bash
.\gradlew build --stacktrace --debug
```

2. Check Java version:
```bash
java -version
javac -version
```

3. Clear Gradle cache:
```bash
rm -r ~/.gradle/caches/
.\gradlew build
```

4. Try offline build (if dependencies cached):
```bash
.\gradlew build --offline
```

5. Check internet connection (for dependency download)

---

## Build Optimization Tips

1. **Enable Gradle Daemon** (faster builds)
   - Already enabled by default

2. **Use Parallel Builds**
   ```properties
   org.gradle.parallel=true
   ```

3. **Increase JVM Memory**
   ```properties
   org.gradle.jvmargs=-Xmx4096m
   ```

4. **Enable Build Cache**
   ```properties
   org.gradle.build.cache=true
   ```

5. **Skip Unnecessary Tasks**
   ```bash
   .\gradlew build -x test -x lint
   ```

---

## Build Success Checklist

- [ ] Android SDK installed
- [ ] ANDROID_HOME environment variable set
- [ ] local.properties points to correct SDK
- [ ] Java 17+ installed
- [ ] Git configured (if using version control)
- [ ] `.\gradlew clean build` completes successfully
- [ ] APK generated in `app/build/outputs/apk/debug/`
- [ ] No lint errors (warnings OK)

---

**Last Updated:** December 9, 2025  
**Version:** 0.1.0 USB-Ready  
**Status:** Build guide complete

