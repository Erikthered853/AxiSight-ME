// Kotlin
package com.etrsystems.axisight.ui

import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.etrsystems.axisight.BlobDetector
import com.etrsystems.axisight.DetectorConfig
import com.etrsystems.axisight.DetectionResult
import com.etrsystems.axisight.R
import com.etrsystems.axisight.usb.UsbDeviceUtils
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.IAspectRatio

class UvcFragment : CameraFragment() {

    interface DetectionCallback {
        fun onPointDetected(x: Float, y: Float)
    }

    interface CameraStateListener {
        fun onUsbCameraOpened()
        fun onUsbCameraClosed()
        fun onUsbCameraError(message: String?)
    }

    private var textureView: SafeAspectRatioTextureView? = null
    private var callback: DetectionCallback? = null
    private var cameraStateListener: CameraStateListener? = null
    private var targetVendorId: Int = -1
    private var targetProductId: Int = -1
    private var targetDeviceName: String? = null

    // Default to VGA (640x480) for broad USB camera compatibility.
    private var previewWidth: Int = 640
    private var previewHeight: Int = 480

    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    @Volatile private var isProcessing = false

    // frameBitmap is accessed from both main thread (recycle) and processing thread (read/write).
    // All access must hold frameBitmapLock.
    private val frameBitmapLock = Any()
    private var frameBitmap: Bitmap? = null

    private val detectorConfigRef = java.util.concurrent.atomic.AtomicReference(DetectorConfig())
    private val detectorConfig get() = detectorConfigRef.get()
    private fun updateDetectorConfig(block: DetectorConfig.() -> DetectorConfig) { detectorConfigRef.set(detectorConfigRef.get().block()) }
    private var targetGlobalX: Float? = null
    private var targetGlobalY: Float? = null
    private var targetRadiusPx: Float = detectorConfig.targetRadiusPx
    // Snapshotted on main thread when setTargetCircle() is called; read on processing thread.
    @Volatile private var textureViewOffsetX: Float = 0f
    @Volatile private var textureViewOffsetY: Float = 0f

    // Frame-stall detection
    @Volatile private var lastFrameTimestampMs: Long = 0L
    @Volatile private var consecutiveNullFrames: Int = 0
    private var stallWatchdog: Runnable? = null
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    fun setDetectionCallback(cb: DetectionCallback) {
        this.callback = cb
    }

    fun setCameraStateListener(listener: CameraStateListener?) {
        cameraStateListener = listener
    }

    fun setTargetCircle(x: Float, y: Float, radiusPx: Float) {
        targetGlobalX = x
        targetGlobalY = y
        targetRadiusPx = radiusPx
        // Snapshot view offsets here on the main thread so applyTargetCircleToDetector()
        // can be called from the processing thread without a data race on View properties.
        textureViewOffsetX = textureView?.x ?: 0f
        textureViewOffsetY = textureView?.y ?: 0f
        applyTargetCircleToDetector()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetVendorId = arguments?.getInt(ARG_VENDOR_ID, -1) ?: -1
        targetProductId = arguments?.getInt(ARG_PRODUCT_ID, -1) ?: -1
        targetDeviceName = arguments?.getString(ARG_DEVICE_NAME)
    }

    /**
     * Toggles target locking.
     * @param active If true, locks onto the center pixel of the current frame. If false, resets lock.
     * @return The locked threshold value if locked, or null.
     */
    @androidx.annotation.MainThread
    fun lockTarget(active: Boolean): Int? {
        if (!active) {
            updateDetectorConfig { copy(lockedThreshold = null) }
            return null
        }

        val bmp = textureView?.bitmap ?: return null
        val w = bmp.width
        val h = bmp.height
        val result = if (w > 0 && h > 0) {
            val cx = w / 2
            val cy = h / 2
            val pixel = bmp.getPixel(cx, cy)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val lum = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            val margin = 30
            val thr = (lum + margin).coerceIn(0, 255)
            updateDetectorConfig { copy(lockedThreshold = thr) }
            Log.i(TAG, "Target LOCKED: centerLum=$lum, thr=$thr")
            thr
        } else {
            null
        }

        bmp.recycle()
        return result
    }

