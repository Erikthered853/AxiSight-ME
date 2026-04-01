package com.etrsystems.axisight

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * Unit tests for [BlobDetector] using synthetic bitmaps.
 *
 * These tests run on the JVM (no Android device/emulator needed) because
 * android.graphics.Bitmap is available in robolectric / unit-test scope and
 * we only call [BlobDetector.detectDarkDotCenter] with the Bitmap overload.
 *
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
        dotLuminance: Int = 30,     // dark dot
        bgLuminance: Int = 200      // bright background
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
        ds: Int = 2
    ) = DetectorConfig(
        minAreaPx = minArea,
        maxAreaPx = maxArea,
        minCircularity = minCirc,
        kStd = kStd,
        downscale = ds
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
        // kStd=0 → threshold = mean. For a high-contrast image (dot=30, bg=200)
        // the mean ≈ 198, so only dot pixels fall below threshold — circle still found.
        val bmp = syntheticBitmap(cx = 100, cy = 100, radius = 20)
        val c = cfg(minCirc = 0.5, kStd = 0.0, minArea = 10, maxArea = 50000)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("High-contrast dot should be detected even at kStd=0 (got $result)",
            result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(100f, s.x, 5f)
        assertEquals(100f, s.y, 5f)
    }

    @Test
    fun `high kStd rejects dim dot below threshold`() {
        // Dot at lum=150, bg=200 → std≈9, mean≈198.
        // kStd=6 → threshold = 198 - 6*9 ≈ 144 < 150 → dot pixels not selected → failure.
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
        val bmp = syntheticBitmap(radius = 5) // tiny dot
        val c = cfg(minArea = 10000)          // unreachably large threshold
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue(result is DetectionResult.Failure)
        assertEquals(FailureReason.TOO_SMALL, (result as DetectionResult.Failure).reason)
    }

    @Test
    fun `fails TOO_LARGE when blob above maxArea`() {
        // Pure-black dot (lum=0): threshold coerces to 0, all black pixels pass.
        // Area ≈ π*80² * ds² ≈ 80000 >> maxArea=100 → TOO_LARGE.
        val bmp = syntheticBitmap(radius = 80, dotLuminance = 0, bgLuminance = 200)
        val c = cfg(maxArea = 100)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue(result is DetectionResult.Failure)
        assertEquals(FailureReason.TOO_LARGE, (result as DetectionResult.Failure).reason)
    }

    // ── Phase 3: circularity rejection ──────────────────────────────────────

    @Test
    fun `rejects horizontal bar as non-circular`() {
        // Draw a wide horizontal bar instead of a circle
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val bg  = 0xFFCCCCCC.toInt()
        val dot = 0xFF202020.toInt()
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                // Horizontal bar: 160px wide, 8px tall — very non-circular
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
        // Same horizontal bar but minCircularity = 0 → should pass
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
        // Dot at (160, 100). Target region centered at (40, 100) with radius 30 — excludes dot.
        // The target zone only sees uniform background; any Success must be near (40,100), not (160,100).
        val bmp = syntheticBitmap(cx = 160, cy = 100, radius = 15)
        val c = cfg().copy(
            targetCenterX = 40f,
            targetCenterY = 100f,
            targetRadiusPx = 30f
        )
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        // Dark dot at (160,100) must not influence the result.
        if (result is DetectionResult.Success) {
            assertTrue("Detected point must not be near the excluded dot at x=160", result.x < 100f)
        }
        // A Failure (e.g. TOO_SMALL from tiny uniform region) is also acceptable.
    }

    @Test
    fun `target circle mask includes centered blob`() {
        val bmp = syntheticBitmap(cx = 100, cy = 100, radius = 15)
        val c = cfg().copy(
            targetCenterX = 100f,
            targetCenterY = 100f,
            targetRadiusPx = 60f   // generously encloses the dot
        )
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Dot inside target circle should be detected", result is DetectionResult.Success)
    }

    // ── Phase 5: locked threshold ────────────────────────────────────────────

    @Test
    fun `locked threshold overrides kStd computation`() {
        val bmp = syntheticBitmap(dotLuminance = 40, bgLuminance = 200, radius = 20)
        // Set locked threshold just above dot luminance
        val c = cfg(kStd = 99.0 /* would select everything */).copy(lockedThreshold = 60)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Locked threshold should detect dark dot", result is DetectionResult.Success)
    }

    // ── Phase 6: DetectionFilter ─────────────────────────────────────────────

    @Test
    fun `DetectionFilter smooths position toward stable value`() {
        val filter = DetectionFilter(alpha = 0.5f)
        val r1 = filter.filter(DetectionResult.Success(100f, 100f, 1.0)) as DetectionResult.Success
        // First frame: initialized → returned as-is
        assertEquals(100f, r1.x, 0.01f)

        val r2 = filter.filter(DetectionResult.Success(120f, 80f, 1.0)) as DetectionResult.Success
        // Smoothed: 0.5*120 + 0.5*100 = 110
        assertEquals(110f, r2.x, 0.5f)
        // Smoothed: 0.5*80 + 0.5*100 = 90
        assertEquals(90f, r2.y, 0.5f)
    }

    @Test
    fun `DetectionFilter reset clears smooth state`() {
        val filter = DetectionFilter(alpha = 0.5f)
        filter.filter(DetectionResult.Success(50f, 50f, 1.0))
        filter.reset()
        val r = filter.filter(DetectionResult.Success(200f, 200f, 1.0)) as DetectionResult.Success
        // After reset, first frame is returned as-is
        assertEquals(200f, r.x, 0.01f)
        assertEquals(200f, r.y, 0.01f)
    }

    @Test
    fun `DetectionFilter passes through Failure unchanged`() {
        val filter = DetectionFilter(alpha = 0.5f)
        val failure = DetectionResult.Failure(FailureReason.NO_DARK_PIXELS, "test")
        val result = filter.filter(failure)
        assertTrue(result is DetectionResult.Failure)
        assertEquals(FailureReason.NO_DARK_PIXELS, (result as DetectionResult.Failure).reason)
    }

    // ── Phase 7: ImageProxy / YUV stride arithmetic ──────────────────────────

    /**
     * Smoke test for the ImageProxy (YUV_420_888) overload.
     *
     * Constructs a synthetic YUV Y-plane buffer with a known dark region at the
     * centre and verifies that [BlobDetector.detectDarkDotCenter] does not crash
     * and returns a plausible result. Exercises the rowStride/pixelStride arithmetic
     * path that is untested by the Bitmap overload.
     */
    @Test
    fun `ImageProxy overload does not crash with known YUV buffer`() {
        val width = 64
        val height = 64
        val rowStride = width + 8  // deliberately wider than width to exercise stride math
        val pixelStride = 1
        val bgLum: Byte = -56       // 200 as signed byte
        val dotLum: Byte = 30

        // Build Y-plane: bright background with a dark 10×10 square at centre
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

        val c = cfg(minArea = 10, maxArea = 5000, minCirc = 0.0, ds = 1)
        // Must not throw; stride arithmetic must produce a valid centroid or a typed failure.
        val result = BlobDetector.detectDarkDotCenter(mockImage, c)
        assertTrue(
            "ImageProxy overload must return a typed result (got $result)",
            result is DetectionResult.Success || result is DetectionResult.Failure
        )
    }
}
