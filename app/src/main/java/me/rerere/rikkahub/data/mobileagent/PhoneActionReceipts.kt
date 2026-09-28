package me.rerere.rikkahub.data.mobileagent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.phoneToolPrefix

/** Host identities only. Arguments, node contents and screenshots never enter this ledger. */
internal data class PhoneActionReceiptKey(val messageId: String, val toolCallId: String, val toolName: String)

internal data class PhoneActionReceipt(
    val accepted: Boolean,
    val postObserveError: String? = null,
    val observationVerified: Boolean = false,
    val screenChanged: Boolean? = null,
    val executor: PhoneActionExecutor? = null,
) {
    fun output(): List<UIMessagePart> = listOf(UIMessagePart.Text(buildJsonObject {
        put("status", "interrupted")
        put("accepted", accepted)
        executor?.let { put("executor", it.wireName) }
        put("execution_outcome", when {
            !accepted -> "not_accepted"
            observationVerified -> "accepted_observed"
            else -> "accepted_unverified"
        })
        put("post_observation_verified", observationVerified)
        postObserveError?.let { put("post_observe_error", it) }
        screenChanged?.let { put("screenChanged", it) }
        put("grantsActionPermission", false)
        put("detail", when {
            !accepted -> "平台明确未接受该动作；本轮调用已结束，不得自动重试。"
            observationVerified -> "平台已接受动作且后续观察已完成，但完整工具结果未发布；这不代表用户任务完成，继续前须重新观察核对。"
            else -> "平台已接受动作，但后续页面未能核实；不能据此确认任务完成，继续前须重新观察核对，不得直接重放。"
        })
    }.toString()))
}

internal class PhoneActionReceiptBatch(
    private val token: PhoneSessionToken,
    private val receipts: Map<PhoneActionReceiptKey, PhoneActionReceipt>,
) {
    fun find(token: PhoneSessionToken, key: PhoneActionReceiptKey): PhoneActionReceipt? =
        if (this.token == token) receipts[key] else null
}

/** One generation owns this bounded map. Freezing prevents every subsequent write. */
internal class PhoneActionReceiptLedger(private val token: PhoneSessionToken) {
    private class Entry(var receipt: PhoneActionReceipt? = null, var ambiguous: Boolean = false)
    private val gate = Any()
    private val entries = linkedMapOf<PhoneActionReceiptKey, Entry>()
    private val allowedToolNames = actionNames.mapTo(mutableSetOf()) { phoneToolPrefix(token) + it }
    private var closed = false

    fun beginCall(messageId: String, toolCallId: String, toolName: String): PhoneActionReceiptContext? = synchronized(gate) {
        if (closed || messageId.length !in 1..128 || toolCallId.length !in 1..128 || toolName.length !in 1..128 ||
            toolName !in allowedToolNames) return@synchronized null
        val key = PhoneActionReceiptKey(messageId, toolCallId, toolName)
        entries[key]?.let {
            it.ambiguous = true
            it.receipt = null
            return@synchronized null
        }
        if (entries.size >= MAX_CALLS) return@synchronized null
        entries[key] = Entry()
        PhoneActionReceiptContext(token, key, PhoneActionReceiptRecorder(token, key, this))
    }

    internal fun update(key: PhoneActionReceiptKey, transform: (PhoneActionReceipt?) -> PhoneActionReceipt?) = synchronized(gate) {
        val entry = entries[key]
        if (!closed && entry != null && !entry.ambiguous) entry.receipt = transform(entry.receipt)
    }

    internal fun peek(key: PhoneActionReceiptKey): PhoneActionReceipt? = synchronized(gate) {
        if (closed) null else entries[key]?.takeUnless { it.ambiguous }?.receipt
    }

    fun freeze(): PhoneActionReceiptBatch = synchronized(gate) {
        val saved = if (closed) emptyMap() else entries.mapNotNull { (key, entry) ->
            entry.receipt?.takeUnless { entry.ambiguous }?.let { key to it }
        }.toMap()
        closed = true
        entries.clear()
        PhoneActionReceiptBatch(token, saved)
    }

    fun clear() = synchronized(gate) { closed = true; entries.clear() }

    companion object {
        const val MAX_CALLS = 64
        private val actionNames = setOf("click", "long_click", "input_text", "scroll", "swipe", "back", "open_app")
    }
}

internal class PhoneActionReceiptContext(
    private val token: PhoneSessionToken,
    private val receiptKey: PhoneActionReceiptKey,
    private val recorder: PhoneActionReceiptRecorder,
) : AbstractCoroutineContextElement(Key) {
    fun forTool(token: PhoneSessionToken, toolName: String): PhoneActionReceiptRecorder? =
        recorder.takeIf { this.token == token && receiptKey.toolName == toolName }

    companion object Key : CoroutineContext.Key<PhoneActionReceiptContext>
}

internal class PhoneActionReceiptRecorder(
    private val token: PhoneSessionToken,
    private val key: PhoneActionReceiptKey,
    private val ledger: PhoneActionReceiptLedger,
) {
    fun forAction(token: PhoneSessionToken, action: PhoneAction): PhoneActionReceiptRecorder? =
        takeIf { this.token == token && phoneActionReceiptOperation(action)?.let { phoneToolPrefix(token) + it } == key.toolName }

    fun backendResult(accepted: Boolean, executor: PhoneActionExecutor? = null) = ledger.update(key) {
        it ?: PhoneActionReceipt(accepted, executor = executor)
    }

    fun postObserveFailed(code: String) = ledger.update(key) { receipt ->
        receipt?.let {
            if (!it.accepted || it.observationVerified || it.postObserveError != null) it
            else it.copy(postObserveError = code.takeIf { it in errorCodes } ?: "POST_OBSERVATION_FAILED")
        }
    }

    fun observed(changed: Boolean) = ledger.update(key) { receipt ->
        receipt?.let { if (!it.accepted || it.postObserveError != null) it else it.copy(observationVerified = true, screenChanged = changed) }
    }

    fun knownResult(): PhoneActionReceipt? = ledger.peek(key)

    private companion object {
        val errorCodes = setOf(
            "PAGE_UNSTABLE", "INCOMPLETE_SCREEN", "USER_REQUIRED", "USER_HANDOVER_REQUIRED",
            "PURCHASE_CONFIRMATION_REQUIRED", "ACTION_RESULT_UNKNOWN", "DEVICE_LOCKED", "FOREGROUND_CONFLICT",
            "FOREGROUND_CHANGED", "TARGET_NOT_FOREGROUND", "WINDOW_TRANSITION", "STOP_UNAVAILABLE",
            "ACCESSIBILITY_DISCONNECTED", "SERVICE_DISCONNECTED", "OBSERVATION_LIMIT", "SESSION_INVALID",
            "SESSION_INACTIVE", "STALE_WINDOW", "OPERATION_FAILED", "CANCELLED",
        )
    }
}

internal fun phoneActionReceiptOperation(action: PhoneAction): String? = when (action) {
    is PhoneAction.Click -> "click"
    is PhoneAction.LongClick -> "long_click"
    is PhoneAction.InputText -> "input_text"
    is PhoneAction.Scroll -> "scroll"
    is PhoneAction.Swipe -> "swipe"
    PhoneAction.Back -> "back"
    PhoneAction.OpenApp -> "open_app"
    PhoneAction.Screenshot -> null
}
