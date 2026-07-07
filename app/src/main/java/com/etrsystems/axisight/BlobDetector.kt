package com.etrsystems.axisight

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.*

sealed class DetectionResult {
    /**
     * @param circularity   Eigenvalue-ratio circularity in [0,1] (see Pass 3), defaults to a
     *                       benign 1.0 for callers (mainly tests) that don't care about it.
     * @param contrastRatio Bore-vs-background contrast ratio (see Pass 2), defaults to 1.0.
     * @param sharpness     Variance of the discrete Laplacian over the ROI — a focus/motion-blur
     *                       proxy (higher = sharper edges present), defaults to 0.0.
     */
    data class Success(
        val x: Float,
        val y: Float,
        val area: Double,
        val circularity: Double = 1.0,
        val contrastRatio: Double = 1.0,
        val sharpness: Double = 0.0
    ) : DetectionResult()
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
 * Algorithm (over the downscaled target region):
 *   Pass 1 — luminance histogram + mean/σ → adaptive threshold = mean − kStd×σ
 *   Pass 2 — collect dark pixels (lum ≤ threshold) → WEIGHTED centroid
 *             Weight = (threshold − lum + 1): darker pixels pull the center harder.
 *             Also checks contrast ratio: the dark blob must be meaningfully darker
 *             than the region background before the result is accepted.
 *   Pass 3 — weighted 2nd-moment matrix → circularity via eigenvalue ratio
 *   Pass 4 — boundary refinement: casts rays out from the centroid, finds the
 *             sub-pixel threshold crossing on each, and fits a circle ([CircleFit])
 *             to those edge points. The weighted centroid is biased by lighting
 *             gradients across the blob; fitting the boundary instead is not, so
 *             this pass is used as the final center when it converges cleanly.
 *
 * [detectCore] is shared by both the [ImageProxy] (YUV) and [Bitmap] (ARGB) overloads.
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

    /** Bilinearly samples the downscaled grid at fractional coordinates, clamping at the edges. */
    private fun sampleBilinear(fi: Double, fj: Double, dw: Int, dh: Int, getPixel: (Int, Int) -> Int): Double {
        val i0 = floor(fi).toInt()
        val j0 = floor(fj).toInt()
        val tx = fi - i0
        val ty = fj - j0
        val ci0 = i0.coerceIn(0, dw - 1); val ci1 = (i0 + 1).coerceIn(0, dw - 1)
        val cj0 = j0.coerceIn(0, dh - 1); val cj1 = (j0 + 1).coerceIn(0, dh - 1)
        val v00 = getPixel(ci0, cj0).toDouble()
        val v10 = getPixel(ci1, cj0).toDouble()
        val v01 = getPixel(ci0, cj1).toDouble()
        val v11 = getPixel(ci1, cj1).toDouble()
        val vTop = v00 + (v10 - v00) * tx
        val vBot = v01 + (v11 - v01) * tx
        return vTop + (vBot - vTop) * ty
    }

    /**
     * Refines [cxD],[cyD] by casting rays outward, locating the sub-pixel dark→light
     * threshold crossing on each, and fitting a circle to the crossing points.
     * Returns null if fewer than half the rays find a clean crossing (e.g. an
     * occluded or clipped blob), leaving the caller to fall back to the centroid.
     */
    private fun refineBoundary(
        cxD: Double,
        cyD: Double,
        estRadiusD: Double,
        thr: Int,
        dw: Int,
        dh: Int,
        getPixel: (Int, Int) -> Int
    ): CircleFit.Result? {
        if (estRadiusD < 1.0) return null
        val rayCount = 24
        val stepD = 0.5
        val searchStart = (estRadiusD * 0.5).coerceAtLeast(1.0)
        val searchEnd = estRadiusD * 1.6

        val edgePoints = ArrayList<Pair<Double, Double>>(rayCount)
        for (k in 0 until rayCount) {
            val angle = 2.0 * PI * k / rayCount
            val ca = cos(angle)
            val sa = sin(angle)
            var prevR = searchStart
            var prevLum = sampleBilinear(cxD + ca * prevR, cyD + sa * prevR, dw, dh, getPixel)
            var r = searchStart + stepD
            while (r <= searchEnd) {
                val lum = sampleBilinear(cxD + ca * r, cyD + sa * r, dw, dh, getPixel)
                if (prevLum <= thr && lum > thr) {
                    val frac = ((thr - prevLum) / (lum - prevLum)).coerceIn(0.0, 1.0)
                    val edgeR = prevR + frac * (r - prevR)
                    edgePoints.add((cxD + ca * edgeR) to (cyD + sa * edgeR))
                    break
                }
                prevR = r
                prevLum = lum
                r += stepD
            }
        }
        if (edgePoints.size < rayCount / 2) return null

        val fit = CircleFit.fit(edgePoints) ?: return null
        val driftD = hypot(fit.cx - cxD, fit.cy - cyD)
        if (driftD > estRadiusD * 0.5) return null // fit dragged too far from centroid, distrust it
        return fit
    }

