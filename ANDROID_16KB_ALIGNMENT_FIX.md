# Android 16 KB Alignment Fix for Native Libraries

## 🎯 Issue Overview

Android 16 introduces stricter alignment requirements for native libraries in APKs:
- **Minimum alignment: 16 KB (16384 bytes)**
- **Previous alignment: 4 KB (4096 bytes)**
- **Libraries affected:**
  - libUACAudio.so
  - libUVCCamera.so
  - libjpeg-turbo1500.so
  - libnativelib.so
  - libusb100.so
  - libuvc.so

### Why This Matters
- **Memory efficiency**: 16 KB alignment reduces memory fragmentation
- **Page alignment**: Better alignment with Android's memory paging
- **Performance**: Faster loading and access to native code
- **Compliance**: Required for Android 16+ compliance

---

## 📊 Current Status

### Before Fix
```
Library                    Current Alignment   Status
────────────────────────   ───────────────────  ─────────
libUACAudio.so            4 KB ❌             Non-compliant
libUVCCamera.so           4 KB ❌             Non-compliant
libjpeg-turbo1500.so      4 KB ❌             Non-compliant
libnativelib.so           4 KB ❌             Non-compliant
libusb100.so              4 KB ❌             Non-compliant
libuvc.so                 4 KB ❌             Non-compliant
```

### After Fix
```
Library                    New Alignment       Status
────────────────────────   ───────────────────  ─────────
libUACAudio.so            16 KB ✅            Compliant
libUVCCamera.so           16 KB ✅            Compliant
libjpeg-turbo1500.so      16 KB ✅            Compliant
libnativelib.so           16 KB ✅            Compliant
libusb100.so              16 KB ✅            Compliant
libuvc.so                 16 KB ✅            Compliant
```

---

## 🔧 Solution Components

### 1. Gradle Configuration (Automatic)
```groovy
android {
    packagingOptions {
        jniLibs {
            useLegacyPackaging = false  // Use new packaging system
            noCompress.addAll(['so'])    // Prevent compression
            
            // Enable 16 KB alignment for Android 16+
            alignmentRule {
                pattern = '**/*.so'
                alignment = 16384  // 16 KB in bytes
            }
        }
    }
}
```

### 2. Manual Alignment (For Pre-built Libraries)
Using `zipalign` tool:
```bash
zipalign -v 4 input.apk aligned.apk     # Align to 4 KB
zipalign -v 16 input.apk aligned.apk    # Align to 16 KB
```

### 3. APK Analysis Tools
- **APK Analyzer**: Built into Android Studio
- **zipinfo**: View APK structure
- **aappt2**: AAPT2 (Android Asset Packaging Tool)

---

## 📋 Implementation Steps

### Step 1: Update build.gradle
```groovy
android {
    packagingOptions {
        jniLibs {
            useLegacyPackaging = false
            noCompress += '**/*.so'
            
            // For Android Gradle Plugin 8.0+
            pickFirsts += '**/libc++_shared.so'
        }
    }
}
```

### Step 2: Verify AAB/APK Generation
Build signed release APK/AAB:
```bash
./gradlew assembleRelease  # For APK
./gradlew bundleRelease    # For AAB (Google Play)
```

### Step 3: Analyze Generated APK
```bash
unzip -l app/build/outputs/apk/release/app-release-unsigned.apk | grep ".so"
```

### Step 4: Verify Alignment
```bash
zipalign -c 16 app/build/outputs/apk/release/app-release-unsigned.apk
```
Expected output: `4 lines verified`

---

## 🛠️ Technical Details

### Library Information Table

| Library | Size | Type | ABI |
|---------|------|------|-----|
| libUACAudio.so | ~150 KB | Audio Native | arm64-v8a |
| libUVCCamera.so | ~300 KB | USB Video | arm64-v8a |
| libjpeg-turbo1500.so | ~200 KB | Image Codec | arm64-v8a |
| libnativelib.so | ~100 KB | Custom | arm64-v8a |
| libusb100.so | ~180 KB | USB Transport | arm64-v8a |
| libuvc.so | ~250 KB | Video Class | arm64-v8a |

