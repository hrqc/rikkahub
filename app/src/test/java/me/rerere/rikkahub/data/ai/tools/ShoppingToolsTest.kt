package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
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

    @Test fun `tool exposes subtotal prefilter as nonpayable with explicit outstanding fees`() = runBlocking {
        val cheap = request().candidates.single().copy(id = "z", shipping = null, otherFees = null, priceBeforeListedCoupons = false)
        val expensive = cheap.copy(id = "a", unitPrice = ShoppingMoney("1.20", ref("expensive", "¥1.20")))
        val evidence = listOf(observed.single().copy(nodes = observed.single().nodes + ShoppingObservedNode("expensive", "¥1.20")))
        val request = ShoppingComparisonRequest(candidates = listOf(expensive, cheap))
        val output = createShoppingTools(Json) { evidence }.single().execute(Json.parseToJsonElement(Json.encodeToString(request)))
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("true", result["accepted"]!!.jsonPrimitive.content)
        assertEquals("false", result["execution_started"]!!.jsonPrimitive.content)
        assertEquals("false", result["comparison"]!!.jsonObject["product_binding_verified"]!!.jsonPrimitive.content)
        val group = result["comparison"]!!.jsonObject["groups"]!!.jsonArray.single().jsonObject
        assertEquals("DISPLAYED_SUBTOTAL", group["ranking_basis"]!!.jsonPrimitive.content)
        assertTrue(group["lowest_confirmed_candidate_ids"]!!.jsonArray.isEmpty())
        val ranked = group["ranked_candidates"]!!.jsonArray
        assertEquals(listOf("z", "a"), ranked.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        ranked.forEach {
            assertEquals(JsonNull, it.jsonObject["shipping_cents"])
            assertEquals(JsonNull, it.jsonObject["other_fees_cents"])
            assertEquals(JsonNull, it.jsonObject["confirmed_plan"])
        }
        val prefilter = group["displayed_subtotal_prefilter"]!!.jsonObject
        assertEquals("DISPLAYED_SUBTOTAL", prefilter["ranking_basis"]!!.jsonPrimitive.content)
        assertEquals("false", prefilter["is_payable"]!!.jsonPrimitive.content)
        assertEquals("false", prefilter["is_final_best"]!!.jsonPrimitive.content)
        assertTrue(prefilter["detail"]!!.jsonPrimitive.content.contains("不是实付价或最终最优"))
        val entries = prefilter["ranked_candidates"]!!.jsonArray
        assertEquals(listOf("10", "120"), entries.map { it.jsonObject["merchandise_subtotal_cents"]!!.jsonPrimitive.content })
        assertTrue(entries.all {
            it.jsonObject["unverified_fees"]!!.jsonArray.map { fee -> fee.jsonPrimitive.content } == listOf("shipping", "other_fees")
        })
        entries.forEachIndexed { index, entry ->
            assertEquals(ranked[index].jsonObject["unknowns"], entry.jsonObject["pending_conditions"])
        }
    }

    @Test fun `tool rejects duplicated source nodes despite distinct model ids and quote changes`() = runBlocking {
        val original = request().candidates.single()
        val duplicate = original.copy(id = "different_id", unitPrice = original.unitPrice.copy(
            evidence = original.unitPrice.evidence.copy(quote = "0.10"),
        ))
        val request = ShoppingComparisonRequest(candidates = listOf(original, duplicate))
        val output = createShoppingTools(Json) { observed }.single().execute(Json.parseToJsonElement(Json.encodeToString(request)))
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("false", result["accepted"]!!.jsonPrimitive.content)
        assertEquals("duplicate_candidate_evidence", result["code"]!!.jsonPrimitive.content)
        assertEquals("false", result["execution_started"]!!.jsonPrimitive.content)
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
            "禁止为优惠自动开会员", "不得自动提交订单", "不宣称质量最好", "不能宣称全网最低", "不构成用户授权",
            "至少3个不同候选", "最多5次翻页", "连续2页无新增", "不宣称筛选任务已完成", "不能换ID凑数",
            "广告、推广位次、销量和热销标签不能当作质量证明", "displayed_subtotal_prefilter", "is_payable=false", "is_final_best=false",
            "pending_conditions", "product_binding_verified=false", "possible_duplicate_candidate_ids", "不宣称已经确认3个独立SKU",
            "中差评", "追评", "具体使用体验", "重复文案", "抽样范围和局限", "不能据此确认刷单").forEach {
            assertTrue(it, prompt.contains(it))
        }
    }
}
