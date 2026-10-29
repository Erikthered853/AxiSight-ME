# API Reference: MainActivity.kt

**Language**: Kotlin

**Source**: `app\src\main\java\com\etrsystems\axisight\MainActivity.kt`

---

## Classes

### MainActivity

**Inherits from**: AppCompatActivity

#### Methods

##### updateCfg(block: DetectorConfig.()

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| block | DetectorConfig.( | - | - |


##### onCreate(savedInstanceState: Bundle?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| savedInstanceState | Bundle? | - | - |


##### calibrationAppliesToActiveSource() → Boolean

**Returns**: `Boolean`


##### onNewIntent(intent: Intent)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| intent | Intent | - | - |


##### isUsbAttachIntent(intent: Intent?) → Boolean

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| intent | Intent? | - | - |

**Returns**: `Boolean`


##### buildCameraController() → CameraController

**Returns**: `CameraController`


##### isAnalysisEnabled()


##### isTrackingEnabled()


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


##### isAnalysisEnabled()


##### isTrackingEnabled()


##### onToolPointDetected(point: FrameAnalyzer.ViewPoint)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| point | FrameAnalyzer.ViewPoint | - | - |


##### onError(message: String)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| message | String | - | - |


##### configureFragment(fragment: UvcFragment)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| fragment | UvcFragment | - | - |


##### isAnalysisEnabled()


##### isTrackingEnabled()


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


##### showToast(message: String)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| message | String | - | - |


##### showRetriesExhausted(retry: ()

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| retry | ( | - | - |


##### onStopped()


##### startInternalCamera()


##### startWifiCamera(url: String)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| url | String | - | - |


##### startUsbCamera()


##### showCalibrationHelp()


##### handleCalibrationEffect(effect: CalibrationViewModel.Effect)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| effect | CalibrationViewModel.Effect | - | - |


##### renderCalibrationPanel(s: CalibrationViewModel.UiState)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| s | CalibrationViewModel.UiState | - | - |


##### applyLoadedCalibration()


##### restoreLoadedCalibrationIfPending()


##### onToolPointDetected(x: Float, y: Float, quality: DetectionQuality? = null)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| x | Float | - | - |
| y | Float | - | - |
| quality | DetectionQuality? | null | - |


##### applyZoom(factor: Float)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| factor | Float | - | - |


##### applyLockThreshold(threshold: Int, sourceTag: String)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| threshold | Int | - | - |
| sourceTag | String | - | - |


##### applyEdgeToEdgeInsets()


##### updateParamsSummary()


##### updateDeltaReadout()


##### signalConfidencePercent(quality: DetectionQuality, cfg: DetectorConfig) → Double

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| quality | DetectionQuality | - | - |
| cfg | DetectorConfig | - | - |

**Returns**: `Double`


##### noDetectionHint() → String

**Returns**: `String`


##### run()


##### updateQualityReadout()


##### appendSegment(text: String, colorRes: Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| text | String | - | - |
| colorRes | Int | - | - |


##### logCurrentAlignment()


##### exportAlignmentHistory()


##### onResume()


##### onPause()


##### onDestroy()


##### ensureCameraPermission(onGranted: ()

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| onGranted | ( | - | - |


##### run()


##### updateTrackingButtons()


##### isNearTargetEdge(x: Float, y: Float) → Boolean

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| x | Float | - | - |
| y | Float | - | - |

**Returns**: `Boolean`



