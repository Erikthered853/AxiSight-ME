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
 *   Pass 1 — luminance grid cache + histogram mean/σ → adaptive threshold = mean − kStd×σ,
 *             plus sharpness (Laplacian variance)
 *   Pass 2 — connected-component labelling of pixels ≤ threshold (4-connected). Each
 *             component gets a WEIGHTED centroid (weight = threshold − lum + 1, so darker
 *             pixels pull harder) and weighted 2nd moments → circularity. Components outside
 *             [minAreaPx, maxAreaPx] are discarded; of the rest the one with the best
 *             weight × circularity² wins. Scratches, shadows and debris next to the bore
 *             therefore become separate, losing components instead of smearing the bore's
 *             centroid and circularity (the earlier "all dark pixels" approach failed on
 *             real bores for exactly that reason).
 *   Pass 3 — contrast-ratio gate (chosen blob must be meaningfully darker than the region)
 *             and circularity gate on the chosen component
 *   Pass 4 — boundary refinement: casts rays out from the centroid, finds the
 *             sub-pixel threshold crossing on each, and fits a circle ([CircleFit])
 *             to those edge points. The weighted centroid is biased by lighting
 *             gradients across the blob; fitting the boundary instead is not, so
 *             this pass is used as the final center when it converges cleanly.
 *
 * [detectCore] is shared by the [ImageProxy] (YUV), [Bitmap] (ARGB) and NV21 overloads.
 * Per-thread scratch buffers are reused across frames, so steady-state detection allocates
 * nothing beyond the result object.
 */
object BlobDetector {

    /** Reusable per-thread work buffers (detection runs on one thread per camera source). */
    private class Scratch {
        var lum = IntArray(0)
        var label = IntArray(0)
        var queue = IntArray(0)
        var comp = DoubleArray(COMP_FIELDS * 64)

        fun ensure(cells: Int) {
            if (lum.size < cells) {
                lum = IntArray(cells)
                label = IntArray(cells)
                queue = IntArray(cells)
            }
        }

        fun ensureComponents(count: Int) {
            if (comp.size < count * COMP_FIELDS) comp = comp.copyOf(max(count, comp.size / COMP_FIELDS * 2) * COMP_FIELDS)
        }
    }

    // Per-component accumulator layout inside Scratch.comp.
    private const val C_COUNT = 0
    private const val C_W = 1
    private const val C_WX = 2
    private const val C_WY = 3
    private const val C_WXX = 4
    private const val C_WYY = 5
    private const val C_WXY = 6
    private const val C_LUM = 7
    private const val COMP_FIELDS = 8

    private val scratchLocal = ThreadLocal.withInitial { Scratch() }

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
     * Upper area bound for a candidate blob. When the operator has placed a target
     * circle, anything that fits inside it is a legitimate candidate (the mask already
     * clips to that circle), so the bound grows to the circle's own area. Without a
     * target the configured [DetectorConfig.maxAreaPx] applies unchanged.
     */
    private fun effectiveMaxArea(cfg: DetectorConfig): Int {
        if (cfg.targetCenterX == null || cfg.targetCenterY == null || cfg.targetRadiusPx <= 0f) return cfg.maxAreaPx
        val circleArea = (PI * cfg.targetRadiusPx * cfg.targetRadiusPx).toInt()
        return max(cfg.maxAreaPx, circleArea)
    }

