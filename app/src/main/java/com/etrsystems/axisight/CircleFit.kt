package com.etrsystems.axisight

import kotlin.math.sqrt
import kotlin.math.abs

/**
 * Least-squares circle fitting using the algebraic method.
 *
 * Used by [OverlayView] to draw a best-fit circle through collected tracking points,
 * giving the operator a visual indication of runout radius and fit quality (RMS error).
 *
 * TODO: Consider using this for sub-pixel tool-center refinement in BlobDetector
 *       by fitting to the boundary pixels of the detected blob.
 */
object CircleFit {
    data class Result(val cx: Double, val cy: Double, val r: Double, val rms: Double)

    /** Fits a circle to [points]. Returns null if fewer than 3 points or system is singular. */
    fun fit(points: List<Pair<Double, Double>>): Result? {
        if (points.size < 3) return null

        val n = points.size.toDouble()
        var a11 = 0.0; var a12 = 0.0; var a13 = 0.0
        var a22 = 0.0; var a23 = 0.0
        var b1  = 0.0; var b2  = 0.0; var b3  = 0.0

        for ((x, y) in points) {
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
        val cx = sol[0]; val cy = sol[1]
        val r  = sqrt(sol[2] + cx * cx + cy * cy)
        if (r <= 0 || r.isNaN() || r.isInfinite()) return null

        var se = 0.0
        for ((x, y) in points) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            se += (d - r) * (d - r)
        }
        return Result(cx, cy, r, sqrt(se / points.size))
    }

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
