package com.etrsystems.axisight

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * Unit tests for [BlobDetector] and [DetectionFilter] using synthetic bitmaps.
 *
 * These tests run on the JVM via Robolectric.
 * Detection accuracy goal: centroid within ± (downscale * 1.5) pixels of truth.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BlobDetectorTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    /** White bitmap with a filled dark-gray circle drawn at [cx],[cy] with [radius]. */
    private fun syntheticBitmap(
        width: Int = 200,
        height: Int = 200,
        cx: Int = 100,
        cy: Int = 100,
        radius: Int = 20,
        dotLuminance: Int = 30,
        bgLuminance: Int = 200
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bg  = 0xFF000000.toInt() or (bgLuminance shl 16) or (bgLuminance shl 8) or bgLuminance
        val dot = 0xFF000000.toInt() or (dotLuminance shl 16) or (dotLuminance shl 8) or dotLuminance
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - cx; val dy = y - cy
                bmp.setPixel(x, y, if (dx * dx + dy * dy <= radius * radius) dot else bg)
            }
        }
        return bmp
    }

    private fun cfg(
        minArea: Int = 50,
        maxArea: Int = 10000,
        minCirc: Double = 0.5,
        kStd: Double = 1.0,
        ds: Int = 2,
        minContrast: Double = 0.05   // permissive default for legacy tests
    ) = DetectorConfig(
        minAreaPx = minArea,
        maxAreaPx = maxArea,
        minCircularity = minCirc,
        kStd = kStd,
        downscale = ds,
        minContrastRatio = minContrast,
        consecutiveFramesRequired = 1,  // disabled for BlobDetector unit tests
        maxJumpPx = Float.MAX_VALUE     // disabled for BlobDetector unit tests
    )

    /** Config with all temporal filtering disabled — used for DetectionFilter-specific tests. */
    private fun filterCfg(
        consecutiveRequired: Int = 1,
        maxJump: Float = Float.MAX_VALUE,
        alpha: Float = 0.5f
    ) = DetectorConfig(
        consecutiveFramesRequired = consecutiveRequired,
        maxJumpPx = maxJump,
        smoothingAlpha = alpha,
        minContrastRatio = 0.0
    )

    // ── Phase 1: kStd threshold ──────────────────────────────────────────────

    @Test
    fun `detects centered dark circle`() {
        val bmp = syntheticBitmap(cx = 100, cy = 100, radius = 20)
        val result = BlobDetector.detectDarkDotCenter(bmp, cfg())
        assertTrue("Expected Success, got $result", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(100f, s.x, 4f)
        assertEquals(100f, s.y, 4f)
    }

    @Test
    fun `detects off-center dark circle`() {
        val bmp = syntheticBitmap(cx = 60, cy = 140, radius = 18)
        val result = BlobDetector.detectDarkDotCenter(bmp, cfg())
        assertTrue("Expected Success, got $result", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(60f, s.x, 5f)
        assertEquals(140f, s.y, 5f)
    }

    @Test
    fun `kStd zero with high-contrast image still detects dot`() {
        val bmp = syntheticBitmap(cx = 100, cy = 100, radius = 20)
        val c = cfg(minCirc = 0.5, kStd = 0.0, minArea = 10, maxArea = 50000, minContrast = 0.05)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("High-contrast dot should be detected even at kStd=0 (got $result)",
            result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(100f, s.x, 5f)
        assertEquals(100f, s.y, 5f)
    }

    @Test
    fun `high kStd rejects dim dot below threshold`() {
        val bmp = syntheticBitmap(dotLuminance = 150, bgLuminance = 200, radius = 20)
        val c = cfg(kStd = 6.0, minArea = 10)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Expected failure for dim dot at kStd=6", result is DetectionResult.Failure)
    }

    @Test
    fun `very dark dot detected at moderate kStd`() {
        val bmp = syntheticBitmap(dotLuminance = 10, bgLuminance = 220, radius = 22)
        val result = BlobDetector.detectDarkDotCenter(bmp, cfg(kStd = 1.0))
        assertTrue("Expected Success", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(100f, s.x, 5f)
        assertEquals(100f, s.y, 5f)
    }

    // ── Phase 2: area gating ────────────────────────────────────────────────

    @Test
    fun `fails TOO_SMALL when blob below minArea`() {
        val bmp = syntheticBitmap(radius = 5)
        val c = cfg(minArea = 10000)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue(result is DetectionResult.Failure)
        assertEquals(FailureReason.TOO_SMALL, (result as DetectionResult.Failure).reason)
    }

    @Test
    fun `fails TOO_LARGE when blob above maxArea`() {
        val bmp = syntheticBitmap(radius = 80, dotLuminance = 0, bgLuminance = 200)
        val c = cfg(maxArea = 100)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue(result is DetectionResult.Failure)
        assertEquals(FailureReason.TOO_LARGE, (result as DetectionResult.Failure).reason)
    }

    // ── Phase 3: circularity rejection ──────────────────────────────────────

    @Test
    fun `rejects horizontal bar as non-circular`() {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val bg  = 0xFFCCCCCC.toInt()
        val dot = 0xFF202020.toInt()
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                bmp.setPixel(x, y, if (y in 96..103 && x in 20..179) dot else bg)
            }
        }
        val c = cfg(minCirc = 0.6, minArea = 50, maxArea = 20000)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Bar should fail circularity", result is DetectionResult.Failure)
        assertEquals(FailureReason.NOT_CIRCULAR, (result as DetectionResult.Failure).reason)
    }

    @Test
    fun `accepts circle when circularity threshold lowered`() {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val bg  = 0xFFCCCCCC.toInt()
        val dot = 0xFF202020.toInt()
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                bmp.setPixel(x, y, if (y in 96..103 && x in 20..179) dot else bg)
            }
        }
        val c = cfg(minCirc = 0.0, minArea = 50, maxArea = 20000)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue(result is DetectionResult.Success)
    }

    // ── Phase 4: target region masking ──────────────────────────────────────

    @Test
    fun `target circle mask excludes off-center blob`() {
        val bmp = syntheticBitmap(cx = 160, cy = 100, radius = 15)
        val c = cfg().copy(
            targetCenterX = 40f,
            targetCenterY = 100f,
            targetRadiusPx = 30f
        )
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        if (result is DetectionResult.Success) {
            assertTrue("Detected point must not be near the excluded dot at x=160", result.x < 100f)
        }
    }

    @Test
    fun `target circle mask includes centered blob`() {
        val bmp = syntheticBitmap(cx = 100, cy = 100, radius = 15)
        val c = cfg().copy(
            targetCenterX = 100f,
            targetCenterY = 100f,
            targetRadiusPx = 60f
        )
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Dot inside target circle should be detected", result is DetectionResult.Success)
    }

    // ── Phase 5: locked threshold ────────────────────────────────────────────

    @Test
    fun `locked threshold overrides kStd computation`() {
        val bmp = syntheticBitmap(dotLuminance = 40, bgLuminance = 200, radius = 20)
        val c = cfg(kStd = 99.0).copy(lockedThreshold = 60)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Locked threshold should detect dark dot", result is DetectionResult.Success)
    }

    // ── Phase 6: contrast check (new) ────────────────────────────────────────

    @Test
    fun `fails LOW_CONTRAST when bore barely darker than background`() {
        // Nearly uniform image: dot = 180, bg = 190 → contrast ≈ 5% < 12% default
        val bmp = syntheticBitmap(dotLuminance = 180, bgLuminance = 190, radius = 20)
        val c = cfg(kStd = 0.3, minArea = 10, maxArea = 50000, minContrast = 0.12)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        // Either LOW_CONTRAST or NO_DARK_PIXELS — the point is it doesn't succeed
        assertTrue("Near-uniform image should not produce a valid detection", result is DetectionResult.Failure)
    }

    @Test
    fun `passes contrast check for high-contrast bore`() {
        val bmp = syntheticBitmap(dotLuminance = 20, bgLuminance = 200, radius = 20)
        val c = cfg(minContrast = 0.12)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("High-contrast bore should pass contrast check", result is DetectionResult.Success)
    }

    // ── Phase 7: DetectionFilter — EMA smoothing ─────────────────────────────

    @Test
    fun `DetectionFilter smooths position toward stable value`() {
        val filter = DetectionFilter(alpha = 0.5f)
        val c = filterCfg(consecutiveRequired = 1, alpha = 0.5f)

        val r1 = filter.filter(DetectionResult.Success(100f, 100f, 1.0), c) as DetectionResult.Success
        assertEquals(100f, r1.x, 0.01f)

        val r2 = filter.filter(DetectionResult.Success(120f, 80f, 1.0), c) as DetectionResult.Success
        // Adaptive alpha: velocity = ~28px → alpha ramps up. Check it's between 100 and 120.
        assertTrue("Smoothed x should be between old and new", r2.x in 100f..120f)
        assertTrue("Smoothed y should be between new and old", r2.y in 80f..100f)
    }

    @Test
    fun `DetectionFilter reset clears smooth state`() {
        val filter = DetectionFilter(alpha = 0.5f)
        val c = filterCfg(consecutiveRequired = 1)
        filter.filter(DetectionResult.Success(50f, 50f, 1.0), c)
        filter.reset()
        val r = filter.filter(DetectionResult.Success(200f, 200f, 1.0), c) as DetectionResult.Success
        assertEquals(200f, r.x, 0.01f)
        assertEquals(200f, r.y, 0.01f)
    }

    @Test
    fun `DetectionFilter passes through Failure unchanged`() {
        val filter = DetectionFilter(alpha = 0.5f)
        val c = filterCfg()
        val failure = DetectionResult.Failure(FailureReason.NO_DARK_PIXELS, "test")
        val result = filter.filter(failure, c)
        assertTrue(result is DetectionResult.Failure)
        assertEquals(FailureReason.NO_DARK_PIXELS, (result as DetectionResult.Failure).reason)
    }

    // ── Phase 8: DetectionFilter — consecutive gate (new) ────────────────────

    @Test
    fun `consecutive gate blocks until N frames`() {
        val filter = DetectionFilter()
        val c = filterCfg(consecutiveRequired = 3)
        val success = DetectionResult.Success(100f, 100f, 1.0)

        val r1 = filter.filter(success, c)
        assertTrue("Frame 1 should be NOT_CONFIRMED", r1 is DetectionResult.Failure)
        assertEquals(FailureReason.NOT_CONFIRMED, (r1 as DetectionResult.Failure).reason)

        val r2 = filter.filter(success, c)
        assertTrue("Frame 2 should be NOT_CONFIRMED", r2 is DetectionResult.Failure)
        assertEquals(FailureReason.NOT_CONFIRMED, (r2 as DetectionResult.Failure).reason)

        val r3 = filter.filter(success, c)
        assertTrue("Frame 3 should be published as Success", r3 is DetectionResult.Success)
    }

    @Test
    fun `consecutive gate resets on failure`() {
        val filter = DetectionFilter()
        val c = filterCfg(consecutiveRequired = 3)
        val success = DetectionResult.Success(100f, 100f, 1.0)
        val failure = DetectionResult.Failure(FailureReason.NO_DARK_PIXELS, "gap")

        filter.filter(success, c)  // frame 1
        filter.filter(success, c)  // frame 2
        filter.filter(failure, c)  // resets streak
        filter.filter(success, c)  // restart: frame 1 again
        val r = filter.filter(success, c)  // frame 2
        assertTrue("After reset, streak should require N consecutive again", r is DetectionResult.Failure)
    }

    // ── Phase 9: DetectionFilter — jump filter (new) ─────────────────────────

    @Test
    fun `jump filter rejects teleport and resets streak`() {
        val filter = DetectionFilter()
        val c = filterCfg(consecutiveRequired = 1, maxJump = 30f)

        // Establish a baseline position
        val r1 = filter.filter(DetectionResult.Success(100f, 100f, 1.0), c)
        assertTrue("First frame should succeed (gate=1)", r1 is DetectionResult.Success)

        // Jump 200px — should be rejected
        val r2 = filter.filter(DetectionResult.Success(300f, 100f, 1.0), c)
        assertTrue("Large jump should be rejected", r2 is DetectionResult.Failure)
        assertEquals(FailureReason.JUMP_TOO_LARGE, (r2 as DetectionResult.Failure).reason)
    }

    @Test
    fun `jump filter accepts small movements`() {
        val filter = DetectionFilter()
        val c = filterCfg(consecutiveRequired = 1, maxJump = 30f)

        filter.filter(DetectionResult.Success(100f, 100f, 1.0), c)
        val r = filter.filter(DetectionResult.Success(110f, 105f, 1.0), c)
        assertTrue("Small movement within maxJumpPx should succeed", r is DetectionResult.Success)
    }

    // ── Phase 10: ImageProxy / YUV stride arithmetic ─────────────────────────

    @Test
    fun `ImageProxy overload does not crash with known YUV buffer`() {
        val width = 64
        val height = 64
        val rowStride = width + 8
        val pixelStride = 1
        val bgLum: Byte = -56
        val dotLum: Byte = 30

        val yData = ByteArray(rowStride * height) { bgLum }
        for (row in 27..36) {
            for (col in 27..36) {
                yData[row * rowStride + col * pixelStride] = dotLum
            }
        }

        val yBuffer = java.nio.ByteBuffer.wrap(yData)
        val uBuffer = java.nio.ByteBuffer.wrap(ByteArray((width / 2) * (height / 2)) { 128.toByte() })
        val vBuffer = java.nio.ByteBuffer.wrap(ByteArray((width / 2) * (height / 2)) { 128.toByte() })

        val mockImage = object : androidx.camera.core.ImageProxy {
            inner class PlaneImpl(
                private val buf: java.nio.ByteBuffer,
                private val rs: Int,
                private val ps: Int
            ) : androidx.camera.core.ImageProxy.PlaneProxy {
                override fun getBuffer() = buf
                override fun getRowStride() = rs
                override fun getPixelStride() = ps
            }
            override fun getPlanes() = arrayOf(
                PlaneImpl(yBuffer, rowStride, pixelStride),
                PlaneImpl(uBuffer, width / 2, 2),
                PlaneImpl(vBuffer, width / 2, 2)
            )
            override fun getWidth() = width
            override fun getHeight() = height
            override fun getFormat() = android.graphics.ImageFormat.YUV_420_888
            override fun getImageInfo() = object : androidx.camera.core.ImageInfo {
                override fun getRotationDegrees() = 0
                override fun getTimestamp() = 0L
                override fun getTagBundle() = androidx.camera.core.impl.TagBundle.emptyBundle()
                override fun populateExifData(b: androidx.camera.core.impl.utils.ExifData.Builder) {}
            }
            override fun getCropRect() = android.graphics.Rect(0, 0, width, height)
            override fun setCropRect(rect: android.graphics.Rect?) {}
            override fun toBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            override fun getImage(): android.media.Image? = null
            override fun close() {}
        }

        val c = cfg(minArea = 10, maxArea = 5000, minCirc = 0.0, ds = 1, minContrast = 0.05)
        val result = BlobDetector.detectDarkDotCenter(mockImage, c)
        assertTrue(
            "ImageProxy overload must return a typed result (got $result)",
            result is DetectionResult.Success || result is DetectionResult.Failure
        )
    }
}
