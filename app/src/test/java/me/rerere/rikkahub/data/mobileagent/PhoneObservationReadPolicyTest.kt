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

        suspend fun read(
            shouldRetry: (String) -> Boolean = { false },
            acceptLastStable: (String) -> Boolean = { false },
            capture: (PhoneObservationVersion) -> String,
        ): String = readStablePhoneObservation(
            nowMillis = { now },
            version = {
                denied?.let { throw PhoneControlException(it, "revoked") }
                current
            },
            capture = { reads++; capture(it) },
            pause = { now += it; waits++; duringWait() },
            shouldRetry = shouldRetry,
            acceptLastStable = acceptLastStable,
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

    @Test fun `only a non-sensitive capture with solely unavailable children can be retried`() {
        assertTrue(shouldRetryPhoneInspection(false, true, listOf("unavailable_child")))
        assertFalse(shouldRetryPhoneInspection(true, true, listOf("unavailable_child")))
        assertFalse(shouldRetryPhoneInspection(false, false, listOf("unavailable_child")))
        // An output preview may be shortened while the actual inspection remains complete.
        assertFalse(shouldRetryPhoneInspection(false, false, emptyList()))
        assertFalse(shouldRetryPhoneInspection(false, true, emptyList()))
        listOf("foreign_node", "children_limit", "visit_limit", "time_limit", "depth_limit", "text_limit").forEach { issue ->
            assertFalse(shouldRetryPhoneInspection(false, true, listOf(issue)))
            assertFalse(shouldRetryPhoneInspection(false, true, listOf("unavailable_child", issue)))
        }
    }

    @Test fun `missing child rereads the same version and publishes only the complete capture`() = runBlocking {
        val f = ReadFixture()
        val result = f.read(shouldRetry = { it == "missing child" }) {
            if (f.reads == 1) "missing child" else "complete revision=${it.revision}"
        }
        assertEquals("complete revision=1", result)
        assertEquals(2, f.reads)
        assertEquals(1, f.waits)
    }

    @Test fun `persistent missing children fail after the existing total retry budget`() = runBlocking {
        val f = ReadFixture()
        expectCode("PAGE_UNSTABLE") {
            f.read(shouldRetry = { true }) { "missing child" }
        }
        assertEquals(4, f.reads)
        assertEquals(3, f.waits)
    }

    @Test fun `explicit limited capability returns only the final stable incomplete read`() = runBlocking {
        val f = ReadFixture()
        val accepted = mutableListOf<String>()
        val result = f.read(shouldRetry = { true }, acceptLastStable = { accepted += it; true }) {
            "partial-${f.reads}"
        }
        assertEquals("partial-4", result)
        assertEquals(listOf("partial-4"), accepted)
        assertEquals(4, f.reads)
        assertEquals(3, f.waits)
    }

    @Test fun `low remaining budget returns only the current completed limited read`() = runBlocking {
        val f = ReadFixture()
        val accepted = mutableListOf<String>()
        val result = f.read(shouldRetry = { true }, acceptLastStable = { accepted += it; true }) {
            f.now = if (f.reads == 1) 1_000 else 2_600
            "partial-${f.reads}"
        }
        assertEquals("partial-2", result)
        assertEquals(listOf("partial-2"), accepted)
        assertEquals(2, f.reads)
        assertEquals(1, f.waits)
    }

    @Test fun `limited early return includes exactly 2120ms remaining but not 2121ms`() = runBlocking {
        listOf(2_121L, 2_120L, 2_119L).forEach { remaining ->
            val f = ReadFixture()
            val accepted = mutableListOf<String>()
            val result = f.read(shouldRetry = { true }, acceptLastStable = { accepted += it; true }) {
                if (f.reads == 1) f.now = 4_500 - remaining
                "partial-${f.reads}"
            }
            val expectedReads = if (remaining == 2_121L) 2 else 1
            assertEquals("partial-$expectedReads", result)
            assertEquals(listOf(result), accepted)
            assertEquals(expectedReads, f.reads)
            assertEquals(expectedReads - 1, f.waits)
        }
    }

    @Test fun `low budget aborted or invalidated read never revives an earlier eligible result`() = runBlocking {
        listOf(false, true).forEach { changesRevision ->
            val f = ReadFixture()
            expectCode("PAGE_UNSTABLE") {
                f.read(shouldRetry = { true }, acceptLastStable = { fail("No current stable result"); true }) {
                    if (f.reads == 1) {
                        f.now = 500
                        "earlier-eligible"
                    } else {
                        if (f.reads == 2) f.now = 3_000
                        if (!changesRevision) throw PhoneObservationInvalidated()
                        f.current = f.current.copy(revision = f.current.revision + 1)
                        "invalidated-${f.reads}"
                    }
                }
            }
            assertEquals(4, f.reads)
        }
    }

    @Test fun `low budget ineligible result cannot use an earlier eligible capture`() = runBlocking {
        val f = ReadFixture()
        val checked = mutableListOf<String>()
        expectCode("PAGE_UNSTABLE") {
            f.read(shouldRetry = { true }, acceptLastStable = { checked += it; it == "earlier-eligible" }) {
                if (f.reads == 1) {
                    f.now = 500
                    "earlier-eligible"
                } else {
                    if (f.reads == 2) f.now = 3_000
                    "no-scroll-node"
                }
            }
        }
        assertEquals(listOf("no-scroll-node", "no-scroll-node", "no-scroll-node"), checked)
        assertEquals(4, f.reads)
    }

    @Test fun `low budget does not opt default callers into limited acceptance`() = runBlocking {
        val f = ReadFixture()
        expectCode("PAGE_UNSTABLE") {
            f.read(shouldRetry = { true }) {
                if (f.reads == 1) f.now = 2_380
                "partial"
            }
        }
        assertEquals(4, f.reads)
        assertEquals(3, f.waits)
    }

    @Test fun `sensitive and other inspection gaps never request early limited capability`() = runBlocking {
        val cases = listOf(true to listOf("unavailable_child")) +
            listOf("foreign_node", "children_limit", "visit_limit", "time_limit", "depth_limit", "text_limit")
                .flatMap { listOf(false to listOf(it), false to listOf("unavailable_child", it)) }
        cases.forEach { (sensitive, issues) ->
            val f = ReadFixture()
            val raw = f.read(
                shouldRetry = { shouldRetryPhoneInspection(sensitive, true, issues) },
                acceptLastStable = { fail("This inspection has no limited capability"); true },
            ) {
                f.now = 3_000
                "raw-rejected-inspection"
            }
            // The backend/controller still handle this raw result; no limited grant was requested.
            assertEquals("raw-rejected-inspection", raw)
            assertEquals(1, f.reads)
            assertEquals(0, f.waits)
        }
    }

    @Test fun `early limited acceptance still rechecks stop version identity and deadline`() = runBlocking {
        val cases: List<Pair<String, (ReadFixture) -> Unit>> = listOf(
            "PAGE_UNSTABLE" to { f -> f.current = f.current.copy(revision = f.current.revision + 1) },
            "STALE_WINDOW" to { f -> f.current = f.current.copy(identity = 2) },
            "STALE_WINDOW" to { f -> f.current = f.current.copy(windowId = 2) },
            "PAGE_UNSTABLE" to { f -> f.now = 4_500 },
            "SESSION_INACTIVE" to { f -> f.denied = "SESSION_INACTIVE" },
        )
        cases.forEachIndexed { index, (code, invalidate) ->
            val f = ReadFixture()
            expectCode(code) {
                f.read(shouldRetry = { true }, acceptLastStable = { invalidate(f); true }) {
                    if (f.reads == 1) f.now = 3_000
                    "partial-${f.reads}"
                }
            }
            assertEquals(if (index == 0) 4 else 1, f.reads)
        }
    }

    @Test fun `early candidate at 4500ms or cancellation is never accepted`() = runBlocking {
        val late = ReadFixture()
        expectCode("PAGE_UNSTABLE") {
            late.read(shouldRetry = { true }, acceptLastStable = { fail("Deadline reached"); true }) {
                late.now = 4_500
                "late-partial"
            }
        }
        assertEquals(1, late.reads)
        assertEquals(0, late.waits)
        val cancelled = ReadFixture()
        val failure = runCatching {
            cancelled.read(shouldRetry = { true }, acceptLastStable = { fail("Cancelled"); true }) {
                cancelled.now = 3_000
                throw CancellationException("stopped")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, cancelled.reads)
        assertEquals(0, cancelled.waits)
    }

    @Test fun `partial capability never returns an earlier read when the final capture aborts`() = runBlocking {
        val f = ReadFixture()
        var capabilityChecks = 0
        expectCode("PAGE_UNSTABLE") {
            f.read(shouldRetry = { true }, acceptLastStable = { capabilityChecks++; true }) {
                if (f.reads == 4) throw PhoneObservationInvalidated()
                "partial-${f.reads}"
            }
        }
        assertEquals(0, capabilityChecks)
        assertEquals(4, f.reads)
    }

    @Test fun `ineligible final partial does not reuse an eligible previous read`() = runBlocking {
        val f = ReadFixture()
        expectCode("PAGE_UNSTABLE") {
            f.read(shouldRetry = { true }, acceptLastStable = { it == "eligible" }) {
                if (f.reads == 4) "no scroll node" else "eligible"
            }
        }
        assertEquals(4, f.reads)
    }

    @Test fun `final partial must still have the same revision identity time and permit`() = runBlocking {
        val cases: List<Pair<String, (ReadFixture) -> Unit>> = listOf(
            "PAGE_UNSTABLE" to { f -> f.current = f.current.copy(revision = 2) },
            "STALE_WINDOW" to { f -> f.current = f.current.copy(identity = 2) },
            "STALE_WINDOW" to { f -> f.current = f.current.copy(windowId = 2) },
            "PAGE_UNSTABLE" to { f -> f.now = 4_500 },
            "SESSION_INACTIVE" to { f -> f.denied = "SESSION_INACTIVE" },
        )
        cases.forEach { (code, invalidate) ->
            val f = ReadFixture()
            expectCode(code) {
                f.read(shouldRetry = { true }, acceptLastStable = { invalidate(f); true }) { "partial" }
            }
            assertEquals(4, f.reads)
        }
    }

    @Test fun `late or cancelled partial cannot be accepted`() = runBlocking {
        val f = ReadFixture()
        expectCode("PAGE_UNSTABLE") {
            f.read(shouldRetry = { true }, acceptLastStable = { fail("Too late"); true }) {
                if (f.reads == 4) f.now = 4_500
                "partial"
            }
        }
        val cancelled = ReadFixture()
        val error = runCatching {
            cancelled.read(shouldRetry = { true }, acceptLastStable = { fail("Cancelled"); true }) {
                if (cancelled.reads == 4) throw CancellationException("stopped")
                "partial"
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(4, cancelled.reads)
    }

    @Test fun `content invalidation and missing children share one total retry budget`() = runBlocking {
        val f = ReadFixture()
        expectCode("PAGE_UNSTABLE") {
            f.read(shouldRetry = { true }) {
                if (f.reads == 1) {
                    f.current = f.current.copy(revision = 2)
                    throw PhoneObservationInvalidated()
                }
                "missing child"
            }
        }
        assertEquals(4, f.reads)
        assertEquals(3, f.waits)
    }

    @Test fun `window identity change during missing child retry cannot capture the replacement`() = runBlocking {
        val f = ReadFixture()
        f.duringWait = { f.current = f.current.copy(identity = 2) }
        expectCode("STALE_WINDOW") {
            f.read(shouldRetry = { true }) { "missing child" }
        }
        assertEquals(1, f.reads)
        assertEquals(1, f.waits)
    }

    @Test fun `stop during missing child retry prevents another read`() = runBlocking {
        val f = ReadFixture()
        f.duringWait = { f.denied = "SESSION_INACTIVE" }
        expectCode("SESSION_INACTIVE") {
            f.read(shouldRetry = { true }) { "missing child" }
        }
        assertEquals(1, f.reads)
        assertEquals(1, f.waits)
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
