package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneInterruptedToolResultTest {
    private val ordinaryResult =
        """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""

    @Test
    fun `phone interruption reports unknown outcome without copying private call data`() {
        var firstOutput: List<UIMessagePart>? = null
        for (name in listOf("phone_current_1_click", "phone_old_7_input_text", "phone_current_1_observe")) {
            val tool = UIMessagePart.Tool(
                toolCallId = "private-call-id",
                toolName = name,
                input = """{"text":"private-input","snapshot_id":"private-snapshot","node_id":"private-node"}""",
                approvalState = ToolApprovalState.Approved,
                metadata = buildJsonObject { put("private", "private-metadata") },
            )
            val interrupted = interruptedToolResult(tool)
            val output = (interrupted.output.single() as UIMessagePart.Text).text
            val payload = Json.parseToJsonElement(output).jsonObject

            assertEquals(setOf("status", "execution_outcome", "detail"), payload.keys)
            assertEquals("interrupted", payload.getValue("status").jsonPrimitive.content)
            assertEquals("unknown", payload.getValue("execution_outcome").jsonPrimitive.content)
            assertTrue(output.contains("重新观察"))
            assertTrue(output.contains("不得直接重放"))
            assertFalse(output.contains("private"))
            assertFalse(output.contains("input"))
            assertFalse(output.contains("未执行"))
            assertFalse(output.contains("用户取消"))
            assertFalse(output.contains("by user"))
            assertEquals(tool.copy(output = interrupted.output), interrupted)
            assertFalse(interrupted.canResumeExecution)
            if (firstOutput == null) firstOutput = interrupted.output else assertEquals(firstOutput, interrupted.output)
        }
    }

    @Test
    fun `ordinary tool interruption keeps its existing exact output`() {
        for (name in listOf("search", "workspace_shell", "request_phone_control")) {
            val tool = UIMessagePart.Tool("call", name, "private input", approvalState = ToolApprovalState.Pending)
            val interrupted = interruptedToolResult(tool)
            assertEquals(listOf(UIMessagePart.Text(ordinaryResult)), interrupted.output)
            assertEquals(tool.copy(output = interrupted.output), interrupted)
            assertFalse(interrupted.isPending)
            assertFalse(interrupted.canResumeExecution)
        }
    }

    @Test
    fun `finishing pending tools preserves published phone acceptance and does not queue a replay`() {
        val text = UIMessagePart.Text("Existing assistant text")
        val completed = UIMessagePart.Tool(
            "done", "phone_current_1_click", "{}",
            output = listOf(UIMessagePart.Text("""{"accepted":true,"screenChanged":false}""")),
        )
        val phone = UIMessagePart.Tool("phone-pending", "phone_current_1_click", "{}",
            approvalState = ToolApprovalState.Approved)
        val ordinary = UIMessagePart.Tool("ordinary-pending", "search", "{}",
            approvalState = ToolApprovalState.Pending)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(text, completed, phone, ordinary))

        val finished = message.finishPendingTools(::interruptedToolResult)

        assertSame(text, finished.parts[0])
        assertSame(completed, finished.parts[1])
        assertEquals("unknown", Json.parseToJsonElement(
            ((finished.parts[2] as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text,
        ).jsonObject.getValue("execution_outcome").jsonPrimitive.content)
        assertEquals(listOf(UIMessagePart.Text(ordinaryResult)), (finished.parts[3] as UIMessagePart.Tool).output)
        finished.getTools().forEach {
            assertTrue(it.isExecuted)
            assertFalse(it.canResumeExecution)
            assertFalse(it.isPending)
        }
        assertSame(finished, finished.finishPendingTools(::interruptedToolResult))
    }

    @Test
    fun `already completed phone or ordinary results are never overwritten`() {
        for (name in listOf("phone_current_1_click", "search")) {
            val completed = UIMessagePart.Tool("call", name, "{}", output = listOf(UIMessagePart.Text("original result")))
            assertSame(completed, interruptedToolResult(completed))
        }
    }
}
