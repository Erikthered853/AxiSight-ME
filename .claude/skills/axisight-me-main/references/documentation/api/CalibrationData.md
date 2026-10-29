# API Reference: CalibrationData.kt

**Language**: Kotlin

**Source**: `app\src\main\java\com\etrsystems\axisight\CalibrationData.kt`

---

## Classes

### CalibrationData

**Inherits from**: (none)

#### Methods

##### toolOffsetInches(toolX: Float, toolY: Float) → Pair<Double, Double>

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| toolX | Float | - | - |
| toolY | Float | - | - |

**Returns**: `Pair<Double, Double>`


##### fromCenterUpAndScale(centerX: Float, centerY: Float, upPointX: Float, upPointY: Float, inchesPerPixel: Double) → CalibrationData?

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| centerX | Float | - | - |
| centerY | Float | - | - |
| upPointX | Float | - | - |
| upPointY | Float | - | - |
| inchesPerPixel | Double | - | - |

**Returns**: `CalibrationData?`




### CalibrationStore

**Inherits from**: (none)

#### Methods

##### save(data: CalibrationData)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| data | CalibrationData | - | - |


##### saveCenter(x: Float, y: Float)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| x | Float | - | - |
| y | Float | - | - |


##### loadCenterOrNull() → Pair<Float, Float>?

**Returns**: `Pair<Float, Float>?`


##### load() → CalibrationData?

**Returns**: `CalibrationData?`


##### saveTarget(x: Float, y: Float, radiusPx: Float)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| x | Float | - | - |
| y | Float | - | - |
| radiusPx | Float | - | - |


##### loadTargetOrNull() → Triple<Float, Float, Float>?

**Returns**: `Triple<Float, Float, Float>?`


##### saveSource(source: String)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| source | String | - | - |


##### loadSourceOrNull() → String?

**Returns**: `String?`


##### saveLastSource(source: String)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| source | String | - | - |


##### loadLastSourceOrNull() → String?

**Returns**: `String?`


##### isFinite() → Boolean

**Returns**: `Boolean`



