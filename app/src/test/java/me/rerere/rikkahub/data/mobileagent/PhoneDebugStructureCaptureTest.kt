package me.rerere.rikkahub.data.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhoneDebugStructureCaptureTest {
    private class Clock(var value: Long = 100)
    private fun shape(children: Int = 0, resource: String = "shop:id/card") = PhoneDebugNodeShape(
        className = "android.view.ViewGroup", viewId = resource,
        bounds = PhoneBounds(0, 0, 1080, 500), visible = true, enabled = true,
        scrollable = false, childCount = children,
    )
    private fun finish(capture: PhoneDebugStructureCapture, truncated: Boolean = false, issues: List<String> = emptyList()) =
        capture.finish("read-1", "DEFAULT", 7, 12, truncated, issues)
    private fun String.document() = Json.parseToJsonElement(this).jsonObject
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    @Test fun `true parent paths repeated resource ids and optional preview ids survive without body fields`() {
        val capture = PhoneDebugStructureCapture(selectedPaths = listOf("/0/0", "/0/1"))
        assertTrue(capture.recordNode(emptyList(), shape(1, "shop:id/root")))
        assertTrue(capture.recordNode(listOf(0), shape(2, "shop:id/list")))
        assertTrue(capture.recordNode(listOf(0, 0), shape(1)))
        assertTrue(capture.recordNode(listOf(0, 0, 0), shape().copy(emittedNodeId = "n4")))
        assertTrue(capture.recordNode(listOf(0, 1), shape()))
        val exported = checkNotNull(capture.finish("read-1", "INCLUDE_UNIMPORTANT", 7, 12, false, emptyList(),
            windowIdentity = 3, actualServiceFlags = 82, treeNodeLimit = 768))
        val document = exported.document()
        assertEquals(capture.structureCaptureId, document.string("structureCaptureId"))
        assertEquals(82, document.getValue("actualServiceFlags").jsonPrimitive.int)
        assertEquals(768, document.getValue("treeNodeLimit").jsonPrimitive.int)
        assertFalse(document.getValue("selectionFiltersStructure").jsonPrimitive.boolean)
        assertFalse(document.getValue("productScopeVerified").jsonPrimitive.boolean)
        val nodes = document.getValue("nodes").jsonArray.map { it.jsonObject }
        assertEquals(5, nodes.size)
        assertEquals(JsonNull, nodes.first()["parentPath"])
        assertEquals("/0/0", nodes.single { it.string("path") == "/0/0/0" }.string("parentPath"))
        assertEquals("n4", nodes.single { it.string("path") == "/0/0/0" }.string("emittedNodeId"))
        assertEquals(3, nodes.count { it.string("resourceId") == "shop:id/card" })
        listOf("text", "description", "contentDescription", "screenshot", "fingerprint", "complete").forEach {
            assertFalse("Unexpected content or completeness field: $it", exported.contains("\"$it\":"))
        }
    }

    @Test fun `caller incomplete status is retained even when no gaps were recorded`() {
        val capture = PhoneDebugStructureCapture()
        capture.recordNode(emptyList(), shape(1))
        val document = checkNotNull(finish(capture, true, listOf("visit_limit"))).document()
        assertTrue(document.getValue("treeTruncated").jsonPrimitive.boolean)
        assertEquals("caller_verified_tree", document.string("treeStatusSource"))
        assertEquals(listOf("visit_limit"), document.getValue("inspectionIssues").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(document.getValue("gaps").jsonArray.isEmpty())
        assertEquals(JsonNull, document["actualServiceFlags"])
        assertEquals(JsonNull, document["windowIdentity"])
        assertEquals(JsonNull, document["treeNodeLimit"])
    }

    @Test fun `missing child indices preserve their actual parent without inventing child bounds`() {
        val capture = PhoneDebugStructureCapture()
        capture.recordNode(emptyList(), shape(1))
        capture.recordNode(listOf(0), shape(3))
        capture.recordNode(listOf(0, 0), shape())
        assertTrue(capture.recordGap(listOf(0), 1))
        assertTrue(capture.recordGap(listOf(0), 2))
        val document = checkNotNull(finish(capture, true, listOf("unavailable_child"))).document()
        val gaps = document.getValue("gaps").jsonArray.map { it.jsonObject }
        assertEquals(listOf(1, 2), gaps.map { it.getValue("unavailableChildIndex").jsonPrimitive.int })
        assertTrue(gaps.all { it.string("parentPath") == "/0" && "bounds" !in it })
    }

    @Test fun `external complete status cannot contradict observed gaps missing children or issues`() {
        val withGap = PhoneDebugStructureCapture()
        withGap.recordNode(emptyList(), shape(1))
        withGap.recordGap(emptyList(), 0)
        assertNull(finish(withGap))
        assertEquals("INCONSISTENT_TREE_STATUS", withGap.refusalReason)
        val prefix = PhoneDebugStructureCapture()
        prefix.recordNode(emptyList(), shape(1))
        assertNull(finish(prefix))
        val withIssue = PhoneDebugStructureCapture()
        withIssue.recordNode(emptyList(), shape())
        assertNull(finish(withIssue, issues = listOf("time_limit")))
    }

    @Test fun `node limit refuses whole export and cannot be recovered by finishing as truncated`() {
        val capture = PhoneDebugStructureCapture(maxNodes = 1)
        assertTrue(capture.recordNode(emptyList(), shape(1)))
        assertFalse(capture.recordNode(listOf(0), shape()))
        assertEquals("NODE_LIMIT", capture.refusalReason)
        assertNull(finish(capture, true, listOf("visit_limit")))
        assertFalse(capture.recordGap(emptyList(), 0))
    }

    @Test fun `default node budget accepts exactly 768 but not a 769th record`() {
        fun populated(extraChild: Boolean): PhoneDebugStructureCapture {
            val capture = PhoneDebugStructureCapture()
            assertTrue(capture.recordNode(emptyList(), shape(if (extraChild) 768 else 767)))
            repeat(767) { assertTrue(capture.recordNode(listOf(it), shape())) }
            return capture
        }
        assertNotNull(finish(populated(false)))
        val overflow = populated(true)
        assertFalse(overflow.recordNode(listOf(767), shape()))
        assertEquals("NODE_LIMIT", overflow.refusalReason)
        assertNull(finish(overflow, true, listOf("visit_limit")))
    }

    @Test fun `byte budget covers final header and newline and refuses any partial json`() {
        val largeShape = shape().copy(className = "a".repeat(160), viewId = "a".repeat(256))
        val baseline = PhoneDebugStructureCapture(maxBytes = 4096)
        baseline.recordNode(emptyList(), largeShape)
        val encoded = checkNotNull(finish(baseline))
        val size = encoded.toByteArray(Charsets.UTF_8).size
        assertTrue(size in 1000..4095)
        // The four-digit budget and fixed-length UUIDs keep both documents the same size.
        val undersized = PhoneDebugStructureCapture(maxBytes = size)
        undersized.recordNode(emptyList(), largeShape)
        assertNull(finish(undersized))
        assertEquals("BYTE_LIMIT", undersized.refusalReason)
        val exact = PhoneDebugStructureCapture(maxBytes = size + 1)
        exact.recordNode(emptyList(), largeShape)
        assertEquals(size + 1, checkNotNull(finish(exact)).toByteArray(Charsets.UTF_8).size + 1)
        val tiny = PhoneDebugStructureCapture(maxBytes = 1)
        assertFalse(tiny.recordNode(emptyList(), shape()))
        assertNull(finish(tiny, true, listOf("visit_limit")))
    }

    @Test fun `time expiration and abort discard everything and reject all later operations`() {
        val clock = Clock()
        val expired = PhoneDebugStructureCapture(now = { clock.value })
        expired.recordNode(emptyList(), shape(1))
        clock.value += 15_000
        assertFalse(expired.recordGap(emptyList(), 0))
        assertNull(finish(expired, true, listOf("time_limit")))
        assertEquals("TIME_LIMIT", expired.refusalReason)
        val aborted = PhoneDebugStructureCapture()
        aborted.recordNode(emptyList(), shape())
        aborted.abort()
        assertNull(finish(aborted))
        assertFalse(aborted.recordNode(emptyList(), shape()))
        assertEquals("ABORTED", aborted.refusalReason)
    }

    @Test fun `finish checks expiration even without further recording and exports only once`() {
        val clock = Clock()
        val expired = PhoneDebugStructureCapture(now = { clock.value })
        expired.recordNode(emptyList(), shape())
        clock.value += 15_000
        assertNull(finish(expired))
        val success = PhoneDebugStructureCapture()
        success.recordNode(emptyList(), shape())
        assertNotNull(finish(success))
        assertNull(finish(success))
        assertFalse(success.recordNode(emptyList(), shape()))
    }

    @Test fun `selected paths require strict canonical format and at most three distinct shallow paths`() {
        val invalid = listOf("", "0", "//", "/01", "/-1", "/+1", "/0/", "/0//1", "/2147483648", "/0\n", "/" + List(41) { "0" }.joinToString("/"))
        invalid.forEach { path ->
            assertTrue(path, runCatching { PhoneDebugStructureCapture(selectedPaths = listOf(path)) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { PhoneDebugStructureCapture(selectedPaths = listOf("/", "/0", "/1", "/2")) }.isFailure)
        assertTrue(runCatching { PhoneDebugStructureCapture(selectedPaths = listOf("/0", "/0")) }.isFailure)
        val root = PhoneDebugStructureCapture(selectedPaths = listOf("/"))
        root.recordNode(emptyList(), shape())
        assertNotNull(finish(root))
    }

    @Test fun `unobserved selections missing parents and duplicate paths cannot produce plausible structure`() {
        val selected = PhoneDebugStructureCapture(selectedPaths = listOf("/0"))
        selected.recordNode(emptyList(), shape())
        assertNull(finish(selected))
        assertEquals("MISSING_ROOT_OR_SELECTION", selected.refusalReason)
        val orphan = PhoneDebugStructureCapture()
        assertFalse(orphan.recordNode(listOf(0), shape()))
        assertNull(finish(orphan, true, listOf("visit_limit")))
        val duplicate = PhoneDebugStructureCapture()
        duplicate.recordNode(emptyList(), shape())
        assertFalse(duplicate.recordNode(emptyList(), shape()))
        assertNull(finish(duplicate))
    }

    @Test fun `invalid gaps or mutable caller paths cannot rewrite tree relationships`() {
        val capture = PhoneDebugStructureCapture()
        capture.recordNode(emptyList(), shape(1))
        val path = mutableListOf(0)
        assertTrue(capture.recordNode(path, shape()))
        path[0] = 7
        val document = checkNotNull(finish(capture)).document()
        assertEquals("/0", document.getValue("nodes").jsonArray.last().jsonObject.string("path"))
        val invalid = PhoneDebugStructureCapture()
        invalid.recordNode(emptyList(), shape(1))
        assertFalse(invalid.recordGap(emptyList(), 1))
        assertNull(finish(invalid, true, listOf("unavailable_child")))
        val overlap = PhoneDebugStructureCapture()
        overlap.recordNode(emptyList(), shape(1))
        overlap.recordGap(emptyList(), 0)
        assertFalse(overlap.recordNode(listOf(0), shape()))
    }

    @Test fun `limits cannot exceed hard maxima and invalid metadata is refused rather than echoed`() {
        assertTrue(runCatching { PhoneDebugStructureCapture(maxNodes = 769) }.isFailure)
        assertTrue(runCatching { PhoneDebugStructureCapture(maxBytes = 256 * 1024 + 1) }.isFailure)
        assertTrue(runCatching { PhoneDebugStructureCapture(maxDurationMillis = 15_001) }.isFailure)
        val capture = PhoneDebugStructureCapture()
        assertFalse(capture.recordNode(emptyList(), shape().copy(className = "private body\ncontent")))
        assertNull(finish(capture))
        assertEquals("INVALID_NODE", capture.refusalReason)
        val invalidFinish = PhoneDebugStructureCapture()
        invalidFinish.recordNode(emptyList(), shape())
        assertNull(invalidFinish.finish("read-1", "DEFAULT", 7, 12, true, listOf("private body text")))
        assertEquals("INVALID_FINISH_METADATA", invalidFinish.refusalReason)
    }

    @Test fun `instances have unique structure identities even if caller reuses a correlation id`() {
        val first = PhoneDebugStructureCapture()
        val second = PhoneDebugStructureCapture()
        assertNotEquals(first.structureCaptureId, second.structureCaptureId)
        first.recordNode(emptyList(), shape())
        second.recordNode(emptyList(), shape())
        assertNotEquals(checkNotNull(finish(first)).document().string("structureCaptureId"),
            checkNotNull(finish(second)).document().string("structureCaptureId"))
    }
}
