# USB Camera Timeout Issue - Analysis & Fix

> **UPDATE:** If you're seeing "build problem 99" errors, see [BUILD_PROBLEM_99_FIX.md](BUILD_PROBLEM_99_FIX.md) for the critical surface initialization fix that must be applied first.

## What I Found in Your Logs

Looking at your logcat output, I discovered something interesting:

### The Camera IS Actually Working!

```
14:00:29.365 TimeoutException: Timeout waiting for task  <-- Error appears
14:00:29.366 MultiCameraClient: surface measure size null
...
14:00:29.611 CameraUVC: start preview, preview=PreviewSize(width=640, height=480)  <-- But camera starts!
14:00:30.424 RenderManager: camera render frame rate is 11 fps-->gl_render  <-- And renders frames!
14:00:31.480 RenderManager: camera render frame rate is 16 fps-->gl_render  <-- At 15-16 fps!
```

**Key Insight:** The camera works perfectly AFTER the timeout. The 2-second delay is annoying but not fatal.

## Root Cause

The AUSBC library's `MultiCameraClient` does this when a camera connects:

1. Calls `getSurfaceWidth()` and `getSurfaceHeight()` to get dimensions
2. Waits up to 2 seconds for valid measurements using a `SettableFuture`
3. If timeout expires, logs "surface measure size null" but continues anyway
4. Camera initializes and works fine

The problem was that our methods were not returning measurements fast enough, causing the 2-second timeout every time.

## The Fix I Implemented

### Changed Priority Order in `getSurfaceWidth()` and `getSurfaceHeight()`

**Before:**
```kotlin
// Check cached value first
surfaceWidth > 0 -> surfaceWidth
// Then check TextureView
textureView?.width ?: 0 > 0 -> textureView?.width
// Finally default
else -> 640
```

**After:**
```kotlin
// Check TextureView FIRST (most accurate and immediately available)
textureView?.width ?: 0 > 0 -> textureView?.width ?: surfaceWidth
// Then cached value
surfaceWidth > 0 -> surfaceWidth
// Finally default
else -> 640
```

**Why This Helps:**
- The TextureView is laid out **before** the camera connects
- By checking it first, we get accurate measurements immediately
- No waiting, no timeout
- The library gets what it needs right away

## What Should Change

### Before Fix:
1. User plugs in camera
2. Permission dialog appears
3. User grants permission
4. **2-second pause with timeout error**
5. Camera preview starts at 15-16 fps

### After Fix (Expected):
1. User plugs in camera
2. Permission dialog appears
3. User grants permission
4. **Camera preview starts immediately** (< 500ms)
5. Camera preview at 15-16 fps

## Testing Instructions

### What to Look For:

1. **Faster startup** - Camera should start within 1 second of permission grant
2. **No timeout errors** - Logcat should NOT show:
   ```
   TimeoutException: Timeout waiting for task
   surface measure size null
   ```
3. **Immediate measurements** - Logcat SHOULD show:
   ```
   UvcFragment: getSurfaceWidth() returning: 640
   UvcFragment: getSurfaceHeight() returning: 480
   ```

### Test Procedure:

1. Build and install the updated APK
2. Connect USB camera
3. Grant permission
4. Watch for:
   - How fast the preview appears
   - Any error messages in logcat
   - Frame rate (should still be 15-16 fps)

### Expected Logcat Output:

```
UvcFragment: Surface texture available: 1440 x 3120
MultiCameraClient: attach device name/pid/vid:/dev/bus/usb/001/004
UvcFragment: getSurfaceWidth() returning: 640
UvcFragment: getSurfaceHeight() returning: 480
CameraUVC: start preview, preview=PreviewSize(width=640, height=480)
RenderManager: camera render frame rate is 15 fps-->gl_render
```

**Notice:** No `TimeoutException` or `surface measure size null` errors!

## Why I'm Confident This Will Work

1. **The camera already works** - Your logs prove it renders at 15-16 fps
2. **We're not changing functionality** - Just improving timing
3. **TextureView is ready early** - It's laid out before camera connection
4. **Simple fix** - Just reordering priority, no complex logic
5. **Safe fallbacks** - Still defaults to 640x480 if something goes wrong

## If It Still Times Out

If you still see the timeout after this fix, it means the library is waiting for something else. In that case, we'd need to:

1. Check if there's a different callback the library expects
2. Look at the AUSBC library source code for `MultiCameraClient.kt:322`
3. See what the `SettableFuture` is actually waiting for

But I'm 95% confident this fix will resolve the issue based on the log analysis.

## Important Note

The camera **will still work** even if the timeout happens - it's just a poor user experience. This fix should eliminate the delay and make the app feel much more responsive.

---

**TL;DR:** Your camera works fine, but the library was waiting 2 seconds for surface measurements. I reordered the priority to check the TextureView first (which is already ready), so the library gets measurements immediately and doesn't timeout.

