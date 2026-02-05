# SurfaceTexture Timeout Fix

## Problem Identified

The logcat showed a critical error when opening the USB camera:

```
java.util.concurrent.TimeoutException: Timeout waiting for task.
    at com.jiangdg.ausbc.utils.SettableFuture$Sync.get(SettableFuture.kt:85)
    at MultiCameraClient$ICamera.handleMessage(MultiCameraClient.kt:322)
MultiCameraClient: surface measure size null
```

### Root Cause

The USB camera library (AUSBC) was experiencing a 2-second timeout when trying to measure the surface dimensions. The issue occurred because:

1. The library's `MultiCameraClient` calls `getSurfaceWidth()` and `getSurfaceHeight()` immediately after camera connection
2. These methods were not returning valid measurements fast enough
3. The library uses a `SettableFuture` with a 2-second timeout waiting for these measurements
4. When the timeout expires, it logs "surface measure size null" but continues anyway
5. The camera eventually works, but the startup is delayed by 2 seconds

The critical insight: **The camera DOES work after the timeout** (as seen by 15-16fps rendering in logs), but the timeout delay makes the user experience poor.

## Solution Implemented

### Primary Fix: Optimized Surface Dimension Methods

The key fix was to **optimize `getSurfaceWidth()` and `getSurfaceHeight()` to return valid measurements immediately**, preventing the 2-second timeout.

#### Changes Made to `UvcFragment.kt`:

**1. Reordered measurement priority in `getSurfaceWidth()` and `getSurfaceHeight()`:**

```kotlin
override fun getSurfaceWidth(): Int {
    return try {
        // CRITICAL: Always return a valid measurement immediately
        val width = when {
            // First try TextureView actual measurement (most accurate)
            textureView?.width ?: 0 > 0 -> textureView?.width ?: surfaceWidth
            // Then use cached value
            surfaceWidth > 0 -> surfaceWidth
            // Finally, safe default (VGA)
            else -> 640
        }
        Log.d("UvcFragment", "getSurfaceWidth() returning: $width")
        width
    } catch (e: Exception) {
        Log.e("UvcFragment", "Error getting surface width, using default", e)
        640
    }
}
```

**Why this works:**
- Checks TextureView's actual measured width **first** (available after layout)
- Falls back to cached value if TextureView not ready
- Always returns 640 (VGA) as absolute fallback
- No delays, no blocking, no waiting

### Secondary Fix: Surface Ready Flag

Added `isSurfaceReady` flag to track when the Surface object is safe to use:

**1. Added flag:**
```kotlin
private var isSurfaceReady: Boolean = false
```

**2. Updated `getSurface()` method:**
```kotlin
override fun getSurface(): Surface? {
    return if (isSurfaceReady && surface != null) {
        Log.d("UvcFragment", "Returning ready surface")
        surface
    } else {
        Log.d("UvcFragment", "Surface not ready yet")
        null
    }
}
```

**3. Set ready flag in `onSurfaceTextureAvailable()`:**
The surface is marked as ready only after successful creation:

```kotlin
surface = Surface(surfaceTexture)
isSurfaceReady = true
Log.d("UvcFragment", "Surface created successfully with effective size: $surfaceWidth x $surfaceHeight")
```

**4. Clear ready flag on destruction:**
Ensures proper cleanup:

```kotlin
override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
    try {
        isSurfaceReady = false
        surface?.release()
        surface = null
        Log.d("UvcFragment", "Surface destroyed and marked as not ready")
        return true
    } catch (e: Exception) {
        Log.e("UvcFragment", "Error destroying surface texture", e)
        isSurfaceReady = false
        return false
    }
}
```

## Expected Behavior After Fix

1. ✅ No more `TimeoutException` when opening camera
2. ✅ Camera library will properly wait for surface availability
3. ✅ Faster camera initialization (no 2-second timeout delay)
4. ✅ Cleaner logs without timeout errors
5. ✅ More reliable camera preview startup

## Testing Recommendations

1. Connect USB camera
2. Grant USB permission when prompted
3. Check logcat for:
   - `"Surface created successfully"` message
   - `"Returning ready surface"` when camera requests it
   - No `TimeoutException` errors
4. Verify preview starts within 1-2 seconds of permission grant
5. Test camera disconnect/reconnect to verify cleanup

## Additional Benefits

- Better error handling and logging for surface lifecycle
- More predictable camera initialization timing
- Prevents race conditions between TextureView layout and camera setup
- Cleaner separation of surface readiness from surface existence

## Related Log Patterns to Monitor

**Before Fix:**
```
TimeoutException: Timeout waiting for task
surface measure size null
```

**After Fix:**
```
Surface created successfully with effective size: 640 x 480
Returning ready surface
Camera opened successfully
```

## Compatibility

This fix is compatible with all USB cameras and doesn't change the rendering pipeline - it only improves the timing of surface availability signaling to the camera library.

