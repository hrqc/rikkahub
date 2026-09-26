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

    @Test fun `automatic task selection never probes a device without verified history`() = runBlocking {
        var calls = 0
        val repository = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, RootProbe {
            calls++
            RootCapability(RootState.ROOT_GRANTED)
        })
        repeat(3) { assertFalse(repository.ensureRootForTask()) }
        assertEquals(0, calls)
        assertFalse(repository.canUseRootNow())
    }

    @Test fun `successful verification is reused briefly but a process restart must recheck`() = runBlocking {
        val preferences = MemoryRootUsagePreferences()
        var clock = 1_000L
        var calls = 0
        val probe = RootProbe { calls++; RootCapability(RootState.ROOT_GRANTED) }
        val repository = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, probe, { clock }, preferences)
        repository.requestRoot()
        repeat(3) { assertTrue(repository.ensureRootForTask()) }
        assertEquals(1, calls)
        clock += 60_000
        assertTrue(repository.ensureRootForTask())
        assertEquals(2, calls)
        val restarted = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, probe, { clock }, preferences)
        assertEquals(RootState.UNKNOWN, restarted.capabilities.value.root.state)
        assertFalse(restarted.canUseRootNow())
        assertTrue(restarted.ensureRootForTask())
        assertEquals(3, calls)
    }

    @Test fun `explicit off preference persists and never launches an automatic probe`() = runBlocking {
        val preferences = MemoryRootUsagePreferences()
        var calls = 0
        val probe = RootProbe { calls++; RootCapability(RootState.ROOT_GRANTED) }
        val repository = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, probe, rootUsagePreferences = preferences)
        repository.requestRoot()
        repository.setRootUsageEnabled(false)
        assertFalse(repository.canUseRootNow())
        assertFalse(repository.ensureRootForTask())
        val restarted = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, probe, rootUsagePreferences = preferences)
        assertFalse(restarted.capabilities.value.rootUsage.enabled)
        assertFalse(restarted.ensureRootForTask())
        assertEquals(1, calls)
    }

    @Test fun `failed recheck stops automatic su calls even across restart until explicit verification`() = runBlocking {
        val preferences = MemoryRootUsagePreferences(RootUsageSettings(previouslyVerified = true, autoVerificationAllowed = true))
        var calls = 0
        var granted = false
        val probe = RootProbe { calls++; RootCapability(if (granted) RootState.ROOT_GRANTED else RootState.ROOT_DENIED) }
        val repository = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, probe, rootUsagePreferences = preferences)
        repeat(3) { assertFalse(repository.ensureRootForTask()) }
        assertEquals(1, calls)
        val restarted = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, probe, rootUsagePreferences = preferences)
        assertFalse(restarted.ensureRootForTask())
        assertEquals(1, calls)
        granted = true
        restarted.requestRoot()
        assertTrue(restarted.ensureRootForTask())
        assertEquals(2, calls)
    }

    @Test fun `backend root failure immediately clears positive cache and suppresses repeated attempts`() = runBlocking {
        var calls = 0
        val repository = DeviceCapabilityRepository(PassiveDeviceProbe { snapshot }, RootProbe {
            calls++
            RootCapability(RootState.ROOT_GRANTED)
        })
        repository.requestRoot()
        assertTrue(repository.canUseRootNow())
        repository.reportRootUnavailable()
        assertFalse(repository.canUseRootNow())
        repeat(3) { assertFalse(repository.ensureRootForTask()) }
        assertTrue(repository.capabilities.value.rootUsage.enabled)
        assertFalse(repository.capabilities.value.rootUsage.autoVerificationAllowed)
        assertEquals(1, calls)
    }

    @Test fun `automatic task check uses the short probe and does not queue concurrent su requests`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var manualCalls = 0
            var automaticCalls = 0
            val repository = DeviceCapabilityRepository(
                PassiveDeviceProbe { snapshot },
                RootProbe { manualCalls++; RootCapability(RootState.ROOT_GRANTED) },
                rootUsagePreferences = MemoryRootUsagePreferences(RootUsageSettings(previouslyVerified = true, autoVerificationAllowed = true)),
                automaticRootProbe = RootProbe {
                    automaticCalls++
                    entered.complete(Unit)
                    release.await()
                    RootCapability(RootState.ROOT_GRANTED)
                },
            )
            val first = async { repository.ensureRootForTask() }
            entered.await()
            assertFalse(repository.ensureRootForTask())
            assertEquals(1, automaticCalls)
            assertEquals(0, manualCalls)
            release.complete(Unit)
            assertTrue(first.await())
        }
    }
}
