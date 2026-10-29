# API Reference: CoordinateMapper.kt

**Language**: Kotlin

**Source**: `app\src\main\java\com\etrsystems\axisight\CoordinateMapper.kt`

---

## Classes

### CoordinateMapper

**Inherits from**: (none)

#### Methods

##### update(imageWidth: Int, imageHeight: Int, rotationDegrees: Int, viewWidth: Int, viewHeight: Int, viewOffsetX: Float = 0f, viewOffsetY: Float = 0f)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| imageWidth | Int | - | - |
| imageHeight | Int | - | - |
| rotationDegrees | Int | - | - |
| viewWidth | Int | - | - |
| viewHeight | Int | - | - |
| viewOffsetX | Float | 0f | - |
| viewOffsetY | Float | 0f | - |


##### imageToView(x: Float, y: Float) → Pair<Float, Float>

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| x | Float | - | - |
| y | Float | - | - |

**Returns**: `Pair<Float, Float>`


##### viewToImage(x: Float, y: Float) → Pair<Float, Float>

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| x | Float | - | - |
| y | Float | - | - |

**Returns**: `Pair<Float, Float>`


##### imageRadiusToView(radius: Float) → Float

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| radius | Float | - | - |

**Returns**: `Float`


##### viewRadiusToImage(radius: Float) → Float

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| radius | Float | - | - |

**Returns**: `Float`


##### invalidate()


##### imageToLogical(xi: Float, yi: Float) → Pair<Float, Float>

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| xi | Float | - | - |
| yi | Float | - | - |

**Returns**: `Pair<Float, Float>`


##### logicalToImage(lx: Float, ly: Float) → Pair<Float, Float>

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| lx | Float | - | - |
| ly | Float | - | - |

**Returns**: `Pair<Float, Float>`



