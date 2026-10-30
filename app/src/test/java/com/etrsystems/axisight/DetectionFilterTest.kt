package com.etrsystems.axisight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tracking-loss and recovery behaviour of [DetectionFilter].
 *
 * The jump gate protects against single-frame teleports, but it must not lock the
 * filter out forever when the tool genuinely moves while detection is lost (operator
 * repositions the camera, tool leaves the frame and comes back somewhere else).
 */
class DetectionFilterTest {

    private fun cfg(
        consecutiveRequired: Int = 3,
        maxJump: Float = 30f,
        lossFrames: Int = 5
    ) = DetectorConfig(
        consecutiveFramesRequired = consecutiveRequired,
        maxJumpPx = maxJump,
        trackingLossFrames = lossFrames,
        smoothingAlpha = 0.5f
    )

    private fun ok(x: Float, y: Float) = DetectionResult.Success(x, y, 100.0)
    private val miss = DetectionResult.Failure(FailureReason.NO_DARK_PIXELS, "miss")

    private fun DetectionFilter.settle(x: Float, y: Float, c: DetectorConfig, frames: Int = c.consecutiveFramesRequired) {
        repeat(frames) { filter(ok(x, y), c) }
    }

    @Test
    fun `consistent far detections re-acquire the tool at its new position`() {
        val filter = DetectionFilter()
        val c = cfg(consecutiveRequired = 3, maxJump = 30f)
        filter.settle(100f, 100f, c)
        assertTrue(filter.filter(ok(100f, 100f), c) is DetectionResult.Success)

        // Tool now consistently appears 200px away. First two frames are gated...
        val r1 = filter.filter(ok(300f, 100f), c)
        val r2 = filter.filter(ok(301f, 101f), c)
        assertEquals(FailureReason.JUMP_TOO_LARGE, (r1 as DetectionResult.Failure).reason)
        assertEquals(FailureReason.JUMP_TOO_LARGE, (r2 as DetectionResult.Failure).reason)

        // ...the third agreeing frame re-acquires, and the published position snaps there
        // (no EMA blend with the stale 100,100 location).
        val r3 = filter.filter(ok(300f, 100f), c)
        assertTrue("third agreeing frame should re-acquire, got $r3", r3 is DetectionResult.Success)
        r3 as DetectionResult.Success
        assertEquals(300f, r3.x, 1.5f)
        assertEquals(100f, r3.y, 1.5f)

        // And tracking continues normally from the new position.
        val r4 = filter.filter(ok(302f, 100f), c)
        assertTrue(r4 is DetectionResult.Success)
    }

    @Test
    fun `scattered far detections do not re-acquire`() {
        val filter = DetectionFilter()
        val c = cfg(consecutiveRequired = 3, maxJump = 30f)
        filter.settle(100f, 100f, c)

        // Three far detections that disagree with each other: noise, not a new position.
        val r1 = filter.filter(ok(300f, 100f), c)
        val r2 = filter.filter(ok(100f, 400f), c)
        val r3 = filter.filter(ok(500f, 500f), c)
        for (r in listOf(r1, r2, r3)) {
            assertTrue("scattered teleports must stay rejected, got $r", r is DetectionResult.Failure)
            assertEquals(FailureReason.JUMP_TOO_LARGE, (r as DetectionResult.Failure).reason)
        }
        // The original position is still the reference: detections there are accepted
        // (a rejected jump resets the confirmation streak, so they re-confirm first).
        val r4 = filter.filter(ok(101f, 100f), c)
        assertEquals(FailureReason.NOT_CONFIRMED, (r4 as DetectionResult.Failure).reason)
        filter.filter(ok(101f, 100f), c)
        assertTrue(filter.filter(ok(101f, 100f), c) is DetectionResult.Success)
    }

    @Test
    fun `re-acquisition needs two agreeing frames even when confirmation is set to one`() {
        val filter = DetectionFilter()
        val c = cfg(consecutiveRequired = 1, maxJump = 30f)
        filter.settle(100f, 100f, c)

        val r1 = filter.filter(ok(300f, 100f), c)
        assertEquals(FailureReason.JUMP_TOO_LARGE, (r1 as DetectionResult.Failure).reason)
        val r2 = filter.filter(ok(300f, 100f), c)
        assertTrue("second agreeing frame should re-acquire, got $r2", r2 is DetectionResult.Success)
        assertEquals(300f, (r2 as DetectionResult.Success).x, 0.01f)
    }

    @Test
    fun `after a long loss the jump gate releases and tracking restarts anywhere`() {
        val filter = DetectionFilter()
        val c = cfg(consecutiveRequired = 2, maxJump = 30f, lossFrames = 5)
        filter.settle(100f, 100f, c)

        repeat(5) { filter.filter(miss, c) } // tool out of view for trackingLossFrames frames

        // Reappears far away: must NOT be jump-gated against the stale position, only confirmed.
        val r1 = filter.filter(ok(400f, 400f), c)
        assertEquals(FailureReason.NOT_CONFIRMED, (r1 as DetectionResult.Failure).reason)
        val r2 = filter.filter(ok(400f, 400f), c)
        assertTrue("should publish after confirmation, got $r2", r2 is DetectionResult.Success)
        r2 as DetectionResult.Success
        assertEquals(400f, r2.x, 0.01f)
        assertEquals(400f, r2.y, 0.01f)
    }

    @Test
    fun `a short loss keeps the jump gate armed`() {
        val filter = DetectionFilter()
        val c = cfg(consecutiveRequired = 2, maxJump = 30f, lossFrames = 5)
        filter.settle(100f, 100f, c)

        repeat(2) { filter.filter(miss, c) } // brief occlusion, shorter than trackingLossFrames

        val r = filter.filter(ok(400f, 400f), c)
        assertEquals(FailureReason.JUMP_TOO_LARGE, (r as DetectionResult.Failure).reason)
    }

    @Test
    fun `reset clears re-acquisition state`() {
        val filter = DetectionFilter()
        val c = cfg(consecutiveRequired = 2, maxJump = 30f)
        filter.settle(100f, 100f, c)
        filter.filter(ok(300f, 300f), c) // one candidate frame at a far position
        filter.reset()

        // Fresh start: first detection is simply confirmed, not treated as candidate #2.
        val r1 = filter.filter(ok(300f, 300f), c)
        assertEquals(FailureReason.NOT_CONFIRMED, (r1 as DetectionResult.Failure).reason)
        assertTrue(filter.filter(ok(300f, 300f), c) is DetectionResult.Success)
    }
}
