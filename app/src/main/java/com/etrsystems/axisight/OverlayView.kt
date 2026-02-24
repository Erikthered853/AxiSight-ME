package com.etrsystems.axisight

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * Custom view that overlays detected point circle fit results.
 * Displays detected points, fitted circle, and measurement information.
 */
class OverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    init {
        Log.d("OverlayView", "Initialized")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d("OverlayView", "Size Changed: ${w}x${h}")
        if (targetInitialized && w > 0 && h > 0) {
            targetX = targetX.coerceIn(0f, w.toFloat())
            targetY = targetY.coerceIn(0f, h.toFloat())
            val maxRadius = maxOf(24f, minOf(w, h) * 0.5f)
            targetRadiusPx = targetRadiusPx.coerceIn(24f, maxRadius)
            notifyTargetChanged()
        }
    }

    private val pts = ArrayDeque<Pair<Float, Float>>()
    private val ptsLock = Any()  // Thread safety for concurrent access

    var maxPoints = 240
    var mmPerPx: Double? = null

    var showSimDot = false
    var simX = 0f
    var simY = 0f
    var onTargetChanged: ((Float, Float, Float) -> Unit)? = null
    private var calCenter: Pair<Float, Float>? = null
    private var calUp: Pair<Float, Float>? = null
    private var calScaleP1: Pair<Float, Float>? = null
    private var calScaleP2: Pair<Float, Float>? = null
    private var calPending: Pair<Float, Float>? = null

    var targetX = 0f
        private set
    var targetY = 0f
        private set
    var targetRadiusPx = 160f
        private set
    private var targetInitialized = false

    private val paintTarget = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val paintTargetDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }
    private val paintPts = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; style = Paint.Style.FILL
    }
    private val paintFit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f
    }
    private val paintSim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.FILL
    }
    private val paintCalCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; style = Paint.Style.FILL
    }
    private val paintCalUp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.FILL
    }
    private val paintCalScale = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.MAGENTA; style = Paint.Style.FILL
    }
    private val paintCalPending = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 165, 0); style = Paint.Style.FILL
    }
    private val paintCalLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val paintCalLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        style = Paint.Style.FILL
    }

    fun clearPoints() {
        synchronized(ptsLock) {
            pts.clear()
        }
        invalidate()
    }

    fun addPoint(px: Float, py: Float) {
        synchronized(ptsLock) {
            if (pts.size >= maxPoints) pts.removeFirst()
            pts.addLast(px to py)
        }
        invalidate()
    }

    fun getPoints(): List<Pair<Float, Float>> {
        synchronized(ptsLock) {
            return pts.toList()
        }
    }

    fun setSimDot(x: Float, y: Float) {
        simX = x; simY = y; showSimDot = true; invalidate()
    }

    fun hideSimDot() { showSimDot = false; invalidate() }

    fun setCalibrationMarkers(
        center: Pair<Float, Float>?,
        up: Pair<Float, Float>?,
        scaleP1: Pair<Float, Float>?,
        scaleP2: Pair<Float, Float>?,
        pending: Pair<Float, Float>?
    ) {
        calCenter = center
        calUp = up
        calScaleP1 = scaleP1
        calScaleP2 = scaleP2
        calPending = pending
        invalidate()
    }

    fun setTargetCenter(x: Float, y: Float) {
        targetInitialized = true
        targetX = x.coerceIn(0f, width.toFloat().coerceAtLeast(1f))
        targetY = y.coerceIn(0f, height.toFloat().coerceAtLeast(1f))
        notifyTargetChanged()
        invalidate()
    }

    fun setTargetRadius(radiusPx: Float) {
        targetInitialized = true
        val maxRadius = maxOf(24f, minOf(width, height) * 0.5f)
        targetRadiusPx = radiusPx.coerceIn(24f, maxRadius)
        notifyTargetChanged()
        invalidate()
    }

    private fun notifyTargetChanged() {
        onTargetChanged?.invoke(targetX, targetY, targetRadiusPx)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Log.v("OverlayView", "onDraw: w=$width h=$height") // Uncomment if needed, spammy
        val w = width; val h = height
        val cx = w / 2f; val cy = h / 2f

        if (!targetInitialized && w > 0 && h > 0) {
            targetInitialized = true
            targetX = cx
            targetY = cy
            targetRadiusPx = minOf(w, h) * 0.2f
            notifyTargetChanged()
        }

        canvas.drawCircle(targetX, targetY, targetRadiusPx, paintTarget)
        canvas.drawCircle(targetX, targetY, 8f, paintTargetDot)

        if (showSimDot) {
            canvas.drawCircle(simX, simY, 8f, paintSim)
        }

        drawCalibrationMarkers(canvas)

        synchronized(ptsLock) {
            for ((x, y) in pts) {
                canvas.drawCircle(x, y, 4f, paintPts)
            }

            if (pts.size >= 3) {
                val dpts = pts.map { it.first.toDouble() to it.second.toDouble() }
                val res = CircleFit.fit(dpts)
                if (res != null) {
                    val ccx = res.cx.toFloat(); val ccy = res.cy.toFloat()
                    val rr = res.r.toFloat()
                    canvas.drawCircle(ccx, ccy, rr, paintFit)
                    canvas.drawCircle(ccx, ccy, 5f, paintFit)
                    val mm = mmPerPx
                    val text = if (mm != null)
                        "r=%.1fpx(%.3fmm)  rms=%.2fpx(%.3fmm)".format(rr, rr*mm, res.rms, res.rms*mm)
                    else
                        "r=%.1fpx  rms=%.2fpx".format(rr, res.rms)
                    canvas.drawText(text, 20f, h - 24f, paintText)
                }
            }
        }
    }

    private fun drawCalibrationMarkers(canvas: Canvas) {
        val c = calCenter
        val u = calUp
        val s1 = calScaleP1
        val s2 = calScaleP2
        val p = calPending

        if (c != null && u != null) {
            canvas.drawLine(c.first, c.second, u.first, u.second, paintCalLine)
        }
        if (s1 != null && s2 != null) {
            canvas.drawLine(s1.first, s1.second, s2.first, s2.second, paintCalLine)
        }

        c?.let { drawCalPoint(canvas, it, "C", paintCalCenter) }
        u?.let { drawCalPoint(canvas, it, "U", paintCalUp) }
        s1?.let { drawCalPoint(canvas, it, "S1", paintCalScale) }
        s2?.let { drawCalPoint(canvas, it, "S2", paintCalScale) }
        p?.let { drawCalPoint(canvas, it, "P", paintCalPending) }
    }

    private fun drawCalPoint(canvas: Canvas, point: Pair<Float, Float>, label: String, paint: Paint) {
        canvas.drawCircle(point.first, point.second, 10f, paint)
        canvas.drawText(label, point.first + 12f, point.second - 12f, paintCalLabel)
    }
}

