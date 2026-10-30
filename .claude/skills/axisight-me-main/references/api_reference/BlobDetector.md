# API Reference: BlobDetector.kt

**Language**: Kotlin

**Source**: `app\src\main\java\com\etrsystems\axisight\BlobDetector.kt`

---

## Classes

### DetectionResult

**Inherits from**: (none)



### FailureReason

**Inherits from**: (none)



### Scratch

**Inherits from**: (none)

#### Methods

##### ensure(cells: Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| cells | Int | - | - |


##### ensureComponents(count: Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| count | Int | - | - |




### BlobDetector

**Inherits from**: (none)

#### Methods

##### ensure(cells: Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| cells | Int | - | - |


##### ensureComponents(count: Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| count | Int | - | - |


##### isInsideTarget(i: Int, j: Int, ds: Int, cfg: DetectorConfig) → Boolean

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| i | Int | - | - |
| j | Int | - | - |
| ds | Int | - | - |
| cfg | DetectorConfig | - | - |

**Returns**: `Boolean`


##### pixelToLuminance(argb: Int) → Int

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| argb | Int | - | - |

**Returns**: `Int`


##### sampleBilinear(fi: Double, fj: Double, dw: Int, dh: Int, getPixel: (Int, Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| fi | Double | - | - |
| fj | Double | - | - |
| dw | Int | - | - |
| dh | Int | - | - |
| getPixel | (Int | - | - |
| Int | None | - | - |


##### refineBoundary(cxD: Double, cyD: Double, estRadiusD: Double, thr: Int, dw: Int, dh: Int, getPixel: (Int, Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| cxD | Double | - | - |
| cyD | Double | - | - |
| estRadiusD | Double | - | - |
| thr | Int | - | - |
| dw | Int | - | - |
| dh | Int | - | - |
| getPixel | (Int | - | - |
| Int | None | - | - |


##### effectiveMaxArea(cfg: DetectorConfig) → Int

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| cfg | DetectorConfig | - | - |

**Returns**: `Int`


##### detectCore(dw: Int, dh: Int, ds: Int, cfg: DetectorConfig, getPixel: (i: Int, j: Int)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| dw | Int | - | - |
| dh | Int | - | - |
| ds | Int | - | - |
| cfg | DetectorConfig | - | - |
| getPixel | (i: Int | - | - |
| j | Int | - | - |


##### circularityOf(comp: DoubleArray, base: Int) → Double

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| comp | DoubleArray | - | - |
| base | Int | - | - |

**Returns**: `Double`


##### detectDarkDotCenter(image: ImageProxy, cfg: DetectorConfig) → DetectionResult

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| image | ImageProxy | - | - |
| cfg | DetectorConfig | - | - |

**Returns**: `DetectionResult`


##### detectDarkDotCenter(nv21: ByteArray, width: Int, height: Int, cfg: DetectorConfig) → DetectionResult

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| nv21 | ByteArray | - | - |
| width | Int | - | - |
| height | Int | - | - |
| cfg | DetectorConfig | - | - |

**Returns**: `DetectionResult`


##### detectDarkDotCenter(bitmap: Bitmap, cfg: DetectorConfig) → DetectionResult

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| bitmap | Bitmap | - | - |
| cfg | DetectorConfig | - | - |

**Returns**: `DetectionResult`



