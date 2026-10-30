// Kotlin
package com.etrsystems.axisight.ui

import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.etrsystems.axisight.R
import com.etrsystems.axisight.camera.FrameAnalyzer
import com.etrsystems.axisight.usb.UsbDeviceUtils
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.IAspectRatio
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB (UVC) camera fragment built on AUSBC's [CameraFragment].
 *
 * Detection runs on the camera's raw NV21 preview frames (delivered by libuvc through
 * [IPreviewDataCallBack]) rather than on screenshots of the preview TextureView: no GL
 * read-back, no ARGB conversion, full camera frame rate, and — because the frame is in
 * camera image space — the shared [FrameAnalyzer] maps results through the app-wide
 * CoordinateMapper exactly like the internal camera, so calibration captured on USB is
 * stored in image space too.
 *
 * Frames arrive on libuvc's thread. Each one is handed to a dedicated processing
 * thread; if that thread is still busy the frame is dropped (keep-latest), so a slow
 * detection never backs up the camera.
 *
 * Two watchdogs report through [CameraStateListener.onUsbCameraError] so the host can
 * run its bounded reconnect:
 *  - open watchdog: the camera never reached OPENED within [OPEN_TIMEOUT_MS] of the
 *    fragment resuming (black preview with no state callback at all);
 *  - stall watchdog: the camera is open but no frame has arrived for
 *    [FRAME_STALL_THRESHOLD_MS] (cable bump, camera firmware hang).
 *
 * Lifecycle note: AUSBC's CameraFragment opens the camera when its view is created and
 * only closes it in onDestroyView — it does nothing on pause/resume. So after the app is
 * backgrounded and resumed the camera is still open and no OPENED callback will arrive;
 * [onResume] restarts frame processing itself based on the tracked [cameraOpen] state.
 */
class UvcFragment : CameraFragment() {

    /** Mirrors InternalCameraSource.Listener for the USB path. */
    interface FrameListener {
        /** Gate for running detection at all (auto-detect on, not simulating). Any thread. */
        fun isAnalysisEnabled(): Boolean

        /** Gate for delivering successful detections (tracking enabled). Any thread. */
        fun isTrackingEnabled(): Boolean

        /** Called on the main thread with a view-space detection. */
        fun onToolPointDetected(point: FrameAnalyzer.ViewPoint)

        /**
         * Called on the PROCESSING thread when a [requestLock] sampled the frame
         * center, so the new threshold is applied to the shared config before this
         * same frame is detected. Implementations must be thread-safe.
         */
        fun onLockThresholdSampled(threshold: Int)
    }

    interface CameraStateListener {
        fun onUsbCameraOpened()
        fun onUsbCameraClosed()
        fun onUsbCameraError(message: String?)
    }

    private var textureView: SafeAspectRatioTextureView? = null
    private var frameAnalyzer: FrameAnalyzer? = null
    private var frameListener: FrameListener? = null
    private var cameraStateListener: CameraStateListener? = null
    private var targetVendorId: Int = -1
    private var targetProductId: Int = -1
    private var targetDeviceName: String? = null

    // Requested preview size. VGA is supported by practically every UVC camera; AUSBC
    // negotiates the closest supported MJPEG size and the frame callback reports the
    // size actually delivered, which is what the analyzer uses.
    private var previewWidth: Int = DEFAULT_PREVIEW_WIDTH
    private var previewHeight: Int = DEFAULT_PREVIEW_HEIGHT

