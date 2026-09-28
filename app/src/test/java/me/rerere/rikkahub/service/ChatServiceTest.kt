package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.ai.tools.phoneToolPrefix
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken
import me.rerere.rikkahub.data.mobileagent.PhoneActionReceiptLedger
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test fun `receipt fills exact missing message call and preserves existing real output`() {
        val id = Uuid.random()
        val assistant = Uuid.random()
        val token = PhoneSessionToken(id.toString(), assistant.toString(), "session", 1)
        val name = phoneToolPrefix(token) + "back"
        val call = UIMessagePart.Tool("reused", name, "{}")
        val oldMessage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call))
        val real = call.copy(toolCallId = "real", output = listOf(UIMessagePart.Text("real output")))
        val realMessage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(real))
        val ledger = PhoneActionReceiptLedger(token)
        ledger.beginCall(message.id.toString(), call.toolCallId, name)!!.forTool(token, name)!!.apply {
            backendResult(true)
            postObserveFailed("PAGE_UNSTABLE")
        }
        ledger.beginCall(realMessage.id.toString(), real.toolCallId, name)!!.forTool(token, name)!!.backendResult(false)
        val conversation = Conversation(id = id, assistantId = assistant,
            messageNodes = listOf(oldMessage, message, realMessage).map { it.toMessageNode() })
        val finished = finishUnresolvedPhoneTools(conversation, token, ledger.freeze())
        fun result(index: Int) = Json.parseToJsonElement(
            (finished.currentMessages[index].getTools().single().output.single() as UIMessagePart.Text).text,
        ).jsonObject
        assertEquals("unknown", result(0)["execution_outcome"]?.jsonPrimitive?.content)
        assertEquals("accepted_unverified", result(1)["execution_outcome"]?.jsonPrimitive?.content)
        assertEquals("PAGE_UNSTABLE", result(1)["post_observe_error"]?.jsonPrimitive?.content)
        assertFalse(result(1).containsKey("screenChanged"))
        assertEquals(real, finished.currentMessages[2].getTools().single())
        assertTrue(finished.currentMessages.all { msg -> msg.getTools().all { !it.canResumeExecution } })
    }

    @Test fun `duplicate pending identity never receives a unique receipt even if only one call began`() {
        val id = Uuid.random()
        val assistant = Uuid.random()
        val token = PhoneSessionToken(id.toString(), assistant.toString(), "session", 1)
        val name = phoneToolPrefix(token) + "click"
        val call = UIMessagePart.Tool("duplicate", name, "{}")
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call, call))
        val ledger = PhoneActionReceiptLedger(token)
        ledger.beginCall(message.id.toString(), call.toolCallId, name)!!.forTool(token, name)!!.backendResult(true)
        val conversation = Conversation(id = id, assistantId = assistant, messageNodes = listOf(message.toMessageNode()))
        val finished = finishUnresolvedPhoneTools(conversation, token, ledger.freeze())
        finished.currentMessages.single().getTools().forEach { tool ->
            val result = Json.parseToJsonElement((tool.output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("unknown", result["execution_outcome"]?.jsonPrimitive?.content)
            assertFalse(result.containsKey("accepted"))
        }
    }

    @Test fun `receipt from old epoch cannot fill replacement pending calls`() {
        val id = Uuid.random()
        val assistant = Uuid.random()
        val old = PhoneSessionToken(id.toString(), assistant.toString(), "session", 1)
        val fresh = old.copy(epoch = 2)
        val oldName = phoneToolPrefix(old) + "back"
        val call = UIMessagePart.Tool("call", phoneToolPrefix(fresh) + "back", "{}")
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call))
        val ledger = PhoneActionReceiptLedger(old)
        ledger.beginCall(message.id.toString(), call.toolCallId, oldName)!!.forTool(old, oldName)!!.backendResult(true)
        val conversation = Conversation(id = id, assistantId = assistant, messageNodes = listOf(message.toMessageNode()))
        val finished = finishUnresolvedPhoneTools(conversation, fresh, ledger.freeze())
        val result = Json.parseToJsonElement(
            (finished.currentMessages.single().getTools().single().output.single() as UIMessagePart.Text).text,
        ).jsonObject
        assertEquals("unknown", result["execution_outcome"]?.jsonPrimitive?.content)
        assertFalse(result.containsKey("accepted"))
    }

    @Test
    fun `ending a phone generation records unknown for unresolved calls and preserves real results`() {
        val id = Uuid.random()
        val assistant = Uuid.random()
        val token = PhoneSessionToken(id.toString(), assistant.toString(), "first-session", 1)
        val pending = UIMessagePart.Tool("pending", phoneToolPrefix(token) + "click", "{\"node_id\":\"n32\"}")
        val completed = UIMessagePart.Tool("completed", phoneToolPrefix(token) + "scroll", "{}",
            output = listOf(UIMessagePart.Text("{\"accepted\":true,\"screenChanged\":true}")))
        val other = UIMessagePart.Tool("other", "read_public_webpage", "{}")
        val explanation = UIMessagePart.Text("已有真实观察")
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(explanation, completed, pending, other))
        val conversation = Conversation(id = id, assistantId = assistant, messageNodes = listOf(message.toMessageNode()))

        val finished = finishUnresolvedPhoneTools(conversation, token)
        val parts = finished.currentMessages.single().parts
        assertEquals(explanation, parts[0])
        assertEquals(completed, parts[1])
        assertEquals(other, parts[3])
        val interrupted = parts[2] as UIMessagePart.Tool
        assertEquals(pending.input, interrupted.input)
        assertEquals(pending.toolCallId, interrupted.toolCallId)
        assertFalse(interrupted.canResumeExecution)
        val result = Json.parseToJsonElement((interrupted.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("interrupted", result.getValue("status").jsonPrimitive.content)
        assertEquals("unknown", result.getValue("execution_outcome").jsonPrimitive.content)
        assertFalse(result.containsKey("accepted"))
        assertTrue(result.getValue("detail").jsonPrimitive.content.contains("不得直接重放"))
        assertSame(finished, finishUnresolvedPhoneTools(finished, token))
    }

    @Test
    fun `old grant cleanup leaves replacement epoch other session and unselected response untouched`() {
        val id = Uuid.random()
        val assistant = Uuid.random()
        val old = PhoneSessionToken(id.toString(), assistant.toString(), "first-session", 1)
        val resumed = old.copy(epoch = 2)
        val replacement = old.copy(sessionId = "second-session")
        fun call(token: PhoneSessionToken, callId: String) =
            UIMessagePart.Tool(callId, phoneToolPrefix(token) + "click", "{}")
        val selected = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(call(old, "old"), call(resumed, "resumed"), call(replacement, "replacement")))
        val unselected = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call(old, "unselected")))
        val conversation = Conversation(id = id, assistantId = assistant,
            messageNodes = listOf(MessageNode(messages = listOf(unselected, selected), selectIndex = 1)))

        val finished = finishUnresolvedPhoneTools(conversation, old)
        assertEquals(unselected, finished.messageNodes.single().messages[0])
        val calls = finished.currentMessages.single().getTools()
        assertTrue(calls[0].isExecuted)
        assertFalse(calls[1].isExecuted)
        assertFalse(calls[2].isExecuted)
        assertSame(conversation, finishUnresolvedPhoneTools(conversation, old.copy(conversationId = Uuid.random().toString())))
        assertSame(conversation, finishUnresolvedPhoneTools(conversation, old.copy(assistantId = Uuid.random().toString())))
    }

    @Test
    fun `a phone generation with all results stays unchanged at completion`() {
        val id = Uuid.random()
        val assistant = Uuid.random()
        val token = PhoneSessionToken(id.toString(), assistant.toString(), "session", 1)
        val completed = UIMessagePart.Tool("done", phoneToolPrefix(token) + "observe", "{}",
            output = listOf(UIMessagePart.Text("真实结果")))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(completed, UIMessagePart.Text("最终说明")))
        val conversation = Conversation(id = id, assistantId = assistant, messageNodes = listOf(message.toMessageNode()))
        assertSame(conversation, finishUnresolvedPhoneTools(conversation, token))
    }

    @Test
    fun `fork conversation inherits folder and workspace context`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = emptyList(),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
        )

        val fork = createForkConversation(source, emptyList())

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        assertEquals("Source conversation(1)", fork.title)
        assertFalse(fork.isPinned)
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val conversationId = Uuid.random()
        val params = backgroundTextGenerationParams(model, conversationId)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
        assertEquals(conversationId.toString(), params.sessionId)
    }

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }
}
