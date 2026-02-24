package com.etrsystems.axisight

import android.content.Context
import kotlin.math.hypot

data class CalibrationData(
    val centerX: Float,
    val centerY: Float,
    val upX: Float,
    val upY: Float,
    val inchesPerPixel: Double
) {
    fun toolOffsetInches(toolX: Float, toolY: Float): Pair<Double, Double> {
        val vx = toolX - centerX
        val vy = toolY - centerY

        // Image-space +Y points down, so "up" is user-defined by calibration.
        val xAxisX = upY
        val xAxisY = -upX

        val deltaXIn = (vx * xAxisX + vy * xAxisY) * inchesPerPixel
        val deltaYIn = (vx * upX + vy * upY) * inchesPerPixel
        return deltaXIn to deltaYIn
    }

    companion object {
        fun fromCenterUpAndScale(
            centerX: Float,
            centerY: Float,
            upPointX: Float,
            upPointY: Float,
            inchesPerPixel: Double
        ): CalibrationData? {
            val ux = upPointX - centerX
            val uy = upPointY - centerY
            val mag = hypot(ux, uy)
            if (mag < 1f || inchesPerPixel <= 0.0) return null
            return CalibrationData(
                centerX = centerX,
                centerY = centerY,
                upX = ux / mag,
                upY = uy / mag,
                inchesPerPixel = inchesPerPixel
            )
        }
    }
}

class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(data: CalibrationData) {
        prefs.edit()
            .putFloat(KEY_CENTER_X, data.centerX)
            .putFloat(KEY_CENTER_Y, data.centerY)
            .putFloat(KEY_UP_X, data.upX)
            .putFloat(KEY_UP_Y, data.upY)
            .putString(KEY_IN_PER_PX, data.inchesPerPixel.toString())
            .apply()
    }

    fun load(): CalibrationData? {
        val centerX = prefs.getFloat(KEY_CENTER_X, Float.NaN)
        val centerY = prefs.getFloat(KEY_CENTER_Y, Float.NaN)
        val upX = prefs.getFloat(KEY_UP_X, Float.NaN)
        val upY = prefs.getFloat(KEY_UP_Y, Float.NaN)
        val inPerPxText = prefs.getString(KEY_IN_PER_PX, null)
        val inPerPx = inPerPxText?.toDoubleOrNull()

        if (!centerX.isFinite() || !centerY.isFinite() || !upX.isFinite() || !upY.isFinite()) {
            return null
        }
        if (inPerPx == null || inPerPx <= 0.0) return null

        return CalibrationData(
            centerX = centerX,
            centerY = centerY,
            upX = upX,
            upY = upY,
            inchesPerPixel = inPerPx
        )
    }

    private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()

    companion object {
        private const val PREFS_NAME = "axisight_calibration"
        private const val KEY_CENTER_X = "center_x"
        private const val KEY_CENTER_Y = "center_y"
        private const val KEY_UP_X = "up_x"
        private const val KEY_UP_Y = "up_y"
        private const val KEY_IN_PER_PX = "in_per_px"
    }
}
