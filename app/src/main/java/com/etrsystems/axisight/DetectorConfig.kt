package com.etrsystems.axisight

/**
 * Configuration parameters for dark dot detection algorithm.
 *
 * @param minAreaPx      Minimum blob area in pixels (default: 200).
 * @param maxAreaPx      Maximum blob area in pixels (default: 50000).
 * @param minCircularity Minimum circularity [0..1] (default: 0.5). sqrt(λ₂/λ₁) from moment matrix.
 * @param kStd           Threshold = mean − kStd × σ of target-region luminance.
 *                       Higher kStd → darker threshold → selects only very dark pixels.
 * @param downscale      Downscaling factor for fast processing (default: 4).
 * @param smoothingAlpha EMA alpha for [DetectionFilter] (default: 0.35). 1.0 = no smoothing.
 */
data class DetectorConfig(
    val minAreaPx: Int = 20,       // Small: 0.015" bore is tiny — allow small blobs
    val maxAreaPx: Int = 8000,     // Cap low to reject large artifacts outside bore
    val minCircularity: Double = 0.65, // Precision bore is very circular; reject elongated noise
    val kStd: Double = 1.5,        // Slightly tighter threshold for dark bore on bright background
    val downscale: Int = 2,        // Less downscaling for better resolution on tiny target
    val smoothingAlpha: Float = 0.35f,
    val lockedThreshold: Int? = null,
    val targetCenterX: Float? = null,
    val targetCenterY: Float? = null,
    val targetRadiusPx: Float = 160f
)
