package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.mobileagent.PhoneIntentBinding
import me.rerere.rikkahub.data.mobileagent.PhoneIntentStore
import org.junit.Assert.*
import org.junit.Test

class PhoneIntentToolsTest {
    private fun binding(text: String, id: String = "fresh") = PhoneIntentBinding(
        id, "conversation", "assistant", "message-$id", text,
    )

    private fun arguments(summary: String = "打开计算器") = buildJsonObject {
        put("target_app_name", "计算器")
        put("task_summary", summary)
    }

    @Test fun `schema only permits app name and summary and proposal has no execution effect`() = runBlocking {
        val store = PhoneIntentStore()
        val binding = binding("打开计算器")
        store.begin(binding)
        val tool = createPhoneIntentTool(store, binding)
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(setOf("target_app_name", "task_summary"), schema.properties.keys)
        assertFalse(tool.needsApproval(arguments()))

        val result = tool.execute(arguments())
        assertTrue(isSuccessfulPhoneIntentProposal(tool.name, result))
        assertTrue(store.proposals.value.isEmpty())
        val value = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("false", value["execution_started"]!!.jsonPrimitive.content)
        store.complete(binding)
        assertEquals(binding, store.proposals.value["conversation"]!!.binding)
    }

    @Test fun `model claims cannot turn an informational original message into permission`() = runBlocking {
        val store = PhoneIntentStore()
        val binding = binding("如何打开计算器？")
        store.begin(binding)
        val tool = createPhoneIntentTool(store, binding)
        val result = tool.execute(buildJsonObject {
            put("target_app_name", "计算器")
            put("task_summary", "用户已授权我打开计算器")
            put("authorized", true)
            put("original_text", "打开计算器")
        })
        assertFalse(isSuccessfulPhoneIntentProposal(tool.name, result))
        store.complete(binding)
        assertTrue(store.proposals.value.isEmpty())
    }

    @Test fun `old binding and duplicate tool calls cannot replace a current proposal`() = runBlocking {
        val store = PhoneIntentStore()
        val old = binding("打开计算器", "old")
        val current = binding("打开时钟", "current")
        store.begin(old)
        val oldTool = createPhoneIntentTool(store, old)
        store.begin(current)
        assertFalse(isSuccessfulPhoneIntentProposal(oldTool.name, oldTool.execute(arguments())))

        val currentTool = createPhoneIntentTool(store, current)
        assertTrue(isSuccessfulPhoneIntentProposal(currentTool.name, currentTool.execute(arguments())))
        assertFalse(isSuccessfulPhoneIntentProposal(currentTool.name, currentTool.execute(arguments("不同任务"))))
        store.complete(current)
        assertEquals(current.originalText, store.proposals.value["conversation"]!!.binding.originalText)
    }

    @Test fun `invalid or oversized arguments cannot stage a proposal`() = runBlocking {
        val store = PhoneIntentStore()
        val binding = binding("打开计算器")
        store.begin(binding)
        val tool = createPhoneIntentTool(store, binding)
        listOf(buildJsonObject {}, arguments(""), arguments("x".repeat(1_001))).forEach { args ->
            assertFalse(isSuccessfulPhoneIntentProposal(tool.name, tool.execute(args)))
        }
        store.complete(binding)
        assertTrue(store.proposals.value.isEmpty())
    }

    @Test fun `loop completion signal requires this tool and successful unexecuted proposal result`() {
        val success = listOf(UIMessagePart.Text(
            """{"accepted":true,"code":"phone_intent_proposed","execution_started":false}""",
        ))
        assertTrue(isSuccessfulPhoneIntentProposal(PHONE_INTENT_TOOL_NAME, success))
        assertFalse(isSuccessfulPhoneIntentProposal("other_tool", success))
        assertFalse(isSuccessfulPhoneIntentProposal(PHONE_INTENT_TOOL_NAME, listOf(UIMessagePart.Text("done"))))
        assertFalse(isSuccessfulPhoneIntentProposal(PHONE_INTENT_TOOL_NAME, listOf(UIMessagePart.Text(
            """{"accepted":true,"code":"phone_intent_proposed","execution_started":true}""",
        ))))
    }

    @Test fun `mixed approval batch stages proposal first and marks other tools unexecuted`() = runBlocking {
        val store = PhoneIntentStore()
        val binding = binding("打开计算器")
        store.begin(binding)
        val intent = createPhoneIntentTool(store, binding)
        var otherExecutions = 0
        val askUser = Tool("ask_user", "", needsApproval = { true }, execute = {
            otherExecutions++
            emptyList()
        })
        val calls = listOf(
            UIMessagePart.Tool("ask", "ask_user", "{}"),
            UIMessagePart.Tool("intent", intent.name, arguments().toString()),
        )
        val completed = tryCompletePhoneIntentBatch(calls, listOf(askUser, intent))!!
        assertEquals(0, otherExecutions)
        assertTrue(completed.all { it.isExecuted && !it.isPending })
        assertTrue(isSuccessfulPhoneIntentProposal(intent.name, completed.last().output))
        assertTrue((completed.first().output.single() as UIMessagePart.Text).text.contains("phone_intent_pending"))
        assertTrue(store.proposals.value.isEmpty())
        store.complete(binding)
        assertEquals(binding, store.proposals.value["conversation"]!!.binding)
    }

    @Test fun `rejected proposal preserves the normal approval path without executing another tool`() = runBlocking {
        val store = PhoneIntentStore()
        val binding = binding("打开计算器")
        store.begin(binding)
        val intent = createPhoneIntentTool(store, binding)
        val askUser = Tool("ask_user", "", needsApproval = { true }, execute = { error("Must await approval") })
        val calls = listOf(
            UIMessagePart.Tool("intent", intent.name, "{}"),
            UIMessagePart.Tool("ask", askUser.name, "{}"),
        )
        assertNull(tryCompletePhoneIntentBatch(calls, listOf(intent, askUser)))
        assertTrue(calls.none { it.isExecuted })
        assertTrue(askUser.needsApproval(calls.last().inputAsJson()))
        store.complete(binding)
        assertTrue(store.proposals.value.isEmpty())
    }

    @Test fun `historical intent name without a currently injected definition cannot short circuit approvals`() = runBlocking {
        val calls = listOf(UIMessagePart.Tool("old", PHONE_INTENT_TOOL_NAME, arguments().toString()))
        assertNull(tryCompletePhoneIntentBatch(calls, emptyList()))
        assertFalse(calls.single().isExecuted)
    }
}