    // Preview-view geometry in overlay coordinates. Written on the main thread on
    // every layout pass, read on the processing thread.
    @Volatile private var viewOffsetX = 0f
    @Volatile private var viewOffsetY = 0f
    @Volatile private var viewWidth = 0
    @Volatile private var viewHeight = 0
    private val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        snapshotViewGeometry(v)
    }

    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    private val frameInFlight = AtomicBoolean(false)
    @Volatile private var isProcessing = false
    @Volatile private var lockRequested = false
    @Volatile private var previewCallbackRegistered = false

    // Tracked from onCameraState. AUSBC's own isCameraOpened() can block the calling
    // thread for up to 2 s while the camera object is still being created, so it must
    // not be consulted from the main thread; this flag is the safe substitute.
    @Volatile private var cameraOpen = false

    // Frame-stall detection: fed by the preview callback, checked on the main thread.
    @Volatile private var lastFrameTimestampMs: Long = 0L
    private var stallWatchdog: Runnable? = null
    private var openWatchdog: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val previewCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(
            data: ByteArray?,
            width: Int,
            height: Int,
            format: IPreviewDataCallBack.DataFormat
        ) {
            onPreviewFrame(data, width, height, format)
        }
    }

    /** Wires this fragment's frames into the app's shared detection pipeline. */
    fun attachAnalyzer(analyzer: FrameAnalyzer, listener: FrameListener) {
        frameAnalyzer = analyzer
        frameListener = listener
    }

    fun setCameraStateListener(listener: CameraStateListener?) {
        cameraStateListener = listener
    }

    /** Samples the center-pixel luminance on the next processed frame. */
    fun requestLock() {
        lockRequested = true
    }

    /** Cancels a pending [requestLock] that has not been consumed by a frame yet. */
    fun cancelLockRequest() {
        lockRequested = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetVendorId = arguments?.getInt(ARG_VENDOR_ID, -1) ?: -1
        targetProductId = arguments?.getInt(ARG_PRODUCT_ID, -1) ?: -1
        targetDeviceName = arguments?.getString(ARG_DEVICE_NAME)
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        return try {
            val view = inflater.inflate(R.layout.fragment_uvc, container, false)
            textureView = view.findViewById<SafeAspectRatioTextureView>(R.id.uvc_texture_view).also {
                it.setAspectRatio(previewWidth, previewHeight)
                it.addOnLayoutChangeListener(layoutListener)
            }
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
            // Deliver decoded NV21 frames to IPreviewDataCallBack for detection.
            .setRawPreviewData(true)
            .create()
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.i(TAG, "Camera opened successfully")
                cameraOpen = true
                mainHandler.post { cancelOpenWatchdog() }
                registerPreviewCallback()
                startProcessing()
                dispatchOnMain {
                    Toast.makeText(context, "USB Camera Connected", Toast.LENGTH_SHORT).show()
                    cameraStateListener?.onUsbCameraOpened()
                }
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.i(TAG, "Camera closed")
                cameraOpen = false
                mainHandler.post { cancelOpenWatchdog() }
                unregisterPreviewCallback()
                stopProcessing()
                dispatchOnMain {
                    cameraStateListener?.onUsbCameraClosed()
                }
            }
            ICameraStateCallBack.State.ERROR -> {
                Log.e(TAG, "Camera error: $msg")
                cameraOpen = false
                mainHandler.post { cancelOpenWatchdog() }
                unregisterPreviewCallback()
                stopProcessing()
                dispatchOnMain {
                    Toast.makeText(context, "Camera Error: $msg", Toast.LENGTH_LONG).show()
                    cameraStateListener?.onUsbCameraError(msg)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startProcessingThread()
        if (cameraOpen) {
            // Still open from before the pause: no OPENED callback is coming.
            startProcessing()
        } else {
            scheduleOpenWatchdog()
        }
    }

    override fun onPause() {
        cancelOpenWatchdog()
        stopProcessing()
        stopProcessingThread()
        super.onPause()
    }

    override fun onDestroyView() {
        cancelOpenWatchdog()
        unregisterPreviewCallback()
        stopProcessing()
        stopProcessingThread()
        cameraOpen = false
        // Do NOT null out cameraStateListener here — late AUSBC callbacks
        // must still be deliverable to the activity for error recovery.
        textureView?.removeOnLayoutChangeListener(layoutListener)
        textureView = null
        super.onDestroyView()
    }

    // ── Frame delivery ─────────────────────────────────────────────────────

    private fun registerPreviewCallback() {
        if (previewCallbackRegistered) return
        addPreviewDataCallBack(previewCallback)
        previewCallbackRegistered = true
    }

    private fun unregisterPreviewCallback() {
        if (!previewCallbackRegistered) return
        previewCallbackRegistered = false
        try {
            removePreviewDataCallBack(previewCallback)
        } catch (e: Exception) {
            Log.w(TAG, "removePreviewDataCallBack failed", e)
        }
    }

    /** libuvc thread. Hands the frame to the processing thread, dropping it if busy. */
    private fun onPreviewFrame(
        data: ByteArray?,
        width: Int,
        height: Int,
        format: IPreviewDataCallBack.DataFormat
    ) {
        lastFrameTimestampMs = SystemClock.elapsedRealtime()
        if (!isProcessing || data == null || width <= 0 || height <= 0) return
        if (format != IPreviewDataCallBack.DataFormat.NV21) return
        val listener = frameListener ?: return
        if (!listener.isAnalysisEnabled()) return
        if (!frameInFlight.compareAndSet(false, true)) return // still busy: keep latest
        val handler = processingHandler
        if (handler == null) {
            frameInFlight.set(false)
            return
        }
        // AUSBC allocates a fresh array per frame and never mutates it afterwards, so
        // it can be processed off-thread without a copy.
        if (!handler.post {
                try {
                    processNv21(data, width, height)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing error", e)
                } finally {
                    frameInFlight.set(false)
                }
            }
        ) {
            frameInFlight.set(false)
        }
    }

    /** Processing thread. */
    private fun processNv21(data: ByteArray, width: Int, height: Int) {
        val analyzer = frameAnalyzer ?: return
        val listener = frameListener ?: return
        if (lockRequested) {
            // Guard like BlobDetector does: a negotiation glitch can report a frame size
            // larger than the delivered buffer, and an exception here would eat the lock.
            if (data.size >= width * height) {
                lockRequested = false
                val lum = data[(height / 2) * width + width / 2].toInt() and 0xFF
                val thr = (lum + LOCK_LUMINANCE_MARGIN).coerceIn(0, 255)
                Log.i(TAG, "Target LOCKED: centerLum=$lum, thr=$thr")
                listener.onLockThresholdSampled(thr)
            } else {
                Log.w(TAG, "Lock deferred: buffer ${data.size} < ${width}x${height} luma bytes")
            }
        }
        val vw = viewWidth
        val vh = viewHeight
        if (vw <= 0 || vh <= 0) return // preview not laid out yet

        val point = analyzer.analyzeNv21Frame(
            data, width, height,
            vw, vh, viewOffsetX, viewOffsetY,
            deliverResult = listener.isTrackingEnabled()
        )
        if (point != null) {
            dispatchOnMain { listener.onToolPointDetected(point) }
        }
    }

    private fun snapshotViewGeometry(v: View) {
        viewOffsetX = v.x
        viewOffsetY = v.y
        viewWidth = v.width
        viewHeight = v.height
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

    // ── Processing lifecycle ───────────────────────────────────────────────

    private fun startProcessingThread() {
        if (processingThread == null) {
            processingThread = HandlerThread("UsbFrameProcessing").also { it.start() }
            processingHandler = Handler(processingThread!!.looper)
        }
    }

    private fun stopProcessingThread() {
        processingHandler = null
        processingThread?.quitSafely()
        processingThread = null
        frameInFlight.set(false)
    }

    private fun startProcessing() {
        if (isProcessing) return
        isProcessing = true
        lastFrameTimestampMs = SystemClock.elapsedRealtime()
        mainHandler.post { scheduleStallWatchdog() }
    }

    private fun stopProcessing() {
        isProcessing = false
        processingHandler?.removeCallbacksAndMessages(null)
        frameInFlight.set(false)
        mainHandler.post { cancelStallWatchdog() }
    }

    // ── Watchdogs (main thread) ────────────────────────────────────────────

    private fun scheduleStallWatchdog() {
        cancelStallWatchdog()
        val watchdog = Runnable {
            if (!isProcessing) return@Runnable
            val age = SystemClock.elapsedRealtime() - lastFrameTimestampMs
            if (age >= FRAME_STALL_THRESHOLD_MS) {
                Log.w(TAG, "Frame stall detected: last frame ${age}ms ago")
                stopProcessing()
                dispatchOnMain {
                    cameraStateListener?.onUsbCameraError("Preview stalled (no frames for ${age}ms)")
                }
            } else {
                scheduleStallWatchdog() // re-arm
            }
        }
        stallWatchdog = watchdog
        mainHandler.postDelayed(watchdog, FRAME_STALL_THRESHOLD_MS)
    }

    private fun cancelStallWatchdog() {
        stallWatchdog?.let { mainHandler.removeCallbacks(it) }
        stallWatchdog = null
    }

    /**
     * Reports a camera that silently never opens (no OPENED, CLOSED or ERROR state at
     * all — typically a black preview after a hot-replug) so the host can reconnect.
     */
    private fun scheduleOpenWatchdog() {
        cancelOpenWatchdog()
        val watchdog = Runnable {
            openWatchdog = null
            if (cameraOpen || !isAdded) return@Runnable
            Log.w(TAG, "Camera did not open within ${OPEN_TIMEOUT_MS}ms")
            dispatchOnMain {
                cameraStateListener?.onUsbCameraError("Camera did not open within ${OPEN_TIMEOUT_MS / 1000}s")
            }
        }
        openWatchdog = watchdog
        mainHandler.postDelayed(watchdog, OPEN_TIMEOUT_MS)
    }

    private fun cancelOpenWatchdog() {
        openWatchdog?.let { mainHandler.removeCallbacks(it) }
        openWatchdog = null
    }

    companion object {
        private const val TAG = "UvcFragment"
        private const val ARG_VENDOR_ID = "vendor_id"
        private const val ARG_PRODUCT_ID = "product_id"
        private const val ARG_DEVICE_NAME = "device_name"
        private const val DEFAULT_PREVIEW_WIDTH = 640
        private const val DEFAULT_PREVIEW_HEIGHT = 480
        private const val FRAME_STALL_THRESHOLD_MS = 3000L
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val LOCK_LUMINANCE_MARGIN = 30

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
