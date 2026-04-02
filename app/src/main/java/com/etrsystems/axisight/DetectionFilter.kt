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
class DetectionFilter(private val alpha: Float = 0.35f) {

    private var smoothX = 0f
    private var smoothY = 0f
    private var lastGoodX = 0f
    private var lastGoodY = 0f
    private var consecutiveCount = 0
    private var hasPublished = false
    private var hasLastGood = false

    /**
     * Filter one frame's detection result.
     *
     * @param result Raw result from [BlobDetector].
     * @param cfg    Current detector config — read for [DetectorConfig.maxJumpPx],
     *               [DetectorConfig.consecutiveFramesRequired], and
     *               [DetectorConfig.smoothingAlpha] (alpha constructor param is the base).
     */
    @Synchronized
    fun filter(result: DetectionResult, cfg: DetectorConfig = DetectorConfig()): DetectionResult {
        if (result !is DetectionResult.Success) {
            // Any single failure resets the consecutive streak.
            // Keep lastGood so a recovering signal still gets jump-checked.
            consecutiveCount = 0
            return result
        }

        // ── Layer 1: Jump filter ────────────────────────────────────────────────
        if (hasLastGood) {
            val dx = result.x - lastGoodX
            val dy = result.y - lastGoodY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > cfg.maxJumpPx) {
                consecutiveCount = 0
                return DetectionResult.Failure(
                    FailureReason.JUMP_TOO_LARGE,
                    "Jump %.1fpx > %.1fpx — streak reset".format(dist, cfg.maxJumpPx)
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
        val velocity = sqrt((result.x - smoothX).pow(2) + (result.y - smoothY).pow(2))
        val baseAlpha = cfg.smoothingAlpha.coerceIn(0.05f, 1f)
        val adaptAlpha = (baseAlpha + (velocity / 20f) * (1f - baseAlpha)).coerceIn(baseAlpha, 1f)

        val newX = adaptAlpha * result.x + (1f - adaptAlpha) * smoothX
        val newY = adaptAlpha * result.y + (1f - adaptAlpha) * smoothY

        // Dead-band: skip the update if sub-pixel — prevents micro-jitter in readout.
        val moved = sqrt((newX - smoothX).pow(2) + (newY - smoothY).pow(2))
        if (moved > 0.3f) {
            smoothX = newX
            smoothY = newY
        }

        return result.copy(x = smoothX, y = smoothY)
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
