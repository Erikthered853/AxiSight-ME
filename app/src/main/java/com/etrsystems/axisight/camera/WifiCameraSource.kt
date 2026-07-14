package com.etrsystems.axisight.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/**
 * WiFi camera source: plays an RTSP stream into a TextureView via ExoPlayer and
 * runs detection on each rendered frame (grabbed as a bitmap on the main thread).
 */
@UnstableApi
class WifiCameraSource(
    private val context: Context,
    private val textureView: TextureView,
    private val frameAnalyzer: FrameAnalyzer,
    private val listener: Listener
) : TextureView.SurfaceTextureListener {

    interface Listener {
        /** Gate for running detection at all (auto-detect on). Main thread. */
        fun isAnalysisEnabled(): Boolean

        /** Gate for delivering successful detections (tracking enabled). Main thread. */
        fun isTrackingEnabled(): Boolean

        /** Called on the main thread with a view-space detection. */
        fun onToolPointDetected(point: FrameAnalyzer.ViewPoint)

        /** Called on the main thread with a user-facing error message. */
        fun onError(message: String)
    }

    private var exoPlayer: ExoPlayer? = null
    private var isActive = false

    /**
     * Starts streaming from [url]. Reports failures through [Listener.onError].
     * @return false only when the URL is rejected up front; true once streaming
     *         is attempted (even if the player later fails asynchronously).
     */
    fun start(url: String): Boolean {
        try {
            if (!isValidRtspUrl(url)) {
                listener.onError("Invalid RTSP URL. Expected format: rtsp://...")
                return false
            }

            textureView.surfaceTextureListener = this
            stop() // Clean up any existing player

            isActive = true
            exoPlayer = ExoPlayer.Builder(context).build()
            textureView.surfaceTexture?.let {
                exoPlayer?.setVideoSurface(Surface(it))
            }

            val mediaSource = RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(url))
            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            exoPlayer?.play()
        } catch (e: Exception) {
            Log.e(TAG, "WiFi camera start failed", e)
            listener.onError("WiFi camera error: ${e.message}")
            stop()
        }
        return true
    }

    fun stop() {
        isActive = false
        try {
            exoPlayer?.run {
                clearVideoSurface()
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WiFi camera", e)
        } finally {
            exoPlayer = null
        }
    }

    /** Validates RTSP URL format to prevent injection/malformed URLs. */
    private fun isValidRtspUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: return false
            scheme in listOf("rtsp", "rtsps") && uri.host != null
        } catch (e: Exception) {
            false
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        exoPlayer?.setVideoSurface(Surface(surface))
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        exoPlayer?.clearVideoSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        try {
            if (isActive && listener.isAnalysisEnabled()) {
                val bmp = textureView.bitmap ?: return
                try {
                    val point = frameAnalyzer.analyzeTextureFrame(
                        bmp,
                        textureView.width,
                        textureView.height,
                        deliverResult = listener.isTrackingEnabled()
                    )
                    if (point != null) {
                        listener.onToolPointDetected(point)
                    }
                } finally {
                    bmp.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Texture frame analysis failed", e)
        }
    }

    companion object {
        private const val TAG = "WifiCameraSource"
    }
}
