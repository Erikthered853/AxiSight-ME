# API Reference: BlobDetectorTest.kt

**Language**: Kotlin

**Source**: `app\src\test\java\com\etrsystems\axisight\BlobDetectorTest.kt`

---

## Classes

### BlobDetectorTest

**Inherits from**: (none)

#### Methods

##### syntheticBitmap(width: Int = 200, height: Int = 200, cx: Int = 100, cy: Int = 100, radius: Int = 20, dotLuminance: Int = 30, bgLuminance: Int = 200) → Bitmap

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| width | Int | 200 | - |
| height | Int | 200 | - |
| cx | Int | 100 | - |
| cy | Int | 100 | - |
| radius | Int | 20 | - |
| dotLuminance | Int | 30 | - |
| bgLuminance | Int | 200 | - |

**Returns**: `Bitmap`


##### syntheticBitmapFractional(width: Int = 200, height: Int = 200, cx: Double, cy: Double, radius: Double = 20.0, dotLuminance: Int = 30, bgLuminance: Int = 200) → Bitmap

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| width | Int | 200 | - |
| height | Int | 200 | - |
| cx | Double | - | - |
| cy | Double | - | - |
| radius | Double | 20.0 | - |
| dotLuminance | Int | 30 | - |
| bgLuminance | Int | 200 | - |

**Returns**: `Bitmap`


##### syntheticGradientBitmap(width: Int = 200, height: Int = 200, cx: Int = 100, cy: Int = 100, radius: Int = 30, dotLumMin: Int = 10, dotLumMax: Int = 80, bgLuminance: Int = 220) → Bitmap

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| width | Int | 200 | - |
| height | Int | 200 | - |
| cx | Int | 100 | - |
| cy | Int | 100 | - |
| radius | Int | 30 | - |
| dotLumMin | Int | 10 | - |
| dotLumMax | Int | 80 | - |
| bgLuminance | Int | 220 | - |

**Returns**: `Bitmap`


##### cfg(minArea: Int = 50, maxArea: Int = 10000, minCirc: Double = 0.5, kStd: Double = 1.0, ds: Int = 2, minContrast: Double = 0.05   // permissive default for legacy tests)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| minArea | Int | 50 | - |
| maxArea | Int | 10000 | - |
| minCirc | Double | 0.5 | - |
| kStd | Double | 1.0 | - |
| ds | Int | 2 | - |
| minContrast | Double | 0.05   // permissive default for legacy tests | - |


##### filterCfg(consecutiveRequired: Int = 1, maxJump: Float = Float.MAX_VALUE, alpha: Float = 0.5f)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| consecutiveRequired | Int | 1 | - |
| maxJump | Float | Float.MAX_VALUE | - |
| alpha | Float | 0.5f | - |


##### syntheticBitmapSoftEdge(width: Int = 200, height: Int = 200, cx: Int = 100, cy: Int = 100, radius: Int = 30, bandPx: Double = 16.0, dotLuminance: Int = 30, bgLuminance: Int = 200) → Bitmap

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| width | Int | 200 | - |
| height | Int | 200 | - |
| cx | Int | 100 | - |
| cy | Int | 100 | - |
| radius | Int | 30 | - |
| bandPx | Double | 16.0 | - |
| dotLuminance | Int | 30 | - |
| bgLuminance | Int | 200 | - |

**Returns**: `Bitmap`


##### getBuffer()


##### getRowStride()


##### getPixelStride()


##### getPlanes()


##### getWidth()


##### getHeight()


##### getFormat()


##### getImageInfo()


##### getRotationDegrees()


##### getTimestamp()


##### getTagBundle()


##### populateExifData(b: androidx.camera.core.impl.utils.ExifData.Builder)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| b | androidx.camera.core.impl.utils.ExifData.Builder | - | - |


##### getCropRect()


##### setCropRect(rect: android.graphics.Rect?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| rect | android.graphics.Rect? | - | - |


##### toBitmap() → Bitmap

**Returns**: `Bitmap`


##### getImage() → android.media.Image?

**Returns**: `android.media.Image?`


##### close()


##### toNv21(bmp: Bitmap) → ByteArray

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| bmp | Bitmap | - | - |

**Returns**: `ByteArray`


##### drawScratch(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int = 3, lum: Int = 40)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| bmp | Bitmap | - | - |
| x0 | Int | - | - |
| y0 | Int | - | - |
| x1 | Int | - | - |
| y1 | Int | - | - |
| thickness | Int | 3 | - |
| lum | Int | 40 | - |




### PlaneImpl

**Inherits from**: androidx.camera.core.ImageProxy.PlaneProxy

#### Methods

##### getBuffer()


##### getRowStride()


##### getPixelStride()



