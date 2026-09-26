package me.rerere.rikkahub.data.mobileagent

internal data class PhoneTextSample(val value: String, val sensitive: Boolean, val truncated: Boolean)

/** Inspect a bounded full field before shortening the public preview. Password getters stay unread. */
internal fun samplePhoneNodeText(protected: Boolean, read: () -> CharSequence?): PhoneTextSample {
    if (protected) return PhoneTextSample("", sensitive = true, truncated = false)
    val raw = read() ?: return PhoneTextSample("", sensitive = false, truncated = false)
    // We cannot establish the safety of content that is too large to inspect.
    if (raw.length > 4_096) return PhoneTextSample("", sensitive = true, truncated = true)
    val full = raw.toString()
    val sensitive = PhoneContentPolicy.isSensitive(full)
    return PhoneTextSample(if (sensitive) "" else full.take(240), sensitive, full.length > 240)
}

internal class PhoneTreeReadBudget(private val startedAtMillis: Long) {
    var visits = 0
        private set
    private var emitted = 0
    private var textChars = 0
    var truncated = false
        private set

    fun visit(depth: Int, nowMillis: Long): Boolean {
        if (visits >= 256 || depth > 24 || nowMillis - startedAtMillis > 1_500) {
            truncated = true
            return false
        }
        visits++
        return true
    }

    fun include(textLength: Int): Boolean {
        require(textLength >= 0)
        if (emitted >= 100 || textChars.toLong() + textLength > 12_000) {
            truncated = true
            return false
        }
        emitted++
        textChars += textLength
        return true
    }

    fun canContinue(nowMillis: Long): Boolean {
        if (visits >= 256 || nowMillis - startedAtMillis > 1_500) {
            truncated = true
            return false
        }
        return true
    }

    fun markTruncated() { truncated = true }
}
