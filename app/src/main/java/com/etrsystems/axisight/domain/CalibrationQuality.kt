package com.etrsystems.axisight.domain

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Quality metrics for a center/up/scale calibration attempt. All distances are
 * in the space the points were tapped in (view space), since the gating models
 * tap-finger precision, which is a screen-space concept.
 */
data class CalibrationQuality(
    val upDistancePx: Double,
    val scaleDistancePx: Double,
    val inchesPerPixel: Double,
    val combinedErrorIn: Double,
    val acceptable: Boolean
)

/** Pure evaluation of manual-calibration tap quality. */
object CalibrationQualityEvaluator {
    const val MIN_UP_DISTANCE_PX = 40.0
    const val MIN_SCALE_DISTANCE_PX = 80.0
    const val MAX_COMBINED_ERROR_IN = 0.020

    fun evaluate(
        center: Pair<Float, Float>,
        upPoint: Pair<Float, Float>,
        scaleP1: Pair<Float, Float>,
        scaleP2: Pair<Float, Float>,
        knownInches: Double
    ): CalibrationQuality {
        val upDistancePx = hypot((upPoint.first - center.first).toDouble(), (upPoint.second - center.second).toDouble())
        val scaleDistancePx = hypot((scaleP2.first - scaleP1.first).toDouble(), (scaleP2.second - scaleP1.second).toDouble())
        val safeScalePx = scaleDistancePx.coerceAtLeast(1.0)
        val inchesPerPixel = knownInches / safeScalePx

        // Endpoint quantization estimate assuming +/-1px around each tap.
        val scaleErrorIn = knownInches * (sqrt(2.0) / safeScalePx)
        // Angular uncertainty estimate from 1px tap uncertainty on up vector.
        val angularErrorRad = atan2(1.0, upDistancePx.coerceAtLeast(1.0))
        val axisErrorAtOneIn = tan(angularErrorRad)
        val combinedErrorIn = hypot(scaleErrorIn, axisErrorAtOneIn)

        val acceptable = upDistancePx >= MIN_UP_DISTANCE_PX &&
            scaleDistancePx >= MIN_SCALE_DISTANCE_PX &&
            combinedErrorIn <= MAX_COMBINED_ERROR_IN

        return CalibrationQuality(
            upDistancePx = upDistancePx,
            scaleDistancePx = scaleDistancePx,
            inchesPerPixel = inchesPerPixel,
            combinedErrorIn = combinedErrorIn,
            acceptable = acceptable
        )
    }
}
