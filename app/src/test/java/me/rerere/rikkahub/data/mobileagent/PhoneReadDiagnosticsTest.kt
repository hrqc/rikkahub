package me.rerere.rikkahub.data.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhoneReadDiagnosticsTest {
    private val token = PhoneSessionToken("conversation", "assistant", "task", 3)
    private val window = ReadDiagnosticWindow(7, 2, 11, 3)
    private val bounds = PhoneBounds(0, 10, 200, 300)
    private class Clock(var value: Long = 1000)

    private fun capture(clock: Clock = Clock(), maxBytes: Int = 128 * 1024) = ReadDiagnosticCapture(
        token, "com.example.target", window, 80, 4096, 36, "debug-test",
        now = { clock.value }, wallTime = { 1_000_000 + clock.value }, maxBytes = maxBytes,
    )
    private fun List<String>.records() = map { Json.parseToJsonElement(it).jsonObject }
    private fun JsonObject.type() = getValue("recordType").jsonPrimitive.content
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.contentOrNull

    @Test fun `revoked diagnostic token cannot reopen until a new session notice activates permission`() {
        val authority = ReadDiagnosticAuthority()
        assertFalse(authority.allows(token))
        assertNull(authority.whileAuthorized(token) { "unexpected" })
        authority.activate(token)
        assertTrue(authority.allows(token))
        val prior = checkNotNull(authority.whileAuthorized(token) { capture() })
        prior.beginSample()

        authority.revoke()
        prior.stop(ReadDiagnosticStop.SESSION_INVALIDATED)
        assertFalse(authority.allows(token))
        var reopened = false
        assertNull(authority.whileAuthorized(token) { reopened = true; capture() })
        assertFalse(reopened)
        assertEquals("SESSION_INVALIDATED", prior.finish().records().last().string("reason"))

        val resumedToken = token.copy(epoch = token.epoch + 1)
        authority.activate(resumedToken)
        assertFalse(authority.allows(token))
        assertNull(authority.whileAuthorized(token) { "unexpected" })
        assertTrue(authority.allows(resumedToken))
        assertEquals("allowed", authority.whileAuthorized(resumedToken) { "allowed" })
    }

    @Test fun `versioned metadata contains no body fields and unknown activity stays null`() {
        val capture = capture()
        val sample = checkNotNull(capture.beginSample())
        val attempt = checkNotNull(capture.beginAttempt(sample, window))
        attempt.rootRefresh = ReadDiagnosticOperation.SUCCEEDED
        attempt.tree.visited()
        attempt.tree.node(emptyList(), "android.view.ViewGroup", "app:id/list", bounds, true, 2, true, false, true)
        attempt.tree.gap(emptyList(), "android.view.ViewGroup", "app:id/list", bounds, true, 2, 1)
        capture.endAttempt(attempt, window, "INCOMPLETE", listOf("unavailable_child"), true, false, false)
        capture.endSample(sample, "PAGE_UNSTABLE", null, window)
        val lines = capture.finish()
        val records = lines.records()
        records.forEach {
            assertEquals(1, it.getValue("schemaVersion").jsonPrimitive.int)
            assertEquals(capture.runId, it.string("diagnosticRunId"))
        }
        val begin = records.first()
        assertNull(begin.string("activityName"))
        assertNull(begin.string("screenHash"))
        val serialized = lines.joinToString("\n")
        listOf("text", "description", "contentDescription", "contentFingerprint", "fingerprint", "input", "eventSource").forEach {
            assertFalse("Unexpected field $it", serialized.contains("\"$it\":"))
        }
        val row = records.single { it.type() == "attempt" }
        assertEquals("SUCCEEDED", row.string("rootRefresh"))
        assertEquals("NOT_ATTEMPTED", row.string("cacheClear"))
        assertEquals(1, row.getValue("tree").jsonObject.getValue("unavailableChildCount").jsonPrimitive.int)
        assertNull(records.single { it.type() == "sample_end" }.string("snapshotId"))
    }

    @Test fun `stop discards late observations while preserving earlier records for one retrieval`() {
        val capture = capture()
        val sample = checkNotNull(capture.beginSample())
        capture.stop(ReadDiagnosticStop.SESSION_INVALIDATED)
        assertFalse(capture.isRecording())
        assertNull(capture.beginSample())
        assertNull(capture.beginAttempt(sample, window))
        capture.event(1, 7, 0, 0, 80, window)
        capture.endSample(sample, "OBSERVED", "late", window)
        val records = capture.finish().records()
        assertEquals(listOf("begin", "sample_start", "end"), records.map { it.type() })
        assertEquals("SESSION_INVALIDATED", records.last().string("reason"))
        assertTrue(capture.finish().isEmpty())
    }

    @Test fun `expiration is checked before storing more events samples or tree metadata`() {
        val clock = Clock()
        val capture = capture(clock)
        val sample = checkNotNull(capture.beginSample())
        val attempt = checkNotNull(capture.beginAttempt(sample, window))
        clock.value += 15_000
        attempt.tree.visited()
        attempt.tree.node(emptyList(), "ExpiredClass", "expired:id", bounds, true, 0, false, false, true)
        assertNull(capture.beginSample())
        capture.event(1, 7, 0, 0, 80, window)
        assertEquals(0, attempt.tree.finish("CANCELLED", emptyList()).getValue("visitedNodeCount").jsonPrimitive.int)
        val records = capture.finish().records()
        assertEquals("TIME_LIMIT", records.last().string("reason"))
        assertFalse(records.any { it.type() == "event" })
    }

    @Test fun `seven completed samples end recording without an eighth sample`() {
        val capture = capture()
        repeat(7) {
            val sample = checkNotNull(capture.beginSample())
            capture.endSample(sample, "OBSERVED", "snapshot-$it", window)
        }
        assertNull(capture.beginSample())
        val records = capture.finish().records()
        assertEquals(7, records.count { it.type() == "sample_end" })
        assertEquals("SAMPLE_LIMIT", records.last().string("reason"))
    }

    @Test fun `attempt and event caps remain independent and event clocks are explicit`() {
        val clock = Clock()
        val capture = capture(clock)
        repeat(140) { capture.event(2048, 7, 1, 2, 40 + it.toLong(), window) }
        val sample = checkNotNull(capture.beginSample())
        repeat(32) { assertNotNull(capture.beginAttempt(sample, window)) }
        assertNull(capture.beginAttempt(sample, window))
        val records = capture.finish().records()
        val events = records.filter { it.type() == "event" }
        assertEquals(128, events.size)
        assertEquals("uptimeMillis", events.first().string("eventClock"))
        assertEquals("elapsedRealtime", events.first().string("clock"))
        assertEquals(12, records.last().getValue("eventsDropped").jsonPrimitive.int)
        assertEquals("ATTEMPT_LIMIT", records.last().string("reason"))
    }

    @Test fun `utf8 byte limit retains valid JSON and reserves the terminal record`() {
        val capture = capture(maxBytes = 4096)
        val sample = checkNotNull(capture.beginSample())
        val attempt = checkNotNull(capture.beginAttempt(sample, window))
        repeat(20) { attempt.tree.gap(listOf(it), "类".repeat(160), "资源".repeat(128), bounds, true, 20, it) }
        capture.endAttempt(attempt, window, "INCOMPLETE", listOf("unavailable_child"), true, false, false)
        val lines = capture.finish()
        assertTrue(lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 } <= 4096)
        assertEquals("BYTE_LIMIT", lines.records().last().string("reason"))
    }

    @Test fun `only twenty gap records are retained but missing count is complete`() {
        val tree = ReadDiagnosticTree()
        repeat(35) { tree.gap(emptyList(), "List", "app:id/list", bounds, true, 35, it) }
        val result = tree.finish("INCOMPLETE", listOf("unavailable_child"))
        assertEquals(35, result.getValue("unavailableChildCount").jsonPrimitive.int)
        assertEquals(20, result.getValue("gaps").jsonArray.size)
        assertEquals(15, result.getValue("gapsOmitted").jsonPrimitive.int)
    }

    @Test fun `structural hash distinguishes paths child counts gaps and completion`() {
        fun hash(path: List<Int> = emptyList(), children: Int = 2, gap: Boolean = false, outcome: String = "COMPLETE"): String? {
            val tree = ReadDiagnosticTree()
            tree.node(path, "List", "app:id/list", bounds, true, children, true, false, true)
            if (gap) tree.gap(path, "List", "app:id/list", bounds, true, children, 1)
            return tree.finish(outcome, if (gap) listOf("unavailable_child") else emptyList()).string("structureHash")
        }
        assertEquals(hash(), hash())
        assertNotEquals(hash(), hash(path = listOf(0)))
        assertNotEquals(hash(), hash(children = 3))
        assertNotEquals(hash(), hash(gap = true))
        assertNotEquals(hash(), hash(outcome = "INVALIDATED"))
    }

    @Test fun `protected and foreign subtree markers contain no class or resource metadata`() {
        val tree = ReadDiagnosticTree()
        tree.visited(); tree.protectedSubtree(listOf(0))
        tree.visited(); tree.foreign(listOf(1))
        val result = tree.finish("SENSITIVE", emptyList())
        assertEquals(1, result.getValue("protectedSubtrees").jsonPrimitive.int)
        assertEquals(1, result.getValue("foreignSubtrees").jsonPrimitive.int)
        assertNull(result.string("rootNodeHash"))
        assertFalse(result.toString().contains("resourceId"))
        assertFalse(result.toString().contains("\"class\""))
    }
}
