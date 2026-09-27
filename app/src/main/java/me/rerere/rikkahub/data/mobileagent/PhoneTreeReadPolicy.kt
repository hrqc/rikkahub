package me.rerere.rikkahub.data.mobileagent

import java.security.MessageDigest

internal const val PRODUCTION_PHONE_TREE_NODE_LIMIT = 768

// AccessibilityServiceInfo: INCLUDE_NOT_IMPORTANT_VIEWS | REPORT_VIEW_IDS | RETRIEVE_INTERACTIVE_WINDOWS.
private const val PRODUCTION_PHONE_READ_FLAGS = 0x2 or 0x10 or 0x40

/** Additional service flags are allowed; every flag needed by the production tree must remain set. */
internal fun hasProductionPhoneReadFlags(flags: Int?): Boolean =
    flags != null && (flags and PRODUCTION_PHONE_READ_FLAGS) == PRODUCTION_PHONE_READ_FLAGS

internal data class PhoneTextSample(
    val value: String, val sensitive: Boolean, val truncated: Boolean,
    val inspectionIncomplete: Boolean = false, val requiresUserConfirmation: Boolean = false,
    val contentFingerprint: String = "",
)

/** Inspect a bounded full field before shortening the public preview. Password getters stay unread. */
internal fun samplePhoneNodeText(protected: Boolean, read: () -> CharSequence?): PhoneTextSample {
    if (protected) return PhoneTextSample("", sensitive = true, truncated = false)
    val raw = read() ?: return PhoneTextSample("", sensitive = false, truncated = false)
    // We cannot establish the safety of content that is too large to inspect.
    if (raw.length > 16_384) return PhoneTextSample("", sensitive = false, truncated = true, inspectionIncomplete = true)
    val full = raw.toString()
    val sensitive = PhoneContentPolicy.isSensitive(full)
    return PhoneTextSample(if (sensitive) "" else full.take(240), sensitive, full.length > 240,
        requiresUserConfirmation = PhonePurchasePolicy.requiresUser(full),
        contentFingerprint = if (sensitive) "" else MessageDigest.getInstance("SHA-256")
            .digest(full.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
}

internal class PhoneTreeReadBudget(
    private val startedAtMillis: Long,
    private val maxVisits: Int = PRODUCTION_PHONE_TREE_NODE_LIMIT,
) {
    init { require(maxVisits in 1..PRODUCTION_PHONE_TREE_NODE_LIMIT) }
    var visits = 0
        private set
    private var emitted = 0
    private var textChars = 0
    var truncated = false
        private set
    var previewTruncated = false
        private set
    val issues = linkedSetOf<String>()

    fun visit(depth: Int, nowMillis: Long): Boolean {
        if (visits >= maxVisits || depth > 40 || nowMillis - startedAtMillis > 2_000) {
            markTruncated(when { visits >= maxVisits -> "visit_limit"; depth > 40 -> "depth_limit"; else -> "time_limit" })
            return false
        }
        visits++
        return true
    }

    fun include(textLength: Int): Boolean {
        require(textLength >= 0)
        if (emitted >= 100 || textChars.toLong() + textLength > 12_000) {
            previewTruncated = true
            return false
        }
        emitted++
        textChars += textLength
        return true
    }

    fun canContinue(nowMillis: Long): Boolean {
        if (visits >= maxVisits || nowMillis - startedAtMillis > 2_000) {
            markTruncated(if (visits >= maxVisits) "visit_limit" else "time_limit")
            return false
        }
        return true
    }

    fun markTruncated(reason: String) { truncated = true; issues += reason }
    fun markPreviewTruncated() { previewTruncated = true }
}
