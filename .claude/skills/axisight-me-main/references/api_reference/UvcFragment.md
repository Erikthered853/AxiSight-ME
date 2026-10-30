# API Reference: UvcFragment.kt

**Language**: Kotlin

**Source**: `app\src\main\java\com\etrsystems\axisight\ui\UvcFragment.kt`

---

## Classes

### UvcFragment

**Inherits from**: CameraFragment

#### Methods

##### isAnalysisEnabled() → Boolean

        /** Gate for delivering successful detections (tracking enabled). Any thread. */
        fun isTrackingEnabled

**Returns**: `Boolean

        /** Gate for delivering successful detections (tracking enabled). Any thread. */
        fun isTrackingEnabled`


##### onToolPointDetected(point: FrameAnalyzer.ViewPoint)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| point | FrameAnalyzer.ViewPoint | - | - |


##### onLockThresholdSampled(threshold: Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| threshold | Int | - | - |


##### onUsbCameraOpened()


##### onUsbCameraClosed()


##### onUsbCameraError(message: String?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| message | String? | - | - |


##### onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| data | ByteArray? | - | - |
| width | Int | - | - |
| height | Int | - | - |
| format | IPreviewDataCallBack.DataFormat | - | - |


##### attachAnalyzer(analyzer: FrameAnalyzer, listener: FrameListener)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| analyzer | FrameAnalyzer | - | - |
| listener | FrameListener | - | - |


##### setCameraStateListener(listener: CameraStateListener?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| listener | CameraStateListener? | - | - |


##### requestLock()


##### cancelLockRequest()


##### onCreate(savedInstanceState: Bundle?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| savedInstanceState | Bundle? | - | - |


##### getRootView(inflater: LayoutInflater, container: ViewGroup?) → View

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| inflater | LayoutInflater | - | - |
| container | ViewGroup? | - | - |

**Returns**: `View`


##### getCameraView() → IAspectRatio?

**Returns**: `IAspectRatio?`


##### getCameraViewContainer() → ViewGroup

**Returns**: `ViewGroup`


##### getDefaultCamera() → UsbDevice?

**Returns**: `UsbDevice?`


##### getCameraRequest() → CameraRequest

**Returns**: `CameraRequest`


##### onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| self | MultiCameraClient.ICamera | - | - |
| code | ICameraStateCallBack.State | - | - |
| msg | String? | - | - |


##### onResume()


##### onPause()


##### onDestroyView()


##### registerPreviewCallback()


##### unregisterPreviewCallback()


##### onPreviewFrame(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| data | ByteArray? | - | - |
| width | Int | - | - |
| height | Int | - | - |
| format | IPreviewDataCallBack.DataFormat | - | - |


##### processNv21(data: ByteArray, width: Int, height: Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| data | ByteArray | - | - |
| width | Int | - | - |
| height | Int | - | - |


##### snapshotViewGeometry(v: View)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| v | View | - | - |


##### dispatchOnMain(block: ()

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| block | ( | - | - |


##### startProcessingThread()


##### stopProcessingThread()


##### startProcessing()


##### stopProcessing()


##### scheduleStallWatchdog()


##### cancelStallWatchdog()


##### newInstance(vendorId: Int, productId: Int, deviceName: String?) → UvcFragment

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| vendorId | Int | - | - |
| productId | Int | - | - |
| deviceName | String? | - | - |

**Returns**: `UvcFragment`



