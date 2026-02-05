package com.etrsystems.axisight

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.*
import java.nio.ByteBuffer
import com.etrsystems.axisight.DetectorConfig



sealed class DetectionResult {
    data class Success(val x: Float, val y: Float, val area: Double) : DetectionResult()
    data class Failure(val reason: FailureReason, val debugInfo: String) : DetectionResult()
}

enum class FailureReason {
    NO_DATA,
    TOO_SMALL, // Found blobs but all were smaller than minArea
    TOO_LARGE, // Found blobs but they exceeded maxArea
    NOT_CIRCULAR, // Shapes were irregular (shadows, gradients)
    NO_DARK_PIXELS // Image is uniform (e.g., solid white/black)
}

object BlobDetector {
    private const val TAG = "BlobDetector"

    /**
     * Calculates luminance from RGB pixel (ARGB format).
     * Uses standard luminance formula: 0.299*R + 0.587*G + 0.114*B
     */
    private fun pixelToLuminance(argbPixel: Int): Int {
        val r = (argbPixel shr 16) and 0xFF
        val g = (argbPixel shr 8) and 0xFF
        val b = argbPixel and 0xFF
        return (r * 0.299 + g * 0.587 + b * 0.114).toInt()
    }

    /**
     * Detects dark dot center from YUV image plane using downscaled sampling.
     */
    fun detectDarkDotCenter(image: ImageProxy, cfg: DetectorConfig): DetectionResult {
        val yPlane = image.planes[0]
        val w = image.width
        val h = image.height
        val rs = yPlane.rowStride
        val ps = yPlane.pixelStride
        val bb: ByteBuffer = yPlane.buffer
        val ds = cfg.downscale
        val dw = w / ds
        val dh = h / ds
        if (dw <= 0 || dh <= 0) return DetectionResult.Failure(FailureReason.NO_DATA, "Image too small: ${dw}x${dh}")

        // 1. Build Histogram
        val histogram = IntArray(256)
        var pixelCount = 0
        
        for (j in 0 until dh) {
            val sy = j * ds
            for (i in 0 until dw) {
                val sx = i * ds
                val idx = sy * rs + sx * ps
                // Y plane is practically luminance [0-255]
                val v = bb.get(idx).toInt() and 0xFF
                histogram[v]++
                pixelCount++
            }
        }
        
        if (pixelCount == 0) return DetectionResult.Failure(FailureReason.NO_DATA, "No pixels scanned")

        // 2. Determine Threshold
        var thr = 0
        if (cfg.lockedThreshold != null) {
            thr = cfg.lockedThreshold!!
            // android.util.Log.d(TAG, "Using LOCKED threshold: $thr")
        } else {
             // Percentile Threshold (darkest 1%)
            val targetCount = (pixelCount * 0.01).toInt().coerceAtLeast(1)
            var cumulative = 0
            for (k in 0..255) {
                cumulative += histogram[k]
                if (cumulative >= targetCount) {
                    thr = k
                    break
                }
            }
             // android.util.Log.d(TAG, "YUV Stats: percentileThr=$thr")
        }

        // 3. Collect pixels below threshold
        var count = 0
        var sxAcc = 0.0; var syAcc = 0.0
        
        // Accumulate second moments for circularity
        val xPoints = FloatArray(cfg.maxAreaPx * 2) // Optimization: buffer
        val yPoints = FloatArray(cfg.maxAreaPx * 2)
        var pIdx = 0
        
        for (j in 0 until dh) {
            val sy0 = j * ds
            for (i in 0 until dw) {
                val sx0 = i * ds
                val idx = sy0 * rs + sx0 * ps
                val v = bb.get(idx).toInt() and 0xFF
                if (v <= thr) {
                    count++
                    sxAcc += i.toDouble()
                    syAcc += j.toDouble()
                    
                    if (pIdx < xPoints.size) {
                       xPoints[pIdx] = i.toFloat()
                       yPoints[pIdx] = j.toFloat()
                       pIdx++
                    }
                }
            }
        }

        if (count == 0) return DetectionResult.Failure(FailureReason.NO_DARK_PIXELS, "No pixels <= $thr")
        
        val fullArea = count * ds * ds
        if (fullArea < cfg.minAreaPx) return DetectionResult.Failure(FailureReason.TOO_SMALL, "Area $fullArea < ${cfg.minAreaPx}")
        if (fullArea > cfg.maxAreaPx) return DetectionResult.Failure(FailureReason.TOO_LARGE, "Area $fullArea > ${cfg.maxAreaPx}")

        val cxD = sxAcc / count
        val cyD = syAcc / count

        // 4. Circularity Check
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        // Use buffered points if valid, else re-scan (YUV re-scan is cheap enough if buffer overflowed)
        if (count <= xPoints.size) {
            for (k in 0 until count) {
                val dx = xPoints[k] - cxD
                val dy = yPoints[k] - cyD
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }
        } else {
             // Fallback re-scan if buffer was too small (unlikely with 1% threshold on correct scene)
             for (j in 0 until dh) {
                val sy0 = j * ds
                for (i in 0 until dw) {
                    val sx0 = i * ds
                    val idx = sy0 * rs + sx0 * ps
                    val v = bb.get(idx).toInt() and 0xFF
                    if (v <= thr) {
                        val dx = i - cxD
                        val dy = j - cyD
                        sxx += dx * dx
                        syy += dy * dy
                        sxy += dx * dy
                    }
                }
            }
        }
        
        sxx /= count; syy /= count; sxy /= count
        val trace = sxx + syy
        val det = sxx * syy - sxy * sxy
        val root = max(0.0, trace * trace / 4.0 - det)
        val l1 = trace / 2.0 + sqrt(root)
        val l2 = trace / 2.0 - sqrt(root)
        val axisRatio = if (l1 > 1e-9) max(0.0, min(1.0, l2 / l1)) else 0.0
        val circularityEstimate = sqrt(axisRatio)

        if (circularityEstimate < cfg.minCircularity) {
            return DetectionResult.Failure(FailureReason.NOT_CIRCULAR, "Circularity $circularityEstimate < ${cfg.minCircularity}")
        }

        val cx = (cxD * ds).toFloat()
        val cy = (cyD * ds).toFloat()
        return DetectionResult.Success(cx, cy, fullArea.toDouble())
    }

