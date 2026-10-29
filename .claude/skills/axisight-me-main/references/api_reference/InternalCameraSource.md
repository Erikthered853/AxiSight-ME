# API Reference: InternalCameraSource.kt

**Language**: Kotlin

**Source**: `app\src\main\java\com\etrsystems\axisight\camera\InternalCameraSource.kt`

---

## Classes

### InternalCameraSource

**Inherits from**: (none)

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


##### start()


##### stop()


##### requestLock()


##### cancelLockRequest()


##### dispose()


##### bindUseCases()


##### analyzeFrame(image: ImageProxy)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| image | ImageProxy | - | - |


##### sampleLockThreshold(image: ImageProxy)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| image | ImageProxy | - | - |



