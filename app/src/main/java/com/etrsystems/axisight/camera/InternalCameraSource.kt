package com.etrsystems.axisight.camera

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Built-in device camera source (CameraX preview + analysis).
 *
 * Owns the CameraX provider, the analysis executor, and the manual-lock
 * threshold sampling that previously lived inline in MainActivity's analyzer.
 */
class InternalCameraSource(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val frameAnalyzer: FrameAnalyzer,
    private val listener: Listener
) {
    interface Listener {
        /** Gate for running detection at all (auto-detect on, not simulating). Any thread. */
        fun isAnalysisEnabled(): Boolean

        /** Gate for delivering successful detections (tracking enabled). Any thread. */
        fun isTrackingEnabled(): Boolean

        /** Called on the main thread with a view-space detection. */
        fun onToolPointDetected(point: FrameAnalyzer.ViewPoint)

        /**
         * Called on the ANALYSIS thread when a [requestLock] sampled the frame
         * center, so the new threshold can be applied to the shared config
         * before this same frame is detected. Implementations must be
         * thread-safe and post any UI work to the main thread themselves.
         */
        fun onLockThresholdSampled(threshold: Int)
    }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null

    @Volatile private var isActive = false
    @Volatile private var lockRequested = false

    fun start() {
        isActive = true
        try {
            val providerFuture = ProcessCameraProvider.getInstance(activity)
            providerFuture.addListener({
                try {
                    cameraProvider = providerFuture.get()
                    bindUseCases()
                } catch (e: Exception) {
                    Toast.makeText(activity, "Camera initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Failed to get camera provider", e)
                }
            }, ContextCompat.getMainExecutor(activity))
        } catch (e: Exception) {
            Toast.makeText(activity, "Camera setup error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "start failed", e)
        }
    }

    fun stop() {
        isActive = false
        cameraProvider?.unbindAll()
        imageAnalyzer = null
    }

    /** Samples the center-pixel luminance on the next analyzed frame. */
    fun requestLock() {
        lockRequested = true
    }

    /** Cancels a pending [requestLock] that has not been consumed by a frame yet. */
    fun cancelLockRequest() {
        lockRequested = false
    }

    /** Shuts down the analysis executor. The source cannot be started again. */
    fun dispose() {
        stop()
        analysisExecutor.shutdown()
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { image -> analyzeFrame(image) }
                }

            provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
        } catch (e: Exception) {
            Toast.makeText(activity, "Camera binding failed: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "bindUseCases failed", e)
        }
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (isActive && listener.isAnalysisEnabled()) {
                if (lockRequested) {
                    lockRequested = false
                    sampleLockThreshold(image)
                }
                val point = frameAnalyzer.analyzeCameraFrame(
                    image,
                    previewView.width,
                    previewView.height,
                    deliverResult = listener.isTrackingEnabled()
                )
                if (point != null) {
                    activity.runOnUiThread { listener.onToolPointDetected(point) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image analysis failed", e)
        } finally {
            image.close()
        }
    }

    /**
     * Samples the center pixel of the Y plane (YUV_420_888) and derives a lock
     * threshold from it. Uses duplicate() to avoid mutating the shared buffer
     * position before the frame is passed to BlobDetector.
     */
    private fun sampleLockThreshold(image: ImageProxy) {
        val yPlane = image.planes[0].buffer.duplicate()
        val cx = image.width / 2
        val cy = image.height / 2
        val rowStride = image.planes[0].rowStride
        val pixelStride = image.planes[0].pixelStride
        val offset = (cy * rowStride) + (cx * pixelStride)
        if (offset < yPlane.remaining()) {
            yPlane.position(offset)
            val lum = yPlane.get().toInt() and 0xFF
            val thr = (lum + LOCK_LUMINANCE_MARGIN).coerceIn(0, 255)
            listener.onLockThresholdSampled(thr)
        }
    }

    companion object {
        private const val TAG = "InternalCameraSource"
        private const val LOCK_LUMINANCE_MARGIN = 30
    }
}
