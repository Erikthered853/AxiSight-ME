package com.etrsystems.axisight

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.etrsystems.axisight.usb.UsbDeviceUtils
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.etrsystems.axisight.databinding.ActivityMainBinding
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

@UnstableApi
class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var b: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var exoPlayer: ExoPlayer? = null
    private var usbPermissionReceiverRegistered = false
    private var usbRecoveryReceiverRegistered = false
    private var usbAttachReceiverRegistered = false
    private var pendingUsbDeviceName: String? = null
    private var usbPermissionRequestInFlight = false
    private var lastUsbPermissionRequestMs = 0L
    private val usbPermissionAction by lazy { "${BuildConfig.APPLICATION_ID}.USB_PERMISSION" }
    // Limits automatic reconnect attempts in MainActivity USB mode to prevent infinite loops.
    private var usbFragmentRetryCount = 0

    private enum class CameraSource { INTERNAL, WIFI, USB }
    private var cameraSource = CameraSource.INTERNAL

    private var simulate = false
    private var autoDetect = true
    private var inPerPx: Double? = null
    private var knownInches: Double = DEFAULT_KNOWN_INCHES

    private val cfgRef = AtomicReference(DetectorConfig())
    private val cfg get() = cfgRef.get()
    private fun updateCfg(block: DetectorConfig.() -> DetectorConfig) { cfgRef.set(cfgRef.get().block()) }

    private data class DetectionQuality(
        val circularity: Double,
        val contrastRatio: Double,
        val sharpness: Double
    )
    private var latestDetectionQuality: DetectionQuality? = null
    private val coordinateMapper = CoordinateMapper()
    private val detectionFilter = DetectionFilter()

    private enum class CalibrationStep { NONE, CENTER, UP, SCALE_P1, SCALE_P2 }
    private var calibrationStep = CalibrationStep.NONE
    private var isCenterOnlyMode = false  // true = Set Center only; false = full or scale+direction wizard
    private var calCenter: Pair<Float, Float>? = null
    private var calUpPoint: Pair<Float, Float>? = null
    private var calScaleP1: Pair<Float, Float>? = null
    private var calScaleP2: Pair<Float, Float>? = null
    private var pendingCalTap: Pair<Float, Float>? = null
    private var calibrationData: CalibrationData? = null
    private var latestToolPoint: Pair<Float, Float>? = null
    private var pendingLoadedCalibrationRestore = false

    // Auto-detect center: collect AUTO_SAMPLE_TARGET frames and average them
    private val AUTO_SAMPLE_TARGET = 30
    private val autoSamples = mutableListOf<Pair<Float, Float>>()
    private var isAutoCapturingCenter = false

    // Rotation-based center calibration: operator rotates the spindle by hand while the
    // detected tool position is traced, then CircleFit derives the true centerline.
    private var isRotationMode = false
    private var isRotationCapturing = false
    private val rotationSamples = mutableListOf<Pair<Float, Float>>()
    private val rotationCoverageBuckets = BooleanArray(ROTATION_COVERAGE_BUCKETS)
    private var rotationSumX = 0.0
    private var rotationSumY = 0.0
    private var lastRotationFitQuality: RotationFitQuality? = null

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
    private var lockRequest = false
    private var trackingEnabled = true
    private var tuningPanelVisible = false

    private var csvLogger: CsvLogger? = null
    private lateinit var calibrationStore: CalibrationStore
    private lateinit var alignmentHistoryStore: AlignmentHistoryStore
    private var latestDeltaInches: Pair<Double, Double>? = null
    private var lastAlignmentToolLabel: String = ""
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingUsbRetryRunnable: Runnable? = null

    private val camPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] == true) startCamera()
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (cameraSource != CameraSource.USB || simulate) return
            if (intent?.action != usbPermissionAction) return

            val device = getUsbDeviceExtra(intent)
            val expected = pendingUsbDeviceName
            usbPermissionRequestInFlight = false
            if (expected != null && device?.deviceName != expected) {
                pendingUsbDeviceName = null
                Log.w(
                    TAG,
                    "USB permission callback device changed. expected=$expected actual=${device?.deviceName}"
                )
                b.root.postDelayed({
                    if (cameraSource == CameraSource.USB && !simulate) {
                        startUsbCamera()
                    }
                }, USB_RECONNECT_DELAY_MS)
                return
            }
            pendingUsbDeviceName = null

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted) {
                Log.w(TAG, "USB permission denied; scheduling retry")
                Toast.makeText(
                    this@MainActivity,
                    "USB permission denied. Retrying...",
                    Toast.LENGTH_LONG
                ).show()
                scheduleUsbFragmentRetry("permission denied")
                return
            }

            val opened = openPermittedUsbCamera(preferredDevice = device)
            if (!opened) {
                Log.w(TAG, "USB permission granted but no permitted UVC device was immediately available")
                b.root.postDelayed({
                    if (cameraSource == CameraSource.USB && !simulate) {
                        startUsbCamera()
                    }
                }, USB_RECONNECT_DELAY_MS)
            }
        }
    }

    private val usbMonitorRecoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AxisightApp.ACTION_USB_MONITOR_RACE) return
            if (cameraSource != CameraSource.USB || simulate) return
            if (usbPermissionRequestInFlight) return

            stopUsbCamera()
            b.root.postDelayed({
                if (cameraSource == CameraSource.USB && !simulate) {
                    startUsbCamera()
                }
            }, 500L)
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (cameraSource != CameraSource.USB || simulate) return
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    b.root.postDelayed({
                        if (cameraSource == CameraSource.USB && !simulate) {
                            startUsbCamera()
                        }
                    }, USB_RECONNECT_DELAY_MS)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    stopUsbCamera()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        val density = resources.displayMetrics.density
        targetTapSlopPx = 18f * density
        targetEdgeTolerancePx = 24f * density
        registerUsbMonitorRecoveryReceiverIfNeeded()
        registerUsbAttachReceiverIfNeeded()

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
                    cameraSource = CameraSource.INTERNAL
                    b.wifiGroup.visibility = View.GONE
                    stopWifiCamera()
                    stopUsbCamera()
                    startCamera()
                }
                R.id.rbWifi -> {
                    cameraSource = CameraSource.WIFI
                    b.wifiGroup.visibility = View.VISIBLE
                    stopCamera()
                    stopUsbCamera()
                }
                R.id.rbUsb -> {
                    cameraSource = CameraSource.USB
                    b.wifiGroup.visibility = View.GONE
                    stopCamera()
                    stopWifiCamera()
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
                stopCamera()
                stopWifiCamera()
                stopUsbCamera()
                b.overlay.clearPoints()
                b.overlay.hideSimDot()
                b.previewView.visibility = View.INVISIBLE
                b.textureView.visibility = View.GONE
                b.previewView.post(simTick)
            } else {
                b.previewView.visibility = View.VISIBLE
                b.overlay.hideSimDot()
                b.overlay.clearPoints()
                when (cameraSource) {
                    CameraSource.INTERNAL -> startCamera()
                    CameraSource.WIFI -> {
                        val url = b.edWifiUrl.text.toString()
                        if (url.isNotEmpty()) {
                            startWifiCamera(url)
                        }
                    }
                    CameraSource.USB -> startUsbCamera()
                }
            }
        }
        b.switchAuto.setOnCheckedChangeListener { _, checked -> autoDetect = checked }

        b.btnCal.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add(0, 1, 0, getString(R.string.cal_set_center))
            popup.menu.add(0, 2, 1, getString(R.string.cal_set_scale))
            popup.menu.add(0, 3, 2, getString(R.string.cal_set_rotation))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startCenterCalibration()
                    2 -> startScaleCalibration()
                    3 -> startRotationCalibration()
                }
                true
            }
            popup.show()
        }
        b.btnCalConfirm.setOnClickListener { confirmCalibrationStep() }
        b.btnCalRetry.setOnClickListener { retryCalibrationStep() }
        b.btnCalCancel.setOnClickListener { cancelCalibrationWizard() }
        b.btnCalAutoCenter.setOnClickListener { startAutoCapturingCenter() }
        b.btnCalRotationCapture.setOnClickListener {
            if (isRotationCapturing) finishRotationCapture() else startRotationCapture()
        }
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
                 if (cameraSource == CameraSource.USB) {
                     val frag = supportFragmentManager.findFragmentById(R.id.usbCameraContainer) as? com.etrsystems.axisight.ui.UvcFragment
                     val lockedThr = frag?.lockTarget(true) ?: return@setOnClickListener
                     Toast.makeText(this, "Locked (USB) Thr=$lockedThr", Toast.LENGTH_SHORT).show()
                 } else if (cameraSource == CameraSource.INTERNAL) {
                     lockRequest = true // Signal analyzer to lock on next frame
                 }
            } else {
                // Unlock
                lockRequest = false
                updateCfg { copy(lockedThreshold = null) }
                if (cameraSource == CameraSource.USB) {
                     val frag = supportFragmentManager.findFragmentById(R.id.usbCameraContainer) as? com.etrsystems.axisight.ui.UvcFragment
                     frag?.lockTarget(false)
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
            val frag = supportFragmentManager.findFragmentById(R.id.usbCameraContainer) as? com.etrsystems.axisight.ui.UvcFragment
            frag?.setTargetCircle(tx, ty, radius)
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
        updateCalibrationPanel()

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
            knownInches = v.text?.toString()?.toDoubleOrNull() ?: DEFAULT_KNOWN_INCHES
            true
        }

        b.overlay.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    val x = ev.x
                    val y = ev.y
                    if (!handleCalibrationTap(x, y)) {
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
                    if (calibrationStep == CalibrationStep.NONE && isResizingTarget) {
                        val radius = hypot((ev.x - b.overlay.targetX).toDouble(), (ev.y - b.overlay.targetY).toDouble()).toFloat()
                        b.overlay.setTargetRadius(radius)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (calibrationStep == CalibrationStep.NONE && !isResizingTarget) {
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

        if (!simulate) ensureCameraPermission { startCamera() }
    }

    private fun startCenterCalibration() {
        isCenterOnlyMode = true
        resetRotationCaptureState()
        calibrationStep = CalibrationStep.CENTER
        calCenter = null
        calUpPoint = null
        calScaleP1 = null
        calScaleP2 = null
        pendingCalTap = null
        updateCalibrationPanel()
    }

    private fun startScaleCalibration() {
        // Require a center to be set first
        val center = calibrationData?.let { it.centerX to it.centerY }
            ?: calibrationStore.loadCenterOrNull()
        if (center == null) {
            Toast.makeText(this, getString(R.string.cal_scale_needs_center), Toast.LENGTH_LONG).show()
            return
        }
        isCenterOnlyMode = false
        resetRotationCaptureState()
        calCenter = center
        calUpPoint = null
        calScaleP1 = null
        calScaleP2 = null
        pendingCalTap = null
        calibrationStep = CalibrationStep.UP
        updateCalibrationPanel()
    }

    private fun startRotationCalibration() {
        isCenterOnlyMode = true
        isRotationMode = true
        resetRotationCaptureState()
        calibrationStep = CalibrationStep.CENTER
        calCenter = null
        calUpPoint = null
        calScaleP1 = null
        calScaleP2 = null
        pendingCalTap = null
        updateCalibrationPanel()
    }

    private fun resetRotationCaptureState() {
        isRotationMode = false
        isRotationCapturing = false
        rotationSamples.clear()
        rotationCoverageBuckets.fill(false)
        rotationSumX = 0.0
        rotationSumY = 0.0
        lastRotationFitQuality = null
    }

    private fun startRotationCapture() {
        isRotationCapturing = true
        rotationSamples.clear()
        rotationCoverageBuckets.fill(false)
        rotationSumX = 0.0
        rotationSumY = 0.0
        lastRotationFitQuality = null
        pendingCalTap = null
        updateCalibrationPanel()
    }

    private fun finishRotationCapture() {
        isRotationCapturing = false
        val quality = evaluateRotationFit(rotationSamples)
        lastRotationFitQuality = quality
        pendingCalTap = if (quality != null && quality.acceptable) {
            quality.centerX to quality.centerY
        } else {
            Toast.makeText(this, getString(R.string.calibration_rotation_quality_fail), Toast.LENGTH_LONG).show()
            null
        }
        updateCalibrationPanel()
    }

    private fun evaluateRotationFit(samples: List<Pair<Float, Float>>): RotationFitQuality? {
        if (samples.size < MIN_ROTATION_SAMPLES) {
            return RotationFitQuality(0f, 0f, 0.0, 0.0, samples.size, acceptable = false)
        }
        val fit = CircleFit.fit(samples.map { it.first.toDouble() to it.second.toDouble() })
            ?: return RotationFitQuality(0f, 0f, 0.0, 0.0, samples.size, acceptable = false)
        val coverage = rotationCoverageFraction(samples, fit.cx, fit.cy)
        val acceptable = coverage >= MIN_ROTATION_COVERAGE_FRACTION && fit.rms <= MAX_ROTATION_FIT_RMS_PX
        return RotationFitQuality(
            centerX = fit.cx.toFloat(),
            centerY = fit.cy.toFloat(),
            rms = fit.rms,
            coverageFraction = coverage,
            sampleCount = samples.size,
            acceptable = acceptable
        )
    }

    private fun rotationCoverageFraction(samples: List<Pair<Float, Float>>, cx: Double, cy: Double): Double {
        if (samples.isEmpty()) return 0.0
        val visited = BooleanArray(ROTATION_COVERAGE_BUCKETS)
        val bucketSizeDeg = 360.0 / ROTATION_COVERAGE_BUCKETS
        for ((x, y) in samples) {
            val deg = (Math.toDegrees(atan2(y - cy, x - cx)) + 360.0) % 360.0
            val idx = (deg / bucketSizeDeg).toInt().coerceIn(0, ROTATION_COVERAGE_BUCKETS - 1)
            visited[idx] = true
        }
        return visited.count { it }.toDouble() / ROTATION_COVERAGE_BUCKETS
    }

    private data class RotationFitQuality(
        val centerX: Float,
        val centerY: Float,
        val rms: Double,
        val coverageFraction: Double,
        val sampleCount: Int,
        val acceptable: Boolean
    )

    private fun handleCalibrationTap(x: Float, y: Float): Boolean {
        if (calibrationStep == CalibrationStep.NONE) return false
        pendingCalTap = x to y
        updateCalibrationPanel()
        return true
    }

    private fun confirmCalibrationStep() {
        if (calibrationStep == CalibrationStep.NONE) return
        val tap = pendingCalTap ?: return
        pendingCalTap = null

        when (calibrationStep) {
            CalibrationStep.NONE -> Unit
            CalibrationStep.CENTER -> {
                calCenter = tap
                if (isCenterOnlyMode) {
                    // Save center only — crosshairs move, scale/direction unchanged.
                    // Persist in image space (via CoordinateMapper) so calibration survives
                    // resolution/orientation changes; the overlay crosshair still uses the
                    // original view-space tap since it draws on screen.
                    val (imgX, imgY) = coordinateMapper.viewToImage(tap.first, tap.second)
                    calibrationStore.saveCenter(imgX, imgY)
                    b.overlay.setTrueCenter(tap.first, tap.second)
                    val existing = calibrationData
                    if (existing != null) {
                        val updated = existing.copy(centerX = imgX, centerY = imgY)
                        calibrationData = updated
                        calibrationStore.save(updated)
                        updateDeltaReadout()
                    }
                    cancelCalibrationWizard()
                    Toast.makeText(this, getString(R.string.cal_center_saved), Toast.LENGTH_SHORT).show()
                    return
                }
                calibrationStep = CalibrationStep.UP
            }
            CalibrationStep.UP -> {
                val center = calCenter
                if (center == null) {
                    calibrationStep = CalibrationStep.CENTER
                    Toast.makeText(this, getString(R.string.calibration_failed), Toast.LENGTH_SHORT).show()
                    updateCalibrationPanel()
                    return
                }
                val upDist = hypot((tap.first - center.first).toDouble(), (tap.second - center.second).toDouble())
                if (upDist < MIN_UP_DISTANCE_PX) {
                    pendingCalTap = tap
                    Toast.makeText(this, getString(R.string.calibration_quality_fail), Toast.LENGTH_SHORT).show()
                    updateCalibrationPanel()
                    return
                }
                calUpPoint = tap
                calibrationStep = CalibrationStep.SCALE_P1
            }
            CalibrationStep.SCALE_P1 -> {
                calScaleP1 = tap
                calibrationStep = CalibrationStep.SCALE_P2
            }
            CalibrationStep.SCALE_P2 -> {
                calScaleP2 = tap
                tryFinalizeCalibration()
            }
        }
        updateCalibrationPanel()
    }

    private fun retryCalibrationStep() {
        if (calibrationStep == CalibrationStep.NONE) return
        isAutoCapturingCenter = false
        autoSamples.clear()
        pendingCalTap = null
        when (calibrationStep) {
            CalibrationStep.CENTER -> {
                calCenter = null
                isRotationCapturing = false
                rotationSamples.clear()
                rotationCoverageBuckets.fill(false)
                rotationSumX = 0.0
                rotationSumY = 0.0
                lastRotationFitQuality = null
            }
            CalibrationStep.UP -> calUpPoint = null
            CalibrationStep.SCALE_P1 -> calScaleP1 = null
            CalibrationStep.SCALE_P2 -> calScaleP2 = null
            CalibrationStep.NONE -> Unit
        }
        updateCalibrationPanel()
    }

    private fun cancelCalibrationWizard() {
        isAutoCapturingCenter = false
        autoSamples.clear()
        isCenterOnlyMode = false
        resetRotationCaptureState()
        calibrationStep = CalibrationStep.NONE
        pendingCalTap = null
        calCenter = null
        calUpPoint = null
        calScaleP1 = null
        calScaleP2 = null
        updateCalibrationPanel()
    }

    private fun tryFinalizeCalibration() {
        val center = calCenter
        val upPoint = calUpPoint
        val scaleP1 = calScaleP1
        val scaleP2 = calScaleP2
        if (center == null || upPoint == null || scaleP1 == null || scaleP2 == null) {
            Toast.makeText(this, getString(R.string.calibration_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val typedKnownInches = b.edKnownMm.text?.toString()?.trim()?.toDoubleOrNull()
        if (typedKnownInches != null && typedKnownInches > 0.0) {
            knownInches = typedKnownInches
        }
        if (knownInches <= 0.0) {
            Toast.makeText(this, getString(R.string.calibration_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val quality = evaluateCalibrationQuality(center, upPoint, scaleP1, scaleP2, knownInches)
        if (!quality.acceptable) {
            b.txtCalQuality.text = getString(R.string.calibration_quality_fail)
            Toast.makeText(this, getString(R.string.calibration_quality_fail), Toast.LENGTH_LONG).show()
            return
        }

        // Persist in image space (via CoordinateMapper) so calibration survives
        // resolution/orientation changes; quality gating above stays in view-space
        // since it's modeling tap-finger precision, which is a screen-space concept.
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
            Toast.makeText(this, getString(R.string.calibration_failed), Toast.LENGTH_SHORT).show()
            return
        }

        calibrationData = data
        inPerPx = quality.inchesPerPixel // view-space, for the on-screen in/px display
        b.overlay.mmPerPx = inPerPx?.times(25.4)
        b.edMmPerPx.setText(String.format(Locale.US, "%.6f", quality.inchesPerPixel))
        calibrationStore.save(data)
        b.overlay.setTrueCenter(center.first, center.second)
        calibrationStep = CalibrationStep.NONE
        pendingCalTap = null
        updateCalibrationPanel()
        updateParamsSummary()
        updateDeltaReadout()
        Toast.makeText(this, getString(R.string.calibration_complete), Toast.LENGTH_SHORT).show()
    }

    private fun evaluateCalibrationQuality(
        center: Pair<Float, Float>,
        upPoint: Pair<Float, Float>,
        scaleP1: Pair<Float, Float>,
        scaleP2: Pair<Float, Float>,
        knownInches: Double
    ): CalibrationQuality {
        val upDistancePx = hypot((upPoint.first - center.first).toDouble(), (upPoint.second - center.second).toDouble())
        val scaleDistancePx = hypot((scaleP2.first - scaleP1.first).toDouble(), (scaleP2.second - scaleP1.second).toDouble())
        val safeScalePx = scaleDistancePx.coerceAtLeast(1.0)
        val inchesPerPixel = knownInches / safeScalePx

        // Endpoint quantization estimate assuming +/-1px around each tap.
        val scaleErrorIn = knownInches * (sqrt(2.0) / safeScalePx)
        // Angular uncertainty estimate from 1px tap uncertainty on up vector.
        val angularErrorRad = atan2(1.0, upDistancePx.coerceAtLeast(1.0))
        val axisErrorAtOneIn = tan(angularErrorRad)
        val combinedErrorIn = hypot(scaleErrorIn, axisErrorAtOneIn)

        val acceptable = upDistancePx >= MIN_UP_DISTANCE_PX &&
            scaleDistancePx >= MIN_SCALE_DISTANCE_PX &&
            combinedErrorIn <= MAX_COMBINED_ERROR_IN

        return CalibrationQuality(
            upDistancePx = upDistancePx,
            scaleDistancePx = scaleDistancePx,
            inchesPerPixel = inchesPerPixel,
            combinedErrorIn = combinedErrorIn,
            acceptable = acceptable
        )
    }

    private fun updateCalibrationPanel() {
        val active = calibrationStep != CalibrationStep.NONE
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

        val stepText = when (calibrationStep) {
            CalibrationStep.NONE -> ""
            CalibrationStep.CENTER -> if (isRotationMode) getString(R.string.calibration_step_rotation) else getString(R.string.calibration_step_center)
            CalibrationStep.UP -> getString(R.string.calibration_step_scale_up)
            CalibrationStep.SCALE_P1 -> getString(R.string.calibration_step_scale_1)
            CalibrationStep.SCALE_P2 -> getString(R.string.calibration_step_scale_2)
        }
        b.txtCalStep.text = stepText

        b.txtCalCapture.text = when {
            calibrationStep == CalibrationStep.CENTER && isRotationMode && isRotationCapturing ->
                b.txtCalCapture.text // left as-is; updated live per-frame in onToolPointDetected
            calibrationStep == CalibrationStep.CENTER && isRotationMode && pendingCalTap != null -> {
                val (x, y) = pendingCalTap!!
                getString(R.string.calibration_point_captured, x, y)
            }
            calibrationStep == CalibrationStep.CENTER && isRotationMode -> getString(R.string.calibration_rotation_idle)
            else -> pendingCalTap?.let { (x, y) -> getString(R.string.calibration_point_captured, x, y) }
                ?: getString(R.string.calibration_step_wait_tap)
        }

        val qualityText = when {
            calibrationStep == CalibrationStep.CENTER && isRotationMode && lastRotationFitQuality != null -> {
                val q = lastRotationFitQuality!!
                getString(
                    R.string.calibration_rotation_quality_value,
                    q.sampleCount,
                    q.coverageFraction * 100.0,
                    q.rms
                )
            }
            calibrationStep == CalibrationStep.SCALE_P2 && calCenter != null && calUpPoint != null && calScaleP1 != null && pendingCalTap != null -> {
                val typedKnownInches = b.edKnownMm.text?.toString()?.trim()?.toDoubleOrNull()
                val useKnown = if (typedKnownInches != null && typedKnownInches > 0.0) typedKnownInches else knownInches
                val q = evaluateCalibrationQuality(
                    center = calCenter!!,
                    upPoint = calUpPoint!!,
                    scaleP1 = calScaleP1!!,
                    scaleP2 = pendingCalTap!!,
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
        b.btnCalConfirm.isEnabled = pendingCalTap != null && (!isRotationMode || lastRotationFitQuality?.acceptable == true)
        val showAutoBtn = calibrationStep == CalibrationStep.CENTER && isCenterOnlyMode && !isRotationMode
        b.btnCalAutoCenter.visibility = if (showAutoBtn) View.VISIBLE else View.GONE
        if (showAutoBtn) b.btnCalAutoCenter.isEnabled = !isAutoCapturingCenter
        val showRotationBtn = calibrationStep == CalibrationStep.CENTER && isRotationMode
        b.btnCalRotationCapture.visibility = if (showRotationBtn) View.VISIBLE else View.GONE
        if (showRotationBtn) {
            b.btnCalRotationCapture.isEnabled = true
            b.btnCalRotationCapture.text = if (isRotationCapturing) {
                getString(R.string.calibration_rotation_finish)
            } else {
                getString(R.string.calibration_rotation_start)
            }
        }
        b.overlay.setCalibrationMarkers(
            center = calCenter,
            up = calUpPoint,
            scaleP1 = calScaleP1,
            scaleP2 = calScaleP2,
            pending = pendingCalTap
        )
    }

    private data class CalibrationQuality(
        val upDistancePx: Double,
        val scaleDistancePx: Double,
        val inchesPerPixel: Double,
        val combinedErrorIn: Double,
        val acceptable: Boolean
    )

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
        if (isRotationCapturing) {
            rotationSamples.add(x to y)
            rotationSumX += x
            rotationSumY += y
            if (rotationSamples.size >= 3) {
                val meanX = rotationSumX / rotationSamples.size
                val meanY = rotationSumY / rotationSamples.size
                val bucketSizeDeg = 360.0 / ROTATION_COVERAGE_BUCKETS
                val deg = (Math.toDegrees(atan2(y - meanY, x - meanX)) + 360.0) % 360.0
                val idx = (deg / bucketSizeDeg).toInt().coerceIn(0, ROTATION_COVERAGE_BUCKETS - 1)
                rotationCoverageBuckets[idx] = true
            }
            val coveragePct = (rotationCoverageBuckets.count { it }.toDouble() / ROTATION_COVERAGE_BUCKETS * 100).roundToInt()
            b.txtCalCapture.text = getString(R.string.calibration_rotation_collecting, rotationSamples.size, coveragePct)
            if (rotationSamples.size >= MAX_ROTATION_SAMPLES) finishRotationCapture()
            return
        }
        if (isAutoCapturingCenter) {
            autoSamples.add(x to y)
            b.txtCalCapture.text = getString(R.string.calibration_auto_collecting, autoSamples.size, AUTO_SAMPLE_TARGET)
            if (autoSamples.size >= AUTO_SAMPLE_TARGET) finishAutoCapture()
            return
        }
        updateDeltaReadout()
        updateQualityReadout()
    }

    private fun startAutoCapturingCenter() {
        if (latestToolPoint == null) {
            Toast.makeText(this, getString(R.string.calibration_auto_no_tool), Toast.LENGTH_SHORT).show()
            return
        }
        autoSamples.clear()
        isAutoCapturingCenter = true
        b.btnCalAutoCenter.isEnabled = false
        b.txtCalCapture.text = getString(R.string.calibration_auto_collecting, 0, AUTO_SAMPLE_TARGET)
    }

    private fun finishAutoCapture() {
        isAutoCapturingCenter = false
        val avgX = autoSamples.map { it.first }.average().toFloat()
        val avgY = autoSamples.map { it.second }.average().toFloat()
        autoSamples.clear()
        pendingCalTap = avgX to avgY
        updateCalibrationPanel()
        updateDeltaReadout()
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

    /** USB frames are detected in [com.etrsystems.axisight.ui.UvcFragment]'s own detector
     *  config, independent of [cfg] — push tuning-slider changes there too when USB is live. */
    private fun syncUsbDetectorTuning() {
        val frag = supportFragmentManager.findFragmentById(R.id.usbCameraContainer) as? com.etrsystems.axisight.ui.UvcFragment
        frag?.setDetectorTuning(cfg.minCircularity, cfg.kStd, cfg.minContrastRatio)
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
                        cameraSource = cameraSource.name,
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
        stopCamera()
        stopWifiCamera()
        stopUsbCamera()
        unregisterUsbPermissionReceiverIfNeeded()
        unregisterUsbMonitorRecoveryReceiverIfNeeded()
        unregisterUsbAttachReceiverIfNeeded()
        pendingUsbRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        analysisExecutor.shutdown()
    }

    private fun ensureCameraPermission(onGranted: () -> Unit) {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cam) onGranted()
        else camPerm.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun startCamera() {
        b.previewView.visibility = View.VISIBLE
        b.textureView.visibility = View.GONE
        try {
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                try {
                    cameraProvider = providerFuture.get()
                    bindUseCases()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Camera initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
                    android.util.Log.e("MainActivity", "Failed to get camera provider", e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Toast.makeText(this, "Camera setup error: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("MainActivity", "startCamera failed", e)
        }
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        imageAnalyzer = null
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(b.previewView.surfaceProvider)
            }

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { image ->
                        try {
                            if (autoDetect && !simulate && cameraSource == CameraSource.INTERNAL) {
                                // Manual Lock Request
                                if (lockRequest) {
                                    lockRequest = false
                                    // Sample center pixel from ImageProxy (YUV_420_888)
                                    // Y plane is first. Use duplicate() to avoid mutating the
                                    // shared buffer position before passing to BlobDetector.
                                    val yPlane = image.planes[0].buffer.duplicate()
                                    val w = image.width
                                    val h = image.height
                                    val cx = w / 2
                                    val cy = h / 2
                                    val rowStride = image.planes[0].rowStride
                                    val pixelStride = image.planes[0].pixelStride
                                    val offset = (cy * rowStride) + (cx * pixelStride)
                                    if (offset < yPlane.remaining()) {
                                        yPlane.position(offset)
                                        val lum = yPlane.get().toInt() and 0xFF
                                        val margin = 30
                                        val thr = (lum + margin).coerceIn(0, 255)
                                        updateCfg { copy(lockedThreshold = thr) }
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "Locked (Int) Thr=$thr", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }

                                val snapshot = cfg
                                val raw = BlobDetector.detectDarkDotCenter(image, snapshot)
                                val result = detectionFilter.filter(raw, snapshot)
                                if (result is DetectionResult.Success && trackingEnabled) {
                                    val previewW = b.previewView.width
                                    val previewH = b.previewView.height
                                    coordinateMapper.update(
                                        image.width, image.height,
                                        image.imageInfo.rotationDegrees,
                                        previewW, previewH
                                    )
                                    val (vx, vy) = coordinateMapper.imageToView(result.x, result.y)
                                    val quality = DetectionQuality(result.circularity, result.contrastRatio, result.sharpness)
                                    runOnUiThread { onToolPointDetected(vx, vy, quality) }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Image analysis failed", e)
                        } finally {
                            image.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera binding failed: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("MainActivity", "bindUseCases failed", e)
        }
    }

    @UnstableApi
    private fun startWifiCamera(url: String) {
        try {
            // Validate URL format
            if (!isValidRtspUrl(url)) {
                Toast.makeText(this, "Invalid RTSP URL. Expected format: rtsp://...", Toast.LENGTH_LONG).show()
                return
            }

            b.previewView.visibility = View.GONE
            b.textureView.visibility = View.VISIBLE
            b.textureView.surfaceTextureListener = this

            stopWifiCamera() // Clean up any existing player

            exoPlayer = ExoPlayer.Builder(this).build()
            b.textureView.surfaceTexture?.let {
                exoPlayer?.setVideoSurface(Surface(it))
            }

            val mediaSource = RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(url))
            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            exoPlayer?.play()
        } catch (e: Exception) {
            Toast.makeText(this, "WiFi camera error: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("MainActivity", "WiFi camera start failed", e)
            stopWifiCamera()
        }
    }

    /**
     * Validates RTSP URL format to prevent injection/malformed URLs
     */
    private fun isValidRtspUrl(url: String): Boolean {
        return try {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: return false
            scheme in listOf("rtsp", "rtsps") && uri.host != null
        } catch (e: Exception) {
            false
        }
    }

    private fun stopWifiCamera() {
        try {
            exoPlayer?.run {
                clearVideoSurface()
                stop()
                release()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping WiFi camera", e)
        } finally {
            exoPlayer = null
        }
    }

    private fun startUsbCamera() {
        try {
            b.previewView.visibility = View.GONE
            b.textureView.visibility = View.GONE
            b.usbCameraContainer.visibility = View.VISIBLE

            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val uvcDevice = usbManager.deviceList.values.firstOrNull { isUvcDevice(it) }
            if (uvcDevice == null) {
                b.usbCameraContainer.visibility = View.GONE
                Toast.makeText(this, "No USB camera detected.", Toast.LENGTH_LONG).show()
                return
            }

            if (!usbManager.hasPermission(uvcDevice)) {
                if (usbPermissionRequestInFlight) {
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastUsbPermissionRequestMs < USB_PERMISSION_RETRY_COOLDOWN_MS) {
                    return
                }
                b.usbCameraContainer.visibility = View.GONE
                usbPermissionRequestInFlight = true
                lastUsbPermissionRequestMs = now
                requestUsbPermission(usbManager, uvcDevice)
                Toast.makeText(this, "Grant USB permission to continue.", Toast.LENGTH_SHORT).show()
                return
            }

            usbPermissionRequestInFlight = false
            showUsbCameraFragment(uvcDevice)
        } catch (e: Exception) {
            usbPermissionRequestInFlight = false
            Toast.makeText(this, "USB camera error: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("MainActivity", "USB camera start failed", e)
            stopUsbCamera()
        }
    }

    private fun openPermittedUsbCamera(preferredDevice: UsbDevice?): Boolean {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val chosen = sequenceOf(preferredDevice)
            .filterNotNull()
            .firstOrNull { isUvcDevice(it) && usbManager.hasPermission(it) }
            ?: usbManager.deviceList.values.firstOrNull { isUvcDevice(it) && usbManager.hasPermission(it) }

        if (chosen == null) {
            return false
        }
        showUsbCameraFragment(chosen)
        return true
    }

    private fun showUsbCameraFragment(device: UsbDevice) {
        b.previewView.visibility = View.GONE
        b.textureView.visibility = View.GONE
        b.usbCameraContainer.visibility = View.VISIBLE

        val uvcFragment = com.etrsystems.axisight.ui.UvcFragment.newInstance(
            vendorId = device.vendorId,
            productId = device.productId,
            deviceName = device.deviceName
        )
        uvcFragment.setTargetCircle(
            x = b.overlay.targetX,
            y = b.overlay.targetY,
            radiusPx = b.overlay.targetRadiusPx
        )
        uvcFragment.setDetectorTuning(cfg.minCircularity, cfg.kStd, cfg.minContrastRatio)
        uvcFragment.setDetectionCallback(object : com.etrsystems.axisight.ui.UvcFragment.DetectionCallback {
            override fun onPointDetected(x: Float, y: Float, circularity: Double, contrastRatio: Double, sharpness: Double) {
                if (autoDetect && !simulate && trackingEnabled) {
                    onToolPointDetected(x, y, DetectionQuality(circularity, contrastRatio, sharpness))
                }
            }
        })
        usbFragmentRetryCount = 0
        uvcFragment.setCameraStateListener(object : com.etrsystems.axisight.ui.UvcFragment.CameraStateListener {
            override fun onUsbCameraOpened() {
                usbFragmentRetryCount = 0
                Log.i(TAG, "USB camera opened in MainActivity")
            }
            override fun onUsbCameraClosed() {
                Log.w(TAG, "USB camera closed in MainActivity (retry $usbFragmentRetryCount)")
                scheduleUsbFragmentRetry("camera closed")
            }
            override fun onUsbCameraError(message: String?) {
                Log.e(TAG, "USB camera error in MainActivity: $message (retry $usbFragmentRetryCount)")
                scheduleUsbFragmentRetry("camera error: $message")
            }
        })

        supportFragmentManager.beginTransaction()
            .replace(R.id.usbCameraContainer, uvcFragment)
            .commit()
    }

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        registerUsbPermissionReceiverIfNeeded()
        pendingUsbDeviceName = device.deviceName

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            1001,
            Intent(usbPermissionAction).setPackage(packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun registerUsbPermissionReceiverIfNeeded() {
        if (usbPermissionReceiverRegistered) return
        val filter = IntentFilter(usbPermissionAction)
        ContextCompat.registerReceiver(
            this, usbPermissionReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        usbPermissionReceiverRegistered = true
    }

    private fun registerUsbMonitorRecoveryReceiverIfNeeded() {
        if (usbRecoveryReceiverRegistered) return
        val filter = IntentFilter(AxisightApp.ACTION_USB_MONITOR_RACE)
        androidx.core.content.ContextCompat.registerReceiver(
            this, usbMonitorRecoveryReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        usbRecoveryReceiverRegistered = true
    }

    private fun registerUsbAttachReceiverIfNeeded() {
        if (usbAttachReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this, usbAttachReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        usbAttachReceiverRegistered = true
    }

    private fun unregisterUsbPermissionReceiverIfNeeded() {
        if (!usbPermissionReceiverRegistered) return
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered by lifecycle races.
        } finally {
            usbPermissionReceiverRegistered = false
            pendingUsbDeviceName = null
        }
    }

    private fun unregisterUsbMonitorRecoveryReceiverIfNeeded() {
        if (!usbRecoveryReceiverRegistered) return
        try {
            unregisterReceiver(usbMonitorRecoveryReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered by lifecycle races.
        } finally {
            usbRecoveryReceiverRegistered = false
        }
    }

    private fun unregisterUsbAttachReceiverIfNeeded() {
        if (!usbAttachReceiverRegistered) return
        try {
            unregisterReceiver(usbAttachReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered by lifecycle races.
        } finally {
            usbAttachReceiverRegistered = false
        }
    }

    private fun getUsbDeviceExtra(intent: Intent): UsbDevice? =
        UsbDeviceUtils.getUsbDeviceExtra(intent)

    private fun isUvcDevice(device: UsbDevice): Boolean =
        UsbDeviceUtils.isUvcDevice(device)

    private fun stopUsbCamera() {
        try {
            usbPermissionRequestInFlight = false
            pendingUsbDeviceName = null
            b.usbCameraContainer.visibility = View.GONE
            val currentFrag = supportFragmentManager.findFragmentById(R.id.usbCameraContainer)
            if (currentFrag != null) {
                 supportFragmentManager.beginTransaction()
                    .remove(currentFrag)
                    .commitAllowingStateLoss()
            }
            if (cameraSource == CameraSource.INTERNAL) {
                 b.previewView.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping USB camera", e)
        }
    }


    private fun scheduleUsbFragmentRetry(reason: String) {
        if (isFinishing || isDestroyed) return
        if (cameraSource != CameraSource.USB || simulate) return
        if (usbFragmentRetryCount >= MAX_USB_FRAGMENT_RETRIES) {
            Log.e(TAG, "USB fragment retries exhausted in MainActivity. reason=$reason")
            usbFragmentRetryCount = 0
            com.google.android.material.snackbar.Snackbar.make(
                b.root,
                "USB camera could not reconnect. Replug it, or tap Retry.",
                com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
            ).setAction("Retry") {
                if (cameraSource == CameraSource.USB && !simulate) startUsbCamera()
            }.show()
            return
        }
        usbFragmentRetryCount++
        val delay = (USB_RECONNECT_DELAY_MS * usbFragmentRetryCount).coerceAtMost(4000L)
        val runnable = Runnable {
            pendingUsbRetryRunnable = null
            if (cameraSource == CameraSource.USB && !simulate && !isFinishing && !isDestroyed) {
                startUsbCamera()
            }
        }
        pendingUsbRetryRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
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

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        exoPlayer?.setVideoSurface(Surface(surface))
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        exoPlayer?.clearVideoSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        try {
            if (cameraSource == CameraSource.WIFI && autoDetect) {
                val bmp = b.textureView.bitmap ?: return
                try {
                    // ExoPlayer renders to textureView without additional rotation
                    coordinateMapper.update(bmp.width, bmp.height, 0, b.textureView.width, b.textureView.height)
                    val snapshot = cfg
                    val raw = BlobDetector.detectDarkDotCenter(bmp, snapshot)
                    val result = detectionFilter.filter(raw, snapshot)
                    if (result is DetectionResult.Success && trackingEnabled) {
                        val (vx, vy) = coordinateMapper.imageToView(result.x, result.y)
                        val quality = DetectionQuality(result.circularity, result.contrastRatio, result.sharpness)
                        onToolPointDetected(vx, vy, quality)
                    }
                } finally {
                    bmp.recycle()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Texture frame analysis failed", e)
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
        private const val TAG = "MainActivity"
        private const val USB_RECONNECT_DELAY_MS = 600L
        private const val USB_PERMISSION_RETRY_COOLDOWN_MS = 1500L
        private const val MAX_USB_FRAGMENT_RETRIES = 6
        private const val MIN_UP_DISTANCE_PX = 40.0
        private const val MIN_SCALE_DISTANCE_PX = 80.0
        private const val MAX_COMBINED_ERROR_IN = 0.020
        const val DEFAULT_KNOWN_INCHES = 0.1181  // Standard 3mm reference (0.015" bore use-case)
        private const val ROTATION_COVERAGE_BUCKETS = 24
        private const val MIN_ROTATION_SAMPLES = 40
        private const val MAX_ROTATION_SAMPLES = 600
        private const val MIN_ROTATION_COVERAGE_FRACTION = 0.75 // ~270 degrees of the trace
        private const val MAX_ROTATION_FIT_RMS_PX = 3.0

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
