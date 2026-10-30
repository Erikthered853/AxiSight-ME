package com.etrsystems.axisight

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalibrationStoreTest {

    private lateinit var store: CalibrationStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("axisight_calibration", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = CalibrationStore(context)
    }

    @Test
    fun `target circle round-trips and is absent until placed`() {
        assertNull(store.loadTargetOrNull())

        store.saveTarget(719f, 1560f, 216f)

        val (x, y, r) = store.loadTargetOrNull()!!
        assertEquals(719f, x, 0f)
        assertEquals(1560f, y, 0f)
        assertEquals(216f, r, 0f)
    }

    @Test
    fun `target with non-positive radius is treated as absent`() {
        store.saveTarget(10f, 10f, 0f)
        assertNull(store.loadTargetOrNull())
    }

    @Test
    fun `calibration source and last-used source round-trip independently`() {
        assertNull(store.loadSourceOrNull())
        assertNull(store.loadLastSourceOrNull())

        store.saveSource("USB")
        store.saveLastSource("INTERNAL")

        assertEquals("USB", store.loadSourceOrNull())
        assertEquals("INTERNAL", store.loadLastSourceOrNull())
    }

    @Test
    fun `saving a target does not disturb a stored center`() {
        store.saveCenter(320f, 240f)
        store.saveTarget(1f, 2f, 3f)
        assertEquals(320f to 240f, store.loadCenterOrNull())
        assertNotNull(store.loadTargetOrNull())
    }

    @Test
    fun `image-space target round-trips with its source and is independent of the view copy`() {
        assertNull(store.loadImageTargetOrNull())

        store.saveImageTarget(320f, 240f, 48f, "USB")
        store.saveTarget(1f, 2f, 3f)

        val img = store.loadImageTargetOrNull()!!
        assertEquals(320f, img.x, 0f)
        assertEquals(240f, img.y, 0f)
        assertEquals(48f, img.radiusPx, 0f)
        assertEquals("USB", img.source)
        assertEquals(Triple(1f, 2f, 3f), store.loadTargetOrNull())
    }

    @Test
    fun `image-space target with non-positive radius is treated as absent`() {
        store.saveImageTarget(10f, 10f, 0f, "USB")
        assertNull(store.loadImageTargetOrNull())
    }

    @Test
    fun `clearScale keeps the center but drops the full calibration`() {
        store.save(CalibrationData(centerX = 5f, centerY = 6f, upX = 0f, upY = -1f, inchesPerPixel = 0.002))
        assertNotNull(store.load())

        store.clearScale()

        assertNull(store.load())
        assertEquals(5f to 6f, store.loadCenterOrNull())
    }
}
