package me.rerere.rikkahub.data.mobileagent

enum class RootState {
    UNKNOWN,
    ROOT_GRANTED,
    ROOT_DENIED,
    ROOT_UNAVAILABLE,
}

data class RootCapability(
    val state: RootState = RootState.UNKNOWN,
    val detail: String = "尚未检查 Root 授权。仅在点击检查后运行固定的只读命令。",
    val checkedAtEpochMillis: Long? = null,
)

enum class CapabilityStatus {
    AVAILABLE,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    NOT_IMPLEMENTED,
    UNKNOWN,
}

data class DeviceCapability(
    val id: String,
    val title: String,
    val status: CapabilityStatus,
    val detail: String,
)

/** Observations for this process only; neither permissions nor Root grants are persisted. */
data class DeviceCapabilities(
    val deviceManufacturer: String = "",
    val deviceModel: String = "",
    val androidRelease: String = "",
    val apiLevel: Int = 0,
    val root: RootCapability = RootCapability(),
    val rootProbeRunning: Boolean = false,
    val capabilities: List<DeviceCapability> = emptyList(),
    val refreshedAtEpochMillis: Long? = null,
)

internal data class PassiveDeviceSnapshot(
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidRelease: String,
    val apiLevel: Int,
    val capabilities: List<DeviceCapability>,
)

/** This boundary must never request a permission, launch an activity or execute a command. */
internal fun interface PassiveDeviceProbe {
    suspend fun capture(): PassiveDeviceSnapshot
}

internal fun interface RootProbe {
    suspend fun check(): RootCapability
}
