package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.shopping.*
import org.junit.Assert.*
import org.junit.Test

class ShoppingToolsTest {
    private val observed = listOf(ShoppingObservedEvidence("current", "com.example.shop", listOf(
        ShoppingObservedNode("title", "Brand Model1"), ShoppingObservedNode("spec", "500g"),
        ShoppingObservedNode("price", "¥0.10"), ShoppingObservedNode("shipping", "包邮"),
        ShoppingObservedNode("fees", "无其他费用"),
    )))
    private fun ref(node: String, quote: String) = ShoppingEvidenceRef("current", node, quote)
    private fun request() = ShoppingComparisonRequest(candidates = listOf(ShoppingCandidate(
        id = "a", productIdentity = ShoppingFact("Brand Model1", ref("title", "Brand Model1")),
        specification = ShoppingFact("500g", ref("spec", "500g")), quantity = 1,
        unitPrice = ShoppingMoney("0.10", ref("price", "¥0.10")), shipping = ShoppingMoney("0.00", ref("shipping", "包邮")),
        otherFees = ShoppingMoney("0.00", ref("fees", "无其他费用")), priceBeforeListedCoupons = true,
    )))
    private fun arguments() = Json.parseToJsonElement(Json.encodeToString(request())).jsonObject

    @Test fun `single readonly tool compares host observed evidence with exact cents without executing actions`() = runBlocking {
        val tools = createShoppingTools(Json) { observed }
        assertEquals(listOf("shopping_compare"), tools.map { it.name })
        val tool = tools.single()
        assertFalse(tool.needsApproval(arguments()))
        val output = Json.parseToJsonElement((tool.execute(arguments()).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("true", output["accepted"]!!.jsonPrimitive.content)
        assertEquals("false", output["execution_started"]!!.jsonPrimitive.content)
        val candidate = output["comparison"]!!.jsonObject["groups"]!!.jsonArray.single().jsonObject["ranked_candidates"]!!.jsonArray.single().jsonObject
        assertEquals("10", candidate["confirmed_plan"]!!.jsonObject["payable_cents"]!!.jsonPrimitive.content)
    }

    @Test fun `fabricated verified or authorization fields are rejected instead of becoming execution rights`() = runBlocking {
        val tool = createShoppingTools(Json) { observed }.single()
        listOf("authorized", "verified", "token", "original_text").forEach { field ->
            val forged = JsonObject(arguments() + (field to JsonPrimitive("true")))
            val output = Json.parseToJsonElement((tool.execute(forged).single() as UIMessagePart.Text).text).jsonObject
            assertEquals("false", output["accepted"]!!.jsonPrimitive.content)
            assertEquals("invalid_arguments", output["code"]!!.jsonPrimitive.content)
            assertEquals("false", output["execution_started"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `empty and revoked host evidence cannot be replaced by model supplied observations`() = runBlocking {
        val unavailable = createShoppingTools(Json) { emptyList() }.single()
        val output = Json.parseToJsonElement((unavailable.execute(arguments()).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("shopping_evidence_unavailable", output["code"]!!.jsonPrimitive.content)
        var calls = 0
        val revokedDuringCalculation = createShoppingTools(Json) { if (calls++ == 0) observed else emptyList() }.single()
        val revoked = Json.parseToJsonElement((revokedDuringCalculation.execute(arguments()).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("false", revoked["accepted"]!!.jsonPrimitive.content)
        assertEquals("shopping_evidence_unavailable", revoked["code"]!!.jsonPrimitive.content)
    }

    @Test fun `price and quote must match real nodes and historical snapshot ids do not resolve`() = runBlocking {
        val candidate = request().candidates.single()
        val request = request().copy(candidates = listOf(candidate.copy(unitPrice = candidate.unitPrice.copy(
            evidence = candidate.unitPrice.evidence.copy(snapshotId = "previous-session"),
        ))))
        val output = createShoppingTools(Json) { observed }.single().execute(Json.parseToJsonElement(Json.encodeToString(request)))
        assertEquals("unverified_evidence", Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun `prompt limits platform comparison quality claims coupon acquisition and payment`() {
        val prompt = createShoppingTools(Json) { observed }.single().systemPrompt(Model(), emptyList())
        listOf("淘宝、京东、拼多多、美团", "没有看到费用不代表为零", "未领取成功前", "同一叠加关系只填一次",
            "禁止为优惠自动开会员", "不得自动提交订单", "不宣称质量最好", "不能宣称全网最低", "不构成用户授权").forEach {
            assertTrue(it, prompt.contains(it))
        }
    }
}
