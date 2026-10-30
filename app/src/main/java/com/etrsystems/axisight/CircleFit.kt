package com.etrsystems.axisight

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Least-squares circle fitting using the algebraic (Kåsa) method.
 *
 * Used by [OverlayView] to draw a best-fit circle through collected tracking points,
 * giving the operator a visual indication of runout radius and fit quality (RMS error).
 *
 * Also used by [BlobDetector] for sub-pixel boundary refinement: fitting a circle to
 * the blob's edge-crossing points is less biased by lighting gradients than a
 * weighted centroid.
 *
 * [fitRobust] wraps [fit] with iterative outlier rejection for rotation calibration,
 * where a handful of glitch samples (detector latching onto a scratch for a few frames)
 * would otherwise drag the centre and inflate the RMS past the acceptance gate.
 */
object CircleFit {
    data class Result(val cx: Double, val cy: Double, val r: Double, val rms: Double)

    /** [fit] of the inliers that survived outlier rejection, plus how many there were. */
    data class RobustResult(val fit: Result, val inlierCount: Int, val inliers: List<Pair<Double, Double>>)

    /** Fits a circle to [points]. Returns null if fewer than 3 points or system is singular. */
    fun fit(points: List<Pair<Double, Double>>): Result? {
        if (points.size < 3) return null

        // Centre the data first: the normal equations carry x⁴-scale terms, so a small
        // circle far from the origin (sub-pixel runout at 4K coordinates) is otherwise
        // badly conditioned.
        val n = points.size.toDouble()
        var meanX = 0.0
        var meanY = 0.0
        for ((x, y) in points) { meanX += x; meanY += y }
        meanX /= n
        meanY /= n

        var a11 = 0.0; var a12 = 0.0; var a13 = 0.0
        var a22 = 0.0; var a23 = 0.0
        var b1  = 0.0; var b2  = 0.0; var b3  = 0.0

        for ((px, py) in points) {
            val x = px - meanX
            val y = py - meanY
            val ax  = 2.0 * x
            val ay  = 2.0 * y
            val rhs = x * x + y * y
            a11 += ax * ax; a12 += ax * ay; a13 += ax
            a22 += ay * ay; a23 += ay
            b1  += ax * rhs; b2 += ay * rhs; b3 += rhs
        }

        val A = arrayOf(
            doubleArrayOf(a11, a12, a13),
            doubleArrayOf(a12, a22, a23),
            doubleArrayOf(a13, a23,  n)
        )
        val b = doubleArrayOf(b1, b2, b3)

        val sol = solve3x3(A, b) ?: return null
        val cxLocal = sol[0]; val cyLocal = sol[1]
        val r  = sqrt(sol[2] + cxLocal * cxLocal + cyLocal * cyLocal)
        if (r <= 0 || r.isNaN() || r.isInfinite()) return null

        val cx = cxLocal + meanX
        val cy = cyLocal + meanY
        var se = 0.0
        for ((x, y) in points) {
            val d = hypot(x - cx, y - cy)
            se += (d - r) * (d - r)
        }
        return Result(cx, cy, r, sqrt(se / points.size))
    }

    /**
     * Fits a circle while iteratively discarding outliers.
     *
     * Each pass fits the current inliers, measures every point's radial residual, and
     * drops those beyond [sigmaCutoff] robust standard deviations (1.4826 × median
     * absolute residual, floored at [minCutoff] px so perfectly clean data never
     * rejects anything). Rejection stops when nothing changes, after [maxIterations],
     * or when it would leave fewer than [minInlierFraction] of the original samples —
     * a trace that is mostly garbage should fail downstream gates, not be "cleaned"
     * into a confident fit.
     *
     * Returns null when even the initial fit is impossible.
     */
    fun fitRobust(
        points: List<Pair<Double, Double>>,
        maxIterations: Int = 3,
        sigmaCutoff: Double = 2.5,
        minCutoff: Double = 0.5,
        minInlierFraction: Double = 0.5
    ): RobustResult? {
        var inliers = points
        var fit = fit(inliers) ?: return null
        val minInliers = max(3, (points.size * minInlierFraction).toInt())

        repeat(maxIterations) {
            val residuals = DoubleArray(inliers.size) { i ->
                val (x, y) = inliers[i]
                abs(hypot(x - fit.cx, y - fit.cy) - fit.r)
            }
            val sorted = residuals.copyOf().also { it.sort() }
            val median = sorted[sorted.size / 2]
            val cutoff = max(sigmaCutoff * MAD_TO_SIGMA * median, minCutoff)

            val kept = inliers.filterIndexed { i, _ -> residuals[i] <= cutoff }
            if (kept.size == inliers.size) return RobustResult(fit, inliers.size, inliers)
            if (kept.size < minInliers) return RobustResult(fit, inliers.size, inliers)

            val refit = fit(kept) ?: return RobustResult(fit, inliers.size, inliers)
            inliers = kept
            fit = refit
        }
        return RobustResult(fit, inliers.size, inliers)
    }

    /** Scale factor from median absolute deviation to standard deviation for Gaussian noise. */
    private const val MAD_TO_SIGMA = 1.4826

    private fun solve3x3(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val M = Array(3) { i -> DoubleArray(4) { j -> if (j < 3) A[i][j] else b[i] } }
        for (col in 0..2) {
            var pivot = col
            var maxAbs = abs(M[col][col])
            for (row in col + 1..2) {
                val v = abs(M[row][col])
                if (v > maxAbs) { maxAbs = v; pivot = row }
            }
            if (maxAbs < 1e-12) return null // near-singular matrix
            if (pivot != col) { val tmp = M[pivot]; M[pivot] = M[col]; M[col] = tmp }
            val p = M[col][col]
            for (j in col..3) M[col][j] /= p
            for (row in 0..2) if (row != col) {
                val f = M[row][col]
                for (j in col..3) M[row][j] -= f * M[col][j]
            }
        }
        return doubleArrayOf(M[0][3], M[1][3], M[2][3])
    }
}
