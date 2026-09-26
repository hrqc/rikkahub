package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val officialRelease = UpdateInfo("2.5.4", "2026-09-26T00:00:00Z", "release", emptyList())

    @Test
    fun `fork build disables the official upstream update source`() {
        assertFalse(BuildConfig.UPSTREAM_UPDATES_ENABLED)
    }

    @Test
    fun `disabled updater never invokes the feed loader or reports a successful check`() = runBlocking {
        var requests = 0
        val states = updateCheckFlow(upstreamUpdatesEnabled = false) {
            requests++
            officialRelease
        }.toList()

        assertEquals(0, requests)
        assertEquals(listOf(UiState.Idle), states)
    }

    @Test
    fun `an enabled feed still returns its release without changing version precedence`() = runBlocking {
        val states = updateCheckFlow(upstreamUpdatesEnabled = true) { officialRelease }.toList()
        assertEquals(listOf(UiState.Loading, UiState.Success(officialRelease)), states)
        assertTrue(Version("2.5.4-mobile-agent-v1-m1") < Version(officialRelease.version))
    }

    @Test
    fun `feed failure is an error and not an assertion that this is the newest version`() = runBlocking {
        val failure = IllegalStateException("offline")
        val states = updateCheckFlow(upstreamUpdatesEnabled = true) { throw failure }.toList()
        assertEquals(UiState.Loading, states.first())
        assertSame(failure, (states.last() as UiState.Error).error)
        assertFalse(states.any { it is UiState.Success<*> })
    }

    @Test
    fun `cancelling an enabled request does not become an update error`() = runBlocking {
        val states = mutableListOf<UiState<UpdateInfo>>()
        var cancelled = false
        try {
            updateCheckFlow(upstreamUpdatesEnabled = true) {
                throw CancellationException("stop")
            }.toList(states)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(listOf(UiState.Loading), states)
    }
}
