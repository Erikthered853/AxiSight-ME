package com.etrsystems.axisight

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.etrsystems.axisight.camera.CameraController
import com.etrsystems.axisight.camera.CameraSourceType
import com.etrsystems.axisight.camera.FrameAnalyzer
import com.etrsystems.axisight.camera.InternalCameraSource
import com.etrsystems.axisight.camera.UsbCameraController
import com.etrsystems.axisight.camera.WifiCameraSource
import com.etrsystems.axisight.databinding.ActivityMainBinding
import com.etrsystems.axisight.domain.CalibrationQualityEvaluator
import com.etrsystems.axisight.presentation.CalibrationViewModel
import com.etrsystems.axisight.ui.UvcFragment
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var camera: CameraController

    private val calibrationViewModel: CalibrationViewModel by viewModels {
        CalibrationViewModel.Factory(CalibrationStore(applicationContext))
    }

    private var simulate = false
    private var autoDetect = true
    private var inPerPx: Double? = null

    private val cfgRef = AtomicReference(DetectorConfig())
    private val cfg get() = cfgRef.get()
    private fun updateCfg(block: DetectorConfig.() -> DetectorConfig) { cfgRef.set(cfgRef.get().block()) }

    private var latestDetectionQuality: DetectionQuality? = null
    // The mapper lives in the ViewModel so wizard state and coordinate mapping
    // survive configuration changes together; camera frames refresh it anyway.
    private val coordinateMapper get() = calibrationViewModel.coordinateMapper
    private val detectionFilter = DetectionFilter()
    private val frameAnalyzer by lazy { FrameAnalyzer(coordinateMapper, detectionFilter) { cfg } }

    private var calibrationData: CalibrationData? = null
    private var latestToolPoint: Pair<Float, Float>? = null
    private var pendingLoadedCalibrationRestore = false

    private var simAngle = 0.0
    private var simRadiusPx = 200f
    private var zoomFactor = 1f

    private var isResizingTarget = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var targetTapSlopPx = 0f
    private var targetEdgeTolerancePx = 0f
    private var updatingTargetRadiusField = false


    
    // Manual Locking
    private var isLocked = false
    private var trackingEnabled = true
    private var tuningPanelVisible = false

    private var csvLogger: CsvLogger? = null
    private lateinit var calibrationStore: CalibrationStore
    private lateinit var alignmentHistoryStore: AlignmentHistoryStore
    private var latestDeltaInches: Pair<Double, Double>? = null
    private var lastAlignmentToolLabel: String = ""

    private val camPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] == true) startInternalCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        val density = resources.displayMetrics.density
        targetTapSlopPx = 18f * density
        targetEdgeTolerancePx = 24f * density
        camera = buildCameraController()

        csvLogger = CsvLogger(this)
        calibrationStore = CalibrationStore(this)
        alignmentHistoryStore = AlignmentHistoryStore(this)
        calibrationData = calibrationStore.load()

        b.rgCameraSource.setOnCheckedChangeListener { _, checkedId ->
            latestToolPoint = null
            latestDetectionQuality = null
            detectionFilter.reset()
            coordinateMapper.invalidate()
            applyZoom(1f)
            updateDeltaReadout()
            updateQualityReadout()
            when (checkedId) {
                R.id.rbInternal -> {
                    camera.activeType = CameraSourceType.INTERNAL
                    b.wifiGroup.visibility = View.GONE
                    camera.wifi.stop()
                    camera.usb.stop()
                    startInternalCamera()
                }
                R.id.rbWifi -> {
                    camera.activeType = CameraSourceType.WIFI
                    b.wifiGroup.visibility = View.VISIBLE
                    camera.internal.stop()
                    camera.usb.stop()
                }
                R.id.rbUsb -> {
                    camera.activeType = CameraSourceType.USB
                    b.wifiGroup.visibility = View.GONE
                    camera.internal.stop()
                    camera.wifi.stop()
                    startUsbCamera()
                }
            }
        }

        b.btnWifiConnect.setOnClickListener {
            val url = b.edWifiUrl.text.toString()
            if (url.isNotEmpty()) {
                startWifiCamera(url)
            }
        }

        b.switchSim.setOnCheckedChangeListener { _, checked ->
            simulate = checked
            coordinateMapper.invalidate()
            if (simulate) {
                camera.stopAll()
                b.overlay.clearPoints()
                b.overlay.hideSimDot()
                b.previewView.visibility = View.INVISIBLE
                b.textureView.visibility = View.GONE
                b.previewView.post(simTick)
            } else {
                b.previewView.visibility = View.VISIBLE
                b.overlay.hideSimDot()
                b.overlay.clearPoints()
                when (camera.activeType) {
                    CameraSourceType.INTERNAL -> startInternalCamera()
                    CameraSourceType.WIFI -> {
                        val url = b.edWifiUrl.text.toString()
                        if (url.isNotEmpty()) {
                            startWifiCamera(url)
                        }
                    }
                    CameraSourceType.USB -> startUsbCamera()
                }
            }
        }
        b.switchAuto.setOnCheckedChangeListener { _, checked -> autoDetect = checked }

        b.btnCal.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add(0, 1, 0, getString(R.string.cal_set_center))
            popup.menu.add(0, 2, 1, getString(R.string.cal_set_scale))
            popup.menu.add(0, 3, 2, getString(R.string.cal_set_rotation))
            popup.menu.add(0, 4, 3, getString(R.string.cal_help))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> calibrationViewModel.startCenterCalibration()
                    2 -> calibrationViewModel.startScaleCalibration()
                    3 -> calibrationViewModel.startRotationCalibration()
                    4 -> showCalibrationHelp()
                }
                true
            }
            popup.show()
        }
        b.btnCalConfirm.setOnClickListener {
            calibrationViewModel.confirmStep(b.edKnownMm.text?.toString()?.trim()?.toDoubleOrNull())
        }
        b.btnCalRetry.setOnClickListener { calibrationViewModel.retryStep() }
        b.btnCalCancel.setOnClickListener { calibrationViewModel.cancelWizard() }
        b.btnCalAutoCenter.setOnClickListener {
            calibrationViewModel.startAutoCapture(hasLiveToolPoint = latestToolPoint != null)
        }
        b.btnCalRotationCapture.setOnClickListener { calibrationViewModel.toggleRotationCapture() }
        b.btnExport.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add(0, 1, 0, getString(R.string.export_points_csv))
            popup.menu.add(0, 2, 1, getString(R.string.log_alignment))
            popup.menu.add(0, 3, 2, getString(R.string.export_alignment_history))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> csvLogger?.exportOverlay(b.overlay, inPerPx?.times(25.4))
                    2 -> logCurrentAlignment()
                    3 -> exportAlignmentHistory()
                }
                true
            }
            popup.show()
        }

        b.btnZoom1x.setOnClickListener { applyZoom(1f) }
        b.btnZoom2x.setOnClickListener { applyZoom(2f) }
        b.btnZoom5x.setOnClickListener { applyZoom(5f) }

        b.btnLock.setOnClickListener {
            isLocked = !isLocked
            b.btnLock.text = if (isLocked) getString(R.string.unlock) else getString(R.string.lock)

            if (isLocked) {
                 // Trigger Lock
                 if (camera.activeType == CameraSourceType.USB) {
                     val lockedThr = camera.usb.lockTarget(true) ?: return@setOnClickListener
                     Toast.makeText(this, "Locked (USB) Thr=$lockedThr", Toast.LENGTH_SHORT).show()
                 } else if (camera.activeType == CameraSourceType.INTERNAL) {
                     camera.internal.requestLock() // Sampled on the next analyzed frame
                 }
            } else {
                // Unlock
                camera.internal.cancelLockRequest()
                updateCfg { copy(lockedThreshold = null) }
                if (camera.activeType == CameraSourceType.USB) {
                     camera.usb.lockTarget(false)
                }
                Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
            }
        }

        b.btnTrackStart.setOnClickListener {
            trackingEnabled = true
            updateTrackingButtons()
        }

        b.btnTrackStop.setOnClickListener {
            trackingEnabled = false
            detectionFilter.reset()
            updateTrackingButtons()
        }

        b.btnTrackReset.setOnClickListener {
            b.overlay.clearPoints()
        }

        updateTrackingButtons()

        b.btnSettings.setOnClickListener {
            tuningPanelVisible = !tuningPanelVisible
            b.tuningPanel.visibility = if (tuningPanelVisible) View.VISIBLE else View.GONE
        }

        // Nudge buttons — move target circle center / adjust radius in view-space steps
        val nudgeStepPx = resources.displayMetrics.density * 4f  // 4dp per tap
        b.btnNudgeUp.setOnClickListener    { b.overlay.setTargetCenter(b.overlay.targetX, b.overlay.targetY - nudgeStepPx) }
        b.btnNudgeDown.setOnClickListener  { b.overlay.setTargetCenter(b.overlay.targetX, b.overlay.targetY + nudgeStepPx) }
        b.btnNudgeLeft.setOnClickListener  { b.overlay.setTargetCenter(b.overlay.targetX - nudgeStepPx, b.overlay.targetY) }
        b.btnNudgeRight.setOnClickListener { b.overlay.setTargetCenter(b.overlay.targetX + nudgeStepPx, b.overlay.targetY) }
        b.btnRadiusMinus.setOnClickListener { b.overlay.setTargetRadius((b.overlay.targetRadiusPx - nudgeStepPx).coerceAtLeast(4f)) }
        b.btnRadiusPlus.setOnClickListener  { b.overlay.setTargetRadius(b.overlay.targetRadiusPx + nudgeStepPx) }

        b.seekCirc.setOnSeekBarChangeListener(SimpleSeek { p -> updateCfg { copy(minCircularity = (p/100.0).coerceIn(0.0,1.0)) }; updateParamsSummary(); syncUsbDetectorTuning() })
        b.seekKstd.setOnSeekBarChangeListener(SimpleSeek { p -> updateCfg { copy(kStd = p/100.0) }; updateParamsSummary(); syncUsbDetectorTuning() })
        b.seekContrast.setOnSeekBarChangeListener(SimpleSeek { p -> updateCfg { copy(minContrastRatio = (p/100.0).coerceIn(0.0,1.0)) }; updateParamsSummary(); syncUsbDetectorTuning() })
        b.overlay.onTargetChanged = { tx, ty, radius ->
            // Convert view-space overlay coords to image-space for the detector
            val (ix, iy) = if (coordinateMapper.isValid) coordinateMapper.viewToImage(tx, ty) else tx to ty
            val ir = if (coordinateMapper.isValid) coordinateMapper.viewRadiusToImage(radius) else radius
            updateCfg { copy(targetCenterX = ix, targetCenterY = iy, targetRadiusPx = ir) }
            if (!b.edTargetRadius.hasFocus()) {
                updatingTargetRadiusField = true
                b.edTargetRadius.setText(String.format(Locale.US, "%.1f", radius))
                updatingTargetRadiusField = false
            }
            updateParamsSummary()
            camera.usb.setTargetCircle(tx, ty, radius)
        }
        b.edTargetRadius.setText(String.format(Locale.US, "%.1f", cfg.targetRadiusPx))
        b.edTargetRadius.setOnEditorActionListener { v, _, _ ->
            if (!updatingTargetRadiusField) {
                val enteredRadius = v.text?.toString()?.trim()?.toFloatOrNull()
                if (enteredRadius != null) {
                    b.overlay.setTargetRadius(enteredRadius)
                }
            }
            true
        }
        b.edTargetRadius.setOnFocusChangeListener { v, hasFocus ->
            if (!hasFocus && !updatingTargetRadiusField) {
                val enteredRadius = (v as? android.widget.EditText)?.text?.toString()?.trim()?.toFloatOrNull()
                if (enteredRadius != null) {
                    b.overlay.setTargetRadius(enteredRadius)
                }
            }
        }
        applyLoadedCalibration()
        updateParamsSummary()
        updateDeltaReadout()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    calibrationViewModel.state.collect { renderCalibrationPanel(it) }
                }
                launch {
                    calibrationViewModel.effects.collect { handleCalibrationEffect(it) }
                }
            }
        }

        b.edMmPerPx.setOnEditorActionListener { v, _, _ ->
            // The field shows/accepts inches-per-VIEW-pixel (what the operator sees on
            // screen); convert to image-space before storing, since CalibrationData is
            // persisted in image space so it survives resolution/orientation changes.
            inPerPx = v.text?.toString()?.trim()?.toDoubleOrNull()
            b.overlay.mmPerPx = inPerPx?.times(25.4)
            updateParamsSummary()
            if (inPerPx != null && calibrationData != null) {
                val imageInchesPerPixel = inPerPx!! * coordinateMapper.imageToViewScale
                calibrationData = calibrationData?.copy(inchesPerPixel = imageInchesPerPixel)
                calibrationData?.let { calibrationStore.save(it) }
            }
            updateDeltaReadout()
            true
        }
        b.edKnownMm.setText(getString(R.string.default_known_mm))
        b.edKnownMm.setOnEditorActionListener { v, _, _ ->
            calibrationViewModel.onKnownInchesChanged(v.text?.toString()?.toDoubleOrNull())
            true
        }

        b.overlay.setOnTouchListener { view, ev ->
            val calibrationActive =
                calibrationViewModel.state.value.step != CalibrationViewModel.Step.NONE
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    val x = ev.x
                    val y = ev.y
                    if (!calibrationViewModel.handleTap(x, y)) {
                        touchDownX = x
                        touchDownY = y
                        isResizingTarget = isNearTargetEdge(x, y)
                        if (isResizingTarget) {
                            val radius = hypot((x - b.overlay.targetX).toDouble(), (y - b.overlay.targetY).toDouble()).toFloat()
                            b.overlay.setTargetRadius(radius)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!calibrationActive && isResizingTarget) {
                        val radius = hypot((ev.x - b.overlay.targetX).toDouble(), (ev.y - b.overlay.targetY).toDouble()).toFloat()
                        b.overlay.setTargetRadius(radius)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!calibrationActive && !isResizingTarget) {
                        val moveDistance = hypot((ev.x - touchDownX).toDouble(), (ev.y - touchDownY).toDouble()).toFloat()
                        if (moveDistance <= targetTapSlopPx) {
                            b.overlay.setTargetCenter(ev.x, ev.y)
                        }
                    }
                    isResizingTarget = false
                    view.performClick()
                    true
                }
                else -> true
            }
        }

        if (!simulate) ensureCameraPermission { startInternalCamera() }
    }

    /** Wires the three camera sources to this activity's shared detection state and UI. */
    private fun buildCameraController(): CameraController {
        val internalSource = InternalCameraSource(
            this, b.previewView, frameAnalyzer,
            object : InternalCameraSource.Listener {
                override fun isAnalysisEnabled() = autoDetect && !simulate
                override fun isTrackingEnabled() = trackingEnabled
                override fun onToolPointDetected(point: FrameAnalyzer.ViewPoint) {
                    this@MainActivity.onToolPointDetected(point.x, point.y, point.quality)
                }
                override fun onLockThresholdSampled(threshold: Int) {
                    updateCfg { copy(lockedThreshold = threshold) }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Locked (Int) Thr=$threshold", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        val wifiSource = WifiCameraSource(
            this, b.textureView, frameAnalyzer,
            object : WifiCameraSource.Listener {
                override fun isAnalysisEnabled() = autoDetect
                override fun isTrackingEnabled() = trackingEnabled
                override fun onToolPointDetected(point: FrameAnalyzer.ViewPoint) {
                    this@MainActivity.onToolPointDetected(point.x, point.y, point.quality)
                }
                override fun onError(message: String) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        )

        val usbController = UsbCameraController(
            this, b.usbCameraContainer, R.id.usbCameraContainer,
            object : UsbCameraController.Host {
                override val isActiveSource: Boolean
                    get() = camera.activeType == CameraSourceType.USB && !simulate

                override fun configureFragment(fragment: UvcFragment) {
                    fragment.setTargetCircle(
                        x = b.overlay.targetX,
                        y = b.overlay.targetY,
                        radiusPx = b.overlay.targetRadiusPx
                    )
                    fragment.setDetectorTuning(cfg.minCircularity, cfg.kStd, cfg.minContrastRatio)
                    fragment.setDetectionCallback(object : UvcFragment.DetectionCallback {
                        override fun onPointDetected(x: Float, y: Float, circularity: Double, contrastRatio: Double, sharpness: Double) {
                            if (autoDetect && !simulate && trackingEnabled) {
                                onToolPointDetected(x, y, DetectionQuality(circularity, contrastRatio, sharpness))
                            }
                        }
                    })
                }

                override fun showToast(message: String) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }

                override fun showRetriesExhausted(retry: () -> Unit) {
                    com.google.android.material.snackbar.Snackbar.make(
                        b.root,
                        "USB camera could not reconnect. Replug it, or tap Retry.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                    ).setAction("Retry") { retry() }.show()
                }

                override fun onStopped() {
                    if (camera.activeType == CameraSourceType.INTERNAL) {
                        b.previewView.visibility = View.VISIBLE
                    }
                }
            }
        )

        return CameraController(internalSource, wifiSource, usbController)
    }

    private fun startInternalCamera() {
        b.previewView.visibility = View.VISIBLE
        b.textureView.visibility = View.GONE
        camera.internal.start()
    }

    private fun startWifiCamera(url: String) {
        if (camera.wifi.start(url)) {
            b.previewView.visibility = View.GONE
            b.textureView.visibility = View.VISIBLE
        }
    }

    private fun startUsbCamera() {
        b.previewView.visibility = View.GONE
        b.textureView.visibility = View.GONE
        camera.usb.start()
    }

    private fun showCalibrationHelp() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.cal_help_title))
            .setMessage(getString(R.string.cal_help_body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Applies one-shot wizard outcomes (messages, persisted calibrations) to the UI. */
    private fun handleCalibrationEffect(effect: CalibrationViewModel.Effect) {
        when (effect) {
            is CalibrationViewModel.Effect.Message -> {
                val duration = if (effect.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this, getString(effect.resId), duration).show()
            }
            is CalibrationViewModel.Effect.CenterSaved -> {
                b.overlay.setTrueCenter(effect.viewX, effect.viewY)
                if (effect.updatedData != null) {
                    calibrationData = effect.updatedData
                    updateDeltaReadout()
                }
            }
            is CalibrationViewModel.Effect.CalibrationCompleted -> {
                calibrationData = effect.data
                inPerPx = effect.viewInchesPerPixel // view-space, for the on-screen in/px display
                b.overlay.mmPerPx = inPerPx?.times(25.4)
                b.edMmPerPx.setText(String.format(Locale.US, "%.6f", effect.viewInchesPerPixel))
                b.overlay.setTrueCenter(effect.viewCenterX, effect.viewCenterY)
                updateParamsSummary()
                updateDeltaReadout()
            }
        }
    }

    /** Renders the calibration panel and overlay markers from the wizard state. */
    private fun renderCalibrationPanel(s: CalibrationViewModel.UiState) {
        val active = s.step != CalibrationViewModel.Step.NONE
        b.calibrationPanel.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            tuningPanelVisible = false
            b.tuningPanel.visibility = View.GONE
        }
        if (!active) {
            b.overlay.setCalibrationMarkers(
                center = null,
                up = null,
                scaleP1 = null,
                scaleP2 = null,
                pending = null
            )
            return
        }

        val stepText = when (s.step) {
            CalibrationViewModel.Step.NONE -> ""
            CalibrationViewModel.Step.CENTER -> if (s.isRotationMode) getString(R.string.calibration_step_rotation) else getString(R.string.calibration_step_center)
            CalibrationViewModel.Step.UP -> getString(R.string.calibration_step_scale_up)
            CalibrationViewModel.Step.SCALE_P1 -> getString(R.string.calibration_step_scale_1)
            CalibrationViewModel.Step.SCALE_P2 -> getString(R.string.calibration_step_scale_2)
        }
        b.txtCalStep.text = stepText

        val isRotationCenterStep = s.step == CalibrationViewModel.Step.CENTER && s.isRotationMode
        b.txtCalCapture.text = when {
            isRotationCenterStep && s.isRotationCapturing ->
                getString(R.string.calibration_rotation_collecting, s.rotationSampleCount, s.rotationCoveragePercent)
            isRotationCenterStep && s.pendingTap != null -> {
                val (x, y) = s.pendingTap
                getString(R.string.calibration_point_captured, x, y)
            }
            isRotationCenterStep -> getString(R.string.calibration_rotation_idle)
            s.isAutoCapturingCenter ->
                getString(R.string.calibration_auto_collecting, s.autoSampleCount, CalibrationViewModel.AUTO_SAMPLE_TARGET)
            else -> s.pendingTap?.let { (x, y) -> getString(R.string.calibration_point_captured, x, y) }
                ?: getString(R.string.calibration_step_wait_tap)
        }

        val qualityText = when {
            isRotationCenterStep && s.rotationFitQuality != null -> {
                val q = s.rotationFitQuality
                getString(
                    R.string.calibration_rotation_quality_value,
                    q.sampleCount,
                    q.coverageFraction * 100.0,
                    q.rms
                )
            }
            s.step == CalibrationViewModel.Step.SCALE_P2 && s.center != null && s.upPoint != null && s.scaleP1 != null && s.pendingTap != null -> {
                val typedKnownInches = b.edKnownMm.text?.toString()?.trim()?.toDoubleOrNull()
                val useKnown = if (typedKnownInches != null && typedKnownInches > 0.0) typedKnownInches else s.knownInches
                val q = CalibrationQualityEvaluator.evaluate(
                    center = s.center,
                    upPoint = s.upPoint,
                    scaleP1 = s.scaleP1,
                    scaleP2 = s.pendingTap,
                    knownInches = useKnown
                )
                getString(
                    R.string.calibration_quality_value,
                    q.upDistancePx,
                    q.scaleDistancePx,
                    q.combinedErrorIn
                )
            }
            else -> getString(R.string.calibration_quality_pending)
        }
        b.txtCalQuality.text = qualityText
        b.btnCalConfirm.isEnabled = s.pendingTap != null && (!s.isRotationMode || s.rotationFitQuality?.acceptable == true)
        val showAutoBtn = s.step == CalibrationViewModel.Step.CENTER && s.isCenterOnlyMode && !s.isRotationMode
        b.btnCalAutoCenter.visibility = if (showAutoBtn) View.VISIBLE else View.GONE
        if (showAutoBtn) b.btnCalAutoCenter.isEnabled = !s.isAutoCapturingCenter
        val showRotationBtn = s.step == CalibrationViewModel.Step.CENTER && s.isRotationMode
        b.btnCalRotationCapture.visibility = if (showRotationBtn) View.VISIBLE else View.GONE
        if (showRotationBtn) {
            b.btnCalRotationCapture.isEnabled = true
            b.btnCalRotationCapture.text = if (s.isRotationCapturing) {
                getString(R.string.calibration_rotation_finish)
            } else {
                getString(R.string.calibration_rotation_start)
            }
        }
        b.overlay.setCalibrationMarkers(
            center = s.center,
            up = s.upPoint,
            scaleP1 = s.scaleP1,
            scaleP2 = s.scaleP2,
            pending = s.pendingTap
        )
    }

    private fun applyLoadedCalibration() {
        // Stored calibration/center is in image space, but restoring the on-screen
        // crosshair needs view space, which requires CoordinateMapper to have seen at
        // least one frame (never true yet here in onCreate). Defer the actual restore
        // to restoreLoadedCalibrationIfPending(), run per-frame until it applies once.
        pendingLoadedCalibrationRestore = calibrationData != null || calibrationStore.loadCenterOrNull() != null
        updateParamsSummary()
    }

    private fun restoreLoadedCalibrationIfPending() {
        if (!pendingLoadedCalibrationRestore || !coordinateMapper.isValid) return
        pendingLoadedCalibrationRestore = false
        val data = calibrationData
        if (data != null) {
            val viewCenter = coordinateMapper.imageToView(data.centerX, data.centerY)
            val viewInchesPerPixel = data.inchesPerPixel / coordinateMapper.imageToViewScale
            inPerPx = viewInchesPerPixel
            b.overlay.mmPerPx = viewInchesPerPixel * 25.4
            b.edMmPerPx.setText(String.format(Locale.US, "%.6f", viewInchesPerPixel))
            b.overlay.setTrueCenter(viewCenter.first, viewCenter.second)
        } else {
            // Restore center-only crosshair position if user set it without full scale cal
            val center = calibrationStore.loadCenterOrNull()
            if (center != null) {
                val viewCenter = coordinateMapper.imageToView(center.first, center.second)
                b.overlay.setTrueCenter(viewCenter.first, viewCenter.second)
            }
        }
        updateParamsSummary()
    }

    private fun onToolPointDetected(x: Float, y: Float, quality: DetectionQuality? = null) {
        restoreLoadedCalibrationIfPending()
        b.overlay.addPoint(x, y)
        latestToolPoint = x to y
        if (quality != null) latestDetectionQuality = quality
        // Rotation/auto-center captures consume the point; panel text updates via state.
        if (calibrationViewModel.onToolPoint(x, y)) return
        updateDeltaReadout()
        updateQualityReadout()
    }

    /**
     * Digital zoom: scales the active preview view and the overlay together, pivoted on
     * their shared center. Android delivers touch coordinates to a scaled view in that
     * view's own unscaled local space, so calibration taps, target-circle dragging, and
     * detection math all keep working unmodified regardless of zoom level.
     */
    private fun applyZoom(factor: Float) {
        zoomFactor = factor
        for (v in listOf(b.previewView, b.textureView, b.usbCameraContainer, b.overlay)) {
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.scaleX = factor
            v.scaleY = factor
        }
    }

    /** USB frames are detected in [UvcFragment]'s own detector
     *  config, independent of [cfg] — push tuning-slider changes there too when USB is live. */
    private fun syncUsbDetectorTuning() {
        camera.usb.setDetectorTuning(cfg.minCircularity, cfg.kStd, cfg.minContrastRatio)
    }

    private fun updateParamsSummary() {
        val inText = inPerPx?.let { String.format(Locale.US, "%.6f", it) } ?: getString(R.string.unset_value)
        val radiusText = String.format(Locale.US, "%.1f", cfg.targetRadiusPx)
        val circText = String.format(Locale.US, "%.2f", cfg.minCircularity)
        val kStdText = String.format(Locale.US, "%.2f", cfg.kStd)
        val contrastText = String.format(Locale.US, "%.2f", cfg.minContrastRatio)
        b.txtParams.text = getString(
            R.string.params_summary,
            radiusText,
            circText,
            kStdText,
            contrastText,
            inText
        )
    }

    private fun updateDeltaReadout() {
        val data = calibrationData
        val point = latestToolPoint
        latestDeltaInches = null
        b.txtDelta.text = when {
            data == null -> getString(R.string.delta_uncalibrated)
            point == null -> getString(R.string.delta_waiting_tool)
            else -> {
                // calibrationData is stored in image space; convert the live view-space
                // tool point before computing the offset.
                val (imgX, imgY) = coordinateMapper.viewToImage(point.first, point.second)
                val (dx, dy) = data.toolOffsetInches(imgX, imgY)
                latestDeltaInches = dx to dy
                getString(R.string.delta_value, dx, dy)
            }
        }
    }

    /** 0-100: how far circularity/contrast sit above their acceptance thresholds. */
    private fun signalConfidencePercent(quality: DetectionQuality, cfg: DetectorConfig): Double {
        val circMargin = if (cfg.minCircularity < 1.0) {
            ((quality.circularity - cfg.minCircularity) / (1.0 - cfg.minCircularity)).coerceIn(0.0, 1.0)
        } else 0.0
        val contrastMargin = if (cfg.minContrastRatio < 1.0) {
            ((quality.contrastRatio - cfg.minContrastRatio) / (1.0 - cfg.minContrastRatio)).coerceIn(0.0, 1.0)
        } else 0.0
        return (circMargin + contrastMargin) / 2.0 * 100.0
    }

    private fun updateQualityReadout() {
        val quality = latestDetectionQuality
        if (quality == null) {
            b.txtQuality.text = getString(R.string.quality_no_signal)
            return
        }

        val builder = android.text.SpannableStringBuilder()
        fun appendSegment(text: String, colorRes: Int) {
            if (builder.isNotEmpty()) builder.append("   ")
            val start = builder.length
            builder.append(text)
            builder.setSpan(
                android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, colorRes)),
                start, builder.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val fitRms = b.overlay.lastFitResult?.rms
        if (fitRms != null) {
            val fitColor = when {
                fitRms <= FIT_RMS_GOOD_PX -> R.color.axisight_ring
                fitRms <= FIT_RMS_OK_PX -> R.color.axisight_caution
                else -> R.color.axisight_warn
            }
            appendSegment(getString(R.string.quality_fit_segment, fitRms), fitColor)
        }

        val sharpColor = when {
            quality.sharpness >= SHARPNESS_GOOD -> R.color.axisight_ring
            quality.sharpness >= SHARPNESS_OK -> R.color.axisight_caution
            else -> R.color.axisight_warn
        }
        appendSegment(getString(R.string.quality_sharp_segment, quality.sharpness), sharpColor)

        val confidencePct = signalConfidencePercent(quality, cfg)
        val signalColor = when {
            confidencePct >= SIGNAL_GOOD_PCT -> R.color.axisight_ring
            confidencePct >= SIGNAL_OK_PCT -> R.color.axisight_caution
            else -> R.color.axisight_warn
        }
        appendSegment(getString(R.string.quality_signal_segment, confidencePct), signalColor)

        b.txtQuality.text = builder
    }

    private fun logCurrentAlignment() {
        val delta = latestDeltaInches
        if (delta == null) {
            Toast.makeText(this, getString(R.string.alignment_log_no_reading), Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            setText(lastAlignmentToolLabel)
            hint = getString(R.string.alignment_log_tool_hint)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.alignment_log_title))
            .setView(input)
            .setPositiveButton(getString(R.string.alignment_log_save)) { _, _ ->
                val label = input.text?.toString()?.trim()
                    .takeUnless { it.isNullOrEmpty() } ?: getString(R.string.alignment_log_default_tool)
                lastAlignmentToolLabel = label
                alignmentHistoryStore.append(
                    AlignmentRecord(
                        timestampMs = System.currentTimeMillis(),
                        toolLabel = label,
                        cameraSource = camera.activeType.name,
                        dxIn = delta.first,
                        dyIn = delta.second,
                        fitRmsPx = b.overlay.lastFitResult?.rms
                    )
                )
                Toast.makeText(this, getString(R.string.alignment_log_saved, label), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportAlignmentHistory() {
        val records = alignmentHistoryStore.readAll()
        if (records.isEmpty()) {
            Toast.makeText(this, getString(R.string.alignment_log_empty), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            this,
            getString(R.string.alignment_log_export_path, records.size, alignmentHistoryStore.exportPath()),
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::camera.isInitialized) camera.dispose()
    }

    private fun ensureCameraPermission(onGranted: () -> Unit) {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cam) onGranted()
        else camPerm.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private val simTick = object : Runnable {
        override fun run() {
            if (!simulate) return
            val w = b.overlay.width.coerceAtLeast(1)
            val h = b.overlay.height.coerceAtLeast(1)
            val cx = w / 2f; val cy = h / 2f
            simRadiusPx = min(w, h) * 0.3f
            simAngle += 0.07
            val x = cx + simRadiusPx * cos(simAngle).toFloat()
            val y = cy + simRadiusPx * sin(simAngle).toFloat()
            b.overlay.setSimDot(x, y)
            if (autoDetect && trackingEnabled) {
                // Simulated points have no real camera image behind them, so image space
                // and view space are the same fictional space: feed an identity mapping.
                coordinateMapper.update(w, h, 0, w, h)
                onToolPointDetected(x, y)
            }
            b.previewView.postDelayed(this, 16L)
        }
    }

    private fun updateTrackingButtons() {
        b.btnTrackStart.isEnabled = !trackingEnabled
        b.btnTrackStop.isEnabled = trackingEnabled
    }

    private fun isNearTargetEdge(x: Float, y: Float): Boolean {
        if (!b.overlay.targetInitialized) return false
        val distance = hypot((x - b.overlay.targetX).toDouble(), (y - b.overlay.targetY).toDouble()).toFloat()
        return abs(distance - b.overlay.targetRadiusPx) <= targetEdgeTolerancePx
    }

    companion object {
        // Quality-readout tiers (green/yellow/red). Starting points, not empirically
        // tuned against real hardware yet — adjust once field data is available.
        private const val FIT_RMS_GOOD_PX = 1.5
        private const val FIT_RMS_OK_PX = 3.0
        private const val SHARPNESS_GOOD = 150.0
        private const val SHARPNESS_OK = 50.0
        private const val SIGNAL_GOOD_PCT = 70.0
        private const val SIGNAL_OK_PCT = 40.0
    }
}

private class SimpleSeek(val on: (Int) -> Unit) : android.widget.SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) { on(progress) }
    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
}
