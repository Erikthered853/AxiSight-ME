# API Reference: LoginActivity.kt

**Language**: Kotlin

**Source**: `app\src\main\java\com\etrsystems\axisight\auth\LoginActivity.kt`

---

## Classes

### LoginActivity

**Inherits from**: AppCompatActivity

#### Methods

##### onCreate(savedInstanceState: Bundle?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| savedInstanceState | Bundle? | - | - |


##### handleEmailSignIn()


##### handleGoogleSignIn()


##### handleCreateAccount()


##### handleForgotPassword()


##### onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| requestCode | Int | - | - |
| resultCode | Int | - | - |
| data | Intent? | - | - |


##### validateInputs(email: String, password: String) → Boolean

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| email | String | - | - |
| password | String | - | - |

**Returns**: `Boolean`


##### checkBackoff() → Boolean

**Returns**: `Boolean`


##### recordFailure()


##### resetBackoff()


##### setButtonsEnabled(enabled: Boolean)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| enabled | Boolean | - | - |


##### onDestroy()


##### setLoading(loading: Boolean)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| loading | Boolean | - | - |


##### navigateToMain()