    private fun recycleFrameBitmap() {
        synchronized(frameBitmapLock) {
            frameBitmap?.let { if (!it.isRecycled) it.recycle() }
            frameBitmap = null
        }
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        return try {
            val view = inflater.inflate(R.layout.fragment_uvc, container, false)
            textureView = view.findViewById(R.id.uvc_texture_view)
            textureView?.setAspectRatio(previewWidth, previewHeight)
            applyTargetCircleToDetector()
            Log.d(TAG, "Root view inflated (${previewWidth}x${previewHeight})")
            view
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing camera view", e)
            Toast.makeText(context, "Error initializing USB camera: ${e.message}", Toast.LENGTH_LONG).show()
            FrameLayout(inflater.context)
        }
    }

    override fun getCameraView(): IAspectRatio? = textureView

    override fun getCameraViewContainer(): ViewGroup =
        view as? ViewGroup ?: FrameLayout(requireContext())

    override fun getDefaultCamera(): UsbDevice? {
        val devices = getDeviceList().orEmpty()
        if (devices.isEmpty()) return null

        targetDeviceName?.let { expectedName ->
            devices.firstOrNull { it.deviceName == expectedName }?.let {
                Log.i(TAG, "Using USB camera by deviceName: $expectedName")
                return it
            }
        }

        if (targetVendorId >= 0 && targetProductId >= 0) {
            devices.firstOrNull {
                it.vendorId == targetVendorId &&
                    it.productId == targetProductId &&
                    UsbDeviceUtils.isUvcDevice(it)
            }?.let {
                Log.i(TAG, "Using USB camera by VID/PID: ${it.vendorId}/${it.productId}")
                return it
            }
        }

        return devices.firstOrNull { UsbDeviceUtils.isUvcDevice(it) }?.also {
            Log.w(TAG, "Falling back to first UVC device: ${it.deviceName}")
        } ?: devices.first().also {
            Log.w(TAG, "Falling back to first USB device: ${it.deviceName}")
        }
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(previewWidth)
            .setPreviewHeight(previewHeight)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(false)
            .create()
    }

    override fun onCameraState(
        self: com.jiangdg.ausbc.MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.i(TAG, "Camera opened successfully")
                dispatchOnMain {
                    Toast.makeText(context, "USB Camera Connected", Toast.LENGTH_SHORT).show()
                    cameraStateListener?.onUsbCameraOpened()
                }
                startProcessing()
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.i(TAG, "Camera closed")
                dispatchOnMain {
                    cameraStateListener?.onUsbCameraClosed()
                }
                stopProcessing()
            }
            ICameraStateCallBack.State.ERROR -> {
                Log.e(TAG, "Camera error: $msg")
                dispatchOnMain {
                    Toast.makeText(context, "Camera Error: $msg", Toast.LENGTH_LONG).show()
                    cameraStateListener?.onUsbCameraError(msg)
                }
                stopProcessing()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startProcessingThread()
    }

    override fun onPause() {
        stopProcessing()
        stopProcessingThread()
        cancelStallWatchdog()
        super.onPause()
    }

    override fun onDestroyView() {
        stopProcessing()
        stopProcessingThread()
        cancelStallWatchdog()
        recycleFrameBitmap()
        // Do NOT null out cameraStateListener here — late AUSBC callbacks
        // must still be deliverable to the activity for error recovery.
        textureView = null
        super.onDestroyView()
    }

    /**
     * Dispatches a block on the main thread, but only if the fragment is still
     * attached to its activity. This prevents silently dropped callbacks when
     * the fragment is being torn down.
     */
    private fun dispatchOnMain(block: () -> Unit) {
        val act = activity ?: return
        if (!isAdded || isDetached) return
        act.runOnUiThread {
            // Re-check inside the post since detach can race with posting.
            if (isAdded && !isDetached) {
                block()
            }
        }
    }

    private fun startProcessingThread() {
        if (processingThread == null) {
            processingThread = HandlerThread("FrameProcessingThread")
            processingThread?.start()
            processingHandler = Handler(processingThread!!.looper)
        }
    }

    private fun stopProcessingThread() {
        processingThread?.quitSafely()
        processingThread = null
        processingHandler = null
        // Recycle bitmap after thread has stopped to avoid the race between
        // the processing thread using the bitmap and main thread recycling it.
        recycleFrameBitmap()
    }

    private fun startProcessing() {
        if (isProcessing) return
        isProcessing = true
        consecutiveNullFrames = 0
        lastFrameTimestampMs = SystemClock.elapsedRealtime()
        scheduleStallWatchdog()
        processFrame()
    }

    private fun stopProcessing() {
        isProcessing = false
        processingHandler?.removeCallbacksAndMessages(null)
        cancelStallWatchdog()
    }

