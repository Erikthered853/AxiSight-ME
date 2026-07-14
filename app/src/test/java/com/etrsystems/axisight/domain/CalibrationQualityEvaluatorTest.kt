package com.etrsystems.axisight.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationQualityEvaluatorTest {

    private val center = 300f to 300f

    @Test
    fun `well separated taps are acceptable`() {
        val q = CalibrationQualityEvaluator.evaluate(
            center = center,
            upPoint = 300f to 100f,     // 200 px up distance
            scaleP1 = 100f to 400f,
            scaleP2 = 400f to 400f,     // 300 px scale distance
            knownInches = 0.1181
        )
        assertTrue(q.acceptable)
        assertEquals(200.0, q.upDistancePx, 1e-9)
        assertEquals(300.0, q.scaleDistancePx, 1e-9)
        assertEquals(0.1181 / 300.0, q.inchesPerPixel, 1e-12)
    }

    @Test
    fun `short up vector is rejected`() {
        val q = CalibrationQualityEvaluator.evaluate(
            center = center,
            upPoint = 300f to 280f,     // 20 px < MIN_UP_DISTANCE_PX
            scaleP1 = 100f to 400f,
            scaleP2 = 400f to 400f,
            knownInches = 0.1181
        )
        assertFalse(q.acceptable)
    }

    @Test
    fun `short scale distance is rejected`() {
        val q = CalibrationQualityEvaluator.evaluate(
            center = center,
            upPoint = 300f to 100f,
            scaleP1 = 300f to 400f,
            scaleP2 = 350f to 400f,     // 50 px < MIN_SCALE_DISTANCE_PX
            knownInches = 0.1181
        )
        assertFalse(q.acceptable)
    }

    @Test
    fun `combined error above threshold is rejected`() {
        // Up distance barely above minimum gives a large angular error term.
        val q = CalibrationQualityEvaluator.evaluate(
            center = center,
            upPoint = 300f to 260f,     // 40 px -> axis error ~= 1/40 = 0.025 > 0.020
            scaleP1 = 100f to 400f,
            scaleP2 = 400f to 400f,
            knownInches = 0.1181
        )
        assertFalse(q.acceptable)
        assertTrue(q.combinedErrorIn > CalibrationQualityEvaluator.MAX_COMBINED_ERROR_IN)
    }
}
