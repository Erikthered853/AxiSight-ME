package com.etrsystems.axisight

import kotlin.math.*

/**
 * Three-layer temporal filter for detection results.
 *
 * Layer 1 — Jump filter
 *   If the raw detection jumps more than [DetectorConfig.maxJumpPx] from the last
 *   accepted position, the result is rejected and the consecutive streak is reset.
 *   This kills noise spikes and brief occlusions that produce implausible teleports.
 *
 * Layer 2 — Consecutive-frames gate
 *   Requires [DetectorConfig.consecutiveFramesRequired] successive successes (after jump
 *   filtering) before the first detection is published. A single failure resets the count.
 *   This kills single-frame glitches, partial occlusions, and startup transients.
 *
 * Layer 3 — Adaptive EMA + dead-band
 *   Once the gate is cleared, position is smoothed with an EMA whose alpha scales upward
 *   with velocity: slow-moving tool → heavy smoothing (low jitter); fast-moving tool →
 *   lighter smoothing (fast response). A sub-pixel dead-band prevents micro-jitter from
 *   dirtying the readout when the tool is stationary.
 *
 * [reset] must be called whenever the camera source changes or tracking is stopped.
 */
class DetectionFilter {

    private var smoothX = 0f
    private var smoothY = 0f
    private var lastGoodX = 0f
    private var lastGoodY = 0f
    private var consecutiveCount = 0
    private var hasPublished = false
    private var hasLastGood = false

    /** Filter one frame's detection result. */
    @Synchronized
    fun filter(result: DetectionResult, cfg: DetectorConfig = DEFAULT_CONFIG): DetectionResult {
        if (result !is DetectionResult.Success) {
            // Any single failure resets the consecutive streak.
            // Keep lastGood so a recovering signal still gets jump-checked.
            consecutiveCount = 0
            return result
        }

        // ── Layer 1: Jump filter (compare squared distances to avoid sqrt) ──────
        if (hasLastGood) {
            val dx = result.x - lastGoodX
            val dy = result.y - lastGoodY
            val distSq = dx * dx + dy * dy
            val maxJumpSq = cfg.maxJumpPx * cfg.maxJumpPx
            if (distSq > maxJumpSq) {
                consecutiveCount = 0
                return DetectionResult.Failure(
                    FailureReason.JUMP_TOO_LARGE,
                    "Jump %.1fpx > %.1fpx — streak reset".format(sqrt(distSq), cfg.maxJumpPx)
                )
            }
        }

        // ── Layer 2: Consecutive-frames gate ────────────────────────────────────
        consecutiveCount++
        lastGoodX = result.x
        lastGoodY = result.y
        hasLastGood = true

        if (consecutiveCount < cfg.consecutiveFramesRequired) {
            return DetectionResult.Failure(
                FailureReason.NOT_CONFIRMED,
                "Confirming: $consecutiveCount / ${cfg.consecutiveFramesRequired} frames"
            )
        }

        // ── Layer 3: Adaptive EMA + dead-band ──────────────────────────────────
        if (!hasPublished) {
            smoothX = result.x
            smoothY = result.y
            hasPublished = true
            return result.copy(x = smoothX, y = smoothY)
        }

        // Alpha scales upward with velocity so fast moves track quickly while
        // a stationary tool gets maximum smoothing.
        val dxV = result.x - smoothX
        val dyV = result.y - smoothY
        val velocity = hypot(dxV, dyV)
        val baseAlpha = cfg.smoothingAlpha.coerceIn(0.05f, 1f)
        val adaptAlpha = (baseAlpha + (velocity / 20f) * (1f - baseAlpha)).coerceIn(baseAlpha, 1f)

        val newX = adaptAlpha * result.x + (1f - adaptAlpha) * smoothX
        val newY = adaptAlpha * result.y + (1f - adaptAlpha) * smoothY

        // Dead-band: skip the update if sub-pixel (compare squared to avoid sqrt).
        val dxM = newX - smoothX
        val dyM = newY - smoothY
        if (dxM * dxM + dyM * dyM > 0.09f) {
            smoothX = newX
            smoothY = newY
        }

        return result.copy(x = smoothX, y = smoothY)
    }

    companion object {
        private val DEFAULT_CONFIG = DetectorConfig()
    }

    @Synchronized
    fun reset() {
        smoothX = 0f
        smoothY = 0f
        lastGoodX = 0f
        lastGoodY = 0f
        consecutiveCount = 0
        hasPublished = false
        hasLastGood = false
    }
}