    /**
     * Core detection algorithm operating on a downscaled grid.
     *
     * @param dw       Downscaled width  (= imageWidth  / downscale)
     * @param dh       Downscaled height (= imageHeight / downscale)
     * @param ds       Downscale factor
     * @param cfg      Detector configuration
     * @param getPixel Returns luminance [0..255] for downscaled grid coord (i, j).
     *                 Called at most 3× per grid cell across 3 passes.
     */
    private fun detectCore(
        dw: Int,
        dh: Int,
        ds: Int,
        cfg: DetectorConfig,
        getPixel: (i: Int, j: Int) -> Int
    ): DetectionResult {

        // ── Pass 1: histogram + mean/σ for adaptive threshold, + sharpness ─────
        // Sharpness = variance of the discrete Laplacian (4·center − 4-neighbors),
        // a cheap focus/motion-blur proxy computed in the same pass since it needs
        // no data pass 1 doesn't already touch.
        var pixelCount = 0
        var sum = 0L
        var sumSq = 0L
        var lapCount = 0
        var lapSum = 0L
        var lapSumSq = 0L
        for (j in 0 until dh) {
            for (i in 0 until dw) {
                if (!isInsideTarget(i, j, ds, cfg)) continue
                val v = getPixel(i, j)
                pixelCount++
                sum += v
                sumSq += v.toLong() * v
                if (i in 1 until dw - 1 && j in 1 until dh - 1) {
                    val lap = 4 * v - getPixel(i - 1, j) - getPixel(i + 1, j) - getPixel(i, j - 1) - getPixel(i, j + 1)
                    lapCount++
                    lapSum += lap
                    lapSumSq += lap.toLong() * lap
                }
            }
        }
        if (pixelCount == 0) return DetectionResult.Failure(FailureReason.NO_DATA, "No pixels in target region")

        val sharpness = if (lapCount > 0) {
            val lapMean = lapSum.toDouble() / lapCount
            (lapSumSq.toDouble() / lapCount) - lapMean * lapMean
        } else 0.0

        val overallMean = sum.toDouble() / pixelCount

        val thr: Int = if (cfg.lockedThreshold != null) {
            cfg.lockedThreshold!!
        } else {
            val variance = (sumSq.toDouble() / pixelCount) - overallMean * overallMean
            val std = sqrt(variance.coerceAtLeast(0.0))
            (overallMean - cfg.kStd * std).toInt().coerceIn(0, 254)
        }

        // ── Pass 2: weighted centroid + contrast check ──────────────────────────
        // Weight = (thr - lum + 1) so darker pixels pull the center harder.
        // This gives sub-pixel accuracy and reduces sensitivity to peripheral noise.
        var count = 0
        var weightSum = 0.0
        var sxAcc = 0.0
        var syAcc = 0.0
        var darkLumSum = 0L

        for (j in 0 until dh) {
            for (i in 0 until dw) {
                if (!isInsideTarget(i, j, ds, cfg)) continue
                val lum = getPixel(i, j)
                if (lum <= thr) {
                    count++
                    darkLumSum += lum
                    val w = (thr - lum + 1).toDouble()
                    weightSum += w
                    sxAcc += i * w
                    syAcc += j * w
                }
            }
        }

        if (count == 0) return DetectionResult.Failure(FailureReason.NO_DARK_PIXELS, "No pixels ≤ $thr")

        val fullArea = count * ds * ds
        if (fullArea < cfg.minAreaPx) return DetectionResult.Failure(FailureReason.TOO_SMALL,  "Area $fullArea < ${cfg.minAreaPx}")
        if (fullArea > cfg.maxAreaPx) return DetectionResult.Failure(FailureReason.TOO_LARGE,  "Area $fullArea > ${cfg.maxAreaPx}")

        // Contrast check: bore must be meaningfully darker than the surrounding region.
        // Without this, a uniformly dim or noisy frame can still produce a centroid.
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

        // ── Pass 3: weighted 2nd-moment matrix → circularity ───────────────────
        var mxx = 0.0; var myy = 0.0; var mxy = 0.0
        var wSum3 = 0.0
        for (j in 0 until dh) {
            for (i in 0 until dw) {
                if (!isInsideTarget(i, j, ds, cfg)) continue
                val lum = getPixel(i, j)
                if (lum <= thr) {
                    val w = (thr - lum + 1).toDouble()
                    val dx = i - cxD
                    val dy = j - cyD
                    mxx += w * dx * dx
                    myy += w * dy * dy
                    mxy += w * dx * dy
                    wSum3 += w
                }
            }
        }
        if (wSum3 > 0.0) { mxx /= wSum3; myy /= wSum3; mxy /= wSum3 }

        val trace = mxx + myy
        val det   = mxx * myy - mxy * mxy
        val root  = max(0.0, trace * trace / 4.0 - det)
        val l1    = trace / 2.0 + sqrt(root)
        val l2    = trace / 2.0 - sqrt(root)
        val axisRatio   = if (l1 > 1e-9) max(0.0, min(1.0, l2 / l1)) else 0.0
        val circularity = sqrt(axisRatio)

        if (circularity < cfg.minCircularity) {
            return DetectionResult.Failure(
                FailureReason.NOT_CIRCULAR,
                "Circularity %.3f < %.3f".format(circularity, cfg.minCircularity)
            )
        }

        // ── Pass 4: boundary refinement → sub-pixel center via CircleFit ──────
        val estRadiusD = sqrt(fullArea / PI) / ds
        val refined = refineBoundary(cxD, cyD, estRadiusD, thr, dw, dh, getPixel)
        val finalXD = refined?.cx ?: cxD
        val finalYD = refined?.cy ?: cyD

        return DetectionResult.Success(
            x = (finalXD * ds).toFloat(),
            y = (finalYD * ds).toFloat(),
            area = fullArea.toDouble(),
            circularity = circularity,
            contrastRatio = contrast,
            sharpness = sharpness
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
