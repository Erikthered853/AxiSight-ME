# API Reference: WifiCameraSource.kt

**Language**: Kotlin

**Source**: `app\src\main\java\com\etrsystems\axisight\camera\WifiCameraSource.kt`

---

## Classes

### WifiCameraSource

**Inherits from**: TextureView.SurfaceTextureListener

#### Methods

##### isAnalysisEnabled() → Boolean

        /** Gate for delivering successful detections (tracking enabled). Main thread. */
        fun isTrackingEnabled

**Returns**: `Boolean

        /** Gate for delivering successful detections (tracking enabled). Main thread. */
        fun isTrackingEnabled`


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


##### start(url: String) → Boolean

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| url | String | - | - |

**Returns**: `Boolean`


##### stop()


##### isValidRtspUrl(url: String) → Boolean

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| url | String | - | - |

**Returns**: `Boolean`


##### onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| surface | SurfaceTexture | - | - |
| width | Int | - | - |
| height | Int | - | - |


##### onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| surface | SurfaceTexture | - | - |
| width | Int | - | - |
| height | Int | - | - |


##### onSurfaceTextureDestroyed(surface: SurfaceTexture) → Boolean

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| surface | SurfaceTexture | - | - |

**Returns**: `Boolean`


##### onSurfaceTextureUpdated(surface: SurfaceTexture)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| surface | SurfaceTexture | - | - |



