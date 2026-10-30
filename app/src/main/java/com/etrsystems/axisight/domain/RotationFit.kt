package com.etrsystems.axisight.domain

import com.etrsystems.axisight.CircleFit
import kotlin.math.atan2

/**
 * Result of fitting a circle to a rotation-calibration trace: the operator
 * rotates the spindle by hand while the detected tool position is recorded,
 * and the fitted circle center is the true spindle centerline.
 *
 * @param inlierCount Samples that survived outlier rejection and contributed to the fit;
 *                    [rms] and [coverageFraction] are computed over these only.
 */
data class RotationFitQuality(
    val centerX: Float,
    val centerY: Float,
    val rms: Double,
    val coverageFraction: Double,
    val sampleCount: Int,
    val acceptable: Boolean,
    val inlierCount: Int = sampleCount
)

/** Pure evaluation of a rotation-calibration sample trace. */
object RotationFitEvaluator {
    const val COVERAGE_BUCKETS = 24
    const val MIN_SAMPLES = 40
    const val MAX_SAMPLES = 600
    const val MIN_COVERAGE_FRACTION = 0.75 // ~270 degrees of the trace
    const val MAX_FIT_RMS_PX = 3.0

    fun evaluate(samples: List<Pair<Float, Float>>): RotationFitQuality {
        if (samples.size < MIN_SAMPLES) {
            return RotationFitQuality(0f, 0f, 0.0, 0.0, samples.size, acceptable = false, inlierCount = 0)
        }
        // Robust fit: a few frames where the detector latched onto a scratch or reflection
        // are dropped instead of dragging the centre and failing the RMS gate.
        val robust = CircleFit.fitRobust(samples.map { it.first.toDouble() to it.second.toDouble() })
            ?: return RotationFitQuality(0f, 0f, 0.0, 0.0, samples.size, acceptable = false, inlierCount = 0)
        val fit = robust.fit
        val inliers = robust.inliers.map { it.first.toFloat() to it.second.toFloat() }
        val coverage = coverageFraction(inliers, fit.cx, fit.cy)
        val acceptable = robust.inlierCount >= MIN_SAMPLES &&
            coverage >= MIN_COVERAGE_FRACTION &&
            fit.rms <= MAX_FIT_RMS_PX
        return RotationFitQuality(
            centerX = fit.cx.toFloat(),
            centerY = fit.cy.toFloat(),
            rms = fit.rms,
            coverageFraction = coverage,
            sampleCount = samples.size,
            acceptable = acceptable,
            inlierCount = robust.inlierCount
        )
    }

    /** Fraction of the 360-degree sweep around ([cx], [cy]) covered by [samples]. */
    fun coverageFraction(samples: List<Pair<Float, Float>>, cx: Double, cy: Double): Double {
        if (samples.isEmpty()) return 0.0
        val visited = BooleanArray(COVERAGE_BUCKETS)
        for ((x, y) in samples) {
            visited[bucketIndex(x, y, cx, cy)] = true
        }
        return visited.count { it }.toDouble() / COVERAGE_BUCKETS
    }

    /** Angular coverage bucket for a sample around center ([cx], [cy]). */
    fun bucketIndex(x: Float, y: Float, cx: Double, cy: Double): Int {
        val bucketSizeDeg = 360.0 / COVERAGE_BUCKETS
        val deg = (Math.toDegrees(atan2(y - cy, x - cx)) + 360.0) % 360.0
        return (deg / bucketSizeDeg).toInt().coerceIn(0, COVERAGE_BUCKETS - 1)
    }
}