    /**
     * Detects dark dot center from Bitmap using downscaled sampling.
     */
    fun detectDarkDotCenter(bitmap: Bitmap, cfg: DetectorConfig): DetectionResult {
        val w = bitmap.width
        val h = bitmap.height
        val ds = cfg.downscale
        val dw = w / ds
        val dh = h / ds
        if (dw <= 0 || dh <= 0) {
             return DetectionResult.Failure(FailureReason.NO_DATA, "Image too small: ${dw}x${dh}")
        }

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. Build Histogram
        val histogram = IntArray(256)
        var pixelCount = 0
        
        for (j in 0 until dh) {
            for (i in 0 until dw) {
                val pixel = pixels[j * ds * w + i * ds]
                val v = pixelToLuminance(pixel)
                histogram[v]++
                pixelCount++
            }
        }
        
        if (pixelCount == 0) return DetectionResult.Failure(FailureReason.NO_DATA, "No pixels scanned")

        // 2. Determine Threshold
        var thr = 0
        if (cfg.lockedThreshold != null) {
            thr = cfg.lockedThreshold!!
        } else {
            // Percentile Threshold (darkest 1%)
            val targetCount = (pixelCount * 0.01).toInt().coerceAtLeast(1)
            var cumulative = 0
            for (k in 0..255) {
                cumulative += histogram[k]
                if (cumulative >= targetCount) {
                    thr = k
                    break
                }
            }
            android.util.Log.d(TAG, "Bitmap Stats: percentileThr=$thr totalPx=$pixelCount")
        }

        // 3. Collect pixels
        var count = 0
        var sxAcc = 0.0; var syAcc = 0.0
        
        // Accumulate second moments
        val xPoints = FloatArray(cfg.maxAreaPx * 2) 
        val yPoints = FloatArray(cfg.maxAreaPx * 2)
        var pIdx = 0

        for (j in 0 until dh) {
            for (i in 0 until dw) {
                val pixel = pixels[j * ds * w + i * ds]
                val v = pixelToLuminance(pixel)
                if (v <= thr) {
                    count++
                    sxAcc += i.toDouble()
                    syAcc += j.toDouble()
                     if (pIdx < xPoints.size) {
                       xPoints[pIdx] = i.toFloat()
                       yPoints[pIdx] = j.toFloat()
                       pIdx++
                    }
                }
            }
        }
        
        if (count == 0) return DetectionResult.Failure(FailureReason.NO_DARK_PIXELS, "No pixels <= $thr")

        val fullArea = count * ds * ds
        if (fullArea < cfg.minAreaPx) return DetectionResult.Failure(FailureReason.TOO_SMALL, "Area $fullArea < ${cfg.minAreaPx}")
        if (fullArea > cfg.maxAreaPx) return DetectionResult.Failure(FailureReason.TOO_LARGE, "Area $fullArea > ${cfg.maxAreaPx}")

        val cxD = sxAcc / count
        val cyD = syAcc / count

        // 4. Circularity
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        if (count <= xPoints.size) {
            for (k in 0 until count) {
                val dx = xPoints[k] - cxD
                val dy = yPoints[k] - cyD
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }
        } else {
             // Fallback
             for (j in 0 until dh) {
                for (i in 0 until dw) {
                    val pixel = pixels[j * ds * w + i * ds]
                    val v = pixelToLuminance(pixel)
                    if (v <= thr) {
                        val dx = i - cxD
                        val dy = j - cyD
                        sxx += dx * dx
                        syy += dy * dy
                        sxy += dx * dy
                    }
                }
            }
        }
        
        sxx /= count; syy /= count; sxy /= count
        val trace = sxx + syy
        val det = sxx * syy - sxy * sxy
        val root = max(0.0, trace * trace / 4.0 - det)
        val l1 = trace / 2.0 + sqrt(root)
        val l2 = trace / 2.0 - sqrt(root)
        val axisRatio = if (l1 > 1e-9) max(0.0, min(1.0, l2 / l1)) else 0.0
        val circularityEstimate = sqrt(axisRatio)

        if (circularityEstimate < cfg.minCircularity) {
             return DetectionResult.Failure(FailureReason.NOT_CIRCULAR, "Circularity $circularityEstimate < ${cfg.minCircularity}")
        }

        val cx = (cxD * ds).toFloat()
        val cy = (cyD * ds).toFloat()
        return DetectionResult.Success(cx, cy, fullArea.toDouble())
    }
}
