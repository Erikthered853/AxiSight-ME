package com.etrsystems.axisight

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

/**
 * Unit tests for [CircleFit], the least-squares circle fit that rotation-based
 * calibration relies on to derive the true spindle centerline from a trace of
 * detected tool positions.
 */
class CircleFitTest {

    private fun pointsOnCircle(cx: Double, cy: Double, r: Double, angles: List<Double>): List<Pair<Double, Double>> =
        angles.map { a -> (cx + r * cos(a)) to (cy + r * sin(a)) }

    private fun fullTurn(n: Int) = (0 until n).map { it * 2.0 * PI / n }

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
        val points = pointsOnCircle(cx = 500.0, cy = 300.0, r = 150.0, angles = fullTurn(36))

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
        val truePoints = pointsOnCircle(cx = 640.0, cy = 360.0, r = 200.0, angles = fullTurn(48))
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

    @Test
    fun `fit stays accurate for a small circle far from the origin`() {
        // Sub-pixel runout on a high-resolution frame: tiny radius, large coordinates.
        // The normal equations are badly conditioned unless the fit centres the data.
        val points = pointsOnCircle(cx = 3840.0, cy = 2160.0, r = 1.5, angles = fullTurn(40))

        val result = CircleFit.fit(points)

        assertNotNull(result)
        result!!
        assertEquals(3840.0, result.cx, 1e-3)
        assertEquals(2160.0, result.cy, 1e-3)
        assertEquals(1.5, result.r, 1e-3)
    }

    // ── Robust fit (outlier rejection) ──────────────────────────────────────

    @Test
    fun `fitRobust ignores a handful of wild outliers that would skew a plain fit`() {
        val rnd = Random(7)
        val clean = pointsOnCircle(cx = 320.0, cy = 240.0, r = 100.0, angles = fullTurn(80))
            .map { (x, y) -> (x + rnd.nextDouble(-0.5, 0.5)) to (y + rnd.nextDouble(-0.5, 0.5)) }
        // 5 glitch samples (a jump to a scratch, a reflection) well off the circle.
        val outliers = listOf(320.0 to 240.0, 470.0 to 240.0, 320.0 to 420.0, 200.0 to 100.0, 330.0 to 250.0)
        val samples = clean + outliers

        val plain = CircleFit.fit(samples)!!
        val robust = CircleFit.fitRobust(samples)!!

        assertTrue("plain fit rms should be inflated by outliers, was ${plain.rms}", plain.rms > 3.0)
        assertEquals(320.0, robust.fit.cx, 0.3)
        assertEquals(240.0, robust.fit.cy, 0.3)
        assertEquals(100.0, robust.fit.r, 0.3)
        assertTrue("robust rms should reflect only the jitter, was ${robust.fit.rms}", robust.fit.rms < 0.6)
        assertEquals(clean.size, robust.inlierCount)
    }

    @Test
    fun `fitRobust keeps everything when there are no outliers`() {
        val points = pointsOnCircle(cx = 50.0, cy = 50.0, r = 20.0, angles = fullTurn(30))
        val robust = CircleFit.fitRobust(points)!!
        assertEquals(points.size, robust.inlierCount)
        assertEquals(50.0, robust.fit.cx, 1e-6)
        assertEquals(50.0, robust.fit.cy, 1e-6)
    }

    @Test
    fun `fitRobust never discards more than half the samples`() {
        // Two concentric rings: the fit must not decide that one whole ring is outliers.
        val inner = pointsOnCircle(cx = 0.0, cy = 0.0, r = 10.0, angles = fullTurn(20))
        val outer = pointsOnCircle(cx = 0.0, cy = 0.0, r = 30.0, angles = fullTurn(20))
        val robust = CircleFit.fitRobust(inner + outer)!!
        assertTrue("inliers ${robust.inlierCount} must be at least half of ${inner.size + outer.size}",
            robust.inlierCount >= (inner.size + outer.size) / 2)
    }

    @Test
    fun `fitRobust returns null when a fit is impossible`() {
        assertNull(CircleFit.fitRobust(listOf(0.0 to 0.0, 1.0 to 1.0, 2.0 to 2.0)))
    }
}
