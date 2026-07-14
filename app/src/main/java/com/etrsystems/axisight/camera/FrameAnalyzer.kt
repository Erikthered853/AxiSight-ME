package com.etrsystems.axisight.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.etrsystems.axisight.BlobDetector
import com.etrsystems.axisight.CoordinateMapper
import com.etrsystems.axisight.DetectionFilter
import com.etrsystems.axisight.DetectionQuality
import com.etrsystems.axisight.DetectionResult
import com.etrsystems.axisight.DetectorConfig

/**
 * Shared frame -> view-space tool point pipeline used by the internal (CameraX)
 * and WiFi (RTSP) sources. USB frames are detected inside
 * [com.etrsystems.axisight.ui.UvcFragment] with its own config and do not pass
 * through here.
 *
 * The [DetectionFilter] runs on every analyzed frame (it keeps temporal state),
 * but the [CoordinateMapper] update / conversion is skipped when
 * [deliverResult] is false, matching the original MainActivity behavior where
 * tracking-disabled frames still feed the filter without touching the mapper.
 */
class FrameAnalyzer(
    private val coordinateMapper: CoordinateMapper,
    private val detectionFilter: DetectionFilter,
    private val configProvider: () -> DetectorConfig
) {
    /** A successful detection converted to view (screen) space. */
    data class ViewPoint(val x: Float, val y: Float, val quality: DetectionQuality)

    /** Analyzes a CameraX frame. Does not close [image]; the caller owns it. */
    fun analyzeCameraFrame(
        image: ImageProxy,
        viewWidth: Int,
        viewHeight: Int,
        deliverResult: Boolean
    ): ViewPoint? {
        val snapshot = configProvider()
        val raw = BlobDetector.detectDarkDotCenter(image, snapshot)
        val result = detectionFilter.filter(raw, snapshot)
        if (result !is DetectionResult.Success || !deliverResult) return null
        coordinateMapper.update(
            image.width, image.height,
            image.imageInfo.rotationDegrees,
            viewWidth, viewHeight
        )
        return result.toViewPoint()
    }

    /**
     * Analyzes a bitmap grabbed from a TextureView (WiFi/RTSP path). The mapper
     * is updated before detection on every frame because ExoPlayer renders with
     * no additional rotation and the texture size can change between frames.
     */
    fun analyzeTextureFrame(
        bitmap: Bitmap,
        viewWidth: Int,
        viewHeight: Int,
        deliverResult: Boolean
    ): ViewPoint? {
        coordinateMapper.update(bitmap.width, bitmap.height, 0, viewWidth, viewHeight)
        val snapshot = configProvider()
        val raw = BlobDetector.detectDarkDotCenter(bitmap, snapshot)
        val result = detectionFilter.filter(raw, snapshot)
        if (result !is DetectionResult.Success || !deliverResult) return null
        return result.toViewPoint()
    }

    private fun DetectionResult.Success.toViewPoint(): ViewPoint {
        val (vx, vy) = coordinateMapper.imageToView(x, y)
        return ViewPoint(vx, vy, DetectionQuality(circularity, contrastRatio, sharpness))
    }
}
