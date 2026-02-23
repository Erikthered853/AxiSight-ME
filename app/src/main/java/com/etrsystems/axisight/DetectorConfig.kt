package com.etrsystems.axisight

/**
 * Configuration parameters for dark dot detection algorithm.
 *
 * @param minAreaPx Minimum blob area in pixels (default: 200). Blobs smaller than this are rejected.
 * @param maxAreaPx Maximum blob area in pixels (default: 50000). Blobs larger than this are rejected.
 * @param minCircularity Minimum circularity score 0-1 (default: 0.5). Higher = more circular.
 *        Computed as sqrt(lambda2/lambda1) from eigenvalues of covariance matrix.
 * @param kStd Number of standard deviations below mean for threshold (default: 1.0).
 *        Higher = darker threshold, detects darker dots.
 * @param downscale Downscaling factor for YUV plane processing (default: 4).
 *        Speeds up detection at cost of precision.
 */
data class DetectorConfig(
    var minAreaPx: Int = 200,
    var maxAreaPx: Int = 50000,
    var minCircularity: Double = 0.5,
    var kStd: Double = 0.5,
    var downscale: Int = 4,
    var lockedThreshold: Int? = null
)
