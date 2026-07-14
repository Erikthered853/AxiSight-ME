package com.etrsystems.axisight.presentation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.etrsystems.axisight.CalibrationStore
import com.etrsystems.axisight.R
import com.etrsystems.axisight.presentation.CalibrationViewModel.Effect
import com.etrsystems.axisight.presentation.CalibrationViewModel.Step
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application so Robolectric does not boot AxisightApp (which needs Firebase).
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CalibrationViewModelTest {

    private lateinit var store: CalibrationStore
    private lateinit var viewModel: CalibrationViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("axisight_calibration", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = CalibrationStore(context)
        viewModel = CalibrationViewModel(store)
    }

    /** Drains all currently buffered one-shot effects. */
    private fun drainEffects(): List<Effect> = runBlocking {
        val drained = mutableListOf<Effect>()
        while (true) {
            val effect = withTimeoutOrNull(50) { viewModel.effects.first() } ?: break
            drained.add(effect)
        }
        drained
    }

    @Test
    fun `center only flow saves center and resets wizard`() {
        viewModel.startCenterCalibration()
        assertEquals(Step.CENTER, viewModel.state.value.step)
        assertTrue(viewModel.state.value.isCenterOnlyMode)

        assertTrue(viewModel.handleTap(120f, 340f))
        assertEquals(120f to 340f, viewModel.state.value.pendingTap)

        viewModel.confirmStep(typedKnownInches = null)

        assertEquals(Step.NONE, viewModel.state.value.step)
        assertEquals(120f to 340f, store.loadCenterOrNull())

        val effects = drainEffects()
        val centerSaved = effects.filterIsInstance<Effect.CenterSaved>().single()
        assertEquals(120f, centerSaved.viewX, 0f)
        assertEquals(340f, centerSaved.viewY, 0f)
        assertNull(centerSaved.updatedData) // no full calibration existed yet
        assertTrue(effects.filterIsInstance<Effect.Message>().any { it.resId == R.string.cal_center_saved })
    }

    @Test
    fun `scale wizard requires a saved center`() {
        viewModel.startScaleCalibration()
        assertEquals(Step.NONE, viewModel.state.value.step)
        val message = drainEffects().filterIsInstance<Effect.Message>().single()
        assertEquals(R.string.cal_scale_needs_center, message.resId)
    }

    @Test
    fun `full scale wizard completes and persists calibration`() {
        store.saveCenter(300f, 300f)

        viewModel.startScaleCalibration()
        assertEquals(Step.UP, viewModel.state.value.step)
        assertEquals(300f to 300f, viewModel.state.value.center)

        viewModel.handleTap(300f, 100f) // 200 px up
        viewModel.confirmStep(null)
        assertEquals(Step.SCALE_P1, viewModel.state.value.step)

        viewModel.handleTap(100f, 400f)
        viewModel.confirmStep(null)
        assertEquals(Step.SCALE_P2, viewModel.state.value.step)

        viewModel.handleTap(400f, 400f) // 300 px scale
        viewModel.confirmStep(null)

        assertEquals(Step.NONE, viewModel.state.value.step)
        val effects = drainEffects()
        val completed = effects.filterIsInstance<Effect.CalibrationCompleted>().single()
        assertEquals(0.1181 / 300.0, completed.viewInchesPerPixel, 1e-9)
        assertEquals(300f, completed.viewCenterX, 0f)
        assertTrue(effects.filterIsInstance<Effect.Message>().any { it.resId == R.string.calibration_complete })

        val persisted = store.load()
        assertNotNull(persisted)
        assertEquals(300f, persisted?.centerX ?: 0f, 1e-3f)
        assertEquals(300f, persisted?.centerY ?: 0f, 1e-3f)
    }

    @Test
    fun `up tap too close to center is rejected and kept pending`() {
        store.saveCenter(300f, 300f)
        viewModel.startScaleCalibration()

        viewModel.handleTap(300f, 290f) // 10 px < 40 px minimum
        viewModel.confirmStep(null)

        assertEquals(Step.UP, viewModel.state.value.step)
        assertEquals(300f to 290f, viewModel.state.value.pendingTap)
        val message = drainEffects().filterIsInstance<Effect.Message>().single()
        assertEquals(R.string.calibration_quality_fail, message.resId)
    }

    @Test
    fun `rotation calibration keeps rotation mode active`() {
        viewModel.startRotationCalibration()
        val s = viewModel.state.value
        assertEquals(Step.CENTER, s.step)
        assertTrue(s.isRotationMode)
        assertTrue(s.isCenterOnlyMode)
    }

    @Test
    fun `rotation capture over a full circle produces an acceptable pending center`() {
        viewModel.startRotationCalibration()
        viewModel.toggleRotationCapture()
        assertTrue(viewModel.state.value.isRotationCapturing)

        repeat(60) { i ->
            val angle = Math.toRadians(360.0 * i / 60)
            val x = 320f + 100f * cos(angle).toFloat()
            val y = 240f + 100f * sin(angle).toFloat()
            assertTrue(viewModel.onToolPoint(x, y))
        }
        assertEquals(60, viewModel.state.value.rotationSampleCount)

        viewModel.toggleRotationCapture()
        val s = viewModel.state.value
        assertFalse(s.isRotationCapturing)
        assertTrue(s.rotationFitQuality?.acceptable == true)
        assertEquals(320f, s.pendingTap?.first ?: 0f, 0.5f)
        assertEquals(240f, s.pendingTap?.second ?: 0f, 0.5f)
    }

    @Test
    fun `rotation capture with too few samples fails quality gate`() {
        viewModel.startRotationCalibration()
        viewModel.toggleRotationCapture()
        repeat(10) { viewModel.onToolPoint(100f + it, 100f) }
        viewModel.toggleRotationCapture()

        val s = viewModel.state.value
        assertNull(s.pendingTap)
        assertFalse(s.rotationFitQuality?.acceptable == true)
        val message = drainEffects().filterIsInstance<Effect.Message>().single()
        assertEquals(R.string.calibration_rotation_quality_fail, message.resId)
    }

    @Test
    fun `auto capture averages samples into pending tap`() {
        viewModel.startCenterCalibration()
        viewModel.startAutoCapture(hasLiveToolPoint = true)
        assertTrue(viewModel.state.value.isAutoCapturingCenter)

        repeat(CalibrationViewModel.AUTO_SAMPLE_TARGET) {
            assertTrue(viewModel.onToolPoint(50f, 60f))
        }

        val s = viewModel.state.value
        assertFalse(s.isAutoCapturingCenter)
        assertEquals(50f to 60f, s.pendingTap)
    }

    @Test
    fun `auto capture without live tool point is refused`() {
        viewModel.startCenterCalibration()
        viewModel.startAutoCapture(hasLiveToolPoint = false)
        assertFalse(viewModel.state.value.isAutoCapturingCenter)
        val message = drainEffects().filterIsInstance<Effect.Message>().single()
        assertEquals(R.string.calibration_auto_no_tool, message.resId)
    }

    @Test
    fun `retry clears only the current step`() {
        store.saveCenter(300f, 300f)
        viewModel.startScaleCalibration()
        viewModel.handleTap(300f, 100f)
        viewModel.confirmStep(null)
        viewModel.handleTap(100f, 400f)

        viewModel.retryStep()
        val s = viewModel.state.value
        assertEquals(Step.SCALE_P1, s.step)
        assertNull(s.pendingTap)
        assertNull(s.scaleP1)
        assertNotNull(s.upPoint) // previous step's point survives
    }

    @Test
    fun `cancel resets wizard but keeps known inches`() {
        viewModel.onKnownInchesChanged(0.25)
        viewModel.startCenterCalibration()
        viewModel.handleTap(10f, 10f)

        viewModel.cancelWizard()
        val s = viewModel.state.value
        assertEquals(Step.NONE, s.step)
        assertNull(s.pendingTap)
        assertEquals(0.25, s.knownInches, 1e-12)
    }

    @Test
    fun `taps are ignored when no wizard is active`() {
        assertFalse(viewModel.handleTap(10f, 10f))
        assertFalse(viewModel.onToolPoint(10f, 10f))
        assertNull(viewModel.state.value.pendingTap)
    }
}
