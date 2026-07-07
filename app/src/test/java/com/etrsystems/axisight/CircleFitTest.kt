package com.etrsystems.axisight

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Unit tests for [CircleFit], the least-squares circle fit that rotation-based
 * calibration relies on to derive the true spindle centerline from a trace of
 * detected tool positions.
 */
class CircleFitTest {

    private fun pointsOnCircle(cx: Double, cy: Double, r: Double, angles: List<Double>): List<Pair<Double, Double>> =
        angles.map { a -> (cx + r * cos(a)) to (cy + r * sin(a)) }

    @Test
    fun `fit returns null with fewer than 3 points`() {
        assertNull(CircleFit.fit(emptyList()))
        assertNull(CircleFit.fit(listOf(1.0 to 1.0)))
        assertNull(CircleFit.fit(listOf(1.0 to 1.0, 2.0 to 2.0)))
    }

    @Test
    fun `fit returns null for colinear points`() {
        val points = listOf(0.0 to 0.0, 1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        assertNull(CircleFit.fit(points))
    }

    @Test
    fun `fit recovers exact center and radius for a full-turn synthetic trace`() {
        val angles = (0 until 36).map { it * 2.0 * PI / 36.0 }
        val points = pointsOnCircle(cx = 500.0, cy = 300.0, r = 150.0, angles = angles)

        val result = CircleFit.fit(points)

        assertNotNull(result)
        result!!
        assertEquals(500.0, result.cx, 1e-6)
        assertEquals(300.0, result.cy, 1e-6)
        assertEquals(150.0, result.r, 1e-6)
        assertEquals(0.0, result.rms, 1e-6)
    }

    @Test
    fun `fit is robust to small jitter around the true circle`() {
        val angles = (0 until 48).map { it * 2.0 * PI / 48.0 }
        val truePoints = pointsOnCircle(cx = 640.0, cy = 360.0, r = 200.0, angles = angles)
        // Deterministic +/-1px alternating jitter simulating detector noise.
        val jittered = truePoints.mapIndexed { i, (x, y) ->
            val jitter = if (i % 2 == 0) 1.0 else -1.0
            (x + jitter) to (y + jitter)
        }

        val result = CircleFit.fit(jittered)

        assertNotNull(result)
        result!!
        assertEquals(640.0, result.cx, 2.0)
        assertEquals(360.0, result.cy, 2.0)
        assertEquals(200.0, result.r, 2.0)
        assertTrue("rms should reflect the injected jitter but stay small", result.rms < 2.0)
    }

    @Test
    fun `fit degrades gracefully with partial angular coverage`() {
        // Only a 90-degree arc of samples - still a valid fit, but higher-level
        // calibration code is expected to gate on coverage separately from CircleFit itself.
        val angles = (0..12).map { it * (PI / 2.0) / 12.0 }
        val points = pointsOnCircle(cx = 100.0, cy = 100.0, r = 50.0, angles = angles)

        val result = CircleFit.fit(points)

        assertNotNull(result)
    }
}
