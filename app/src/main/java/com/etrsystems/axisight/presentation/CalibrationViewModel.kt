package com.etrsystems.axisight.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.etrsystems.axisight.CalibrationData
import com.etrsystems.axisight.CalibrationStore
import com.etrsystems.axisight.CoordinateMapper
import com.etrsystems.axisight.R
import com.etrsystems.axisight.domain.CalibrationQualityEvaluator
import com.etrsystems.axisight.domain.RotationFitEvaluator
import com.etrsystems.axisight.domain.RotationFitQuality
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * State machine for the calibration wizard (center / scale / rotation),
 * extracted from MainActivity so it survives configuration changes and can be
 * unit tested. All tap and sample coordinates are view-space; conversion to
 * image space (via [coordinateMapper]) happens only at persistence time,
 * matching the original flow.
 *
 * The [coordinateMapper] is owned here (rather than by the activity) so a
 * recreated activity keeps mapping through the same instance the persisted
 * wizard state was built against; camera sources refresh it on every frame.
 *
 * [activeSourceProvider] names the camera source currently running (a
 * CameraSourceType name) so a center captured on one camera never inherits the
 * scale measured on another; null means "unknown", which keeps legacy behaviour.
 */
class CalibrationViewModel(
    private val calibrationStore: CalibrationStore,
    val coordinateMapper: CoordinateMapper = CoordinateMapper(),
    private val activeSourceProvider: () -> String? = { null }
) : ViewModel() {

    enum class Step { NONE, CENTER, UP, SCALE_P1, SCALE_P2 }

    data class UiState(
        val step: Step = Step.NONE,
        /** true = Set Center only; false = scale+direction wizard. */
        val isCenterOnlyMode: Boolean = false,
        val isRotationMode: Boolean = false,
        val isRotationCapturing: Boolean = false,
        val isAutoCapturingCenter: Boolean = false,
        val center: Pair<Float, Float>? = null,
        val upPoint: Pair<Float, Float>? = null,
        val scaleP1: Pair<Float, Float>? = null,
        val scaleP2: Pair<Float, Float>? = null,
        val pendingTap: Pair<Float, Float>? = null,
        val rotationSampleCount: Int = 0,
        val rotationCoveragePercent: Int = 0,
        val rotationFitQuality: RotationFitQuality? = null,
        val autoSampleCount: Int = 0,
        val knownInches: Double = DEFAULT_KNOWN_INCHES
    )

    sealed interface Effect {
        /** One-shot user feedback message. */
        data class Message(@param:StringRes val resId: Int, val isLong: Boolean = false) : Effect

        /** Center-only calibration persisted; view-space coords for the overlay crosshair. */
        data class CenterSaved(
            val viewX: Float,
            val viewY: Float,
            val updatedData: CalibrationData?
        ) : Effect

        /** Full wizard completed and persisted. */
        data class CalibrationCompleted(
            val data: CalibrationData,
            val viewCenterX: Float,
            val viewCenterY: Float,
            val viewInchesPerPixel: Double
        ) : Effect
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = _effects.receiveAsFlow()

    // Capture accumulators; only derived counts are exposed through UiState.
    private val rotationSamples = mutableListOf<Pair<Float, Float>>()
    private val rotationCoverageBuckets = BooleanArray(RotationFitEvaluator.COVERAGE_BUCKETS)
    private var rotationSumX = 0.0
    private var rotationSumY = 0.0
    private val autoSamples = mutableListOf<Pair<Float, Float>>()

    fun startCenterCalibration() {
        clearCaptureBuffers()
        _state.update {
            UiState(step = Step.CENTER, isCenterOnlyMode = true, knownInches = it.knownInches)
        }
    }

    fun startScaleCalibration() {
        // Require a center to be set first.
        val storedCenter = calibrationStore.load()?.let { it.centerX to it.centerY }
            ?: calibrationStore.loadCenterOrNull()
        if (storedCenter == null) {
            sendEffect(Effect.Message(R.string.cal_scale_needs_center, isLong = true))
            return
        }
        // The stored center is in image space, but the wizard works in view
        // space throughout (taps, quality gating, overlay markers) and
        // finalizeCalibration converts everything back to image space. Convert
        // on the way in; before the first frame the mapper is identity, so
        // this is safe even if no camera is running yet.
        val center = coordinateMapper.imageToView(storedCenter.first, storedCenter.second)
        clearCaptureBuffers()
        _state.update {
            UiState(step = Step.UP, center = center, knownInches = it.knownInches)
        }
    }

    fun startRotationCalibration() {
        clearCaptureBuffers()
        _state.update {
            UiState(
                step = Step.CENTER,
                isCenterOnlyMode = true,
                isRotationMode = true,
                knownInches = it.knownInches
            )
        }
    }

    /** @return true if the tap was consumed by an active wizard step. */
    fun handleTap(x: Float, y: Float): Boolean {
        if (_state.value.step == Step.NONE) return false
        _state.update { it.copy(pendingTap = x to y) }
        return true
    }

    fun confirmStep(typedKnownInches: Double?) {
        val s = _state.value
        if (s.step == Step.NONE) return
        val tap = s.pendingTap ?: return
        _state.update { it.copy(pendingTap = null) }

        when (s.step) {
            Step.NONE -> Unit
            Step.CENTER -> {
                if (s.isCenterOnlyMode) {
                    saveCenterOnly(tap)
                    return
                }
                _state.update { it.copy(center = tap, step = Step.UP) }
            }
            Step.UP -> {
                val center = s.center
                if (center == null) {
                    _state.update { it.copy(step = Step.CENTER) }
                    sendEffect(Effect.Message(R.string.calibration_failed))
                    return
                }
                val upDist = hypot(
                    (tap.first - center.first).toDouble(),
                    (tap.second - center.second).toDouble()
                )
                if (upDist < CalibrationQualityEvaluator.MIN_UP_DISTANCE_PX) {
                    _state.update { it.copy(pendingTap = tap) }
                    sendEffect(Effect.Message(R.string.calibration_quality_fail))
                    return
                }
                _state.update { it.copy(upPoint = tap, step = Step.SCALE_P1) }
            }
            Step.SCALE_P1 -> _state.update { it.copy(scaleP1 = tap, step = Step.SCALE_P2) }
            Step.SCALE_P2 -> {
                _state.update { it.copy(scaleP2 = tap) }
                finalizeCalibration(typedKnownInches)
            }
        }
    }

    fun retryStep() {
        val s = _state.value
        if (s.step == Step.NONE) return
        autoSamples.clear()
        when (s.step) {
            Step.CENTER -> {
                clearRotationBuffers()
                _state.update {
                    it.copy(
                        isAutoCapturingCenter = false,
                        autoSampleCount = 0,
                        pendingTap = null,
                        center = null,
                        isRotationCapturing = false,
                        rotationSampleCount = 0,
                        rotationCoveragePercent = 0,
                        rotationFitQuality = null
                    )
                }
            }
            Step.UP -> _state.update { it.copy(isAutoCapturingCenter = false, autoSampleCount = 0, pendingTap = null, upPoint = null) }
            Step.SCALE_P1 -> _state.update { it.copy(isAutoCapturingCenter = false, autoSampleCount = 0, pendingTap = null, scaleP1 = null) }
            Step.SCALE_P2 -> _state.update { it.copy(isAutoCapturingCenter = false, autoSampleCount = 0, pendingTap = null, scaleP2 = null) }
            Step.NONE -> Unit
        }
    }

    fun cancelWizard() {
        clearCaptureBuffers()
        _state.update { UiState(knownInches = it.knownInches) }
    }

    fun startAutoCapture(hasLiveToolPoint: Boolean) {
        if (!hasLiveToolPoint) {
            sendEffect(Effect.Message(R.string.calibration_auto_no_tool))
            return
        }
        autoSamples.clear()
        _state.update { it.copy(isAutoCapturingCenter = true, autoSampleCount = 0) }
    }

    fun toggleRotationCapture() {
        if (_state.value.isRotationCapturing) finishRotationCapture() else startRotationCapture()
    }

    /**
     * Feeds a live view-space detection into an active rotation or auto-center
     * capture. @return true if the point was consumed (caller should skip its
     * normal readout updates for this frame).
     */
    fun onToolPoint(x: Float, y: Float): Boolean {
        val s = _state.value
        if (s.isRotationCapturing) {
            rotationSamples.add(x to y)
            rotationSumX += x
            rotationSumY += y
            if (rotationSamples.size >= 3) {
                val meanX = rotationSumX / rotationSamples.size
                val meanY = rotationSumY / rotationSamples.size
                rotationCoverageBuckets[RotationFitEvaluator.bucketIndex(x, y, meanX, meanY)] = true
            }
            val coveragePct = (rotationCoverageBuckets.count { it }.toDouble() /
                RotationFitEvaluator.COVERAGE_BUCKETS * 100).roundToInt()
            _state.update {
                it.copy(rotationSampleCount = rotationSamples.size, rotationCoveragePercent = coveragePct)
            }
            if (rotationSamples.size >= RotationFitEvaluator.MAX_SAMPLES) finishRotationCapture()
            return true
        }
        if (s.isAutoCapturingCenter) {
            autoSamples.add(x to y)
            _state.update { it.copy(autoSampleCount = autoSamples.size) }
            if (autoSamples.size >= AUTO_SAMPLE_TARGET) finishAutoCapture()
            return true
        }
        return false
    }

    fun onKnownInchesChanged(value: Double?) {
        _state.update { it.copy(knownInches = value ?: DEFAULT_KNOWN_INCHES) }
    }

    private fun startRotationCapture() {
        clearRotationBuffers()
        _state.update {
            it.copy(
                isRotationCapturing = true,
                rotationSampleCount = 0,
                rotationCoveragePercent = 0,
                rotationFitQuality = null,
                pendingTap = null
            )
        }
    }

    private fun finishRotationCapture() {
        val quality = RotationFitEvaluator.evaluate(rotationSamples.toList())
        val pendingTap = if (quality.acceptable) {
            quality.centerX to quality.centerY
        } else {
            sendEffect(Effect.Message(R.string.calibration_rotation_quality_fail, isLong = true))
            null
        }
        _state.update {
            it.copy(isRotationCapturing = false, rotationFitQuality = quality, pendingTap = pendingTap)
        }
    }

    private fun finishAutoCapture() {
        val avgX = autoSamples.map { it.first }.average().toFloat()
        val avgY = autoSamples.map { it.second }.average().toFloat()
        autoSamples.clear()
        _state.update {
            it.copy(isAutoCapturingCenter = false, pendingTap = avgX to avgY)
        }
    }

    /**
     * Saves a center-only calibration: crosshairs move, scale/direction stay
     * unchanged. Persisted in image space (via [coordinateMapper]) so it
     * survives resolution/orientation changes; the overlay crosshair still uses
     * the original view-space tap since it draws on screen.
     */
    private fun saveCenterOnly(tap: Pair<Float, Float>) {
        val (imgX, imgY) = coordinateMapper.viewToImage(tap.first, tap.second)
        calibrationStore.saveCenter(imgX, imgY)
        val storedSource = calibrationStore.loadSourceOrNull()
        val activeSource = activeSourceProvider()
        val scaleAppliesHere = storedSource == null || activeSource == null || storedSource == activeSource
        val updated = if (scaleAppliesHere) {
            calibrationStore.load()?.copy(centerX = imgX, centerY = imgY)
        } else {
            // The stored up-vector/scale were measured on another camera, in that camera's
            // image space. Keeping them would let the host re-tag them as this source's
            // calibration and report inch offsets with the wrong scale and direction.
            calibrationStore.clearScale()
            null
        }
        if (updated != null) {
            calibrationStore.save(updated)
        }
        clearCaptureBuffers()
        _state.update { UiState(knownInches = it.knownInches) }
        sendEffect(Effect.CenterSaved(tap.first, tap.second, updated))
        sendEffect(Effect.Message(R.string.cal_center_saved))
    }

    private fun finalizeCalibration(typedKnownInches: Double?) {
        val s = _state.value
        val center = s.center
        val upPoint = s.upPoint
        val scaleP1 = s.scaleP1
        val scaleP2 = s.scaleP2
        if (center == null || upPoint == null || scaleP1 == null || scaleP2 == null) {
            sendEffect(Effect.Message(R.string.calibration_failed))
            return
        }

        var knownInches = s.knownInches
        if (typedKnownInches != null && typedKnownInches > 0.0) {
            knownInches = typedKnownInches
            _state.update { it.copy(knownInches = knownInches) }
        }
        if (knownInches <= 0.0) {
            sendEffect(Effect.Message(R.string.calibration_failed))
            return
        }

        val quality = CalibrationQualityEvaluator.evaluate(center, upPoint, scaleP1, scaleP2, knownInches)
        if (!quality.acceptable) {
            sendEffect(Effect.Message(R.string.calibration_quality_fail, isLong = true))
            return
        }

        // Persist in image space (via CoordinateMapper) so calibration survives
        // resolution/orientation changes; quality gating above stays in view
        // space since it models tap-finger precision.
        val (imgCenterX, imgCenterY) = coordinateMapper.viewToImage(center.first, center.second)
        val (imgUpX, imgUpY) = coordinateMapper.viewToImage(upPoint.first, upPoint.second)
        val (imgScaleP1X, imgScaleP1Y) = coordinateMapper.viewToImage(scaleP1.first, scaleP1.second)
        val (imgScaleP2X, imgScaleP2Y) = coordinateMapper.viewToImage(scaleP2.first, scaleP2.second)
        val imageScaleDistancePx = hypot(
            (imgScaleP2X - imgScaleP1X).toDouble(),
            (imgScaleP2Y - imgScaleP1Y).toDouble()
        ).coerceAtLeast(1.0)
        val imageInchesPerPixel = knownInches / imageScaleDistancePx

        val data = CalibrationData.fromCenterUpAndScale(
            centerX = imgCenterX,
            centerY = imgCenterY,
            upPointX = imgUpX,
            upPointY = imgUpY,
            inchesPerPixel = imageInchesPerPixel
        )
        if (data == null) {
            sendEffect(Effect.Message(R.string.calibration_failed))
            return
        }

        calibrationStore.save(data)
        _state.update { it.copy(step = Step.NONE, pendingTap = null) }
        sendEffect(
            Effect.CalibrationCompleted(
                data = data,
                viewCenterX = center.first,
                viewCenterY = center.second,
                viewInchesPerPixel = quality.inchesPerPixel
            )
        )
        sendEffect(Effect.Message(R.string.calibration_complete))
    }

    private fun clearCaptureBuffers() {
        autoSamples.clear()
        clearRotationBuffers()
    }

    private fun clearRotationBuffers() {
        rotationSamples.clear()
        rotationCoverageBuckets.fill(false)
        rotationSumX = 0.0
        rotationSumY = 0.0
    }

    private fun sendEffect(effect: Effect) {
        _effects.trySend(effect)
    }

    class Factory(
        private val calibrationStore: CalibrationStore,
        private val activeSourceProvider: () -> String? = { null }
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CalibrationViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return CalibrationViewModel(calibrationStore, activeSourceProvider = activeSourceProvider) as T
        }
    }

    companion object {
        const val DEFAULT_KNOWN_INCHES = 0.1181 // Standard 3mm reference (0.015" bore use-case)
        const val AUTO_SAMPLE_TARGET = 30
    }
}
