package com.etrsystems.axisight

/**
 * Exponential moving average (EMA) filter for detection results.
 *
 * Smooths frame-to-frame jitter in the detected tool-center position
 * without adding a fixed delay.
 *
 * Formula:  smoothed = alpha * raw + (1 - alpha) * previous
 *
 * alpha = 1.0  → no smoothing (raw position each frame)
 * alpha = 0.3  → strong smoothing, ~3-frame effective lag at 30 fps
 *
 * [reset] should be called whenever tracking is stopped or the camera source changes.
 */
class DetectionFilter(private val alpha: Float = 0.35f) {

    private var smoothX = 0f
    private var smoothY = 0f
    private var initialized = false

    @Synchronized
    fun filter(result: DetectionResult): DetectionResult {
        if (result !is DetectionResult.Success) {
            // On failure, keep existing smooth state but pass the failure through.
            return result
        }
        if (!initialized) {
            smoothX = result.x
            smoothY = result.y
            initialized = true
            return result
        }
        val a = alpha.coerceIn(0f, 1f)
        smoothX = a * result.x + (1f - a) * smoothX
        smoothY = a * result.y + (1f - a) * smoothY
        return result.copy(x = smoothX, y = smoothY)
    }

    @Synchronized
    fun reset() {
        initialized = false
    }
}
