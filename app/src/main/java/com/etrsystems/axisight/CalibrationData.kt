package com.etrsystems.axisight

import android.content.Context
import kotlin.math.hypot

/**
 * Calibration state, stored in camera image-space pixels (not on-screen view-space
 * pixels) so it stays valid across screen resolution, orientation, and letterbox
 * changes. Callers must convert view-space points (e.g. touch taps, live detections)
 * through [CoordinateMapper] before passing them in or comparing them against this data.
 */
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

    fun saveCenter(x: Float, y: Float) {
        prefs.edit().putFloat(KEY_CENTER_X, x).putFloat(KEY_CENTER_Y, y).apply()
    }

    /**
     * Drops the up-vector and scale, keeping the center. Used when a center is captured on
     * a camera other than the one the scale was measured on: that scale is in the other
     * camera's image space and must not be re-tagged as belonging to this one.
     */
    fun clearScale() {
        prefs.edit().remove(KEY_UP_X).remove(KEY_UP_Y).remove(KEY_IN_PER_PX).apply()
    }

    fun loadCenterOrNull(): Pair<Float, Float>? {
        val x = prefs.getFloat(KEY_CENTER_X, Float.NaN)
        val y = prefs.getFloat(KEY_CENTER_Y, Float.NaN)
        return if (x.isFinite() && y.isFinite()) x to y else null
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

    /**
     * Operator's target circle, in overlay/view space, so it is back where they left it
     * on the next launch instead of requiring a fresh tap before anything is detected.
     */
    fun saveTarget(x: Float, y: Float, radiusPx: Float) {
        prefs.edit()
            .putFloat(KEY_TARGET_X, x)
            .putFloat(KEY_TARGET_Y, y)
            .putFloat(KEY_TARGET_R, radiusPx)
            .apply()
    }

    /** @return (x, y, radiusPx) in view space, or null if no target was ever placed. */
    fun loadTargetOrNull(): Triple<Float, Float, Float>? {
        val x = prefs.getFloat(KEY_TARGET_X, Float.NaN)
        val y = prefs.getFloat(KEY_TARGET_Y, Float.NaN)
        val r = prefs.getFloat(KEY_TARGET_R, Float.NaN)
        return if (x.isFinite() && y.isFinite() && r.isFinite() && r > 0f) Triple(x, y, r) else null
    }

    /** Operator's target circle in camera image space, tagged with the source it was placed on. */
    data class ImageTarget(val x: Float, val y: Float, val radiusPx: Float, val source: String?)

    /**
     * Image-space copy of the target circle. Unlike [saveTarget]'s view-space copy this
     * survives rotation and letterbox changes exactly, by re-projecting through the live
     * CoordinateMapper on the next frame (the same way calibration is restored).
     */
    fun saveImageTarget(x: Float, y: Float, radiusPx: Float, source: String) {
        prefs.edit()
            .putFloat(KEY_TARGET_IMG_X, x)
            .putFloat(KEY_TARGET_IMG_Y, y)
            .putFloat(KEY_TARGET_IMG_R, radiusPx)
            .putString(KEY_TARGET_IMG_SOURCE, source)
            .apply()
    }

    fun loadImageTargetOrNull(): ImageTarget? {
        val x = prefs.getFloat(KEY_TARGET_IMG_X, Float.NaN)
        val y = prefs.getFloat(KEY_TARGET_IMG_Y, Float.NaN)
        val r = prefs.getFloat(KEY_TARGET_IMG_R, Float.NaN)
        if (!x.isFinite() || !y.isFinite() || !r.isFinite() || r <= 0f) return null
        return ImageTarget(x, y, r, prefs.getString(KEY_TARGET_IMG_SOURCE, null))
    }

    /**
     * Camera source (a CameraSourceType name) the stored center/scale were captured on.
     * Calibration lives in that camera's image space and means nothing on another one.
     */
    fun saveSource(source: String) {
        prefs.edit().putString(KEY_SOURCE, source).apply()
    }

    fun loadSourceOrNull(): String? = prefs.getString(KEY_SOURCE, null)

    /** Last camera source the operator selected, so the next launch starts there. */
    fun saveLastSource(source: String) {
        prefs.edit().putString(KEY_LAST_SOURCE, source).apply()
    }

    fun loadLastSourceOrNull(): String? = prefs.getString(KEY_LAST_SOURCE, null)

    private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()

    companion object {
        private const val PREFS_NAME = "axisight_calibration"
        private const val KEY_SOURCE = "source"
        private const val KEY_LAST_SOURCE = "last_source"
        private const val KEY_CENTER_X = "center_x"
        private const val KEY_CENTER_Y = "center_y"
        private const val KEY_UP_X = "up_x"
        private const val KEY_UP_Y = "up_y"
        private const val KEY_IN_PER_PX = "in_per_px"
        private const val KEY_TARGET_X = "target_x"
        private const val KEY_TARGET_Y = "target_y"
        private const val KEY_TARGET_R = "target_r"
        private const val KEY_TARGET_IMG_X = "target_img_x"
        private const val KEY_TARGET_IMG_Y = "target_img_y"
        private const val KEY_TARGET_IMG_R = "target_img_r"
        private const val KEY_TARGET_IMG_SOURCE = "target_img_source"
    }
}
