package com.etrsystems.axisight.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import androidx.annotation.WorkerThread
import com.jiangdg.ausbc.widget.IAspectRatio

/**
 * Stable IAspectRatio implementation that never reports zero surface size.
 * The AUSBC stack times out if getSurfaceWidth/getSurfaceHeight return 0.
 */
class SafeAspectRatioTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), IAspectRatio {

    private var aspectRatio = 0.0
    private var fallbackWidth = 640
    private var fallbackHeight = 480
    private var surface: Surface? = null

    override fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        fallbackWidth = width
        fallbackHeight = height

        val ratio = width.toDouble() / height.toDouble()
        if (aspectRatio != ratio) {
            aspectRatio = ratio
            post { requestLayout() }
        }
    }

    override fun getSurfaceWidth(): Int {
        val w = width
        return if (w > 0) w else fallbackWidth
    }

    override fun getSurfaceHeight(): Int {
        val h = height
        return if (h > 0) h else fallbackHeight
    }

    override fun getSurface(): Surface {
        // surfaceTexture can be null when the view is detached or not yet laid out.
        // Throwing here crashes the AUSBC library with no recovery path.
        // Instead, wait up to SURFACE_WAIT_TIMEOUT_MS for the texture to become available.
        val st: SurfaceTexture = awaitSurfaceTexture()
            ?: throw IllegalStateException("SurfaceTexture unavailable after timeout")

        val w = getSurfaceWidth()
        val h = getSurfaceHeight()
        if (w > 0 && h > 0) {
            st.setDefaultBufferSize(w, h)
        }

        val existing = surface
        if (existing != null && existing.isValid) return existing

        existing?.release()
        return Surface(st).also { surface = it }
    }

    /**
     * Polls for a valid SurfaceTexture up to [SURFACE_WAIT_TIMEOUT_MS].
     * This avoids crashing the AUSBC library when the surface is momentarily
     * unavailable during layout transitions or fragment re-attach.
     *
     * Must NOT be called on the main thread — this method blocks.
     * The AUSBC library calls [getSurface] from a background camera thread.
     * [TextureView.getSurfaceTexture] is written by the framework on the main thread;
     * the cross-thread read here is accepted as a platform implementation detail
     * (TextureView uses volatile/atomic storage internally).
     */
    @WorkerThread
    private fun awaitSurfaceTexture(): SurfaceTexture? {
        val deadline = System.currentTimeMillis() + SURFACE_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val st = surfaceTexture
            if (st != null) return st
            try {
                Thread.sleep(SURFACE_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    override fun postUITask(block: () -> Unit) {
        post { block.invoke() }
    }

    override fun onDetachedFromWindow() {
        surface?.release()
        surface = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val SURFACE_WAIT_TIMEOUT_MS = 500L
        private const val SURFACE_POLL_INTERVAL_MS = 20L
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (aspectRatio <= 0.0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (measuredWidth == 0 || measuredHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        var targetWidth = measuredWidth
        var targetHeight = measuredHeight
        val viewRatio = measuredWidth.toDouble() / measuredHeight.toDouble()
        if (viewRatio > aspectRatio) {
            targetWidth = (measuredHeight * aspectRatio).toInt()
        } else {
            targetHeight = (measuredWidth / aspectRatio).toInt()
        }
        setMeasuredDimension(targetWidth, targetHeight)
    }
}