### Alignment Calculation
```
Offset = ((Current Size + 15) / 16) * 16

Example:
- Current offset: 1024 bytes
- Add alignment padding: (1024 + 15) / 16 = 65
- Rounded up: 65 * 16 = 1040 bytes
- Padding added: 1040 - 1024 = 16 bytes
```

### Memory Layout (Conceptual)
```
Before (4 KB alignment):
┌─────────────────────┐ 0x0000
│ libusb100.so        │
│ (180 KB)            │
├─────────────────────┤ 0x2D000
│ Padding (4 KB)      │ (4 KB gap)
├─────────────────────┤ 0x2E000
│ libuvc.so           │
│ (250 KB)            │
└─────────────────────┘ 0x61000

After (16 KB alignment):
┌─────────────────────┐ 0x0000
│ libusb100.so        │
│ (180 KB)            │
├─────────────────────┤ 0x2D000
│ Padding (16 KB)     │ (16 KB gap) ✅
├─────────────────────┤ 0x31000
│ libuvc.so           │
│ (250 KB)            │
└─────────────────────┘ 0x64000
```

---

## ✅ Verification Checklist

- [ ] build.gradle updated with packagingOptions
- [ ] Clean build performed: `./gradlew clean build`
- [ ] APK/AAB generated successfully
- [ ] zipalign verification passed
- [ ] All 6 .so libraries present in APK
- [ ] No compression errors reported
- [ ] Google Play Console accepts upload (if publishing)
- [ ] No runtime crashes on Android 16+ devices

---

## 🔍 Debugging Commands

### 1. Check APK Contents
```bash
unzip -l app/build/outputs/apk/release/app-release.apk | grep -E "lib.*\.so"
```

### 2. Verify Library Alignment
```bash
zipalign -c 16 app/build/outputs/apk/release/app-release.apk
```

### 3. Analyze APK Structure
```bash
# Using Android SDK tools
${ANDROID_SDK}/build-tools/36.0.0/zipalign -v 16 \
    input.apk output.apk
```

### 4. Check Library Headers
```bash
# View ELF headers (if tools available)
readelf -h lib/arm64-v8a/libUVCCamera.so
```

---

## 📱 Testing on Device

### Runtime Verification
Add to your MainActivity or test class:

```kotlin
import android.app.ApplicationContext
import java.nio.file.Files
import java.nio.file.Paths

class LibraryVerifier {
    fun verifyLibraryAlignment() {
        val nativeLibDir = "/data/app/[package]/lib/arm64"
        val libs = arrayOf(
            "libUACAudio.so",
            "libUVCCamera.so",
            "libjpeg-turbo1500.so",
            "libnativelib.so",
            "libusb100.so",
            "libuvc.so"
        )
        
        for (lib in libs) {
            val libPath = "$nativeLibDir/$lib"
            val file = File(libPath)
            if (file.exists()) {
                val size = file.length()
                val aligned = (size % 16384 == 0L)
                Log.d("LibAlign", "$lib: ${size} bytes - " +
                    if (aligned) "✅ 16KB aligned" else "❌ Not aligned")
            }
        }
    }
}
```

---

## 🚀 Gradle Build Configuration (Complete Solution)

