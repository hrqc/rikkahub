package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.*
import org.junit.Test

class PhoneTaskControlRegistryTest {
    private val initial = PhoneSessionToken("chat", "assistant", "session", 1L)

    @Test fun `pause epoch retains owner and resume invokes its task continuation with current token`() {
        var state = PhoneSessionState(token = initial, status = PhoneSessionStatus.RUNNING)
        val registry = PhoneTaskControlRegistry { state }
        var resumedJobs = 0
        registry.register(Any(), initial,
            onPause = { state = state.copy(token = initial.copy(epoch = 2L), status = PhoneSessionStatus.PAUSED) },
            onResume = {
                resumedJobs++
                state = state.copy(token = initial.copy(epoch = 3L), status = PhoneSessionStatus.RUNNING)
            },
            onStop = { state = state.copy(token = initial.copy(epoch = 4L), status = PhoneSessionStatus.STOPPED) },
        )

        assertTrue(registry.dispatch(initial, PhoneTaskControl.PAUSE))
        val paused = state.token!!
        assertFalse(registry.canResume(initial))
        assertFalse(registry.dispatch(initial, PhoneTaskControl.RESUME))
        assertTrue(registry.canResume(paused))
        assertTrue(registry.dispatch(paused, PhoneTaskControl.RESUME))
        assertEquals(1, resumedJobs)
        assertFalse(registry.dispatch(paused, PhoneTaskControl.RESUME))
        assertTrue(registry.dispatch(state.token!!, PhoneTaskControl.STOP))
        assertFalse(registry.canResume(state.token!!))
    }

    @Test fun `unregister compares owner identity and old owner cannot clear replacement`() {
        data class Owner(val id: Int)
        val state = PhoneSessionState(token = initial, status = PhoneSessionStatus.PAUSED)
        val registry = PhoneTaskControlRegistry { state }
        val old = Owner(1)
        val replacement = Owner(1)
        var oldResumes = 0
        var newResumes = 0
        registry.register(old, initial, {}, { oldResumes++ }, {})
        registry.register(replacement, initial, {}, { newResumes++ }, {})
        registry.unregister(old)
        assertTrue(registry.dispatch(initial, PhoneTaskControl.RESUME))
        assertEquals(0, oldResumes)
        assertEquals(1, newResumes)
        registry.unregister(replacement)
        assertFalse(registry.canResume(initial))
        assertFalse(registry.dispatch(initial, PhoneTaskControl.RESUME))
    }

    @Test fun `stale registration clearing and clicks cannot affect a replacement grant`() {
        var state = PhoneSessionState(token = initial, status = PhoneSessionStatus.RUNNING)
        val registry = PhoneTaskControlRegistry { state }
        val oldOwner = Any()
        var oldCalls = 0
        registry.register(oldOwner, initial, { oldCalls++ }, { oldCalls++ }, { oldCalls++ })
        val newer = initial.copy(sessionId = "new-session", epoch = 8L)
        state = state.copy(token = newer)
        assertFalse(registry.dispatch(newer, PhoneTaskControl.PAUSE))

        var newCalls = 0
        registry.register(Any(), newer, { newCalls++ }, { newCalls++ }, { newCalls++ })
        registry.register(oldOwner, initial, { oldCalls++ }, { oldCalls++ }, { oldCalls++ })
        registry.clearSession(initial.sessionId)
        registry.unregister(oldOwner)
        assertFalse(registry.dispatch(initial, PhoneTaskControl.STOP))
        assertTrue(registry.dispatch(newer, PhoneTaskControl.PAUSE))
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
        registry.clearSession(newer.sessionId)
        assertFalse(registry.dispatch(newer, PhoneTaskControl.STOP))
    }

    @Test fun `conversation and assistant identity cannot borrow another task owner`() {
        var state = PhoneSessionState(token = initial, status = PhoneSessionStatus.PAUSED)
        val registry = PhoneTaskControlRegistry { state }
        registry.register(Any(), initial, { fail("Wrong owner") }, { fail("Wrong owner") }, { fail("Wrong owner") })
        listOf(initial.copy(conversationId = "other"), initial.copy(assistantId = "other")).forEach { replacement ->
            state = state.copy(token = replacement)
            assertFalse(registry.canResume(replacement))
            assertFalse(registry.dispatch(replacement, PhoneTaskControl.RESUME))
            assertFalse(registry.dispatch(replacement, PhoneTaskControl.STOP))
        }
    }

    @Test fun `controls enforce lifecycle status and never resume an ended task`() {
        PhoneSessionStatus.entries.forEach { status ->
            var state = PhoneSessionState(token = initial, status = PhoneSessionStatus.RUNNING)
            val registry = PhoneTaskControlRegistry { state }
            registry.register(Any(), initial, {}, {}, {})
            state = state.copy(status = status)
            assertEquals(status == PhoneSessionStatus.RUNNING, registry.dispatch(initial, PhoneTaskControl.PAUSE))
            assertEquals(status in setOf(PhoneSessionStatus.PAUSED, PhoneSessionStatus.WAITING_FOR_FOREGROUND),
                registry.dispatch(initial, PhoneTaskControl.RESUME))
            assertEquals(status in setOf(PhoneSessionStatus.RUNNING, PhoneSessionStatus.PAUSED, PhoneSessionStatus.WAITING_FOR_FOREGROUND),
                registry.dispatch(initial, PhoneTaskControl.STOP))
        }
    }

    @Test fun `missing or failing owner cannot claim a working resume route`() {
        val state = PhoneSessionState(token = initial, status = PhoneSessionStatus.PAUSED)
        val registry = PhoneTaskControlRegistry { state }
        assertFalse(registry.canResume(initial))
        assertFalse(registry.dispatch(initial, PhoneTaskControl.RESUME))
        registry.register(Any(), initial, {}, { throw IllegalStateException("Owner unavailable") }, {})
        assertFalse(registry.dispatch(initial, PhoneTaskControl.RESUME))
        assertEquals(PhoneSessionStatus.PAUSED, state.status)
    }
}
