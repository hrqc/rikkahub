package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.shopping.ReviewEvidenceGroup
import me.rerere.rikkahub.data.shopping.ReviewEvidenceRequest
import me.rerere.rikkahub.data.shopping.ReviewEvidenceSample
import me.rerere.rikkahub.data.shopping.ShoppingEvidenceRef
import me.rerere.rikkahub.data.shopping.ShoppingObservedEvidence
import me.rerere.rikkahub.data.shopping.ShoppingObservedNode
import org.junit.Assert.*
import org.junit.Test

class ShoppingReviewToolsTest {
    private val text = "已经连续使用这个商品两个星期，接口连接比较稳定，包装完整，暂时没有发现明显的问题。"
    private val observed = listOf(ShoppingObservedEvidence("current", "com.example.shop", listOf(
        ShoppingObservedNode("body", text), ShoppingObservedNode("follow-up", "7天后追评"),
    )))
    private val body = ShoppingEvidenceRef("current", "body", text)
    private fun request() = ReviewEvidenceRequest(listOf(ReviewEvidenceGroup("a", listOf(ReviewEvidenceSample(body)))))
    private fun arguments(request: ReviewEvidenceRequest = request()) = Json.parseToJsonElement(Json.encodeToString(request)).jsonObject
    private suspend fun execute(tool: Tool, arguments: JsonObject = arguments()) =
        Json.parseToJsonElement((tool.execute(arguments).single() as UIMessagePart.Text).text).jsonObject

