package com.etrsystems.axisight.domain

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationFitEvaluatorTest {

    private fun circlePoints(
        cx: Float,
        cy: Float,
        radius: Float,
        count: Int,
        sweepDegrees: Double = 360.0
    ): List<Pair<Float, Float>> = (0 until count).map { i ->
        val angle = Math.toRadians(sweepDegrees * i / count)
        (cx + radius * cos(angle).toFloat()) to (cy + radius * sin(angle).toFloat())
    }

    @Test
    fun `full circle trace is acceptable and recovers center`() {
        val samples = circlePoints(cx = 320f, cy = 240f, radius = 100f, count = 60)
        val q = RotationFitEvaluator.evaluate(samples)
        assertTrue(q.acceptable)
        assertEquals(320f, q.centerX, 0.5f)
        assertEquals(240f, q.centerY, 0.5f)
        assertTrue(q.rms < 0.1)
        assertEquals(1.0, q.coverageFraction, 1e-9)
        assertEquals(samples.size, q.inlierCount)
    }

    @Test
    fun `too few samples are rejected`() {
        val samples = circlePoints(320f, 240f, 100f, count = RotationFitEvaluator.MIN_SAMPLES - 1)
        val q = RotationFitEvaluator.evaluate(samples)
        assertFalse(q.acceptable)
        assertEquals(RotationFitEvaluator.MIN_SAMPLES - 1, q.sampleCount)
    }

    @Test
    fun `quarter arc has insufficient coverage`() {
        val samples = circlePoints(320f, 240f, 100f, count = 60, sweepDegrees = 90.0)
        val q = RotationFitEvaluator.evaluate(samples)
        assertFalse(q.acceptable)
        assertTrue(q.coverageFraction < RotationFitEvaluator.MIN_COVERAGE_FRACTION)
    }

    @Test
    fun `coverage fraction counts distinct angular buckets`() {
        val samples = circlePoints(0f, 0f, 50f, count = 240)
        assertEquals(1.0, RotationFitEvaluator.coverageFraction(samples, 0.0, 0.0), 1e-9)

        val arc = circlePoints(0f, 0f, 50f, count = 240, sweepDegrees = 180.0)
        val coverage = RotationFitEvaluator.coverageFraction(arc, 0.0, 0.0)
        assertTrue(coverage in 0.45..0.60)
    }

    @Test
    fun `a few glitch samples during the sweep do not fail the fit gate`() {
        val rnd = Random(3)
        val clean = circlePoints(cx = 320f, cy = 240f, radius = 100f, count = 90).map { (x, y) ->
            (x + rnd.nextDouble(-0.5, 0.5).toFloat()) to (y + rnd.nextDouble(-0.5, 0.5).toFloat())
        }
        // The detector briefly latched onto a scratch 40px inside the circle, then a reflection.
        val glitches = listOf(360f to 240f, 362f to 243f, 250f to 300f, 255f to 305f)
        val samples = clean + glitches

        val q = RotationFitEvaluator.evaluate(samples)

        assertTrue("glitches must be rejected as outliers; rms=${q.rms} inliers=${q.inlierCount}", q.acceptable)
        assertEquals(320f, q.centerX, 0.5f)
        assertEquals(240f, q.centerY, 0.5f)
        assertTrue(q.rms <= 1.0)
        assertEquals(clean.size, q.inlierCount)
        assertEquals(samples.size, q.sampleCount)
    }

    @Test
    fun `a trace that is mostly garbage is still rejected`() {
        val rnd = Random(11)
        val samples = (0 until 80).map { rnd.nextDouble(0.0, 640.0).toFloat() to rnd.nextDouble(0.0, 480.0).toFloat() }
        val q = RotationFitEvaluator.evaluate(samples)
        assertFalse(q.acceptable)
    }
}
