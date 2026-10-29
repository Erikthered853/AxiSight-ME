# API Reference: RotationFit.kt

**Language**: Kotlin

**Source**: `app\src\main\java\com\etrsystems\axisight\domain\RotationFit.kt`

---

## Classes

### RotationFitEvaluator

**Inherits from**: (none)

#### Methods

##### evaluate(samples: List<Pair<Float, Float>>) → RotationFitQuality

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| samples | List<Pair<Float | - | - |
| Float>> | None | - | - |

**Returns**: `RotationFitQuality`


##### coverageFraction(samples: List<Pair<Float, Float>>, cx: Double, cy: Double) → Double

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| samples | List<Pair<Float | - | - |
| Float>> | None | - | - |
| cx | Double | - | - |
| cy | Double | - | - |

**Returns**: `Double`


##### bucketIndex(x: Float, y: Float, cx: Double, cy: Double) → Int

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| x | Float | - | - |
| y | Float | - | - |
| cx | Double | - | - |
| cy | Double | - | - |

**Returns**: `Int`



