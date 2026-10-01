package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.mobileagent.PhoneController
import me.rerere.rikkahub.data.mobileagent.PhoneSearchWorkflow
import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken

/** One model call; local code performs at most three existing, independently guarded actions. */
internal fun createPhoneSearchTools(controller: PhoneController, token: PhoneSessionToken, json: Json): List<Tool> {
    if (controller.state.value.let { it.token != token || it.targetPackage != "com.jingdong.app.mall" }) return emptyList()
    val name = phoneToolPrefix(token) + "search"
    return listOf(Tool(
        name = name,
        description = "在本次已授权的京东中本地完成普通商品搜索：打开搜索输入、核对查询词并提交；不领券、不加购、不下单。",
        parameters = { InputSchema.Obj(properties = buildJsonObject {
            put("query", buildJsonObject { put("type", "string"); put("description", "用户实际要搜索的商品关键词，最多120字符。") })
        }, required = listOf("query")) },
        systemPrompt = { _, _ -> "京东商品搜索优先使用 $name，把真实用户查询词交给本地有界流程，减少观察和点击之间的模型等待。" +
            "仍须使用返回 observation 核对实际查询词和商品结果；QUERY_SUBMITTED 不代表商品筛选完成。" +
            "SEARCH_STOPPED 或 SEARCH_ACTION_UNVERIFIED 后先观察当前页；已接受或未知的动作不能直接重放。" },
        needsApproval = { false },
        execute = { arguments ->
            try {
                val value = (arguments as? JsonObject)?.get("query") as? JsonPrimitive
                if (value == null || !value.isString) {
                    listOf(UIMessagePart.Text("""{"status":"INVALID_QUERY","detail":"query必须为字符串"}"""))
                } else listOf(UIMessagePart.Text(json.encodeToString(PhoneSearchWorkflow(controller, token).search(value.content))))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                listOf(UIMessagePart.Text("""{"status":"SEARCH_OUTCOME_UNKNOWN","detail":"本地搜索已停止；先观察实际页面，不重放未知步骤。"}"""))
            }
        },
    ))
}
