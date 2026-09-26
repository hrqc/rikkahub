package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.research.PublicWebReader
import me.rerere.rikkahub.data.research.PublicWebResponse
import me.rerere.rikkahub.data.research.PublicWebTransport
import me.rerere.rikkahub.data.research.ResearchRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `tool exposes public read and local reread without arbitrary paths or downloads`() {
        val tools = createResearchTools(ResearchRepository(temporary.newFolder()), "chat")
        assertEquals(listOf("read_public_webpage", "read_research_source"), tools.map { it.name })
        assertEquals(listOf("url"), (tools.first().parameters() as InputSchema.Obj).required)
        assertEquals(listOf("source_id"), (tools.last().parameters() as InputSchema.Obj).required)
    }

    @Test fun `bounded text output preserves source evidence and flags truncation`() = runBlocking {
        var requests = 0
        val reader = PublicWebReader(PublicWebTransport {
            requests++
            PublicWebResponse(200, "text/plain", null, "Evidence. ".repeat(200).toByteArray())
        })
        val tools = createResearchTools(ResearchRepository(temporary.newFolder(), reader), "chat")
        val response = tools.first().execute(buildJsonObject { put("url", "https://example.com/source"); put("max_chars", 1_000) })
        val result = Json.parseToJsonElement((response.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("true", result["success"]?.jsonPrimitive?.content)
        assertEquals(1_000, result["text"]!!.jsonPrimitive.content.length)
        assertEquals("true", result["returnedTextTruncated"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/source", result["finalUrl"]?.jsonPrimitive?.content)
        val id = result["source_id"]!!.jsonPrimitive.content
        tools.last().execute(buildJsonObject { put("source_id", id) })
        assertEquals(1, requests)
    }

    @Test fun `invalid character budget and private URL fail without a request or fabricated body`() = runBlocking {
        var requests = 0
        val reader = PublicWebReader(PublicWebTransport { requests++; error("Must not fetch") })
        val tool = createResearchTools(ResearchRepository(temporary.newFolder(), reader), "chat").first()
        listOf(
            buildJsonObject { put("url", "https://example.com/source"); put("max_chars", 50_000) } to "INVALID_ARGUMENTS",
            buildJsonObject { put("url", "http://127.0.0.1/private") } to "URL_NOT_PUBLIC",
        ).forEach { (input, code) ->
            val result = Json.parseToJsonElement((tool.execute(input).single() as UIMessagePart.Text).text).jsonObject
            assertEquals("false", result["success"]?.jsonPrimitive?.content)
            assertEquals(code, result["code"]?.jsonPrimitive?.content)
            assertFalse(result.containsKey("text"))
        }
        assertEquals(0, requests)
    }

    @Test fun `cached pagination can retrieve evidence after the first eighteen thousand characters`() = runBlocking {
        var requests = 0
        val reader = PublicWebReader(PublicWebTransport {
            requests++
            PublicWebResponse(200, "text/plain", null, ("x".repeat(20_000) + "verified ending").toByteArray())
        })
        val tools = createResearchTools(ResearchRepository(temporary.newFolder(), reader), "chat")
        val initial = Json.parseToJsonElement((tools.first().execute(buildJsonObject {
            put("url", "https://example.com/source"); put("max_chars", 18_000)
        }).single() as UIMessagePart.Text).text).jsonObject
        val tail = Json.parseToJsonElement((tools.last().execute(buildJsonObject {
            put("source_id", initial["source_id"]!!.jsonPrimitive.content); put("offset", 18_000)
        }).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(tail["text"]!!.jsonPrimitive.content.endsWith("verified ending"))
        assertEquals("18000", tail["textStart"]!!.jsonPrimitive.content)
        assertEquals(1, requests)
    }
}
