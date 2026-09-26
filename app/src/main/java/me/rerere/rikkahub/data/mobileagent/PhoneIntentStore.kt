package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PhoneIntentBinding(
    val id: String,
    val conversationId: String,
    val assistantId: String,
    val userMessageId: String,
    val originalText: String,
)

data class PhoneIntentProposal(
    val id: String,
    val binding: PhoneIntentBinding,
    val targetAppName: String,
    val summary: String,
)

/** In-memory proposals only. This store cannot read the phone or grant device access. */
class PhoneIntentStore(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private data class Entry(
        val binding: PhoneIntentBinding,
        var staged: PhoneIntentProposal? = null,
        var completed: Boolean = false,
        var consumed: Boolean = false,
    )

    private val gate = Any()
    private val entries = mutableMapOf<String, Entry>()
    // Keep only nonces, not old user text. An old asynchronous begin cannot revive a
    // consumed/cancelled request, even after another request replaced it.
    private val seenBindingIds = mutableSetOf<String>()
    private val mutableProposals = MutableStateFlow<Map<String, PhoneIntentProposal>>(emptyMap())
    val proposals: StateFlow<Map<String, PhoneIntentProposal>> = mutableProposals.asStateFlow()

    fun begin(binding: PhoneIntentBinding): Unit = synchronized(gate) {
        require(binding.id.isNotBlank() && binding.conversationId.isNotBlank() &&
            binding.assistantId.isNotBlank() && binding.userMessageId.isNotBlank())
        if (!seenBindingIds.add(binding.id)) return@synchronized
        entries[binding.conversationId] = Entry(binding)
        mutableProposals.value = mutableProposals.value - binding.conversationId
    }

    fun propose(binding: PhoneIntentBinding, targetAppName: String, summary: String): PhoneIntentProposal? =
        synchronized(gate) {
            val entry = currentEntry(binding) ?: return@synchronized null
            if (entry.completed || entry.consumed || entry.staged != null || binding.originalText.isBlank()) return@synchronized null
            if (targetAppName.length > 256 || summary.length > 2_000) return@synchronized null
            PhoneIntentProposal(newId(), binding, targetAppName.trim(), summary.trim()).also {
                entry.staged = it
            }
        }

    /** Publish only after the originating generation has finished successfully. */
    fun complete(binding: PhoneIntentBinding): Unit = synchronized(gate) {
        val entry = currentEntry(binding) ?: return@synchronized
        if (entry.completed || entry.consumed) return@synchronized
        entry.completed = true
        entry.staged?.let { proposal ->
            mutableProposals.value = mutableProposals.value + (binding.conversationId to proposal)
        }
        Unit
    }

    fun invalidate(conversationId: String): Unit = synchronized(gate) {
        entries.remove(conversationId)
        mutableProposals.value = mutableProposals.value - conversationId
    }

    /** A late failure from an old generation must not invalidate its replacement. */
    fun invalidate(binding: PhoneIntentBinding): Unit = synchronized(gate) {
        if (currentEntry(binding) == null) return@synchronized
        entries.remove(binding.conversationId)
        mutableProposals.value = mutableProposals.value - binding.conversationId
    }

    fun consume(conversationId: String, proposalId: String): PhoneIntentProposal? = synchronized(gate) {
        val proposal = mutableProposals.value[conversationId] ?: return@synchronized null
        if (proposal.id != proposalId) return@synchronized null
        val entry = currentEntry(proposal.binding) ?: return@synchronized null
        if (!entry.completed || entry.consumed || entry.staged != proposal) return@synchronized null
        entry.consumed = true
        entry.staged = null
        mutableProposals.value = mutableProposals.value - conversationId
        proposal
    }

    // Consumption does not invalidate the binding: launch preparation still needs to
    // detect a later user message/cancel while waiting for the target window.
    fun isCurrent(binding: PhoneIntentBinding): Boolean = synchronized(gate) { currentEntry(binding) != null }

    private fun currentEntry(binding: PhoneIntentBinding): Entry? =
        entries[binding.conversationId]?.takeIf { it.binding == binding }
}