    private fun processFrame() {
        if (!isProcessing) return

        processingHandler?.postDelayed({
            if (!isProcessing) return@postDelayed

            try {
                val bmp = obtainFrameBitmap()
                if (bmp != null) {
                    consecutiveNullFrames = 0
                    lastFrameTimestampMs = SystemClock.elapsedRealtime()
                    processBitmap(bmp)
                } else {
                    consecutiveNullFrames++
                    if (consecutiveNullFrames >= MAX_CONSECUTIVE_NULL_FRAMES) {
                        Log.w(TAG, "Frame stream lost: $consecutiveNullFrames consecutive null frames")
                        dispatchOnMain {
                            cameraStateListener?.onUsbCameraError("Preview frame stream lost")
                        }
                        consecutiveNullFrames = 0
                        return@postDelayed
                    }
                    Log.w(TAG, "TextureView bitmap unavailable ($consecutiveNullFrames/$MAX_CONSECUTIVE_NULL_FRAMES)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
            } finally {
                if (isProcessing) processFrame()
            }
        }, 66) // ~15 FPS
    }

    private fun obtainFrameBitmap(): Bitmap? {
        val tv = textureView ?: return null
        val w = tv.width
        val h = tv.height
        if (w <= 0 || h <= 0) return null

        synchronized(frameBitmapLock) {
            val reusable = frameBitmap
            if (reusable == null || reusable.isRecycled || reusable.width != w || reusable.height != h) {
                reusable?.let { if (!it.isRecycled) it.recycle() }
                frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            val target = frameBitmap ?: return null
            return tv.getBitmap(target)
        }
    }

    private fun processBitmap(bmp: Bitmap) {
        try {
            applyTargetCircleToDetector()
            val result = BlobDetector.detectDarkDotCenter(bmp, detectorConfig)
            when (result) {
                is DetectionResult.Success -> {
                    Log.d(TAG, "Blob detected at: ${result.x}, ${result.y}")
                    dispatchOnMain {
                        val viewX = textureView?.x ?: 0f
                        val viewY = textureView?.y ?: 0f
                        callback?.onPointDetected(result.x + viewX, result.y + viewY)
                    }
                }
                is DetectionResult.Failure -> {
                    Log.d(TAG, "No blob: ${result.reason} - ${result.debugInfo}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection error", e)
        }
    }

    private fun scheduleStallWatchdog() {
        cancelStallWatchdog()
        val watchdog = Runnable {
            if (!isProcessing) return@Runnable
            val age = SystemClock.elapsedRealtime() - lastFrameTimestampMs
            if (age >= FRAME_STALL_THRESHOLD_MS) {
                Log.w(TAG, "Frame stall detected: last frame ${age}ms ago")
                dispatchOnMain {
                    cameraStateListener?.onUsbCameraError("Preview stalled (no frames for ${age}ms)")
                }
            } else {
                // Re-arm if still processing and no stall yet
                scheduleStallWatchdog()
            }
        }
        stallWatchdog = watchdog
        mainHandler.postDelayed(watchdog, FRAME_STALL_THRESHOLD_MS)
    }

    private fun cancelStallWatchdog() {
        stallWatchdog?.let { mainHandler.removeCallbacks(it) }
        stallWatchdog = null
    }

    private fun applyTargetCircleToDetector() {
        val x = targetGlobalX ?: return
        val y = targetGlobalY ?: return
        updateDetectorConfig { copy(targetCenterX = x - textureViewOffsetX, targetCenterY = y - textureViewOffsetY, targetRadiusPx = this@UvcFragment.targetRadiusPx) }
    }

    companion object {
        private const val TAG = "UvcFragment"
        private const val ARG_VENDOR_ID = "vendor_id"
        private const val ARG_PRODUCT_ID = "product_id"
        private const val ARG_DEVICE_NAME = "device_name"
        private const val MAX_CONSECUTIVE_NULL_FRAMES = 45  // ~3 seconds at 15fps
        private const val FRAME_STALL_THRESHOLD_MS = 3000L  // 3 seconds

        fun newInstance(vendorId: Int, productId: Int, deviceName: String?): UvcFragment {
            return UvcFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_VENDOR_ID, vendorId)
                    putInt(ARG_PRODUCT_ID, productId)
                    putString(ARG_DEVICE_NAME, deviceName)
                }
            }
        }
    }
}
