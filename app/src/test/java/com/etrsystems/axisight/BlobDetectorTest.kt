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

    /** Same as [syntheticBitmap] but with a fractional (sub-pixel) true center. */
    private fun syntheticBitmapFractional(
        width: Int = 200,
        height: Int = 200,
        cx: Double,
        cy: Double,
        radius: Double = 20.0,
        dotLuminance: Int = 30,
        bgLuminance: Int = 200
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bg  = 0xFF000000.toInt() or (bgLuminance shl 16) or (bgLuminance shl 8) or bgLuminance
        val dot = 0xFF000000.toInt() or (dotLuminance shl 16) or (dotLuminance shl 8) or dotLuminance
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Sample at pixel centers so the rasterized edge approximates the true
                // analog circle boundary rather than snapping to an integer grid.
                val dx = (x + 0.5) - cx; val dy = (y + 0.5) - cy
                bmp.setPixel(x, y, if (dx * dx + dy * dy <= radius * radius) dot else bg)
            }
        }
        return bmp
    }

    /**
     * Dark circle with a left-to-right luminance gradient across its interior, simulating
     * an off-axis light source. A pure weighted centroid is pulled toward the darker side;
     * boundary-fit should stay centered since the gradient never crosses the threshold.
     */
    private fun syntheticGradientBitmap(
        width: Int = 200,
        height: Int = 200,
        cx: Int = 100,
        cy: Int = 100,
        radius: Int = 30,
        dotLumMin: Int = 10,
        dotLumMax: Int = 80,
        bgLuminance: Int = 220
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bg = 0xFF000000.toInt() or (bgLuminance shl 16) or (bgLuminance shl 8) or bgLuminance
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - cx; val dy = y - cy
                if (dx * dx + dy * dy <= radius * radius) {
                    val t = ((x - (cx - radius)).toDouble() / (2 * radius)).coerceIn(0.0, 1.0)
                    val lum = (dotLumMin + t * (dotLumMax - dotLumMin)).toInt()
                    bmp.setPixel(x, y, 0xFF000000.toInt() or (lum shl 16) or (lum shl 8) or lum)
                } else {
                    bmp.setPixel(x, y, bg)
                }
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

    // ── Phase 6b: boundary refinement (sub-pixel) ────────────────────────────

    @Test
    fun `resolves fractional true center tightly via boundary refinement`() {
        val trueCx = 100.35
        val trueCy = 99.62
        val bmp = syntheticBitmapFractional(cx = trueCx, cy = trueCy, radius = 30.0)
        val result = BlobDetector.detectDarkDotCenter(bmp, cfg(minArea = 100, maxArea = 20000))
        assertTrue("Expected Success, got $result", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(trueCx, s.x.toDouble(), 1.0)
        assertEquals(trueCy, s.y.toDouble(), 1.0)
    }

    @Test
    fun `boundary refinement resists lighting-gradient bias that would skew a weighted centroid`() {
        val bmp = syntheticGradientBitmap(cx = 100, cy = 100, radius = 30)
        val result = BlobDetector.detectDarkDotCenter(bmp, cfg(kStd = 1.0, minArea = 100, maxArea = 20000))
        assertTrue("Expected Success, got $result", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(100f, s.x, 2f)
        assertEquals(100f, s.y, 2f)
    }

    // ── Phase 6c: quality metrics on Success (circularity/contrast/sharpness) ─

    /** Circle whose edge fades over [bandPx] instead of stepping abruptly, simulating blur. */
    private fun syntheticBitmapSoftEdge(
        width: Int = 200,
        height: Int = 200,
        cx: Int = 100,
        cy: Int = 100,
        radius: Int = 30,
        bandPx: Double = 16.0,
        dotLuminance: Int = 30,
        bgLuminance: Int = 200
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dist = kotlin.math.hypot((x - cx).toDouble(), (y - cy).toDouble())
                val t = ((dist - (radius - bandPx / 2)) / bandPx).coerceIn(0.0, 1.0)
                val lum = (dotLuminance + t * (bgLuminance - dotLuminance)).toInt()
                bmp.setPixel(x, y, 0xFF000000.toInt() or (lum shl 16) or (lum shl 8) or lum)
            }
        }
        return bmp
    }

    @Test
    fun `success result reports high circularity and contrast for a clean circle`() {
        val bmp = syntheticBitmap(cx = 100, cy = 100, radius = 25, dotLuminance = 20, bgLuminance = 220)
        val result = BlobDetector.detectDarkDotCenter(bmp, cfg(minContrast = 0.1))
        assertTrue("Expected Success, got $result", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertTrue("circularity should be high for a clean disk, was ${s.circularity}", s.circularity > 0.9)
        assertTrue("contrastRatio should be well above zero, was ${s.contrastRatio}", s.contrastRatio > 0.3)
    }

    @Test
    fun `sharpness is higher for a crisp edge than a soft blurred edge`() {
        val crisp = syntheticBitmap(cx = 100, cy = 100, radius = 30, dotLuminance = 30, bgLuminance = 200)
        val soft = syntheticBitmapSoftEdge(cx = 100, cy = 100, radius = 30, bandPx = 16.0, dotLuminance = 30, bgLuminance = 200)

        val crispResult = BlobDetector.detectDarkDotCenter(crisp, cfg(minArea = 100, maxArea = 20000))
        val softResult = BlobDetector.detectDarkDotCenter(soft, cfg(minArea = 100, maxArea = 20000))
        assertTrue("Expected Success for crisp edge, got $crispResult", crispResult is DetectionResult.Success)
        assertTrue("Expected Success for soft edge, got $softResult", softResult is DetectionResult.Success)

        val crispSharpness = (crispResult as DetectionResult.Success).sharpness
        val softSharpness = (softResult as DetectionResult.Success).sharpness
        assertTrue(
            "Crisp edge should have higher Laplacian-variance sharpness ($crispSharpness) than soft edge ($softSharpness)",
            crispSharpness > softSharpness
        )
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

    // ── NV21 (USB raw frame) overload ────────────────────────────────────────

    /** Converts a bitmap to an NV21 buffer whose Y plane holds the bitmap's luminance. */
    private fun toNv21(bmp: Bitmap): ByteArray {
        val w = bmp.width; val h = bmp.height
        val out = ByteArray(w * h * 3 / 2)
        for (y in 0 until h) for (x in 0 until w) {
            val p = bmp.getPixel(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            out[y * w + x] = (r * 0.299 + g * 0.587 + b * 0.114).toInt().toByte()
        }
        java.util.Arrays.fill(out, w * h, out.size, 128.toByte())
        return out
    }

    @Test
    fun `nv21 overload matches bitmap overload on the same frame`() {
        val bmp = syntheticBitmap(cx = 70, cy = 120, radius = 22)
        val c = cfg(minArea = 100, maxArea = 20000)
        val fromBitmap = BlobDetector.detectDarkDotCenter(bmp, c)
        val fromNv21 = BlobDetector.detectDarkDotCenter(toNv21(bmp), bmp.width, bmp.height, c)
        assertTrue(fromBitmap is DetectionResult.Success)
        assertTrue(fromNv21 is DetectionResult.Success)
        fromBitmap as DetectionResult.Success; fromNv21 as DetectionResult.Success
        assertEquals(fromBitmap.x, fromNv21.x, 0.5f)
        assertEquals(fromBitmap.y, fromNv21.y, 0.5f)
        assertEquals(70f, fromNv21.x, 2f)
        assertEquals(120f, fromNv21.y, 2f)
    }

    @Test
    fun `nv21 overload rejects a buffer too short for its stated size`() {
        val c = cfg()
        val result = BlobDetector.detectDarkDotCenter(ByteArray(10), 200, 200, c)
        assertTrue(result is DetectionResult.Failure)
        assertEquals(FailureReason.NO_DATA, (result as DetectionResult.Failure).reason)
    }

    // ── Target-relative area gate ────────────────────────────────────────────

    @Test
    fun `blob larger than maxArea is accepted when it fits inside the target circle`() {
        // radius 45 -> area ~6360px, well above maxArea=3000 but inside a r=60 target.
        // kStd is lowered because the blob covers ~56% of the target region: the
        // adaptive threshold (mean - kStd*sigma) assumes the dark blob is a minority
        // of the region, and at kStd=1.0 it drops below the blob's own luminance.
        val bmp = syntheticBitmap(radius = 45)
        val base = cfg(minArea = 50, maxArea = 3000, kStd = 0.5)
        val without = BlobDetector.detectDarkDotCenter(bmp, base)
        assertEquals(FailureReason.TOO_LARGE, (without as DetectionResult.Failure).reason)

        val withTarget = base.copy(targetCenterX = 100f, targetCenterY = 100f, targetRadiusPx = 60f)
        val result = BlobDetector.detectDarkDotCenter(bmp, withTarget)
        assertTrue("blob inside operator target circle should be accepted", result is DetectionResult.Success)
        result as DetectionResult.Success
        assertEquals(100f, result.x, 2f)
        assertEquals(100f, result.y, 2f)
    }

    // ── Connected-component selection ────────────────────────────────────────

    /** Draws a dark [thickness]-px line from (x0,y0) to (x1,y1) onto [bmp]. */
    private fun drawScratch(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int = 3, lum: Int = 40) {
        val dark = 0xFF000000.toInt() or (lum shl 16) or (lum shl 8) or lum
        val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)).coerceAtLeast(1)
        for (k in 0..steps) {
            val x = x0 + (x1 - x0) * k / steps
            val y = y0 + (y1 - y0) * k / steps
            for (dy in 0 until thickness) for (dx in 0 until thickness) {
                val px = x + dx; val py = y + dy
                if (px in 0 until bmp.width && py in 0 until bmp.height) bmp.setPixel(px, py, dark)
            }
        }
    }

    @Test
    fun `round hole is found despite a dark scratch inside the target region`() {
        // Hole r=20 at (100,100) plus a long dark scratch 25px away. Taken together the
        // dark pixels are far from round; as separate components the hole wins.
        val bmp = syntheticBitmap(cx = 100, cy = 100, radius = 20)
        drawScratch(bmp, 40, 135, 170, 150)
        val c = cfg(minCirc = 0.65, minArea = 50, maxArea = 20000)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Expected Success, got $result", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(100f, s.x, 2f)
        assertEquals(100f, s.y, 2f)
        assertTrue("hole circularity should be high, was ${s.circularity}", s.circularity > 0.85)
    }

    @Test
    fun `hole beats a smaller round speck when both are in the area range`() {
        val bmp = syntheticBitmap(cx = 100, cy = 100, radius = 20)
        // Tiny round speck (r=4, area ~50px) well away from the hole.
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val dx = x - 40; val dy = y - 40
            if (dx * dx + dy * dy <= 16) bmp.setPixel(x, y, 0xFF1E1E1E.toInt())
        }
        val c = cfg(minCirc = 0.5, minArea = 20, maxArea = 20000)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Expected Success, got $result", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(100f, s.x, 2f)
        assertEquals(100f, s.y, 2f)
    }

    @Test
    fun `scratch alone with no hole is rejected as non-circular`() {
        val bmp = syntheticBitmap(radius = 0) // background only
        drawScratch(bmp, 40, 100, 160, 110)
        val c = cfg(minCirc = 0.65, minArea = 50, maxArea = 20000)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Expected Failure, got $result", result is DetectionResult.Failure)
        assertEquals(FailureReason.NOT_CIRCULAR, (result as DetectionResult.Failure).reason)
    }

    @Test
    fun `oversized bore is reported TOO_LARGE rather than a nearby speck being promoted`() {
        // Bore r=60 (~11300px) far above maxArea=3000; a round r=5 speck is in range.
        val bmp = syntheticBitmap(cx = 100, cy = 100, radius = 60)
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val dx = x - 20; val dy = y - 20
            if (dx * dx + dy * dy <= 25) bmp.setPixel(x, y, 0xFF1E1E1E.toInt())
        }
        val c = cfg(minCirc = 0.5, minArea = 20, maxArea = 3000, kStd = 0.5)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Expected Failure, got $result", result is DetectionResult.Failure)
        assertEquals(FailureReason.TOO_LARGE, (result as DetectionResult.Failure).reason)
    }

    // ── Noise tolerance and partial data ─────────────────────────────────────

    /** Adds seeded Gaussian luminance noise (sigma [sigma]) to every pixel of [bmp]. */
    private fun addNoise(bmp: Bitmap, sigma: Double, seed: Int = 42): Bitmap {
        val rnd = java.util.Random(seed.toLong())
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val lum = bmp.getPixel(x, y) and 0xFF
            val noisy = (lum + rnd.nextGaussian() * sigma).roundToInt().coerceIn(0, 255)
            bmp.setPixel(x, y, 0xFF000000.toInt() or (noisy shl 16) or (noisy shl 8) or noisy)
        }
        return bmp
    }

    @Test
    fun `heavy sensor noise does not move the detected center`() {
        // sigma=20 on a 170-level contrast: individual background pixels routinely dip
        // under the threshold and must be rejected as specks rather than merged into the hole.
        val bmp = addNoise(syntheticBitmap(cx = 100, cy = 100, radius = 25, dotLuminance = 30, bgLuminance = 200), sigma = 20.0)
        val c = cfg(minCirc = 0.6, minArea = 100, maxArea = 20000, kStd = 1.0, minContrast = 0.12)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Expected Success, got $result", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(100f, s.x, 2f)
        assertEquals(100f, s.y, 2f)
        assertTrue("noisy hole should still be round, was ${s.circularity}", s.circularity > 0.8)
    }

    @Test
    fun `noise alone with no hole does not produce a detection`() {
        val bmp = addNoise(syntheticBitmap(radius = 0, bgLuminance = 150), sigma = 25.0)
        val c = cfg(minCirc = 0.6, minArea = 100, maxArea = 20000, kStd = 1.0, minContrast = 0.12)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Pure noise must not be detected as a hole, got $result", result is DetectionResult.Failure)
    }

    @Test
    fun `hole clipped by the target circle edge is still located inside the hole`() {
        // Hole r=25 at (100,100); the operator's target circle only covers its right 80%.
        val bmp = syntheticBitmap(cx = 100, cy = 100, radius = 25)
        val c = cfg(minCirc = 0.5, minArea = 100, maxArea = 20000)
            .copy(targetCenterX = 115f, targetCenterY = 100f, targetRadiusPx = 35f)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Expected Success, got $result", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        // Clipping biases the estimate toward the visible side, but it must stay within the hole.
        assertTrue("x=${s.x} should sit inside the hole", s.x in 95f..115f)
        assertEquals(100f, s.y, 2f)
    }

    @Test
    fun `hole touching the image border is detected without crashing`() {
        val bmp = syntheticBitmap(cx = 12, cy = 100, radius = 20) // extends past x=0
        val c = cfg(minCirc = 0.5, minArea = 100, maxArea = 20000)
        val result = BlobDetector.detectDarkDotCenter(bmp, c)
        assertTrue("Expected Success, got $result", result is DetectionResult.Success)
        val s = result as DetectionResult.Success
        assertEquals(100f, s.y, 2f)
        assertTrue("x=${s.x} should be near the clipped hole", s.x in 8f..22f)
    }
}
