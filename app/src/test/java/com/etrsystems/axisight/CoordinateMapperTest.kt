package com.etrsystems.axisight

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [CoordinateMapper], which converts between camera image-space pixels
 * and on-screen view-space pixels. Calibration is now persisted in image space, so a
 * round trip through this mapper must be lossless (up to float rounding) for calibration
 * to actually survive resolution/orientation changes as intended.
 */
class CoordinateMapperTest {

    @Test
    fun `unset mapper is invalid and behaves as identity`() {
        val mapper = CoordinateMapper()
        assertFalse(mapper.isValid)
        assertEquals(1f, mapper.imageToViewScale, 1e-6f)
        val (vx, vy) = mapper.imageToView(42f, 99f)
        assertEquals(42f, vx, 1e-6f)
        assertEquals(99f, vy, 1e-6f)
    }

    @Test
    fun `view to image and back round-trips for same-orientation scaled image`() {
        val mapper = CoordinateMapper()
        // 640x480 image scaled up into a 1280x960 view - no letterboxing, no rotation.
        mapper.update(imageWidth = 640, imageHeight = 480, rotationDegrees = 0, viewWidth = 1280, viewHeight = 960)
        assertTrue(mapper.isValid)
        assertEquals(2f, mapper.imageToViewScale, 1e-4f)

        val (vx, vy) = mapper.imageToView(320f, 240f)
        assertEquals(640f, vx, 1e-3f)
        assertEquals(480f, vy, 1e-3f)

        val (ix, iy) = mapper.viewToImage(vx, vy)
        assertEquals(320f, ix, 1e-3f)
        assertEquals(240f, iy, 1e-3f)
    }

    @Test
    fun `letterboxed view offsets are accounted for`() {
        val mapper = CoordinateMapper()
        // 640x480 (4:3) image inside a 1000x1000 (1:1) view - pillarboxed left/right.
        mapper.update(imageWidth = 640, imageHeight = 480, rotationDegrees = 0, viewWidth = 1000, viewHeight = 1000)

        val (ix, iy) = mapper.viewToImage(500f, 500f) // view center
        assertEquals(320f, ix, 1.0f) // image center
        assertEquals(240f, iy, 1.0f)
    }

    @Test
    fun `90 degree rotation swaps axes through the round trip`() {
        val mapper = CoordinateMapper()
        // Landscape sensor image displayed on a portrait device.
        mapper.update(imageWidth = 640, imageHeight = 480, rotationDegrees = 90, viewWidth = 480, viewHeight = 640)

        val (ix, iy) = 100f to 200f
        val (vx, vy) = mapper.imageToView(ix, iy)
        val (rix, riy) = mapper.viewToImage(vx, vy)

        assertEquals(ix, rix, 1e-2f)
        assertEquals(iy, riy, 1e-2f)
    }

    @Test
    fun `radius conversions are consistent with point-scale conversions`() {
        val mapper = CoordinateMapper()
        mapper.update(imageWidth = 640, imageHeight = 480, rotationDegrees = 0, viewWidth = 1280, viewHeight = 960)

        assertEquals(20f, mapper.imageRadiusToView(10f), 1e-4f)
        assertEquals(10f, mapper.viewRadiusToImage(20f), 1e-4f)
    }

    @Test
    fun `invalidate resets to identity and clears validity`() {
        val mapper = CoordinateMapper()
        mapper.update(imageWidth = 640, imageHeight = 480, rotationDegrees = 0, viewWidth = 1280, viewHeight = 960)
        assertTrue(mapper.isValid)

        mapper.invalidate()

        assertFalse(mapper.isValid)
        assertEquals(1f, mapper.imageToViewScale, 1e-6f)
        val (vx, vy) = mapper.imageToView(7f, 11f)
        assertEquals(7f, vx, 1e-6f)
        assertEquals(11f, vy, 1e-6f)
    }

    @Test
    fun `update ignores non-positive dimensions`() {
        val mapper = CoordinateMapper()
        mapper.update(imageWidth = 0, imageHeight = 480, rotationDegrees = 0, viewWidth = 1280, viewHeight = 960)
        assertFalse(mapper.isValid)
    }
}
