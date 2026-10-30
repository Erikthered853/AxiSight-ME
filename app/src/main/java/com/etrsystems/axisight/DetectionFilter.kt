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
 *   Two escape hatches stop the gate from locking tracking out permanently:
 *   - Re-acquisition: if [DetectorConfig.consecutiveFramesRequired] successive far
 *     detections agree with each other (within maxJumpPx), the tool has genuinely moved
 *     (or the camera was repositioned) and tracking snaps to the new position.
 *   - Loss timeout: after [DetectorConfig.trackingLossFrames] consecutive misses the
 *     tool is considered lost and the next detection is not jump-checked at all.
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

    // Consecutive raw failures since the last accepted detection.
    private var missCount = 0

    // Re-acquisition candidate: a far-away position seen on consecutive frames.
    private var candidateX = 0f
    private var candidateY = 0f
    private var candidateCount = 0

    /**
     * Filter one frame's detection result.
     *
     * @param result Raw result from [BlobDetector].
     * @param cfg    Current detector config — read for [DetectorConfig.maxJumpPx],
     *               [DetectorConfig.consecutiveFramesRequired],
     *               [DetectorConfig.trackingLossFrames] and
     *               [DetectorConfig.smoothingAlpha] (alpha constructor param is the base).
     */
    @Synchronized
    fun filter(result: DetectionResult, cfg: DetectorConfig = DetectorConfig()): DetectionResult {
        if (result !is DetectionResult.Success) {
            // Any single failure resets the consecutive streak and any re-acquisition
            // candidate. lastGood is kept so a briefly occluded tool is still jump-checked,
            // but a long loss disarms the gate: wherever the tool reappears is legitimate.
            consecutiveCount = 0
            candidateCount = 0
            missCount++
            if (hasLastGood && missCount >= cfg.trackingLossFrames.coerceAtLeast(1)) {
                hasLastGood = false
                hasPublished = false
            }
            return result
        }
        missCount = 0

        // ── Layer 1: Jump filter ────────────────────────────────────────────────
        if (hasLastGood) {
            val dist = hypot(result.x - lastGoodX, result.y - lastGoodY)
            if (dist > cfg.maxJumpPx) {
                consecutiveCount = 0
                // One frame can never prove a move (that is exactly the teleport the gate
                // exists to reject), so re-acquisition needs at least two agreeing frames
                // even when the operator has turned confirmation down to 1.
                val required = cfg.consecutiveFramesRequired.coerceAtLeast(MIN_REACQUIRE_FRAMES)
                val agreesWithCandidate = candidateCount > 0 &&
                    hypot(result.x - candidateX, result.y - candidateY) <= cfg.maxJumpPx
                if (agreesWithCandidate) {
                    candidateCount++
                } else {
                    candidateX = result.x
                    candidateY = result.y
                    candidateCount = 1
                }
                if (candidateCount < required) {
                    return DetectionResult.Failure(
                        FailureReason.JUMP_TOO_LARGE,
                        "Jump %.1fpx > %.1fpx — re-acquiring %d/%d".format(dist, cfg.maxJumpPx, candidateCount, required)
                    )
                }
                // The far position has been seen on `required` consecutive frames: the
                // tool really is there. Snap tracking to it (no EMA blend with the old spot).
                candidateCount = 0
                hasPublished = false
                consecutiveCount = required - 1
            } else {
                candidateCount = 0
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
        missCount = 0
        candidateX = 0f
        candidateY = 0f
        candidateCount = 0
    }

    private companion object {
        const val MIN_REACQUIRE_FRAMES = 2
    }
}
