package me.rerere.rikkahub.data.mobileagent

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhoneReadOnlyContentCollectorTest {
    private fun PhoneReadOnlyContentCollector.addText(
        text: String,
        id: String? = null,
        description: String = "",
        visible: Boolean = true,
        password: Boolean = false,
        sensitive: Boolean = false,
        fieldsTruncated: Boolean = false,
    ) = add(id, visible, password, sensitive, text, description, fieldsTruncated)

    private fun PhoneReadOnlyContentCollector.complete() = checkNotNull(finish(inspectionComplete = true, sensitive = false))

    @Test fun `only visible non-protected nonblank text enters read-only evidence`() {
        val collector = PhoneReadOnlyContentCollector()
        collector.addText("invisible", visible = false)
        collector.addText("password", password = true)
        collector.addText("sensitive", sensitive = true)
        collector.addText(" \n", description = " ")
        collector.addText("", id = "n0")
        collector.addText("visible", id = "n1")
        collector.addText("", description = "description")
        assertEquals(listOf(
            PhoneReadOnlyContentNode("n1", "visible"),
            PhoneReadOnlyContentNode("r0", "", "description"),
        ), collector.complete().nodes)
        assertFalse(collector.complete().truncated)
    }

    @Test fun `preview IDs remain unique and pagination puts preview before the extra prefix`() {
        val collector = PhoneReadOnlyContentCollector()
        collector.addText("first preview", id = "n0")
        collector.addText("first omitted")
        collector.addText("second preview", id = "n1")
        collector.addText("second omitted")
        val content = collector.complete()
        assertEquals(listOf("n0", "n1", "r0", "r1"), content.nodes.map { it.id })
        assertEquals(4, content.nodes.map { it.id }.distinct().size)
        assertEquals("first omitted", content.nodes[2].text)
        val nodeJson = Json.encodeToString(content.nodes.last())
        assertEquals(setOf("id", "text"), Json.parseToJsonElement(nodeJson).jsonObject.keys)
    }

    @Test fun `fields clip at 240 characters and preserve upstream clipping metadata`() {
        val collector = PhoneReadOnlyContentCollector()
        collector.addText("文".repeat(241), description = "述".repeat(242))
        val content = collector.complete()
        assertEquals(240, content.nodes.single().text.length)
        assertEquals(240, content.nodes.single().description.length)
        assertTrue(content.nodes.single().truncated)
        assertTrue(content.truncated)
        val upstream = PhoneReadOnlyContentCollector()
        upstream.addText("already sampled", fieldsTruncated = true)
        assertTrue(upstream.complete().nodes.single().truncated)
        assertTrue(upstream.complete().truncated)
        val exact = PhoneReadOnlyContentCollector()
        exact.addText("x".repeat(240), id = "n0", description = "y".repeat(240))
        assertFalse(exact.complete().nodes.single().truncated)
        assertFalse(exact.complete().truncated)
    }

    @Test fun `exact node budget is accepted and overflow never fills later holes`() {
        val collector = PhoneReadOnlyContentCollector()
        repeat(768) { collector.addText("x") }
        assertEquals(768, collector.complete().nodes.size)
        assertFalse(collector.complete().truncated)
        collector.addText("overflow")
        collector.addText("a")
        val content = collector.complete()
        assertEquals((0 until 768).map { "r$it" }, content.nodes.map { it.id })
        assertTrue(content.truncated)
    }

    @Test fun `late preview text evicts only the extra tail at the node boundary`() {
        val collector = PhoneReadOnlyContentCollector()
        repeat(768) { collector.addText("x") }
        collector.addText("late preview", id = "n0")
        collector.addText("must remain omitted")
        val content = collector.complete()
        assertEquals(listOf("n0") + (0 until 767).map { "r$it" }, content.nodes.map { it.id })
        assertEquals(768, content.nodes.size)
        assertTrue(content.truncated)
    }

    @Test fun `64000 character boundary counts text and description rather than encoded bytes`() {
        val collector = PhoneReadOnlyContentCollector()
        repeat(133) { collector.addText("字".repeat(240), description = "文".repeat(240)) }
        collector.addText("尾".repeat(160))
        val exact = collector.complete()
        assertEquals(64_000, exact.nodes.sumOf { it.text.length + it.description.length })
        assertEquals(134, exact.nodes.size)
        assertFalse(exact.truncated)
        collector.addText("再")
        val exceeded = collector.complete()
        assertEquals(exact.nodes, exceeded.nodes)
        assertTrue(exceeded.truncated)
    }

    @Test fun `character overflow closes the extra prefix even when a later short value fits`() {
        val collector = PhoneReadOnlyContentCollector()
        repeat(133) { collector.addText("x".repeat(240), description = "y".repeat(240)) }
        collector.addText("too large".repeat(20)) // 180 characters, only 160 remain.
        collector.addText("fits but is beyond the prefix")
        val content = collector.complete()
        assertEquals(133, content.nodes.size)
        assertEquals(63_840, content.nodes.sumOf { it.text.length + it.description.length })
        assertTrue(content.truncated)
    }

    @Test fun `late published preview text remains available after character prefix closure`() {
        val collector = PhoneReadOnlyContentCollector()
        repeat(133) { collector.addText("x".repeat(240), description = "y".repeat(240)) }
        collector.addText("z".repeat(160))
        collector.addText("overflow")
        collector.addText("published", id = "n99")
        collector.addText("must not backfill remaining space")
        val content = collector.complete()
        assertEquals(listOf("n99") + (0 until 133).map { "r$it" }, content.nodes.map { it.id })
        assertEquals(63_849, content.nodes.sumOf { it.text.length + it.description.length })
        assertTrue(content.truncated)
    }

    @Test fun `incomplete or sensitive trees publish no collected body`() {
        val collector = PhoneReadOnlyContentCollector()
        collector.addText("must remain private")
        assertNull(collector.finish(inspectionComplete = false, sensitive = false))
        assertNull(collector.finish(inspectionComplete = true, sensitive = true))
        assertNull(collector.finish(inspectionComplete = false, sensitive = true))
    }

    @Test fun `finished evidence is immutable when collection later changes`() {
        val collector = PhoneReadOnlyContentCollector()
        collector.addText("first")
        val first = collector.complete()
        collector.addText("second")
        assertEquals(listOf(PhoneReadOnlyContentNode("r0", "first")), first.nodes)
        assertEquals(2, collector.complete().nodes.size)
    }

    private fun observation() = PhoneObservation(
        id = "snapshot", packageName = "test.target", windowId = 1, windowRevision = 2,
        capturedAtMillis = 123, nodes = listOf(PhoneNode("n0", text = "preview", bounds = PhoneBounds(0, 0, 10, 10), clickable = true)),
        truncated = false, sensitive = false, fingerprint = "same-action-hash",
    )

    @Test fun `transient body is absent from observation and nested action JSON even with defaults`() {
        val original = observation().copy(
            readOnlyContentAvailable = true,
            readOnlyContentTruncated = true,
            readOnlyContent = PhoneReadOnlyContent(listOf(PhoneReadOnlyContentNode("r0", "hidden_evidence_sample")), true),
        )
        listOf(false, true).forEach { defaults ->
            val json = Json { encodeDefaults = defaults }
            val encoded = json.encodeToString(original)
            val fields = Json.parseToJsonElement(encoded).jsonObject
            assertFalse(fields.containsKey("readOnlyContent"))
            assertFalse(encoded.contains("hidden_evidence_sample"))
            assertTrue(fields.getValue("readOnlyContentAvailable").jsonPrimitive.boolean)
            assertTrue(fields.getValue("readOnlyContentTruncated").jsonPrimitive.boolean)
            assertNull(json.decodeFromString<PhoneObservation>(encoded).readOnlyContent)
            assertFalse(json.encodeToString(PhoneActionResult(true, detail = "accepted", observation = original)).contains("hidden_evidence_sample"))
        }
    }

    @Test fun `only read-only metadata is excluded from action observation identity`() {
        val original = observation()
        val annotated = original.copy(
            readOnlyContentAvailable = true,
            readOnlyContentTruncated = true,
            readOnlyContent = PhoneReadOnlyContent(listOf(PhoneReadOnlyContentNode("r0", "extra")), true),
        )
        assertEquals(original, annotated.withoutReadOnlyContentMetadata())
        assertEquals(original.fingerprint, annotated.withoutReadOnlyContentMetadata().fingerprint)
        assertSame(original.nodes, annotated.withoutReadOnlyContentMetadata().nodes)
        val forgedActionNode = annotated.copy(nodes = original.nodes + original.nodes.single().copy(id = "r0"))
        assertNotEquals(original, forgedActionNode.withoutReadOnlyContentMetadata())
        assertNotEquals(original, annotated.copy(windowRevision = 3).withoutReadOnlyContentMetadata())
        assertNotEquals(original, annotated.copy(scrollOnly = true).withoutReadOnlyContentMetadata())
    }
}
