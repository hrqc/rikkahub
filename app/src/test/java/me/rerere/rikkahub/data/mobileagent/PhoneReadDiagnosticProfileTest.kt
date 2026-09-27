package me.rerere.rikkahub.data.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhoneReadDiagnosticProfileTest {
    private val token = PhoneSessionToken("conversation", "assistant", "task", 3)

    @Test fun `profiles change only include-unimportant bit and share one experiment node budget`() {
        ReadDiagnosticProfile.entries.forEach { profile ->
            val original = 80 or 128 or 512
            val configured = profile.applyFlags(original)
            assertEquals(original, configured and 2.inv())
            assertEquals(profile.includeUnimportant, configured and 2 != 0)
            assertEquals(configured, profile.applyFlags(configured))
            assertEquals(768, profile.nodeLimit)
        }
        assertFalse(ReadDiagnosticProfile.DEFAULT.refreshParents)
        assertFalse(ReadDiagnosticProfile.INCLUDE_UNIMPORTANT.refreshParents)
        assertTrue(ReadDiagnosticProfile.REFRESH_PARENTS.refreshParents)
        assertTrue(ReadDiagnosticProfile.INCLUDE_UNIMPORTANT_REFRESH_PARENTS.refreshParents)
        assertEquals(80, ReadDiagnosticProfile.DEFAULT.applyFlags(82))
    }

    @Test fun `production and explicit diagnostic reads share the 768 node boundary`() {
        val normal = PhoneTreeReadBudget(0)
        repeat(768) { assertTrue(normal.visit(0, 100)) }
        assertFalse(normal.visit(0, 100))
        assertEquals(setOf("visit_limit"), normal.issues)

        val experimental = PhoneTreeReadBudget(0, ReadDiagnosticProfile.DEFAULT.nodeLimit)
        repeat(768) { assertTrue(experimental.visit(0, 100)) }
        assertFalse(experimental.visit(0, 100))
        assertEquals(setOf("visit_limit"), experimental.issues)
        assertFalse(PhoneTreeReadBudget(0, 768).visit(41, 100))
        assertFalse(PhoneTreeReadBudget(0, 768).visit(0, 2_001))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `experiment cannot increase the hard budget above 768`() {
        PhoneTreeReadBudget(0, 769)
    }

    @Test fun `normal diagnostic export reports the actual production node budget`() {
        val window = ReadDiagnosticWindow(7, 2, 11, 1)
        val capture = ReadDiagnosticCapture(token, "com.example.target", window, 82, 1, 36, "test",
            now = { 1000 }, wallTime = { 1000 })
        val sample = checkNotNull(capture.beginSample())
        val attempt = checkNotNull(capture.beginAttempt(sample, window))
        attempt.serviceFlags = 82
        capture.endAttempt(attempt, window, "COMPLETE", emptyList(), false, false, false)
        capture.endSample(sample, "COMPLETE", null, window)
        val records = capture.finish().map { Json.parseToJsonElement(it).jsonObject }
        val begin = records.single { it["recordType"]?.jsonPrimitive?.content == "begin" }
        assertEquals(768, begin.getValue("limits").jsonObject.getValue("treeNodes").jsonPrimitive.int)
        val row = records.single { it["recordType"]?.jsonPrimitive?.content == "attempt" }
        assertEquals("normal_observe", row["readMode"]?.jsonPrimitive?.content)
        assertEquals(768, row.getValue("treeNodeLimit").jsonPrimitive.int)
        assertEquals(82, row.getValue("serviceFlags").jsonPrimitive.int)
    }

    @Test fun `restoration preserves unrelated flag changes and the original include bit`() {
        val service = Any()
        val lease = ReadDiagnosticFlagLease(token, "run", service, 80, 82)
        assertEquals(80 or 128, lease.restoreFlags(service, token, "run", 82 or 128))
        val originallyIncluded = ReadDiagnosticFlagLease(token, "run", service, 82, 80)
        assertEquals(82 or 128, originallyIncluded.restoreFlags(service, token, "run", 80 or 128))
    }

    @Test fun `replaced service token handle or externally changed bit is not overwritten`() {
        val service = Any()
        val lease = ReadDiagnosticFlagLease(token, "run", service, 80, 82)
        assertNull(lease.restoreFlags(Any(), token, "run", 82))
        assertNull(lease.restoreFlags(service, token.copy(epoch = 4), "run", 82))
        assertNull(lease.restoreFlags(service, token, "new-run", 82))
        assertNull(lease.restoreFlags(service, token, "run", 80))
    }

    @Test fun `revocation still permits restoring own temporary flags without a new owner`() {
        val service = Any()
        val lease = ReadDiagnosticFlagLease(token, "run", service, 80, 82)
        assertEquals(80, lease.restoreFlags(service, null, "run", 82))
        assertEquals(80, lease.restoreFlags(service, null, null, 82))
    }

    @Test fun `variant export identifies actual budget flags and failed node refresh`() {
        val window = ReadDiagnosticWindow(7, 2, 11, 1)
        val capture = ReadDiagnosticCapture(token, "com.example.target", window, 80, 1, 36, "test",
            now = { 1000 }, wallTime = { 1000 }, profile = ReadDiagnosticProfile.INCLUDE_UNIMPORTANT_REFRESH_PARENTS)
        val sample = checkNotNull(capture.beginSample())
        val attempt = checkNotNull(capture.beginAttempt(sample, window))
        attempt.variantRead = true
        attempt.profile = capture.profile
        attempt.treeNodeLimit = capture.profile.nodeLimit
        attempt.serviceFlags = 82
        attempt.tree.nodeRefresh(true)
        attempt.tree.nodeRefresh(false)
        capture.endAttempt(attempt, window, "INCOMPLETE", listOf("node_refresh_failed"), true, false, false)
        capture.endSample(sample, "INCOMPLETE", null, window)
        val records = capture.finish().map { Json.parseToJsonElement(it).jsonObject }
        val row = records.single { it["recordType"]?.jsonPrimitive?.content == "attempt" }
        assertEquals("diagnostic_variant", row["readMode"]?.jsonPrimitive?.content)
        assertEquals("INCLUDE_UNIMPORTANT_REFRESH_PARENTS", row["diagnosticProfile"]?.jsonPrimitive?.content)
        assertEquals(768, row.getValue("treeNodeLimit").jsonPrimitive.int)
        assertEquals(82, row.getValue("serviceFlags").jsonPrimitive.int)
        val tree = row.getValue("tree").jsonObject
        assertEquals(1, tree.getValue("nodeRefreshSucceeded").jsonPrimitive.int)
        assertEquals(1, tree.getValue("nodeRefreshFailed").jsonPrimitive.int)
        assertEquals("INCOMPLETE", row["outcome"]?.jsonPrimitive?.content)
    }
}
