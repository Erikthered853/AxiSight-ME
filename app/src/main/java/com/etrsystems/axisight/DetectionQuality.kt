package com.etrsystems.axisight

/**
 * Per-detection quality metrics surfaced to the operator readout.
 * Produced by every camera source alongside the detected tool point.
 */
data class DetectionQuality(
    val circularity: Double,
    val contrastRatio: Double,
    val sharpness: Double
)
