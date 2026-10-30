package com.etrsystems.axisight.ui

import android.app.Application
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SafeAspectRatioTextureView] is the USB preview surface. Its measured size is what
 * the overlay's CoordinateMapper is fed (via the fragment's layout listener), so the
 * aspect-fit maths here directly determines overlay accuracy on the USB path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SafeAspectRatioTextureViewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    @Test
    fun `4 by 3 preview inside a square container is letterboxed to full width`() {
        val view = SafeAspectRatioTextureView(context)
        view.setAspectRatio(640, 480)
        view.measure(exactly(1000), exactly(1000))
        assertEquals(1000, view.measuredWidth)
        assertEquals(750, view.measuredHeight)
    }

    @Test
    fun `4 by 3 preview inside a tall portrait container is pillarboxed to full width`() {
        val view = SafeAspectRatioTextureView(context)
        view.setAspectRatio(640, 480)
        view.measure(exactly(1080), exactly(2000))
        assertEquals(1080, view.measuredWidth)
        assertEquals(810, view.measuredHeight)
    }

    @Test
    fun `4 by 3 preview inside a wide landscape container is limited by height`() {
        val view = SafeAspectRatioTextureView(context)
        view.setAspectRatio(640, 480)
        view.measure(exactly(2000), exactly(900))
        assertEquals(1200, view.measuredWidth)
        assertEquals(900, view.measuredHeight)
    }

    @Test
    fun `without an aspect ratio the view fills its container`() {
        val view = SafeAspectRatioTextureView(context)
        view.measure(exactly(777), exactly(333))
        assertEquals(777, view.measuredWidth)
        assertEquals(333, view.measuredHeight)
    }

    @Test
    fun `invalid aspect ratio is ignored`() {
        val view = SafeAspectRatioTextureView(context)
        view.setAspectRatio(640, 480)
        view.setAspectRatio(0, 480)
        view.setAspectRatio(640, -1)
        view.measure(exactly(1000), exactly(1000))
        assertEquals(750, view.measuredHeight)
    }

    @Test
    fun `surface size never reports zero before layout`() {
        val view = SafeAspectRatioTextureView(context)
        assertEquals(640, view.getSurfaceWidth())
        assertEquals(480, view.getSurfaceHeight())

        view.setAspectRatio(1280, 720)
        assertEquals(1280, view.getSurfaceWidth())
        assertEquals(720, view.getSurfaceHeight())
    }

    @Test
    fun `surface size reports the laid-out size once available`() {
        val view = SafeAspectRatioTextureView(context)
        view.setAspectRatio(640, 480)
        view.measure(exactly(1000), exactly(1000))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertEquals(1000, view.getSurfaceWidth())
        assertEquals(750, view.getSurfaceHeight())
    }
}