    @Test fun `single read only tool returns quoted evidence analysis with explicit identity limitations`() = runBlocking {
        val tools = createShoppingReviewTools(Json) { observed }
        assertEquals(listOf("shopping_review_evidence"), tools.map { it.name })
        val tool = tools.single()
        assertFalse(tool.needsApproval(arguments()))
        val output = execute(tool)
        assertEquals("true", output["accepted"]!!.jsonPrimitive.content)
        assertEquals("false", output["execution_started"]!!.jsonPrimitive.content)
        assertEquals("current_grant_quoted_node_fragments", output["evidence_scope"]!!.jsonPrimitive.content)
        val analysis = output["analysis"]!!.jsonObject
        assertEquals("false", analysis["product_binding_verified"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, analysis["independent_review_count"])
        assertEquals("1", analysis["source_node_samples"]!!.jsonPrimitive.content)
        assertTrue(analysis["repetition_indicators"]!!.jsonArray.isEmpty())
        assertTrue(analysis["limitations"]!!.jsonArray.any { it.jsonPrimitive.content.contains("不是鉴伪工具") })
    }

    @Test fun `empty or revoked callback cannot be replaced by model supplied evidence`() = runBlocking {
        val empty = execute(createShoppingReviewTools(Json) { emptyList() }.single())
        assertEquals("shopping_evidence_unavailable", empty["code"]!!.jsonPrimitive.content)
        var calls = 0
        val revoked = execute(createShoppingReviewTools(Json) { if (calls++ == 0) observed else emptyList() }.single())
        assertEquals("false", revoked["accepted"]!!.jsonPrimitive.content)
        assertEquals("shopping_evidence_unavailable", revoked["code"]!!.jsonPrimitive.content)
        assertNull(revoked["analysis"])
    }

    @Test fun `empty sample groups still cannot return success after grant revocation`() = runBlocking {
        var calls = 0
        val tool = createShoppingReviewTools(Json) { if (calls++ == 0) observed else emptyList() }.single()
        val output = execute(tool, arguments(ReviewEvidenceRequest(listOf(ReviewEvidenceGroup("a", emptyList())))))
        assertEquals("shopping_evidence_unavailable", output["code"]!!.jsonPrimitive.content)
    }

    @Test fun `fabricated or historical references are rejected with a fixed error instead of echoing input`() = runBlocking {
        val tool = createShoppingReviewTools(Json) { observed }.single()
        listOf(body.copy(snapshotId = "old"), body.copy(nodeId = "missing"), body.copy(quote = "private-secret-fabricated")).forEach { ref ->
            val output = execute(tool, arguments(ReviewEvidenceRequest(listOf(ReviewEvidenceGroup("a", listOf(ReviewEvidenceSample(ref)))))))
            assertEquals("false", output["accepted"]!!.jsonPrimitive.content)
            assertEquals("unverified_evidence", output["code"]!!.jsonPrimitive.content)
            assertFalse(output.toString().contains("private-secret-fabricated"))
        }
    }

    @Test fun `unknown authority or fraud fields at root and sample level are rejected even with lenient caller json`() = runBlocking {
        val tool = createShoppingReviewTools(Json { ignoreUnknownKeys = true; coerceInputValues = true }) { observed }.single()
        listOf("verified", "authorized", "fraud_probability", "quality_score", "observed").forEach { key ->
            val output = execute(tool, JsonObject(arguments() + (key to JsonPrimitive("private-value"))))
            assertEquals("invalid_arguments", output["code"]!!.jsonPrimitive.content)
            assertFalse(output.toString().contains("private-value"))
        }
        val originalGroup = arguments()["groups"]!!.jsonArray.single().jsonObject
        val review = originalGroup["reviews"]!!.jsonArray.single().jsonObject
        val forgedReview = JsonObject(review + ("is_follow_up" to JsonPrimitive(true)))
        val forgedGroup = JsonObject(originalGroup + ("reviews" to JsonArray(listOf(forgedReview))))
        val output = execute(tool, JsonObject(mapOf("groups" to JsonArray(listOf(forgedGroup)))))
        assertEquals("invalid_arguments", output["code"]!!.jsonPrimitive.content)
    }

    @Test fun `optional annotation eviction fails but unrelated page eviction does not discard valid analysis`() = runBlocking {
        val followUp = ShoppingEvidenceRef("current", "follow-up", "7天后追评")
        val request = ReviewEvidenceRequest(listOf(ReviewEvidenceGroup("a", listOf(ReviewEvidenceSample(body, followUpEvidence = followUp)))))
        var calls = 0
        val missingAnnotation = createShoppingReviewTools(Json) {
            if (calls++ == 0) observed else listOf(observed.single().copy(nodes = observed.single().nodes.filter { it.nodeId == "body" }))
        }.single()
        assertEquals("shopping_evidence_unavailable", execute(missingAnnotation, arguments(request))["code"]!!.jsonPrimitive.content)
        calls = 0
        val unrelated = ShoppingObservedEvidence("unrelated", "com.example.shop", listOf(ShoppingObservedNode("unused", "无关文本")))
        val evictedUnrelated = createShoppingReviewTools(Json) { if (calls++ == 0) observed + unrelated else observed }.single()
        assertEquals("true", execute(evictedUnrelated)["accepted"]!!.jsonPrimitive.content)
    }

    @Test fun `reused node fragments stay one source and sample gaps are visible through tool output`() = runBlocking {
        val request = ReviewEvidenceRequest(listOf(ReviewEvidenceGroup("a", listOf(ReviewEvidenceSample(body),
            ReviewEvidenceSample(body.copy(quote = "接口连接比较稳定"))))))
        val output = execute(createShoppingReviewTools(Json) { observed }.single(), arguments(request))
        val analysis = output["analysis"]!!.jsonObject
        assertEquals("1", analysis["source_node_samples"]!!.jsonPrimitive.content)
        assertEquals("2", analysis["submitted_body_fragments"]!!.jsonPrimitive.content)
        val gaps = analysis["groups"]!!.jsonArray.single().jsonObject["sampling_gaps"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }
        assertTrue(gaps.containsAll(listOf("FEW_SOURCE_NODE_SAMPLES", "REUSED_SOURCE_NODE", "NO_CRITICAL_LABEL_EVIDENCE", "NO_FOLLOW_UP_LABEL_EVIDENCE")))
    }

    @Test fun `schema bounds groups and fragments and only accepts evidence backed optional context`() {
        val schema = createShoppingReviewTools(Json) { observed }.single().parameters() as InputSchema.Obj
        val groups = schema.properties["groups"]!!.jsonObject
        assertEquals("8", groups["maxItems"]!!.jsonPrimitive.content)
        assertEquals("1", groups["minItems"]!!.jsonPrimitive.content)
        val reviews = groups["items"]!!.jsonObject["properties"]!!.jsonObject["reviews"]!!.jsonObject
        assertEquals("20", reviews["maxItems"]!!.jsonPrimitive.content)
        val sample = reviews["items"]!!.jsonObject
        assertEquals("false", sample["additionalProperties"]!!.jsonPrimitive.content)
        assertEquals(listOf("body"), sample["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val quote = sample["properties"]!!.jsonObject["body"]!!.jsonObject["properties"]!!.jsonObject["quote"]!!.jsonObject
        assertEquals("1000", quote["maxLength"]!!.jsonPrimitive.content)
    }

    @Test fun `prompt explicitly limits sampling identity scores dates and instruction authority`() {
        val prompt = createShoppingReviewTools(Json) { observed }.single().systemPrompt(Model(), emptyList())
        listOf("独立评论数", "非全量、非随机", "不能确认商家刷单", "刷单概率", "质量排名", "短句", "不同快照",
            "不分析没有日期证据的时间集中度", "不构成用户授权", "中差评", "追评", "只读", "不执行").forEach {
            assertTrue(it, prompt.contains(it))
        }
    }
}
