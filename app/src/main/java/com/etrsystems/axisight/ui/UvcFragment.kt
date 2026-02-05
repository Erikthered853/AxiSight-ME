// Kotlin
package com.etrsystems.axisight.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.etrsystems.axisight.BlobDetector
import com.etrsystems.axisight.DetectorConfig
import com.etrsystems.axisight.DetectionResult
import com.etrsystems.axisight.R
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio

class UvcFragment : CameraFragment() {

    interface DetectionCallback {
        fun onPointDetected(x: Float, y: Float)
    }

    private var textureView: AspectRatioTextureView? = null
    private var callback: DetectionCallback? = null
    
    // Default to VGA (640x480) - Safer for initial embedded test
    private var previewWidth: Int = 640
    private var previewHeight: Int = 480
    
    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    private var isProcessing = false
    private val detectorConfig = DetectorConfig()

    fun setDetectionCallback(cb: DetectionCallback) {
        this.callback = cb
    }

    /**
     * Toggles target locking.
     * @param active If true, locks onto the center pixel of the current frame. If false, resets lock.
     * @return The locked threshold value if locked, or null.
     */
    fun lockTarget(active: Boolean): Int? {
        if (!active) {
            detectorConfig.lockedThreshold = null
            return null
        }
        
        // Capture current center pixel
        val bmp = textureView?.bitmap ?: return null
        val w = bmp.width
        val h = bmp.height
        if (w > 0 && h > 0) {
            // Sample center 5x5 area for robust color
            val cx = w / 2
            val cy = h / 2
            val pixel = bmp.getPixel(cx, cy)
            
            // Convert to luminance 
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val lum = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            
            // Set threshold with a safety margin (e.g. +30 brighter allowed)
            // But since it's a "dark dot", we want to detect pixels *darker* than this threshold.
            // If the dot is dark (lum=50) and background is bright (lum=200),
            // we want to catch things <= 50 + margin.
            val margin = 30
            val thr = (lum + margin).coerceIn(0, 255)
            detectorConfig.lockedThreshold = thr
            Log.i(TAG, "Target LOCKED: centerLum=$lum, thr=$thr")
            return thr
        }
        return null
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        return try {
            val view = inflater.inflate(R.layout.fragment_uvc, container, false)
            textureView = view.findViewById(R.id.uvc_texture_view)
            textureView?.setAspectRatio(previewWidth, previewHeight)
            Log.d(TAG, "Root view inflated")
            view
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing camera view", e)
            Toast.makeText(context, "Error initializing USB camera: ${e.message}", Toast.LENGTH_LONG).show()
            FrameLayout(inflater.context)
        }
    }

    override fun getCameraView(): IAspectRatio? {
        return textureView
    }

    override fun getCameraViewContainer(): ViewGroup {
        return view as? ViewGroup ?: FrameLayout(requireContext())
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(previewWidth)
            .setPreviewHeight(previewHeight)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            //.setDefaultCameraId(0) // Removed: undefined reference
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .create()
    }

    override fun onCameraState(
        self: com.jiangdg.ausbc.MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.i(TAG, "Camera opened successfully")
                activity?.runOnUiThread {
                    Toast.makeText(context, "USB Camera Connected", Toast.LENGTH_SHORT).show()
                }
                startProcessing()
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.i(TAG, "Camera closed")
                stopProcessing()
            }
            ICameraStateCallBack.State.ERROR -> {
                Log.e(TAG, "Camera error: $msg")
                activity?.runOnUiThread {
                    Toast.makeText(context, "Camera Error: $msg", Toast.LENGTH_LONG).show()
                }
                stopProcessing()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startProcessingThread()
    }

    override fun onPause() {
        stopProcessing()
        stopProcessingThread()
        super.onPause()
    }

    private fun startProcessingThread() {
        if (processingThread == null) {
            processingThread = HandlerThread("FrameProcessingThread")
            processingThread?.start()
            processingHandler = Handler(processingThread!!.looper)
        }
    }

    private fun stopProcessingThread() {
        processingThread?.quitSafely()
        processingThread = null
        processingHandler = null
    }

    private fun startProcessing() {
        if (isProcessing) return
        isProcessing = true
        processFrame()
    }

    private fun stopProcessing() {
        isProcessing = false
        processingHandler?.removeCallbacksAndMessages(null)
    }

    private fun processFrame() {
        if (!isProcessing) return
        
        processingHandler?.postDelayed({
            if (!isProcessing) return@postDelayed
            
            try {
                // Log.d(TAG, "Processing frame...") // Uncomment for verbose logging
                // Warning: getBitmap() is slow, but acceptable for < 30fps analysis
                val bmp = textureView?.bitmap
                if (bmp != null) {
                    processBitmap(bmp)
                } else {
                    android.util.Log.w(TAG, "TextureView bitmap is null - Surface not ready?")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
            } finally {
                // Schedule next frame
                if (isProcessing) processFrame()
            }
        }, 66) // ~15 FPS
    }

    private fun processBitmap(bmp: Bitmap) {
        try {
            val result = BlobDetector.detectDarkDotCenter(bmp, detectorConfig)
            when (result) {
                is DetectionResult.Success -> {
                    Log.d(TAG, "Blob detected at: ${result.x}, ${result.y}")
                    activity?.runOnUiThread {
                        val viewX = textureView?.x ?: 0f
                        val viewY = textureView?.y ?: 0f
                        callback?.onPointDetected(result.x + viewX, result.y + viewY)
                    }
                }
                is DetectionResult.Failure -> {
                    // Log failure for debugging, but reduced noise
                     Log.d(TAG, "No blob: ${result.reason} - ${result.debugInfo}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection error", e)
        }
        // No explicit recycle needed as we don't own the bitmap creation policy of textureView.bitmap?
        // Actually textureView.getBitmap() creates a new bitmap. We should let GC handle it or recycle it if we are sure.
        // For safety/simplicity, let GC handle it.
    }

    companion object {
        private const val TAG = "UvcFragment"
    }
}