// Kotlin
package com.etrsystems.axisight.ui

import android.graphics.Bitmap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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
    private var isProcessing = false
    private var frameBitmap: Bitmap? = null
    private val detectorConfig = DetectorConfig()
    private var targetGlobalX: Float? = null
    private var targetGlobalY: Float? = null
    private var targetRadiusPx: Float = detectorConfig.targetRadiusPx

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
    fun lockTarget(active: Boolean): Int? {
        if (!active) {
            detectorConfig.lockedThreshold = null
            return null
        }

        // Capture current center pixel
        val bmp = textureView?.bitmap ?: return null
        val w = bmp.width
        val h = bmp.height
        val result = if (w > 0 && h > 0) {
            // Sample center 5x5 area for robust color
            val cx = w / 2
            val cy = h / 2
            val pixel = bmp.getPixel(cx, cy)
            
            // Convert to luminance 
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val lum = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            
            // Set threshold with a safety margin (e.g. +30 brighter allowed)
            // But since it's a "dark dot", we want to detect pixels *darker* than this threshold.
            // If the dot is dark (lum=50) and background is bright (lum=200),
            // we want to catch things <= 50 + margin.
            val margin = 30
            val thr = (lum + margin).coerceIn(0, 255)
            detectorConfig.lockedThreshold = thr
            Log.i(TAG, "Target LOCKED: centerLum=$lum, thr=$thr")
            thr
        } else {
            null
        }

        bmp.recycle()
        return result
    }

    private fun recycleFrameBitmap() {
        frameBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        frameBitmap = null
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

    override fun getCameraView(): IAspectRatio? {
        return textureView
    }

    override fun getCameraViewContainer(): ViewGroup {
        return view as? ViewGroup ?: FrameLayout(requireContext())
    }

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
                    isUvcDevice(it)
            }?.let {
                Log.i(TAG, "Using USB camera by VID/PID: ${it.vendorId}/${it.productId}")
                return it
            }
        }

        return devices.firstOrNull { isUvcDevice(it) }?.also {
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
                activity?.runOnUiThread {
                    Toast.makeText(context, "USB Camera Connected", Toast.LENGTH_SHORT).show()
                    cameraStateListener?.onUsbCameraOpened()
                }
                startProcessing()
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.i(TAG, "Camera closed")
                activity?.runOnUiThread {
                    cameraStateListener?.onUsbCameraClosed()
                }
                stopProcessing()
            }
            ICameraStateCallBack.State.ERROR -> {
                Log.e(TAG, "Camera error: $msg")
                activity?.runOnUiThread {
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
        super.onPause()
    }

    override fun onDestroyView() {
        stopProcessing()
        stopProcessingThread()
        recycleFrameBitmap()
        cameraStateListener = null
        textureView = null
        super.onDestroyView()
    }

    private fun startProcessingThread() {
        if (processingThread == null) {
            processingThread = HandlerThread("FrameProcessingThread")
            processingThread?.start()
            processingHandler = Handler(processingThread!!.looper)
        }
    }

    private fun stopProcessingThread() {
        recycleFrameBitmap()
        processingThread?.quitSafely()
        processingThread = null
        processingHandler = null
    }

    private fun startProcessing() {
        if (isProcessing) return
        isProcessing = true
        processFrame()
    }

    private fun stopProcessing() {
        isProcessing = false
        processingHandler?.removeCallbacksAndMessages(null)
    }

    private fun processFrame() {
        if (!isProcessing) return

        processingHandler?.postDelayed({
            if (!isProcessing) return@postDelayed
            
            try {
                val bmp = obtainFrameBitmap()
                if (bmp != null) {
                    processBitmap(bmp)
                } else {
                    Log.w(TAG, "TextureView bitmap unavailable - surface not ready?")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
            } finally {
                // Schedule next frame
                if (isProcessing) processFrame()
            }
        }, 66) // ~15 FPS
    }

    private fun obtainFrameBitmap(): Bitmap? {
        val tv = textureView ?: return null
        val w = tv.width
        val h = tv.height
        if (w <= 0 || h <= 0) return null

        val reusable = frameBitmap
        if (reusable == null || reusable.isRecycled || reusable.width != w || reusable.height != h) {
            recycleFrameBitmap()
            frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val target = frameBitmap ?: return null
        return tv.getBitmap(target)
    }

    private fun processBitmap(bmp: Bitmap) {
        try {
            applyTargetCircleToDetector()
            val result = BlobDetector.detectDarkDotCenter(bmp, detectorConfig)
            when (result) {
                is DetectionResult.Success -> {
                    Log.d(TAG, "Blob detected at: ${result.x}, ${result.y}")
                    activity?.runOnUiThread {
                        val viewX = textureView?.x ?: 0f
                        val viewY = textureView?.y ?: 0f
                        callback?.onPointDetected(result.x + viewX, result.y + viewY)
                    }
                }
                is DetectionResult.Failure -> {
                    // Log failure for debugging, but reduced noise
                     Log.d(TAG, "No blob: ${result.reason} - ${result.debugInfo}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection error", e)
        }
    }

    private fun applyTargetCircleToDetector() {
        val x = targetGlobalX ?: return
        val y = targetGlobalY ?: return
        val viewX = textureView?.x ?: 0f
        val viewY = textureView?.y ?: 0f
        detectorConfig.targetCenterX = x - viewX
        detectorConfig.targetCenterY = y - viewY
        detectorConfig.targetRadiusPx = targetRadiusPx
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "UvcFragment"
        private const val ARG_VENDOR_ID = "vendor_id"
        private const val ARG_PRODUCT_ID = "product_id"
        private const val ARG_DEVICE_NAME = "device_name"

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
