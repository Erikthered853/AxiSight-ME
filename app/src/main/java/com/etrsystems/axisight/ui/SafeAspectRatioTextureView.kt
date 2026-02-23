package com.etrsystems.axisight.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
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
        val st: SurfaceTexture = surfaceTexture
            ?: throw IllegalStateException("SurfaceTexture unavailable")

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

    override fun postUITask(block: () -> Unit) {
        post { block.invoke() }
    }

    override fun onDetachedFromWindow() {
        surface?.release()
        surface = null
        super.onDetachedFromWindow()
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
