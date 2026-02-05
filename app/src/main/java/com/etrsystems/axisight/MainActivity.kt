package com.etrsystems.axisight

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlin.math.*

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var b: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var exoPlayer: ExoPlayer? = null

    private enum class CameraSource { INTERNAL, WIFI, USB }
    private var cameraSource = CameraSource.INTERNAL

    private var simulate = false
    private var autoDetect = true
    private var mmPerPx: Double? = null
    private var knownMm: Double = 10.0

    private val cfg = DetectorConfig()

    private var calMode = false
    private var calP1: Pair<Float, Float>? = null

    private var simAngle = 0.0
    private var simRadiusPx = 200f


    
    // Manual Locking
    private var isLocked = false
    private var lockRequest = false

    private var csvLogger: CsvLogger? = null

    private val camPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] == true) startCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        csvLogger = CsvLogger(this)

        b.rgCameraSource.setOnCheckedChangeListener { _, checkedId ->
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

        b.btnCal.setOnClickListener { calMode = true; calP1 = null }
        b.btnExport.setOnClickListener { csvLogger?.exportOverlay(b.overlay, mmPerPx) }
        
        b.btnLock.setOnClickListener {
            isLocked = !isLocked
            b.btnLock.text = if (isLocked) "Unlock" else "Lock"
            
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
                cfg.lockedThreshold = null
                if (cameraSource == CameraSource.USB) {
                     val frag = supportFragmentManager.findFragmentById(R.id.usbCameraContainer) as? com.etrsystems.axisight.ui.UvcFragment
                     frag?.lockTarget(false)
                }
                Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
            }
        }

        fun updateParamsText() {
            val mmText = mmPerPx?.let { String.format("%.4f", it) } ?: "unset"
            b.txtParams.text = "minA ${cfg.minAreaPx}  maxA ${cfg.maxAreaPx}  circ≥${"%.2f".format(cfg.minCircularity)}  kStd ${"%.2f".format(cfg.kStd)}  mm/px $mmText"
        }
        b.seekMinArea.setOnSeekBarChangeListener(SimpleSeek { p -> cfg.minAreaPx = max(1, p); updateParamsText() })
        b.seekMaxArea.setOnSeekBarChangeListener(SimpleSeek { p -> cfg.maxAreaPx = max(cfg.minAreaPx+1, p); updateParamsText() })
        b.seekCirc.setOnSeekBarChangeListener(SimpleSeek { p -> cfg.minCircularity = (p/100.0).coerceIn(0.0,1.0); updateParamsText() })
        b.seekKstd.setOnSeekBarChangeListener(SimpleSeek { p -> cfg.kStd = p/100.0; updateParamsText() })
        updateParamsText()

        b.edMmPerPx.setOnEditorActionListener { v, _, _ ->
            mmPerPx = v.text?.toString()?.trim()?.toDoubleOrNull()
            b.overlay.mmPerPx = mmPerPx
            updateParamsText()
            true
        }
        b.edKnownMm.setText("10")
        b.edKnownMm.setOnEditorActionListener { v, _, _ ->
            knownMm = v.text?.toString()?.toDoubleOrNull() ?: 10.0
            true
        }

        b.overlay.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                val x = ev.x; val y = ev.y
                if (calMode) {
                    if (calP1 == null) calP1 = x to y
                    else {
                        val p1 = calP1!!
                        val dp = hypot((x - p1.first).toDouble(), (y - p1.second).toDouble())
                        if (dp > 0.0) {
                            mmPerPx = knownMm / dp
                            b.overlay.mmPerPx = mmPerPx
                            updateParamsText()
                        }
                        calP1 = null; calMode = false
                    }
                } else if (!autoDetect || simulate) {
                    b.overlay.addPoint(x, y)
                }
            }
            true
        }

        if (!simulate) ensureCameraPermission { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        stopWifiCamera()
        stopUsbCamera()
    }

    private fun ensureCameraPermission(onGranted: () -> Unit) {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        if (cam && audio) onGranted()
        else camPerm.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
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
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                        try {
                            if (autoDetect && !simulate && cameraSource == CameraSource.INTERNAL) {
                                // Manual Lock Request
                                if (lockRequest) {
                                    lockRequest = false
                                    // Sample center pixel from ImageProxy (YUV_420_888)
                                    // Y plane is first. Center pixel is roughly at offset:
                                    val yPlane = image.planes[0].buffer
                                    val w = image.width
                                    val h = image.height
                                    val cx = w / 2
                                    val cy = h / 2
                                    // RowStride might differ from width
                                    val rowStride = image.planes[0].rowStride
                                    val pixelStride = image.planes[0].pixelStride // usually 1 for Y
                                    
                                    val offset = (cy * rowStride) + (cx * pixelStride)
                                    if (offset < yPlane.remaining()) {
                                        yPlane.position(offset)
                                        val lum = yPlane.get().toInt() and 0xFF
                                        val margin = 30
                                        cfg.lockedThreshold = (lum + margin).coerceIn(0, 255)
                                        runOnUiThread { Toast.makeText(this@MainActivity, "Locked (Int) Thr=${cfg.lockedThreshold}", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                                
                                val result = BlobDetector.detectDarkDotCenter(image, cfg)
                                if (result is DetectionResult.Success) {
                                    b.overlay.addPoint(result.x, result.y)
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
            val usbManager = getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            if (usbManager.deviceList.isEmpty()) {
                Toast.makeText(this, "No USB devices detected. Please check connection.", Toast.LENGTH_LONG).show()
            }
            
            b.previewView.visibility = View.GONE
            b.textureView.visibility = View.GONE
            b.usbCameraContainer.visibility = View.VISIBLE
            
            val currentFrag = supportFragmentManager.findFragmentById(R.id.usbCameraContainer)
            if (currentFrag == null) {
                val uvcFragment = com.etrsystems.axisight.ui.UvcFragment()
                uvcFragment.setDetectionCallback(object : com.etrsystems.axisight.ui.UvcFragment.DetectionCallback {
                    override fun onPointDetected(x: Float, y: Float) {
                        if (autoDetect && !simulate) {
                             b.overlay.addPoint(x, y)
                        }
                    }
                })
                
                supportFragmentManager.beginTransaction()
                    .replace(R.id.usbCameraContainer, uvcFragment)
                    .commit()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "USB camera error: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("MainActivity", "USB camera start failed", e)
            stopUsbCamera()
        }
    }

    private fun stopUsbCamera() {
        try {
            b.usbCameraContainer.visibility = View.GONE
            val currentFrag = supportFragmentManager.findFragmentById(R.id.usbCameraContainer)
            if (currentFrag != null) {
                 supportFragmentManager.beginTransaction()
                    .remove(currentFrag)
                    .commit()
            }
            if (cameraSource == CameraSource.INTERNAL) {
                 b.previewView.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping USB camera", e)
        }
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
            if (autoDetect) b.overlay.addPoint(x, y)
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
                val result = BlobDetector.detectDarkDotCenter(bmp, cfg)
                if (result is DetectionResult.Success) {
                    b.overlay.addPoint(result.x, result.y)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Texture frame analysis failed", e)
        }
    }
}

private class SimpleSeek(val on: (Int) -> Unit) : android.widget.SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) { on(progress) }
    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
}