    /**
     * Core detection algorithm operating on a downscaled grid.
     *
     * @param dw       Downscaled width  (= imageWidth  / downscale)
     * @param dh       Downscaled height (= imageHeight / downscale)
     * @param ds       Downscale factor
     * @param cfg      Detector configuration
     * @param getPixel Returns luminance [0..255] for downscaled grid coord (i, j).
     *                 Called once per grid cell plus a handful of boundary samples.
     */
    private fun detectCore(
        dw: Int,
        dh: Int,
        ds: Int,
        cfg: DetectorConfig,
        getPixel: (i: Int, j: Int) -> Int
    ): DetectionResult {
        val cells = dw * dh
        val scratch = scratchLocal.get()!!
        scratch.ensure(cells)
        val lum = scratch.lum

        // ── Pass 1: luminance cache + histogram mean/σ for adaptive threshold ──
        // Cells outside the target circle are stored as -1 and ignored from here on.
        var pixelCount = 0
        var sum = 0L
        var sumSq = 0L
        for (j in 0 until dh) {
            val row = j * dw
            for (i in 0 until dw) {
                if (!isInsideTarget(i, j, ds, cfg)) { lum[row + i] = -1; continue }
                val v = getPixel(i, j)
                lum[row + i] = v
                pixelCount++
                sum += v
                sumSq += v.toLong() * v
            }
        }
        if (pixelCount == 0) return DetectionResult.Failure(FailureReason.NO_DATA, "No pixels in target region")

        // Sharpness = variance of the discrete Laplacian (4·center − 4-neighbors) over the
        // region's interior cells, a cheap focus/motion-blur proxy.
        var lapCount = 0
        var lapSum = 0L
        var lapSumSq = 0L
        for (j in 1 until dh - 1) {
            val row = j * dw
            for (i in 1 until dw - 1) {
                val c = lum[row + i]
                if (c < 0) continue
                val l = lum[row + i - 1]; val r = lum[row + i + 1]
                val u = lum[row - dw + i]; val d = lum[row + dw + i]
                if (l < 0 || r < 0 || u < 0 || d < 0) continue
                val lap = 4 * c - l - r - u - d
                lapCount++
                lapSum += lap
                lapSumSq += lap.toLong() * lap
            }
        }
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

        // ── Pass 2: connected components of dark cells, with weighted moments ──
        val label = scratch.label
        val queue = scratch.queue
        java.util.Arrays.fill(label, 0, cells, 0)
        var componentCount = 0
        for (start in 0 until cells) {
            val v = lum[start]
            if (v < 0 || v > thr || label[start] != 0) continue
            componentCount++
            scratch.ensureComponents(componentCount)
            val comp = scratch.comp
            val base = (componentCount - 1) * COMP_FIELDS
            for (f in 0 until COMP_FIELDS) comp[base + f] = 0.0
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = componentCount
            while (head < tail) {
                val idx = queue[head++]
                val pl = lum[idx]
                val i = idx % dw
                val j = idx / dw
                val w = (thr - pl + 1).toDouble()
                comp[base + C_COUNT] += 1.0
                comp[base + C_W] += w
                comp[base + C_WX] += w * i
                comp[base + C_WY] += w * j
                comp[base + C_WXX] += w * i * i
                comp[base + C_WYY] += w * j * j
                comp[base + C_WXY] += w * i * j
                comp[base + C_LUM] += pl
                // 4-connected neighbours
                if (i > 0) { val n = idx - 1; if (label[n] == 0 && lum[n] in 0..thr) { label[n] = componentCount; queue[tail++] = n } }
                if (i < dw - 1) { val n = idx + 1; if (label[n] == 0 && lum[n] in 0..thr) { label[n] = componentCount; queue[tail++] = n } }
                if (j > 0) { val n = idx - dw; if (label[n] == 0 && lum[n] in 0..thr) { label[n] = componentCount; queue[tail++] = n } }
                if (j < dh - 1) { val n = idx + dw; if (label[n] == 0 && lum[n] in 0..thr) { label[n] = componentCount; queue[tail++] = n } }
            }
        }
        if (componentCount == 0) return DetectionResult.Failure(FailureReason.NO_DARK_PIXELS, "No pixels ≤ $thr")

        // Choose the component: in area range, best weight × circularity².
        val maxArea = effectiveMaxArea(cfg)
        val comp = scratch.comp
        var best = -1
        var bestScore = -1.0
        var bestCirc = 0.0
        var largestArea = 0
        var anyInRange = false
        var anyTooLarge = false
        for (c in 0 until componentCount) {
            val base = c * COMP_FIELDS
            val area = (comp[base + C_COUNT] * ds * ds).toInt()
            if (area > largestArea) largestArea = area
            if (area < cfg.minAreaPx) continue
            if (area > maxArea) { anyTooLarge = true; continue }
            anyInRange = true
            val circ = circularityOf(comp, base)
            val score = comp[base + C_W] * circ * circ
            if (score > bestScore) { bestScore = score; best = c; bestCirc = circ }
        }
        if (anyTooLarge) {
            // Without a target circle the dominant dark mass IS the bore the operator is
            // after. Quietly promoting a smaller in-range leftover (speck, shadow fragment)
            // to a confident detection would report a wrong tool position; the "too large"
            // diagnostic tells them to place the target or raise kStd instead. With a target
            // placed, effectiveMaxArea covers the whole circle, so this cannot trigger.
            return DetectionResult.Failure(FailureReason.TOO_LARGE, "Area $largestArea > $maxArea")
        }
        if (!anyInRange) {
            return DetectionResult.Failure(FailureReason.TOO_SMALL, "Area $largestArea < ${cfg.minAreaPx}")
        }

        val base = best * COMP_FIELDS
        val count = comp[base + C_COUNT]
        val fullArea = count * ds * ds
        val wSum = comp[base + C_W]
        val cxD = comp[base + C_WX] / wSum
        val cyD = comp[base + C_WY] / wSum

        // ── Pass 3: contrast + circularity gates on the chosen component ───────
        val darkMean = comp[base + C_LUM] / count
        val contrast = (overallMean - darkMean) / overallMean.coerceAtLeast(1.0)
        if (contrast < cfg.minContrastRatio) {
            return DetectionResult.Failure(
                FailureReason.LOW_CONTRAST,
                "Contrast %.3f < %.3f (dark=%.1f bg=%.1f)".format(contrast, cfg.minContrastRatio, darkMean, overallMean)
            )
        }
        if (bestCirc < cfg.minCircularity) {
            return DetectionResult.Failure(
                FailureReason.NOT_CIRCULAR,
                "Circularity %.3f < %.3f".format(bestCirc, cfg.minCircularity)
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
            area = fullArea,
            circularity = bestCirc,
            contrastRatio = contrast,
            sharpness = sharpness
        )
    }

    /** sqrt(λ₂/λ₁) of the weighted covariance matrix of a component's cells, in [0,1]. */
    private fun circularityOf(comp: DoubleArray, base: Int): Double {
        val w = comp[base + C_W]
        if (w <= 0.0) return 0.0
        val cx = comp[base + C_WX] / w
        val cy = comp[base + C_WY] / w
        val mxx = comp[base + C_WXX] / w - cx * cx
        val myy = comp[base + C_WYY] / w - cy * cy
        val mxy = comp[base + C_WXY] / w - cx * cy
        val trace = mxx + myy
        val det   = mxx * myy - mxy * mxy
        val root  = max(0.0, trace * trace / 4.0 - det)
        val l1    = trace / 2.0 + sqrt(root)
        val l2    = trace / 2.0 - sqrt(root)
        val axisRatio = if (l1 > 1e-9) max(0.0, min(1.0, l2 / l1)) else 0.0
        return sqrt(axisRatio)
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

    /**
     * Detects dark dot center from the luminance (Y) plane of an NV21 / YUV420SP frame
     * (USB camera raw-preview path). Only the first [width]×[height] bytes are read, so
     * the buffer may be a full NV21 frame or a bare Y plane.
     */
    fun detectDarkDotCenter(nv21: ByteArray, width: Int, height: Int, cfg: DetectorConfig): DetectionResult {
        val ds = cfg.downscale
        val dw = width / ds
        val dh = height / ds
        if (width <= 0 || height <= 0 || dw <= 0 || dh <= 0) {
            return DetectionResult.Failure(FailureReason.NO_DATA, "Image too small: ${width}×${height}")
        }
        if (nv21.size < width * height) {
            return DetectionResult.Failure(FailureReason.NO_DATA, "Buffer ${nv21.size} < ${width * height} luma bytes")
        }
        return detectCore(dw, dh, ds, cfg) { i, j ->
            nv21[j * ds * width + i * ds].toInt() and 0xFF
        }
    }

    /** Detects dark dot center from a [Bitmap] (WiFi camera path). */
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
