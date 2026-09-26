package me.rerere.rikkahub.data.mobileagent

enum class PhoneTaskControl { PAUSE, RESUME, STOP }

/**
 * Routes controls back to the in-memory owner that knows how to continue the real task.
 * The controller dispatches under its own lifecycle gate; callers must use that entry
 * point so token validation and the synchronous owner callback cannot cross a new grant.
 */
class PhoneTaskControlRegistry(private val currentState: () -> PhoneSessionState) {
    private data class Entry(
        val owner: Any,
        val token: PhoneSessionToken,
        val onPause: () -> Unit,
        val onResume: () -> Unit,
        val onStop: () -> Unit,
    )

    private val gate = Any()
    private var entry: Entry? = null

    fun register(
        owner: Any,
        token: PhoneSessionToken,
        onPause: () -> Unit,
        onResume: () -> Unit,
        onStop: () -> Unit,
    ) = synchronized(gate) {
        val current = currentState()
        if (current.token != token || !isLive(current.status)) return@synchronized
        entry = Entry(owner, token, onPause, onResume, onStop)
    }

    fun unregister(owner: Any) = synchronized(gate) {
        if (entry?.owner === owner) entry = null
    }

    fun clearSession(sessionId: String) = synchronized(gate) {
        if (entry?.token?.sessionId == sessionId) entry = null
    }

    fun canResume(expectedToken: PhoneSessionToken): Boolean = synchronized(gate) {
        matchingEntry(expectedToken, PhoneTaskControl.RESUME) != null
    }

    /** Never falls back to controller.resume: a missing owner cannot rebuild a model job. */
    fun dispatch(expectedToken: PhoneSessionToken, action: PhoneTaskControl): Boolean = synchronized(gate) {
        val owner = matchingEntry(expectedToken, action) ?: return@synchronized false
        val callback = when (action) {
            PhoneTaskControl.PAUSE -> owner.onPause
            PhoneTaskControl.RESUME -> owner.onResume
            PhoneTaskControl.STOP -> owner.onStop
        }
        try {
            callback()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun matchingEntry(expected: PhoneSessionToken, action: PhoneTaskControl): Entry? {
        val current = currentState()
        if (current.token != expected) return null
        val candidate = entry ?: return null
        // Pausing revokes the old epoch. The task owner remains valid for the same grant,
        // while each button event must still carry the complete current token above.
        if (candidate.token.sessionId != expected.sessionId ||
            candidate.token.conversationId != expected.conversationId ||
            candidate.token.assistantId != expected.assistantId
        ) return null
        val allowed = when (action) {
            PhoneTaskControl.PAUSE -> current.status == PhoneSessionStatus.RUNNING
            PhoneTaskControl.RESUME -> current.status == PhoneSessionStatus.PAUSED ||
                current.status == PhoneSessionStatus.WAITING_FOR_FOREGROUND
            PhoneTaskControl.STOP -> isLive(current.status)
        }
        return candidate.takeIf { allowed }
    }

    private fun isLive(status: PhoneSessionStatus) = status == PhoneSessionStatus.RUNNING ||
        status == PhoneSessionStatus.PAUSED || status == PhoneSessionStatus.WAITING_FOR_FOREGROUND
}
