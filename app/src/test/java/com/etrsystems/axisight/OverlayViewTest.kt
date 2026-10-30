package com.etrsystems.axisight

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Target-circle placement rules of [OverlayView]. The target lives in view space, so
 * the view itself must keep it meaningful when its size changes (device rotation,
 * window resize, preview container re-layout).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayViewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun overlay(width: Int, height: Int): OverlayView =
        OverlayView(context).also { it.layout(0, 0, width, height) }

    @Test
    fun `target is not drawn or reported until the operator places it`() {
        val view = overlay(1000, 500)
        assertFalse(view.targetInitialized)
        var notified = false
        view.onTargetChanged = { _, _, _ -> notified = true }
        view.layout(0, 0, 800, 400)
        assertFalse("size change must not invent a target", notified)
    }

    @Test
    fun `first placement picks a radius proportional to the view`() {
        val view = overlay(1000, 500)
        view.setTargetCenter(400f, 200f)
        assertTrue(view.targetInitialized)
        assertEquals(400f, view.targetX, 0f)
        assertEquals(200f, view.targetY, 0f)
        assertEquals(500 * 0.15f, view.targetRadiusPx, 0.01f)
    }

    @Test
    fun `target scales with the view instead of being clamped when the view resizes`() {
        val view = overlay(1000, 500)
        view.setTargetCenter(800f, 400f)
        view.setTargetRadius(100f)

        // Portrait rotation: width and height swap.
        view.layout(0, 0, 500, 1000)

        assertEquals(400f, view.targetX, 0.01f)
        assertEquals(800f, view.targetY, 0.01f)
        // Radius follows the shorter side (500 -> 500): unchanged here.
        assertEquals(100f, view.targetRadiusPx, 0.01f)

        // Growing the view scales everything up.
        view.layout(0, 0, 1000, 2000)
        assertEquals(800f, view.targetX, 0.01f)
        assertEquals(1600f, view.targetY, 0.01f)
        assertEquals(200f, view.targetRadiusPx, 0.01f)
    }

    @Test
    fun `resize notifies the listener with the rescaled target`() {
        val view = overlay(1000, 500)
        view.setTargetCenter(500f, 250f)
        view.setTargetRadius(50f)
        var last: Triple<Float, Float, Float>? = null
        view.onTargetChanged = { x, y, r -> last = Triple(x, y, r) }

        view.layout(0, 0, 2000, 1000)

        assertEquals(Triple(1000f, 500f, 100f), last)
    }

    @Test
    fun `target and radius are clamped to the view after a resize`() {
        val view = overlay(1000, 1000)
        view.setTargetCenter(990f, 990f)
        view.setTargetRadius(30f)

        // Shrink to a tiny view: the scaled position stays inside and the scaled radius
        // (3px) is lifted back to the 24px floor so the circle stays grabbable.
        view.layout(0, 0, 100, 100)

        assertEquals(99f, view.targetX, 0.01f)
        assertEquals(99f, view.targetY, 0.01f)
        assertEquals(24f, view.targetRadiusPx, 0.01f)

        // In a view smaller than twice the floor, the grabbable floor wins over the cap.
        view.layout(0, 0, 40, 40)
        assertEquals(24f, view.targetRadiusPx, 0.01f)
    }

    @Test
    fun `radius is clamped on direct set`() {
        val view = overlay(400, 300)
        view.setTargetCenter(200f, 150f)
        view.setTargetRadius(5f)
        assertEquals(24f, view.targetRadiusPx, 0f) // floor
        view.setTargetRadius(5000f)
        assertEquals(150f, view.targetRadiusPx, 0f) // half the shorter side
    }
}
