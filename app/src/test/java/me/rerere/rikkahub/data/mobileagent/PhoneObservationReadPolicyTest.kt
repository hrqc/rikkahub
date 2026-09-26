package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneObservationReadPolicyTest {
    private class ReadFixture {
        var now = 0L
        var current = PhoneObservationVersion(windowId = 1, identity = 1, revision = 1)
        var denied: String? = null
        var reads = 0
        var waits = 0
        var duringWait: () -> Unit = {}

        suspend fun read(capture: (PhoneObservationVersion) -> String): String = readStablePhoneObservation(
            nowMillis = { now },
            version = {
                denied?.let { throw PhoneControlException(it, "revoked") }
                current
            },
            capture = { reads++; capture(it) },
            pause = { now += it; waits++; duringWait() },
        )
    }

    private suspend fun expectCode(code: String, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected $code but got $failure", failure is PhoneControlException)
        assertEquals(code, (failure as PhoneControlException).code)
    }

    @Test fun `stable first read returns its original revision without waiting`() = runBlocking {
        val f = ReadFixture()
        assertEquals("revision=1", f.read { "revision=${it.revision}" })
        assertEquals(1, f.reads)
        assertEquals(0, f.waits)
    }

    @Test fun `same window content invalidation reacquires and returns only the stable read`() = runBlocking {
        val f = ReadFixture()
        assertEquals("revision=2", f.read {
            if (f.reads == 1) {
                f.current = f.current.copy(revision = 2)
                throw PhoneObservationInvalidated()
            }
            "revision=${it.revision}"
        })
        assertEquals(2, f.reads)
        assertEquals(1, f.waits)
    }

    @Test fun `change between capture and publication discards the old result`() = runBlocking {
        val f = ReadFixture()
        assertEquals("revision=2", f.read {
            if (f.reads == 1) f.current = f.current.copy(revision = 2)
            "revision=${it.revision}"
        })
        assertEquals(2, f.reads)
    }

    @Test fun `continuous content churn stops after four reads with distinct page unstable code`() = runBlocking {
        val f = ReadFixture()
        expectCode("PAGE_UNSTABLE") {
            f.read {
                f.current = f.current.copy(revision = f.current.revision + 1)
                throw PhoneObservationInvalidated()
            }
        }
        assertEquals(4, f.reads)
        assertEquals(3, f.waits)
    }

    @Test fun `elapsed budget ends retries before the attempt limit`() = runBlocking {
        val f = ReadFixture()
        expectCode("PAGE_UNSTABLE") {
            f.read {
                f.now = 4_500
                throw PhoneObservationInvalidated()
            }
        }
        assertEquals(1, f.reads)
        assertEquals(0, f.waits)
    }

    @Test fun `an over budget successful tree is not published`() = runBlocking {
        val f = ReadFixture()
        expectCode("PAGE_UNSTABLE") { f.read { f.now = 4_500; "too late" } }
        assertEquals(1, f.reads)
    }

    @Test fun `switching window within same package never retries the new window`() = runBlocking {
        val f = ReadFixture()
        expectCode("STALE_WINDOW") {
            f.read {
                f.current = f.current.copy(windowId = 2, identity = 2, revision = 2)
                throw PhoneObservationInvalidated()
            }
        }
        assertEquals(1, f.reads)
        assertEquals(0, f.waits)
    }

    @Test fun `switching away and back keeps identity change and rejects the old read`() = runBlocking {
        val f = ReadFixture()
        f.duringWait = { f.current = f.current.copy(identity = 3, revision = 3) }
        expectCode("STALE_WINDOW") { f.read { throw PhoneObservationInvalidated() } }
        assertEquals(1, f.reads)
        assertEquals(1, f.waits)
    }

    @Test fun `stop lock disconnect and foreground conflict take priority over content retries`() = runBlocking {
        listOf("SESSION_INACTIVE", "DEVICE_LOCKED", "ACCESSIBILITY_DISCONNECTED", "FOREGROUND_CONFLICT").forEach { code ->
            val f = ReadFixture()
            expectCode(code) {
                f.read {
                    f.denied = code
                    throw PhoneObservationInvalidated()
                }
            }
            assertEquals(1, f.reads)
            assertEquals(0, f.waits)
        }
    }

    @Test fun `stop during retry wait prevents another capture`() = runBlocking {
        val f = ReadFixture()
        f.duringWait = { f.denied = "SESSION_INACTIVE" }
        expectCode("SESSION_INACTIVE") { f.read { throw PhoneObservationInvalidated() } }
        assertEquals(1, f.reads)
    }

    @Test fun `cancellation and unrelated capture failures are never retried`() = runBlocking {
        val f = ReadFixture()
        val cancelled = CancellationException("cancelled")
        val failure = runCatching { f.read { throw cancelled } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure?.message)
        assertEquals(1, f.reads)
        assertEquals(0, f.waits)
        val other = ReadFixture()
        expectCode("WINDOW_UNAVAILABLE") { other.read { throw PhoneControlException("WINDOW_UNAVAILABLE", "unavailable") } }
        assertEquals(1, other.reads)
        assertEquals(0, other.waits)
    }

    private fun target(active: Boolean = true) = PhoneWindowMetadata(1, active, focused = true, accessibilityOverlay = false)
    private fun overlay(id: Int = 2, focused: Boolean = false, overlayType: Boolean = true) =
        PhoneWindowMetadata(id, active = true, focused = focused, accessibilityOverlay = overlayType)

    @Test fun `registered own nonfocused overlay selects underlying focused target only`() {
        assertEquals(1, selectPhoneWindowId(listOf(overlay(), target(active = false)), controlOverlayId = 2))
        assertEquals(null, selectPhoneWindowId(listOf(overlay()), controlOverlayId = 2))
    }

    @Test fun `unregistered foreign focused or wrong type overlays are never filtered`() {
        assertEquals(2, selectPhoneWindowId(listOf(overlay(), target()), controlOverlayId = null))
        assertEquals(3, selectPhoneWindowId(listOf(overlay(3), target()), controlOverlayId = 2))
        assertEquals(2, selectPhoneWindowId(listOf(overlay(focused = true), target()), controlOverlayId = 2))
        assertEquals(2, selectPhoneWindowId(listOf(overlay(overlayType = false), target()), controlOverlayId = 2))
    }

    @Test fun `system window above own overlay remains the selected foreground`() {
        val system = PhoneWindowMetadata(3, active = true, focused = true, accessibilityOverlay = false)
        assertEquals(3, selectPhoneWindowId(listOf(overlay(), system, target()), controlOverlayId = 2))
    }

    @Test fun `only verified own overlay broadcasts can be excluded from target revision`() {
        assertFalse(shouldInvalidatePhoneWindowRevision(2, 1, structuralChange = true, verifiedControlOverlay = true))
        assertFalse(shouldInvalidatePhoneWindowRevision(2, 1, structuralChange = false, verifiedControlOverlay = true))
        assertTrue(shouldInvalidatePhoneWindowRevision(1, 1, structuralChange = false, verifiedControlOverlay = false))
        assertTrue(shouldInvalidatePhoneWindowRevision(2, 1, structuralChange = true, verifiedControlOverlay = false))
        assertTrue(shouldInvalidatePhoneWindowRevision(-1, 1, structuralChange = true, verifiedControlOverlay = false))
    }

    @Test fun `late event for B aborts a read even when live metadata is already back at A`() = runBlocking {
        val f = ReadFixture()
        expectCode("STALE_WINDOW") {
            f.read {
                // Production processes the queued B event before refreshing the live list (now A).
                if (shouldChangePhoneWindowIdentity(2, f.current.windowId, true, false)) {
                    f.current = f.current.copy(identity = f.current.identity + 1)
                }
                if (shouldInvalidatePhoneWindowRevision(2, f.current.windowId, true, false)) {
                    f.current = f.current.copy(revision = f.current.revision + 1)
                }
                throw PhoneObservationInvalidated()
            }
        }
        assertEquals(1, f.current.windowId)
        assertEquals(1, f.reads)
        assertEquals(0, f.waits)
    }

    @Test fun `unknown or external structural events cannot be treated as same window churn`() {
        assertTrue(shouldChangePhoneWindowIdentity(-1, 1, true, false))
        assertTrue(shouldChangePhoneWindowIdentity(3, 1, true, false))
        assertTrue(shouldChangePhoneWindowIdentity(-1, null, true, false))
        assertFalse(shouldChangePhoneWindowIdentity(1, 1, true, false))
        assertFalse(shouldChangePhoneWindowIdentity(1, 1, false, false))
        assertFalse(shouldChangePhoneWindowIdentity(2, 1, true, true))
    }
}
