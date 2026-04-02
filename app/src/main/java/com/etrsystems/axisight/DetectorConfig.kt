package com.etrsystems.axisight

/**
 * Configuration parameters for dark dot detection algorithm.
 *
 * @param minAreaPx                 Minimum blob area in pixels.
 * @param maxAreaPx                 Maximum blob area in pixels.
 * @param minCircularity            Minimum circularity [0..1]. sqrt(λ₂/λ₁) from weighted moment matrix.
 * @param kStd                      Threshold = mean − kStd × σ of target-region luminance.
 *                                  Higher = selects only very dark pixels.
 * @param downscale                 Downscaling factor for fast processing.
 * @param smoothingAlpha            Base EMA alpha for [DetectionFilter]. Adapts upward when moving.
 * @param consecutiveFramesRequired Frames of continuous success before first detection is published.
 *                                  Kills single-frame glitches and noise spikes.
 * @param maxJumpPx                 Max pixel displacement allowed between frames.
 *                                  Detections that jump farther than this reset the consecutive count.
 * @param minContrastRatio          (bgMean - boreMean) / bgMean must exceed this value.
 *                                  Guards against firing on uniform or low-contrast regions.
 */
data class DetectorConfig(
    val minAreaPx: Int = 20,
    val maxAreaPx: Int = 8000,
    val minCircularity: Double = 0.65,
    val kStd: Double = 1.5,
    val downscale: Int = 2,
    val smoothingAlpha: Float = 0.35f,
    val lockedThreshold: Int? = null,
    val targetCenterX: Float? = null,
    val targetCenterY: Float? = null,
    val targetRadiusPx: Float = 160f,
    val consecutiveFramesRequired: Int = 3,
    val maxJumpPx: Float = 60f,
    val minContrastRatio: Double = 0.12,
)
