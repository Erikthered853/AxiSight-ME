package com.etrsystems.axisight

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.*

sealed class DetectionResult {
    data class Success(val x: Float, val y: Float, val area: Double) : DetectionResult()
    data class Failure(val reason: FailureReason, val debugInfo: String) : DetectionResult()
}

enum class FailureReason {
    NO_DATA,
    TOO_SMALL,
    TOO_LARGE,
    NOT_CIRCULAR,
    NO_DARK_PIXELS,
    LOW_CONTRAST,    // bore not meaningfully darker than surrounding region
    JUMP_TOO_LARGE,  // detected position jumped > maxJumpPx (temporal, see DetectionFilter)
    NOT_CONFIRMED,   // consecutive-frame gate not yet cleared (temporal, see DetectionFilter)
}

/**
 * Detects the center of the darkest circular blob in an image.
 *
 * Algorithm (2 passes over the downscaled target region):
 *   Pass 1 — luminance mean/σ → adaptive threshold = mean − kStd×σ
 *   Pass 2 — collect dark pixels (lum ≤ threshold) → WEIGHTED centroid + raw moments.
 *             Weight = (threshold − lum + 1): darker pixels pull the center harder.
 *             Contrast ratio check rejects uniform/low-contrast regions.
 *             Central moments derived via parallel axis theorem (no third pass).
 *
 * No per-frame heap allocation. [detectCore] is shared by both the
 * [ImageProxy] (YUV) and [Bitmap] (ARGB) overloads.
 */
object BlobDetector {

    private fun isInsideTarget(i: Int, j: Int, ds: Int, cfg: DetectorConfig): Boolean {
        val cx = cfg.targetCenterX ?: return true
        val cy = cfg.targetCenterY ?: return true
        val r  = cfg.targetRadiusPx
        if (r <= 0f) return true
        val dx = i * ds - cx
        val dy = j * ds - cy
        return dx * dx + dy * dy <= r * r
    }

