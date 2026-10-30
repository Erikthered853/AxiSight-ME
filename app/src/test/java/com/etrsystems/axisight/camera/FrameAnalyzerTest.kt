package com.etrsystems.axisight.camera

import com.etrsystems.axisight.CoordinateMapper
import com.etrsystems.axisight.DetectionFilter
import com.etrsystems.axisight.DetectorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the NV21 (USB) path of [FrameAnalyzer]: raw luminance frames must be
 * detected in image space and mapped into overlay/view space through the shared
 * [CoordinateMapper], including the on-screen offset of the USB preview view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FrameAnalyzerTest {

    /** NV21 frame (Y plane + dummy chroma) with a dark disk on a light background. */
    private fun nv21Frame(
        width: Int = 640,
        height: Int = 480,
        cx: Int = 320,
        cy: Int = 240,
        radius: Int = 20,
        dotLuminance: Int = 30,
        bgLuminance: Int = 200
    ): ByteArray {
        val data = ByteArray(width * height * 3 / 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - cx; val dy = y - cy
                val lum = if (dx * dx + dy * dy <= radius * radius) dotLuminance else bgLuminance
                data[y * width + x] = lum.toByte()
            }
        }
        java.util.Arrays.fill(data, width * height, data.size, 128.toByte())
        return data
    }

    private val cfg = DetectorConfig(
        minAreaPx = 50,
        maxAreaPx = 20000,
        minCircularity = 0.5,
        kStd = 1.0,
        downscale = 2,
        minContrastRatio = 0.05,
        consecutiveFramesRequired = 1,
        maxJumpPx = Float.MAX_VALUE
    )

    private fun analyzer(
        mapper: CoordinateMapper = CoordinateMapper(),
        target: () -> FrameAnalyzer.ViewTarget? = { null }
    ) = FrameAnalyzer(mapper, DetectionFilter(), { cfg }, target)

    @Test
    fun `nv21 frame is detected and mapped into view space with preview offset`() {
        val mapper = CoordinateMapper()
        val analyzer = analyzer(mapper)

        // 640x480 frame shown in a 1280x960 preview that sits 200px down inside the overlay.
        val point = analyzer.analyzeNv21Frame(
            data = nv21Frame(), width = 640, height = 480,
            viewWidth = 1280, viewHeight = 960,
            viewOffsetX = 0f, viewOffsetY = 200f,
            deliverResult = true
        )

        assertNotNull("blob should be detected", point)
        assertTrue(mapper.isValid)
        assertEquals(640f, point!!.x, 3f)
        assertEquals(680f, point.y, 3f)
        assertTrue(point.quality.circularity > 0.9)
    }

    @Test
    fun `nv21 frame still updates the mapper but delivers nothing when tracking is off`() {
        val mapper = CoordinateMapper()
        val analyzer = analyzer(mapper)

        val point = analyzer.analyzeNv21Frame(
            data = nv21Frame(), width = 640, height = 480,
            viewWidth = 1280, viewHeight = 960,
            viewOffsetX = 0f, viewOffsetY = 0f,
            deliverResult = false
        )

        assertNull(point)
        assertTrue(mapper.isValid)
    }

    @Test
    fun `lastFailure explains a miss and clears after a success`() {
        val analyzer = analyzer()
        val blank = ByteArray(640 * 480 * 3 / 2) { 200.toByte() }

        assertNull(analyzer.analyzeNv21Frame(blank, 640, 480, 1280, 960, 0f, 0f, deliverResult = true))
        assertNotNull("a miss must record its reason", analyzer.lastFailure)

        assertNotNull(analyzer.analyzeNv21Frame(nv21Frame(), 640, 480, 1280, 960, 0f, 0f, deliverResult = true))
        assertNull("a success must clear the recorded reason", analyzer.lastFailure)
    }

    @Test
    fun `view-space target circle is converted through the mapper before detection`() {
        val mapper = CoordinateMapper()
        // Target placed (in view space) far from the blob: blob at image (320,240)
        // = view (640, 480+200). Target at view (200, 400) r=100 -> image (100,100) r=50.
        val awayTarget = analyzer(mapper) { FrameAnalyzer.ViewTarget(200f, 400f, 100f) }
        val missed = awayTarget.analyzeNv21Frame(
            data = nv21Frame(), width = 640, height = 480,
            viewWidth = 1280, viewHeight = 960,
            viewOffsetX = 0f, viewOffsetY = 200f,
            deliverResult = true
        )
        assertNull("blob outside the target circle must be ignored", missed)

        // Target centered on the blob's view position accepts it.
        val onTarget = analyzer(mapper) { FrameAnalyzer.ViewTarget(640f, 680f, 120f) }
        val hit = onTarget.analyzeNv21Frame(
            data = nv21Frame(), width = 640, height = 480,
            viewWidth = 1280, viewHeight = 960,
            viewOffsetX = 0f, viewOffsetY = 200f,
            deliverResult = true
        )
        assertNotNull("blob inside the target circle must be detected", hit)
        assertEquals(640f, hit!!.x, 3f)
        assertEquals(680f, hit.y, 3f)
    }
}
