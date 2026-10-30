package com.etrsystems.axisight

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
        CalibrationViewModel.Factory(CalibrationStore(applicationContext)) {
            if (::camera.isInitialized) camera.activeType.name else null
        }
    }

    private var simulate = false
    private var autoDetect = true
    private var inPerPx: Double? = null

    private val cfgRef = AtomicReference(DetectorConfig())
    private val cfg get() = cfgRef.get()
    private fun updateCfg(block: DetectorConfig.() -> DetectorConfig) { cfgRef.set(cfgRef.get().block()) }

    private var latestDetectionQuality: DetectionQuality? = null
    private var latestDetectionAtMs = 0L
    // The mapper lives in the ViewModel so wizard state and coordinate mapping
    // survive configuration changes together; camera frames refresh it anyway.
    private val coordinateMapper get() = calibrationViewModel.coordinateMapper
    private val detectionFilter = DetectionFilter()
    // Operator's target circle, kept in view space; FrameAnalyzer converts it to image
    // space per frame through the live mapper (correct for whichever source is running).
    private val targetViewRef = AtomicReference<FrameAnalyzer.ViewTarget?>(null)
    private val frameAnalyzer by lazy {
        FrameAnalyzer(coordinateMapper, detectionFilter, { cfg }, { targetViewRef.get() })
    }

    private var calibrationData: CalibrationData? = null
    private var latestToolPoint: Pair<Float, Float>? = null
    private var pendingLoadedCalibrationRestore = false
    // Set whenever the stored image-space target must be re-projected through a fresh
    // mapper: launch, source switch, overlay resize. Consumed on the next valid frame.
    private var pendingTargetRestore = false
    private val persistTargetRunnable = Runnable { persistTargetNow() }

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
        enableEdgeToEdge()
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyEdgeToEdgeInsets()
        val density = resources.displayMetrics.density
        targetTapSlopPx = 18f * density
        targetEdgeTolerancePx = 24f * density
        camera = buildCameraController()

        csvLogger = CsvLogger(this)
        calibrationStore = CalibrationStore(this)
        alignmentHistoryStore = AlignmentHistoryStore(this)
        calibrationData = calibrationStore.load()

        b.rgCameraSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newType = when (checkedId) {
                R.id.rbUsb -> CameraSourceType.USB
                R.id.rbWifi -> CameraSourceType.WIFI
                else -> CameraSourceType.INTERNAL
            }
            camera.activeType = newType
            calibrationStore.saveLastSource(newType.name)
            latestToolPoint = null
            latestDetectionQuality = null
            detectionFilter.reset()
            frameAnalyzer.reset()
            coordinateMapper.invalidate()
            // The target circle is stored per source in image space; re-project it once
            // this source's first frame has refreshed the mapper.
            cancelTargetPersist()
            pendingTargetRestore = true
            applyZoom(1f)
            // Calibration is per camera source: hide the crosshair until this source's
            // first frame lets us re-project the stored center (or decide it does not
            // apply here). restoreLoadedCalibrationIfPending() runs from qualityTick.
            b.overlay.clearTrueCenter()
            calibrationData = calibrationStore.load()
            pendingLoadedCalibrationRestore = true
            updateDeltaReadout()
            updateQualityReadout()
            when (newType) {
                CameraSourceType.INTERNAL -> {
                    b.wifiGroup.visibility = View.GONE
                    camera.wifi.stop()
                    camera.usb.stop()
                    startInternalCamera()
                }
                CameraSourceType.WIFI -> {
                    b.wifiGroup.visibility = View.VISIBLE
                    camera.internal.stop()
                    camera.usb.stop()
                }
                CameraSourceType.USB -> {
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
                    2 -> if (calibrationAppliesToActiveSource()) {
                        calibrationViewModel.startScaleCalibration()
                    } else {
                        // A center captured on another camera is in that camera's image space.
                        Toast.makeText(this, getString(R.string.cal_scale_needs_center), Toast.LENGTH_LONG).show()
                    }
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

        b.zoomGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            applyZoom(
                when (checkedId) {
                    R.id.btnZoom2x -> 2f
                    R.id.btnZoom5x -> 5f
                    else -> 1f
                }
            )
        }

        b.btnLock.setOnClickListener {
            isLocked = !isLocked
            updateLockButton()

            if (isLocked) {
                // Threshold is sampled on the next analyzed frame of the active source.
                when (camera.activeType) {
                    CameraSourceType.USB -> camera.usb.requestLock()
                    CameraSourceType.INTERNAL -> camera.internal.requestLock()
                    CameraSourceType.WIFI -> Unit
                }
            } else {
                camera.internal.cancelLockRequest()
                camera.usb.cancelLockRequest()
                updateCfg { copy(lockedThreshold = null) }
                Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
            }
        }

        b.btnTrack.setOnClickListener {
            trackingEnabled = !trackingEnabled
            if (!trackingEnabled) detectionFilter.reset()
            updateTrackingButtons()
            updateQualityReadout()
        }

        b.btnTrackReset.setOnClickListener {
            b.overlay.clearPoints()
        }

        updateTrackingButtons()
        updateLockButton()

        b.btnSettings.setOnClickListener { setTuningPanelVisible(!tuningPanelVisible) }
        b.btnTuningClose.setOnClickListener { setTuningPanelVisible(false) }

        // Keep the overlay's fit readout clear of the bottom bar and the zoom control.
        val overlayInsetListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val gap = resources.displayMetrics.density * 16f
            b.overlay.bottomInset = b.bottomBar.height + b.zoomGroup.height + gap
        }
        b.bottomBar.addOnLayoutChangeListener(overlayInsetListener)
        b.zoomGroup.addOnLayoutChangeListener(overlayInsetListener)

        // Nudge buttons — move target circle center / adjust radius in view-space steps
        val nudgeStepPx = resources.displayMetrics.density * 4f  // 4dp per tap
        b.btnNudgeUp.setOnClickListener    { b.overlay.setTargetCenter(b.overlay.targetX, b.overlay.targetY - nudgeStepPx) }
        b.btnNudgeDown.setOnClickListener  { b.overlay.setTargetCenter(b.overlay.targetX, b.overlay.targetY + nudgeStepPx) }
        b.btnNudgeLeft.setOnClickListener  { b.overlay.setTargetCenter(b.overlay.targetX - nudgeStepPx, b.overlay.targetY) }
        b.btnNudgeRight.setOnClickListener { b.overlay.setTargetCenter(b.overlay.targetX + nudgeStepPx, b.overlay.targetY) }
        b.btnRadiusMinus.setOnClickListener { b.overlay.setTargetRadius((b.overlay.targetRadiusPx - nudgeStepPx).coerceAtLeast(4f)) }
        b.btnRadiusPlus.setOnClickListener  { b.overlay.setTargetRadius(b.overlay.targetRadiusPx + nudgeStepPx) }

        // Sliders start at the config defaults so the readout and the detector agree.
        b.seekCirc.value = cfg.minCircularity.toFloat()
        b.seekKstd.value = cfg.kStd.toFloat()
        b.seekContrast.value = cfg.minContrastRatio.toFloat()
        b.seekCirc.addOnChangeListener { _, v, _ ->
            updateCfg { copy(minCircularity = v.toDouble().coerceIn(0.0, 1.0)) }
            updateTuningValueLabels(); updateParamsSummary()
        }
        b.seekKstd.addOnChangeListener { _, v, _ ->
            updateCfg { copy(kStd = v.toDouble().coerceAtLeast(0.0)) }
            updateTuningValueLabels(); updateParamsSummary()
        }
        b.seekContrast.addOnChangeListener { _, v, _ ->
            updateCfg { copy(minContrastRatio = v.toDouble().coerceIn(0.0, 1.0)) }
            updateTuningValueLabels(); updateParamsSummary()
        }
        updateTuningValueLabels()
        b.overlay.onTargetChanged = { tx, ty, radius ->
            // Kept in view space; every source converts it to image space per frame.
            targetViewRef.set(FrameAnalyzer.ViewTarget(tx, ty, radius))
            scheduleTargetPersist()
            if (!b.edTargetRadius.hasFocus()) {
                updatingTargetRadiusField = true
                b.edTargetRadius.setText(String.format(Locale.US, "%.1f", radius))
                updatingTargetRadiusField = false
            }
            updateParamsSummary()
        }
        b.edTargetRadius.setText(String.format(Locale.US, "%.1f", b.overlay.targetRadiusPx))
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
        // The overlay rescales its target proportionally when its own size changes, but the
        // image under it moves too (letterbox/scale), so the stretched view coordinates are
        // only a placeholder: the image-space copy is re-projected on the next frame.
        b.overlay.addOnLayoutChangeListener { _, l, t, r, btm, ol, ot, orr, ob ->
            if ((r - l) != (orr - ol) || (btm - t) != (ob - ot)) {
                cancelTargetPersist()
                pendingTargetRestore = true
            }
        }
        // Restore the operator's target circle once the overlay has a size to clamp against.
        // The view-space copy gives an immediate placeholder; restoreTargetIfPending()
        // replaces it with the exact image-space position after the first frame.
        calibrationStore.loadTargetOrNull()?.let { (tx, ty, radius) ->
            b.overlay.post {
                b.overlay.setTargetCenter(tx, ty)
                b.overlay.setTargetRadius(radius)
                cancelTargetPersist()
                pendingTargetRestore = true
            }
        }
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

        // Start on the source the operator used last (or USB when launched by plugging a
        // camera in) so the stored calibration is projected through the right camera.
        // The attach intent is sticky across recreation (rotation), so only honour it on a
        // fresh launch; otherwise it would override whatever source the operator chose.
        val startSource = when {
            isUsbAttachIntent(intent) && savedInstanceState == null -> CameraSourceType.USB
            else -> calibrationStore.loadLastSourceOrNull()
                ?.let { name -> CameraSourceType.entries.firstOrNull { it.name == name } }
                ?: CameraSourceType.INTERNAL
        }
        when (startSource) {
            CameraSourceType.USB -> b.rgCameraSource.check(R.id.rbUsb)
            CameraSourceType.WIFI -> b.rgCameraSource.check(R.id.rbWifi)
            CameraSourceType.INTERNAL -> if (!simulate) ensureCameraPermission { startInternalCamera() }
        }
    }

    /** True when the persisted calibration was captured on the currently active source. */
    private fun calibrationAppliesToActiveSource(): Boolean {
        val source = calibrationStore.loadSourceOrNull() ?: return true // legacy data: assume yes
        return source == camera.activeType.name
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask: a camera plugged in while we are running arrives here. If USB is
        // already the active source the attach broadcast receiver handles the restart.
        if (isUsbAttachIntent(intent) && b.rgCameraSource.checkedButtonId != R.id.rbUsb) {
            b.rgCameraSource.check(R.id.rbUsb)
        }
    }

    private fun isUsbAttachIntent(intent: Intent?): Boolean =
        intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED

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
                override fun onLockThresholdSampled(threshold: Int) = applyLockThreshold(threshold, "Int")
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
                    // USB frames run through the same FrameAnalyzer / DetectorConfig /
                    // CoordinateMapper as the other sources.
                    fragment.attachAnalyzer(frameAnalyzer, object : UvcFragment.FrameListener {
                        override fun isAnalysisEnabled() = autoDetect && !simulate
                        override fun isTrackingEnabled() = trackingEnabled
                        override fun onToolPointDetected(point: FrameAnalyzer.ViewPoint) {
                            this@MainActivity.onToolPointDetected(point.x, point.y, point.quality)
                        }
                        override fun onLockThresholdSampled(threshold: Int) = applyLockThreshold(threshold, "USB")
                    })
                }

                override fun showToast(message: String) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }

                override fun showRecoveryAction(message: String, retry: () -> Unit) {
                    com.google.android.material.snackbar.Snackbar.make(
                        b.root,
                        message,
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
                calibrationStore.saveSource(camera.activeType.name)
                b.overlay.setTrueCenter(effect.viewX, effect.viewY)
                // null when no full calibration applies to this source (none existed, or
                // the stored scale belonged to another camera and was dropped).
                calibrationData = effect.updatedData
                if (effect.updatedData == null) {
                    inPerPx = null
                    b.overlay.mmPerPx = null
                    updateParamsSummary()
                }
                updateDeltaReadout()
            }
            is CalibrationViewModel.Effect.CalibrationCompleted -> {
                calibrationStore.saveSource(camera.activeType.name)
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
        if (active) setTuningPanelVisible(false)
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
        if (!calibrationAppliesToActiveSource()) {
            // Captured on another camera: keep it stored, but do not show or use it here.
            calibrationData = null
            inPerPx = null
            b.overlay.mmPerPx = null
            b.overlay.clearTrueCenter()
            updateParamsSummary()
            updateDeltaReadout()
            return
        }
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

    /**
     * Target-circle persistence. The circle is saved in two forms: view space (immediate
     * placeholder on next launch) and image space tagged with the camera source (exact,
     * survives rotation/letterbox changes, re-projected through the live mapper). Saves
     * are debounced so dragging the circle does not rewrite SharedPreferences per
     * touch-move event.
     */
    private fun scheduleTargetPersist() {
        b.root.removeCallbacks(persistTargetRunnable)
        b.root.postDelayed(persistTargetRunnable, TARGET_PERSIST_DEBOUNCE_MS)
    }

    private fun cancelTargetPersist() {
        b.root.removeCallbacks(persistTargetRunnable)
    }

    private fun persistTargetNow() {
        if (!b.overlay.targetInitialized) return
        val tx = b.overlay.targetX
        val ty = b.overlay.targetY
        val radius = b.overlay.targetRadiusPx
        calibrationStore.saveTarget(tx, ty, radius)
        // Only trust the mapper for this source's current geometry: not while a restore is
        // pending (mapper may still describe the previous layout/source) and not in sim.
        if (coordinateMapper.isValid && !pendingTargetRestore && !simulate) {
            val (ix, iy) = coordinateMapper.viewToImage(tx, ty)
            calibrationStore.saveImageTarget(ix, iy, coordinateMapper.viewRadiusToImage(radius), camera.activeType.name)
        }
    }

    private fun restoreTargetIfPending() {
        if (!pendingTargetRestore || !coordinateMapper.isValid || simulate) return
        pendingTargetRestore = false
        val stored = calibrationStore.loadImageTargetOrNull()
        if (stored != null && stored.source == camera.activeType.name) {
            val (vx, vy) = coordinateMapper.imageToView(stored.x, stored.y)
            b.overlay.setTargetCenter(vx, vy)
            b.overlay.setTargetRadius(coordinateMapper.imageRadiusToView(stored.radiusPx))
        } else if (b.overlay.targetInitialized) {
            // No image-space copy for this source yet: adopt the current circle as-is.
            scheduleTargetPersist()
        }
    }

    private fun onToolPointDetected(x: Float, y: Float, quality: DetectionQuality? = null) {
        restoreLoadedCalibrationIfPending()
        restoreTargetIfPending()
        b.overlay.addPoint(x, y)
        latestToolPoint = x to y
        latestDetectionAtMs = SystemClock.elapsedRealtime()
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
        val id = when (factor) {
            2f -> R.id.btnZoom2x
            5f -> R.id.btnZoom5x
            else -> R.id.btnZoom1x
        }
        if (b.zoomGroup.checkedButtonId != id) b.zoomGroup.check(id)
    }

    private fun setTuningPanelVisible(visible: Boolean) {
        tuningPanelVisible = visible
        b.tuningPanel.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateTuningValueLabels() {
        b.txtCircVal.text = String.format(Locale.US, "%.2f", cfg.minCircularity)
        b.txtKstdVal.text = String.format(Locale.US, "%.2f", cfg.kStd)
        b.txtContrastVal.text = String.format(Locale.US, "%.2f", cfg.minContrastRatio)
    }

    private fun updateLockButton() {
        val accent = ContextCompat.getColor(this, R.color.axisight_caution)
        val neutral = ContextCompat.getColor(this, R.color.axisight_text)
        b.btnLock.text = getString(if (isLocked) R.string.unlock else R.string.lock)
        b.btnLock.setIconResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        b.btnLock.iconTint = android.content.res.ColorStateList.valueOf(if (isLocked) accent else neutral)
        b.btnLock.setTextColor(if (isLocked) accent else neutral)
    }

    /** Applies a lock threshold sampled on a source's analysis thread to the shared config. */
    private fun applyLockThreshold(threshold: Int, sourceTag: String) {
        updateCfg { copy(lockedThreshold = threshold) }
        runOnUiThread {
            Toast.makeText(this, "Locked ($sourceTag) Thr=$threshold", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * targetSdk 35+ draws edge-to-edge. The camera preview stays full-bleed, but the
     * top strip and bottom bar are padded by the system-bar/cutout insets so controls
     * never sit under the status bar or the gesture-navigation area.
     */
    private fun applyEdgeToEdgeInsets() {
        val topBase = intArrayOf(b.topStrip.paddingLeft, b.topStrip.paddingTop, b.topStrip.paddingRight)
        val bottomBase = intArrayOf(b.bottomBar.paddingLeft, b.bottomBar.paddingBottom, b.bottomBar.paddingRight)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            b.topStrip.updatePadding(
                left = topBase[0] + bars.left,
                top = topBase[1] + bars.top,
                right = topBase[2] + bars.right
            )
            b.bottomBar.updatePadding(
                left = bottomBase[0] + bars.left,
                bottom = bottomBase[1] + bars.bottom,
                right = bottomBase[2] + bars.right
            )
            insets
        }
    }

    private fun updateParamsSummary() {
        val inText = inPerPx?.let { String.format(Locale.US, "%.6f", it) } ?: getString(R.string.unset_value)
        val radiusText = if (b.overlay.targetInitialized) {
            String.format(Locale.US, "%.1f", b.overlay.targetRadiusPx)
        } else {
            getString(R.string.unset_value)
        }
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

    /** Why nothing is being detected right now, phrased as what to do about it. */
    private fun noDetectionHint(): String {
        if (!b.overlay.targetInitialized) return getString(R.string.quality_hint_place_target)
        if (!trackingEnabled) return getString(R.string.quality_hint_tracking_stopped)
        val failure = frameAnalyzer.lastFailure ?: return getString(R.string.quality_hint_no_frames)
        val resId = when (failure.reason) {
            FailureReason.NO_DATA -> R.string.quality_hint_no_frames
            FailureReason.NO_DARK_PIXELS -> R.string.quality_hint_no_dark
            FailureReason.TOO_SMALL -> R.string.quality_hint_too_small
            FailureReason.TOO_LARGE -> R.string.quality_hint_too_large
            FailureReason.NOT_CIRCULAR -> R.string.quality_hint_not_circular
            FailureReason.LOW_CONTRAST -> R.string.quality_hint_low_contrast
            FailureReason.JUMP_TOO_LARGE, FailureReason.NOT_CONFIRMED -> R.string.quality_hint_confirming
        }
        return getString(resId)
    }

    /**
     * Housekeeping tick: restores the persisted calibration crosshair as soon as the
     * mapper has seen a frame (no detection required), and refreshes the "no detection"
     * hint while nothing is being detected.
     */
    private val qualityTick = object : Runnable {
        override fun run() {
            restoreLoadedCalibrationIfPending()
            restoreTargetIfPending()
            val stale = SystemClock.elapsedRealtime() - latestDetectionAtMs > DETECTION_STALE_MS
            if (latestDetectionQuality == null || stale) updateQualityReadout()
            b.root.postDelayed(this, QUALITY_TICK_MS)
        }
    }

    private fun updateQualityReadout() {
        val fresh = SystemClock.elapsedRealtime() - latestDetectionAtMs <= DETECTION_STALE_MS
        val quality = latestDetectionQuality?.takeIf { fresh }
        if (quality == null) {
            b.txtQuality.text = getString(R.string.quality_no_signal_reason, noDetectionHint())
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
        b.root.removeCallbacks(qualityTick)
        b.root.post(qualityTick)
    }

    override fun onPause() {
        b.root.removeCallbacks(qualityTick)
        // Flush a debounced target save so nothing is lost if the process is killed.
        if (b.root.removeCallbacks(persistTargetRunnable)) persistTargetNow()
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

    /** One toggle: "Pause" while tracking, "Start" (accented) while paused. */
    private fun updateTrackingButtons() {
        val accent = ContextCompat.getColor(this, R.color.axisight_ring)
        val neutral = ContextCompat.getColor(this, R.color.axisight_text)
        b.btnTrack.text = getString(if (trackingEnabled) R.string.track_pause else R.string.track_start)
        b.btnTrack.setIconResource(if (trackingEnabled) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnTrack.iconTint = android.content.res.ColorStateList.valueOf(if (trackingEnabled) neutral else accent)
        b.btnTrack.setTextColor(if (trackingEnabled) neutral else accent)
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
        private const val DETECTION_STALE_MS = 1000L
        private const val QUALITY_TICK_MS = 500L
        private const val TARGET_PERSIST_DEBOUNCE_MS = 300L
    }
}