    private fun pixelToLuminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8)  and 0xFF
        val b =  argb         and 0xFF
        return (r * 0.299 + g * 0.587 + b * 0.114).toInt()
    }

    /**
     * Core detection algorithm operating on a downscaled grid.
     *
     * @param dw       Downscaled width  (= imageWidth  / downscale)
     * @param dh       Downscaled height (= imageHeight / downscale)
     * @param ds       Downscale factor
     * @param cfg      Detector configuration
     * @param getPixel Returns luminance [0..255] for downscaled grid coord (i, j).
     *                 Called at most 2× per grid cell across 2 passes.
     */
    private fun detectCore(
        dw: Int,
        dh: Int,
        ds: Int,
        cfg: DetectorConfig,
        getPixel: (i: Int, j: Int) -> Int
    ): DetectionResult {

        // ── Pass 1: histogram + mean/σ for adaptive threshold ──────────────────
        var pixelCount = 0
        var sum = 0L
        var sumSq = 0L
        for (j in 0 until dh) {
            for (i in 0 until dw) {
                if (!isInsideTarget(i, j, ds, cfg)) continue
                val v = getPixel(i, j)
                pixelCount++
                sum += v
                sumSq += v.toLong() * v
            }
        }
        if (pixelCount == 0) return DetectionResult.Failure(FailureReason.NO_DATA, "No pixels in target region")

        val overallMean = sum.toDouble() / pixelCount

        val thr: Int = if (cfg.lockedThreshold != null) {
            cfg.lockedThreshold!!
        } else {
            val variance = (sumSq.toDouble() / pixelCount) - overallMean * overallMean
            val std = sqrt(variance.coerceAtLeast(0.0))
            (overallMean - cfg.kStd * std).toInt().coerceIn(0, 254)
        }

        // ── Pass 2: weighted centroid + contrast + raw moments ─────────────────
        // Weight = (thr - lum + 1) so darker pixels pull the center harder.
        // Also accumulates raw weighted moments (Σw·i², Σw·j², Σw·i·j) so that
        // central moments can be derived via parallel axis theorem, eliminating Pass 3.
        var count = 0
        var weightSum = 0.0
        var sxAcc = 0.0
        var syAcc = 0.0
        var darkLumSum = 0L
        var rawWii = 0.0; var rawWjj = 0.0; var rawWij = 0.0

        for (j in 0 until dh) {
            for (i in 0 until dw) {
                if (!isInsideTarget(i, j, ds, cfg)) continue
                val lum = getPixel(i, j)
                if (lum <= thr) {
                    count++
                    darkLumSum += lum
                    val w = (thr - lum + 1).toDouble()
                    weightSum += w
                    val wi = i.toDouble()
                    val wj = j.toDouble()
                    sxAcc += wi * w
                    syAcc += wj * w
                    rawWii += w * wi * wi
                    rawWjj += w * wj * wj
                    rawWij += w * wi * wj
                }
            }
        }

        if (count == 0) return DetectionResult.Failure(FailureReason.NO_DARK_PIXELS, "No pixels ≤ $thr")

        val fullArea = count * ds * ds
        if (fullArea < cfg.minAreaPx) return DetectionResult.Failure(FailureReason.TOO_SMALL,  "Area $fullArea < ${cfg.minAreaPx}")
        if (fullArea > cfg.maxAreaPx) return DetectionResult.Failure(FailureReason.TOO_LARGE,  "Area $fullArea > ${cfg.maxAreaPx}")

        // Contrast check: bore must be meaningfully darker than the surrounding region.
        val darkMean = darkLumSum.toDouble() / count
        val contrast = (overallMean - darkMean) / overallMean.coerceAtLeast(1.0)
        if (contrast < cfg.minContrastRatio) {
            return DetectionResult.Failure(
                FailureReason.LOW_CONTRAST,
                "Contrast %.3f < %.3f (dark=%.1f bg=%.1f)".format(contrast, cfg.minContrastRatio, darkMean, overallMean)
            )
        }

        val cxD = sxAcc / weightSum
        val cyD = syAcc / weightSum

        // Central moments via parallel axis theorem: Var(X) = E[X²] - E[X]²
        var mxx = rawWii / weightSum - cxD * cxD
        var myy = rawWjj / weightSum - cyD * cyD
        var mxy = rawWij / weightSum - cxD * cyD

        val trace = mxx + myy
        val det   = mxx * myy - mxy * mxy
        val root  = max(0.0, trace * trace / 4.0 - det)
        val sqrtRoot = sqrt(root)
        val l1    = trace / 2.0 + sqrtRoot
        val l2    = trace / 2.0 - sqrtRoot
        val axisRatio   = if (l1 > 1e-9) max(0.0, min(1.0, l2 / l1)) else 0.0
        val circularity = sqrt(axisRatio)

        if (circularity < cfg.minCircularity) {
            return DetectionResult.Failure(
                FailureReason.NOT_CIRCULAR,
                "Circularity %.3f < %.3f".format(circularity, cfg.minCircularity)
            )
        }

        return DetectionResult.Success(
            x    = (cxD * ds).toFloat(),
            y    = (cyD * ds).toFloat(),
            area = fullArea.toDouble()
        )
    }

    /** Detects dark dot center from a YUV [ImageProxy] (CameraX internal camera). */
    fun detectDarkDotCenter(image: ImageProxy, cfg: DetectorConfig): DetectionResult {
        val yPlane = image.planes[0]
        val w  = image.width
        val h  = image.height
        val ds = cfg.downscale
        val dw = w / ds
        val dh = h / ds
        if (dw <= 0 || dh <= 0) return DetectionResult.Failure(FailureReason.NO_DATA, "Image too small: ${dw}×${dh}")

        val buf = yPlane.buffer
        val rs  = yPlane.rowStride
        val ps  = yPlane.pixelStride

        return detectCore(dw, dh, ds, cfg) { i, j ->
            buf.get(j * ds * rs + i * ds * ps).toInt() and 0xFF
        }
    }

    /** Detects dark dot center from a [Bitmap] (WiFi / USB camera paths). */
    fun detectDarkDotCenter(bitmap: Bitmap, cfg: DetectorConfig): DetectionResult {
        val w  = bitmap.width
        val h  = bitmap.height
        val ds = cfg.downscale
        val dw = w / ds
        val dh = h / ds
        if (dw <= 0 || dh <= 0) return DetectionResult.Failure(FailureReason.NO_DATA, "Image too small: ${dw}×${dh}")

        // Read all pixels once; getPixels is faster than individual getPixel calls.
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        return detectCore(dw, dh, ds, cfg) { i, j ->
            pixelToLuminance(pixels[j * ds * w + i * ds])
        }
    }
}
