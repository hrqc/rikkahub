package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityRepositoryTest {
    private val snapshot = PassiveDeviceSnapshot("maker", "model", "15", 35, emptyList())

    @Test
    fun `constructing and refreshing never invokes root probe`() = runBlocking {
        var passiveCalls = 0
        var rootCalls = 0
        val repository = DeviceCapabilityRepository(
            PassiveDeviceProbe { passiveCalls++; snapshot },
            RootProbe { rootCalls++; RootCapability(RootState.ROOT_GRANTED) },
            now = { 456L },
        )
        assertEquals(0, passiveCalls)
        assertEquals(0, rootCalls)
        repeat(3) { repository.refresh() }
        assertEquals(3, passiveCalls)
        assertEquals(0, rootCalls)
        assertEquals(RootState.UNKNOWN, repository.capabilities.value.root.state)
        assertEquals("model", repository.capabilities.value.deviceModel)
        assertEquals(456L, repository.capabilities.value.refreshedAtEpochMillis)
    }

    @Test
    fun `only explicit request probes root and another repository starts unknown`() = runBlocking {
        var calls = 0
        val probe = RootProbe { calls++; RootCapability(RootState.ROOT_GRANTED) }
        val repository = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, probe)
        repository.requestRoot()
        assertEquals(1, calls)
        assertEquals(RootState.ROOT_GRANTED, repository.capabilities.value.root.state)
        repository.refresh()
        assertEquals(1, calls)
        assertFalse(repository.capabilities.value.rootProbeRunning)
        val another = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, probe)
        assertEquals(RootState.UNKNOWN, another.capabilities.value.root.state)
    }

    @Test
    fun `duplicate requests are ignored while probe runs and refresh preserves progress`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val repository = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, RootProbe {
                calls++
                entered.complete(Unit)
                release.await()
                RootCapability(RootState.ROOT_GRANTED)
            })
            val request = async { repository.requestRoot() }
            entered.await()
            assertTrue(repository.capabilities.value.rootProbeRunning)
            repository.requestRoot()
            repository.refresh()
            assertEquals(1, calls)
            assertTrue(repository.capabilities.value.rootProbeRunning)
            release.complete(Unit)
            request.await()
            assertFalse(repository.capabilities.value.rootProbeRunning)
        }
    }

    @Test
    fun `cancel button stops probe without cancelling caller scope and permits retry`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            var cleaned = false
            var calls = 0
            val repository = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, RootProbe {
                calls++
                if (calls == 1) {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cleaned = true
                    }
                }
                RootCapability(RootState.ROOT_GRANTED)
            })
            val request = launch { repository.requestRoot() }
            entered.await()
            repository.cancelRootProbe()
            request.join()
            assertTrue(request.isCancelled)
            assertTrue(cleaned)
            assertFalse(repository.capabilities.value.rootProbeRunning)
            assertEquals(RootState.UNKNOWN, repository.capabilities.value.root.state)
            repository.requestRoot()
            assertEquals(2, calls)
            assertEquals(RootState.ROOT_GRANTED, repository.capabilities.value.root.state)
        }
    }

    @Test
    fun `cancelling caller also clears running state and releases the probe lock`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val repository = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, RootProbe {
                entered.complete(Unit)
                awaitCancellation()
            })
            val request = launch { repository.requestRoot() }
            entered.await()
            request.cancelAndJoin()
            assertFalse(repository.capabilities.value.rootProbeRunning)
            assertEquals(RootState.UNKNOWN, repository.capabilities.value.root.state)
        }
    }
}
