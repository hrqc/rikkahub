package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

data class PhoneSessionToken(
    val conversationId: String,
    val assistantId: String,
    val sessionId: String,
    val epoch: Long,
)

enum class PhoneSessionStatus { IDLE, RUNNING, PAUSED, WAITING_FOR_FOREGROUND, STOPPED, EXPIRED }
enum class PhoneActivity { READY, WAITING_MODEL, OBSERVING, ACTING }

enum class PhoneBackendStopReason { NOTIFICATION_STOP, SERVICE_INTERRUPTED, SERVICE_DISCONNECTED, SERVICE_REPLACED }

data class PhoneSessionState(
    val token: PhoneSessionToken? = null,
    val targetPackage: String = "",
    val status: PhoneSessionStatus = PhoneSessionStatus.IDLE,
    val detail: String = "尚未开启手机控制",
    val useRoot: Boolean = false,
    val allowScreenshots: Boolean = false,
    val startedAtMillis: Long = 0,
    val actionsUsed: Int = 0,
    val observationsUsed: Int = 0,
    val actionLimit: Int = 30,
    val observationLimit: Int = 90,
    val durationLimitMillis: Long = 300_000,
    val audit: List<PhoneAuditEntry> = emptyList(),
    val modelWorking: Boolean = false,
    val activity: PhoneActivity = PhoneActivity.READY,
    val activityStartedAtMillis: Long = 0,
)

data class PhoneAuditEntry(val atMillis: Long, val operation: String, val result: String)

data class PhoneBackendState(
    val connected: Boolean = false,
    val windowRevision: Long = 0,
    val foregroundPackage: String? = null,
    val windowId: Int? = null,
    val locked: Boolean = true,
)

/** Constructed only by the controller. Backends must recheck this immediately before dispatch. */
data class PhonePermit(
    val token: PhoneSessionToken,
    val targetPackage: String,
    val isValid: () -> Boolean,
    val useRoot: Boolean = false,
    val allowScreenshots: Boolean = false,
)

@Serializable
data class PhoneBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable
data class PhoneNode(
    val id: String,
    val parentId: String? = null,
    val role: String = "",
    val viewId: String = "",
    val text: String = "",
    val description: String = "",
    val bounds: PhoneBounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
)

@Serializable
data class PhoneObservation(
    val id: String,
    val packageName: String,
    val windowId: Int,
    val windowRevision: Long,
    val capturedAtMillis: Long,
    val nodes: List<PhoneNode>,
    val truncated: Boolean,
    val sensitive: Boolean,
    val fingerprint: String,
)

enum class PhoneSwipeDirection { UP, DOWN, LEFT, RIGHT }

sealed interface PhoneAction {
    data class Click(val nodeId: String) : PhoneAction
    data class LongClick(val nodeId: String) : PhoneAction
    data class InputText(val nodeId: String, val text: String) : PhoneAction
    data class Scroll(val nodeId: String, val forward: Boolean) : PhoneAction
    data class Swipe(val direction: PhoneSwipeDirection) : PhoneAction
    data object Back : PhoneAction
    data object OpenApp : PhoneAction
    data object Screenshot : PhoneAction
}

data class PhoneBackendResult(
    val accepted: Boolean,
    val detail: String,
    val screenshotUri: String? = null,
)

@Serializable
data class PhoneActionResult(
    val accepted: Boolean,
    val screenChanged: Boolean = false,
    val detail: String,
    val observation: PhoneObservation? = null,
    val screenshotUri: String? = null,
)

/** Messages must be fixed local descriptions, never raw platform errors or user input. */
class PhoneControlException(val code: String, message: String) : IllegalStateException(message)

interface PhoneBackend {
    val state: StateFlow<PhoneBackendState>
    val supportsScreenshot: Boolean get() = false
    fun isTargetAllowed(packageName: String): Boolean
    fun showSessionNotice(token: PhoneSessionToken, targetPackage: String, onStop: (PhoneBackendStopReason) -> Unit): Boolean
    fun endSessionNotice()
    suspend fun observe(permit: PhonePermit): PhoneObservation
    suspend fun execute(
        permit: PhonePermit,
        observation: PhoneObservation?,
        action: PhoneAction,
    ): PhoneBackendResult
    /** Clears handles and cancels in-flight commands/gestures where possible. Must not block STOP. */
    fun invalidate()
}
