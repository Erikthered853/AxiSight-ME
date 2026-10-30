# API Reference: UsbCameraController.kt

**Language**: Kotlin

**Source**: `app\src\main\java\com\etrsystems\axisight\camera\UsbCameraController.kt`

---

## Classes

### UsbCameraController

**Inherits from**: (none)

#### Methods

##### configureFragment(fragment: UvcFragment)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| fragment | UvcFragment | - | - |


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


##### onReceive(context: Context?, intent: Intent?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| context | Context? | - | - |
| intent | Intent? | - | - |


##### onReceive(context: Context?, intent: Intent?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| context | Context? | - | - |
| intent | Intent? | - | - |


##### onReceive(context: Context?, intent: Intent?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| context | Context? | - | - |
| intent | Intent? | - | - |


##### start()


##### stop()


##### dispose()


##### currentFragment() → UvcFragment?

**Returns**: `UvcFragment?`


##### requestLock()


##### cancelLockRequest()


##### restartAfterDelay(delayMs: Long)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| delayMs | Long | - | - |


##### openPermittedCamera(preferredDevice: UsbDevice?) → Boolean

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| preferredDevice | UsbDevice? | - | - |

**Returns**: `Boolean`


##### showFragment(device: UsbDevice)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| device | UsbDevice | - | - |


##### onUsbCameraOpened()


##### onUsbCameraClosed()


##### onUsbCameraError(message: String?)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| message | String? | - | - |


##### requestPermission(usbManager: UsbManager, device: UsbDevice)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| usbManager | UsbManager | - | - |
| device | UsbDevice | - | - |


##### scheduleFragmentRetry(reason: String)

**Parameters**:

| Name | Type | Default | Description |
|------|------|---------|-------------|
| reason | String | - | - |


##### registerPermissionReceiverIfNeeded()


##### registerRecoveryReceiverIfNeeded()


##### registerAttachReceiverIfNeeded()


##### unregisterPermissionReceiverIfNeeded()


##### unregisterRecoveryReceiverIfNeeded()


##### unregisterAttachReceiverIfNeeded()



