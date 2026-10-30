package com.etrsystems.axisight.camera

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.etrsystems.axisight.BlobDetector
import com.etrsystems.axisight.CoordinateMapper
import com.etrsystems.axisight.DetectionFilter
import com.etrsystems.axisight.DetectionQuality
import com.etrsystems.axisight.DetectionResult
import com.etrsystems.axisight.DetectorConfig

/**
 * Shared frame -> view-space tool point pipeline used by every camera source:
 * internal (CameraX [ImageProxy]), WiFi (TextureView [Bitmap]) and USB (raw NV21
 * frames from libuvc).
 *
 * Per frame, in order:
 *  1. refresh the [CoordinateMapper] with the frame and preview-view geometry,
 *  2. convert the operator's view-space target circle (from [targetProvider]) into
 *     image space through that mapper, so the mask is always correct for the source
 *     that is actually running (rotation, scale, letterbox, source switches),
 *  3. run [BlobDetector] on the image-space frame,
 *  4. pass the result through the temporal [DetectionFilter] (jump gate, confirmation
 *     streak, adaptive EMA), and
 *  5. map a confirmed success back into view space for the overlay.
 *
 * The filter runs on every analyzed frame (it keeps temporal state) even when
 * [deliverResult] is false, so re-enabling tracking does not restart confirmation.
 */
class FrameAnalyzer(
    private val coordinateMapper: CoordinateMapper,
    private val detectionFilter: DetectionFilter,
    private val configProvider: () -> DetectorConfig,
    private val targetProvider: () -> ViewTarget? = { null }
) {
    /** A successful detection converted to view (screen) space. */
    data class ViewPoint(val x: Float, val y: Float, val quality: DetectionQuality)

    /** Operator-placed target circle in overlay/view space. */
    data class ViewTarget(val x: Float, val y: Float, val radiusPx: Float)

    /**
     * Why the most recent frame produced no point (null right after a success).
     * Readable from any thread; the UI shows it so the operator knows which
     * control to adjust instead of staring at "no detection".
     */
    @Volatile var lastFailure: DetectionResult.Failure? = null
        private set
    private var lastMissLogMs = 0L

    /** Forgets the last failure reason. Call on camera-source switch so the hint cannot go stale. */
    fun reset() {
        lastFailure = null
    }

    /** Analyzes a CameraX frame. Does not close [image]; the caller owns it. */
    fun analyzeCameraFrame(
        image: ImageProxy,
        viewWidth: Int,
        viewHeight: Int,
        deliverResult: Boolean
    ): ViewPoint? {
        coordinateMapper.update(
            image.width, image.height,
            image.imageInfo.rotationDegrees,
            viewWidth, viewHeight
        )
        val cfg = resolveConfig()
        return finish(BlobDetector.detectDarkDotCenter(image, cfg), cfg, deliverResult)
    }

    /**
     * Analyzes a bitmap grabbed from a TextureView (WiFi/RTSP path). ExoPlayer renders
     * with no additional rotation and the texture size can change between frames.
     */
    fun analyzeTextureFrame(
        bitmap: Bitmap,
        viewWidth: Int,
        viewHeight: Int,
        deliverResult: Boolean
    ): ViewPoint? {
        coordinateMapper.update(bitmap.width, bitmap.height, 0, viewWidth, viewHeight)
        val cfg = resolveConfig()
        return finish(BlobDetector.detectDarkDotCenter(bitmap, cfg), cfg, deliverResult)
    }

    /**
     * Analyzes a raw NV21 frame from the USB camera. The preview is an aspect-fitted
     * view of [viewWidth]×[viewHeight] whose top-left corner sits at
     * ([viewOffsetX], [viewOffsetY]) inside the overlay, so the mapper needs both.
     */
    fun analyzeNv21Frame(
        data: ByteArray,
        width: Int,
        height: Int,
        viewWidth: Int,
        viewHeight: Int,
        viewOffsetX: Float,
        viewOffsetY: Float,
        deliverResult: Boolean
    ): ViewPoint? {
        coordinateMapper.update(width, height, 0, viewWidth, viewHeight, viewOffsetX, viewOffsetY)
        val cfg = resolveConfig()
        return finish(BlobDetector.detectDarkDotCenter(data, width, height, cfg), cfg, deliverResult)
    }

    private fun finish(raw: DetectionResult, cfg: DetectorConfig, deliverResult: Boolean): ViewPoint? {
        val result = detectionFilter.filter(raw, cfg)
        if (result is DetectionResult.Failure) {
            lastFailure = result
            val now = SystemClock.elapsedRealtime()
            if (now - lastMissLogMs >= MISS_LOG_INTERVAL_MS) {
                lastMissLogMs = now
                Log.d(TAG, "no detection: ${result.reason} ${result.debugInfo}")
            }
            return null
        }
        lastFailure = null
        if (result !is DetectionResult.Success || !deliverResult) return null
        return result.toViewPoint()
    }

    /** Snapshot of the shared config with the view-space target converted to image space. */
    private fun resolveConfig(): DetectorConfig {
        val base = configProvider()
        val target = targetProvider() ?: return base
        val (ix, iy) = coordinateMapper.viewToImage(target.x, target.y)
        return base.copy(
            targetCenterX = ix,
            targetCenterY = iy,
            targetRadiusPx = coordinateMapper.viewRadiusToImage(target.radiusPx)
        )
    }

    private fun DetectionResult.Success.toViewPoint(): ViewPoint {
        val (vx, vy) = coordinateMapper.imageToView(x, y)
        return ViewPoint(vx, vy, DetectionQuality(circularity, contrastRatio, sharpness))
    }

    companion object {
        private const val TAG = "FrameAnalyzer"
        private const val MISS_LOG_INTERVAL_MS = 1000L
    }
}