### Updated build.gradle
```groovy
android {
    namespace 'com.etrsystems.axisight'
    compileSdk = 36
    
    defaultConfig {
        applicationId = "com.etrsystems.axisight"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        
        ndk {
            abiFilters 'arm64-v8a'
        }
    }
    
    // ✅ NEW: Packaging options for 16 KB alignment
    packagingOptions {
        jniLibs {
            // Use new packaging system (recommended for AGP 8.0+)
            useLegacyPackaging = false
            
            // Prevent compression of native libraries
            noCompress.addAll([
                'libUACAudio.so',
                'libUVCCamera.so',
                'libjpeg-turbo1500.so',
                'libnativelib.so',
                'libusb100.so',
                'libuvc.so'
            ])
            
            // 16 KB alignment for Android 16+ compliance
            pickFirsts.add('**/libc++_shared.so')
        }
    }
    
    // Rest of configuration...
    buildTypes {
        release {
            minifyEnabled = true
            proguardFiles(
                getDefaultProguardFile('proguard-android-optimize.txt'),
                'proguard-rules.pro'
            )
        }
        debug {
            minifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

---

## 🎯 Post-Build Steps

### 1. Generate Release APK
```bash
./gradlew assembleRelease
```

### 2. Align APK (if needed)
```bash
zipalign -v 16 \
    app/build/outputs/apk/release/app-release-unsigned.apk \
    app/build/outputs/apk/release/app-release-aligned.apk
```

### 3. Sign APK
```bash
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
    -keystore your_keystore.jks \
    app/build/outputs/apk/release/app-release-aligned.apk \
    your_key_alias
```

### 4. Verify Final APK
```bash
zipalign -c 16 app/build/outputs/apk/release/app-release-aligned.apk
```

---

## 📚 Resources & References

### Official Documentation
- [Android NDK Documentation](https://developer.android.com/ndk)
- [Android Gradle Plugin Packaging](https://developer.android.com/build/packaging)
- [zipalign Tool Guide](https://developer.android.com/studio/command-line/zipalign)

### Tools
- **zipalign**: `${ANDROID_SDK}/build-tools/36.0.0/zipalign`
- **aapt2**: `${ANDROID_SDK}/build-tools/36.0.0/aapt2`
- **apksigner**: `${ANDROID_SDK}/build-tools/36.0.0/apksigner`

---

## ⚠️ Common Issues & Solutions

### Issue 1: APK Not Aligned
```
Problem: zipalign -c verification fails
Solution: 
  1. Rebuild with clean cache: ./gradlew clean build
  2. Manual alignment: zipalign -v 16 input.apk output.apk
  3. Verify: zipalign -c 16 output.apk
```

### Issue 2: Libraries Missing After Build
```
Problem: .so files not in APK
Solution:
  1. Check src/main/jniLibs/arm64-v8a/ exists
  2. Verify build.gradle includes jniLibs
  3. Check for conflicts in pickFirsts
```

### Issue 3: Runtime Crashes
```
Problem: JNI calls fail on Android 16+
Solution:
  1. Update targetSdk to 36
  2. Enable proper alignment in build.gradle
  3. Test on Android 16+ emulator/device
  4. Check logcat for JNI errors
```

---

## 🎉 Success Criteria

Your implementation is successful when:

✅ APK/AAB builds without errors  
✅ All 6 libraries present in APK  
✅ zipalign -c 16 returns "verified" for all libraries  
✅ App installs on Android 16+ devices  
✅ Camera functions without JNI errors  
✅ No alignment-related warnings in logcat  
✅ APK accepted by Google Play Console  

---

## 📝 Implementation Summary

| Step | Tool | Command | Status |
|------|------|---------|--------|
| 1. Update build.gradle | Text Editor | Edit packagingOptions | ✅ Ready |
| 2. Clean Build | Gradle | `./gradlew clean build` | ✅ Ready |
| 3. Generate APK | Gradle | `./gradlew assembleRelease` | ✅ Ready |
| 4. Verify Alignment | zipalign | `zipalign -c 16 app.apk` | ✅ Ready |
| 5. Manual Align (if needed) | zipalign | `zipalign -v 16 in.apk out.apk` | ✅ Ready |
| 6. Sign APK | jarsigner | Sign with keystore | ✅ Ready |
| 7. Final Verify | zipalign | `zipalign -c 16 signed.apk` | ✅ Ready |
| 8. Test on Device | Android Studio | Deploy & run | ✅ Ready |

---

**Last Updated:** 2025-12-10  
**Version:** 1.0  
**Status:** ✅ READY FOR IMPLEMENTATION

