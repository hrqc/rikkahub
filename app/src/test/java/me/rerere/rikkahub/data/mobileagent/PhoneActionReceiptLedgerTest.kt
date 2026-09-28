package me.rerere.rikkahub.data.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.phoneToolPrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActionReceiptLedgerTest {
    private val token = PhoneSessionToken("conversation", "assistant", "session-123456789", 7)
    private val name = phoneToolPrefix(token) + "click"
    private val key = PhoneActionReceiptKey("message", "call", name)

    private fun PhoneActionReceiptLedger.recorder(
        messageId: String = key.messageId,
        callId: String = key.toolCallId,
        toolName: String = name,
    ): PhoneActionReceiptRecorder = checkNotNull(beginCall(messageId, callId, toolName)?.forTool(token, toolName))

    private fun differentTokens() = listOf(
        token.copy(conversationId = "other-conversation"),
        token.copy(assistantId = "other-assistant"),
        // Same advertised prefix: exact token matching must still reject this session.
        token.copy(sessionId = "session-123456789-other"),
        token.copy(epoch = token.epoch + 1),
    )

    @Test
    fun `lookup requires exact message call name and every token field`() {
        val ledger = PhoneActionReceiptLedger(token)
        ledger.recorder().backendResult(true)
        val batch = ledger.freeze()
        assertEquals(true, batch.find(token, key)?.accepted)
        assertNull(batch.find(token, key.copy(messageId = "other-message")))
        assertNull(batch.find(token, key.copy(toolCallId = "other-call")))
        assertNull(batch.find(token, key.copy(toolName = phoneToolPrefix(token) + "back")))
        differentTokens().forEach { assertNull(batch.find(it, key)) }
    }

    @Test
    fun `context and action recorder require exact token and operation`() {
        val ledger = PhoneActionReceiptLedger(token)
        val context = checkNotNull(ledger.beginCall(key.messageId, key.toolCallId, name))
        val recorder = checkNotNull(context.forTool(token, name))
        assertSame(recorder, recorder.forAction(token, PhoneAction.Click("n0")))
        assertNull(context.forTool(token, phoneToolPrefix(token) + "back"))
        assertNull(recorder.forAction(token, PhoneAction.Back))
        assertNull(recorder.forAction(token, PhoneAction.Screenshot))
        differentTokens().forEach {
            assertNull(context.forTool(it, name))
            assertNull(recorder.forAction(it, PhoneAction.Click("n0")))
        }
    }

    @Test
    fun `repeated exact key becomes ambiguous and rejects late writes from first invocation`() {
        val ledger = PhoneActionReceiptLedger(token)
        val first = ledger.recorder()
        first.backendResult(true)
        assertNull(ledger.beginCall(key.messageId, key.toolCallId, name))
        first.observed(true)
        first.backendResult(true)
        assertNull(first.knownResult())
        assertNull(ledger.freeze().find(token, key))
    }

    @Test
    fun `same call id in another message or operation does not reuse a receipt`() {
        val ledger = PhoneActionReceiptLedger(token)
        ledger.recorder().backendResult(true)
        val otherMessage = key.copy(messageId = "message-2")
        val otherName = key.copy(toolName = phoneToolPrefix(token) + "back")
        ledger.recorder(messageId = otherMessage.messageId).backendResult(false)
        ledger.recorder(toolName = otherName.toolName).backendResult(false)
        val batch = ledger.freeze()
        assertEquals(true, batch.find(token, key)?.accepted)
        assertEquals(false, batch.find(token, otherMessage)?.accepted)
        assertEquals(false, batch.find(token, otherName)?.accepted)
    }

    @Test
    fun `identity length and capacity bounds fail closed without evicting an earlier receipt`() {
        val ledger = PhoneActionReceiptLedger(token)
        for (invalid in listOf("", "x".repeat(129))) {
            assertNull(ledger.beginCall(invalid, "call", name))
            assertNull(ledger.beginCall("message", invalid, name))
            assertNull(ledger.beginCall("message", "call", invalid))
        }
        val longest = PhoneActionReceiptKey("m".repeat(128), "c".repeat(128), name)
        checkNotNull(ledger.beginCall(longest.messageId, longest.toolCallId, name)?.forTool(token, name))
            .backendResult(true)
        repeat(PhoneActionReceiptLedger.MAX_CALLS - 1) { index ->
            assertNotNull(ledger.beginCall("message-$index", "call", name))
        }
        assertNull(ledger.beginCall("over-capacity", "call", name))
        val batch = ledger.freeze()
        assertEquals(true, batch.find(token, longest)?.accepted)
        assertNull(batch.find(token, PhoneActionReceiptKey("over-capacity", "call", name)))
        // Allocation without a backend result must never imply dispatch.
        assertNull(batch.find(token, PhoneActionReceiptKey("message-0", "call", name)))
    }

    @Test
    fun `read only and screenshot calls never receive an action recorder`() {
        val ledger = PhoneActionReceiptLedger(token)
        for (operation in listOf("observe", "read_observed_content", "screenshot", "invented")) {
            assertNull(ledger.beginCall("message", "call", phoneToolPrefix(token) + operation))
        }
        assertNull(ledger.beginCall("message", "call", "shopping_compare"))
        assertNull(ledger.beginCall("message", "call", phoneToolPrefix(token.copy(epoch = 8)) + "click"))
        for (operation in listOf("click", "long_click", "input_text", "scroll", "swipe", "back", "open_app")) {
            assertNotNull(ledger.beginCall("message", "call", phoneToolPrefix(token) + operation))
        }
    }

    @Test
    fun `freeze closes writes and produces an immutable one shot batch`() {
        val ledger = PhoneActionReceiptLedger(token)
        val recorder = ledger.recorder()
        recorder.backendResult(true)
        val batch = ledger.freeze()
        val original = checkNotNull(batch.find(token, key))
        recorder.backendResult(false)
        recorder.postObserveFailed("PAGE_UNSTABLE")
        recorder.observed(true)
        assertNull(recorder.knownResult())
        assertNull(ledger.beginCall("later", "call", name))
        assertEquals(original, batch.find(token, key))
        assertNull(ledger.freeze().find(token, key))
        ledger.clear()
        assertEquals(original, batch.find(token, key))
    }

    @Test
    fun `clear prevents subsequent recording registration and recovery`() {
        val ledger = PhoneActionReceiptLedger(token)
        val recorder = ledger.recorder()
        recorder.backendResult(true)
        ledger.clear()
        recorder.backendResult(true)
        recorder.postObserveFailed("PAGE_UNSTABLE")
        recorder.observed(true)
        assertNull(recorder.knownResult())
        assertNull(ledger.beginCall("later", "call", name))
        assertNull(ledger.freeze().find(token, key))
    }

    @Test
    fun `metadata preserves only fixed error codes and never private identities or arguments`() {
        for (code in listOf("PAGE_UNSTABLE", "INCOMPLETE_SCREEN", "SESSION_INVALID", "private-input-error")) {
            val ledger = PhoneActionReceiptLedger(token)
            val recorder = ledger.recorder("private-message", "private-call")
            recorder.backendResult(true)
            recorder.postObserveFailed(code)
            val receipt = checkNotNull(recorder.knownResult())
            val text = (receipt.output().single() as UIMessagePart.Text).text
            val result = Json.parseToJsonElement(text).jsonObject
            assertEquals(if (code.startsWith("private")) "POST_OBSERVATION_FAILED" else code,
                result.getValue("post_observe_error").jsonPrimitive.content)
            assertEquals(setOf("status", "accepted", "execution_outcome", "post_observation_verified",
                "post_observe_error", "grantsActionPermission", "detail"), result.keys)
            assertEquals("accepted_unverified", result.getValue("execution_outcome").jsonPrimitive.content)
            assertEquals("false", result.getValue("grantsActionPermission").jsonPrimitive.content)
            assertTrue(text.contains("不得直接重放"))
            assertFalse(text.contains("private"))
            assertFalse(text.contains("snapshot"))
            assertFalse(text.contains("node_id"))
            assertFalse(text.contains("input"))
        }
    }

    @Test
    fun `first backend result cannot be overwritten and failed observation cannot become verified`() {
        val ledger = PhoneActionReceiptLedger(token)
        val recorder = ledger.recorder()
        recorder.postObserveFailed("PAGE_UNSTABLE")
        recorder.observed(true)
        assertNull(recorder.knownResult())
        recorder.backendResult(true)
        recorder.backendResult(false)
        recorder.postObserveFailed("PAGE_UNSTABLE")
        recorder.postObserveFailed("INCOMPLETE_SCREEN")
        recorder.observed(true)
        assertEquals(PhoneActionReceipt(true, "PAGE_UNSTABLE"), recorder.knownResult())
    }

    @Test
    fun `verified observation survives late error but never claims business completion`() {
        val ledger = PhoneActionReceiptLedger(token)
        val recorder = ledger.recorder()
        recorder.backendResult(true)
        recorder.observed(false)
        recorder.postObserveFailed("PAGE_UNSTABLE")
        recorder.backendResult(false)
        val receipt = checkNotNull(recorder.knownResult())
        assertEquals(PhoneActionReceipt(true, observationVerified = true, screenChanged = false), receipt)
        val result = Json.parseToJsonElement((receipt.output().single() as UIMessagePart.Text).text).jsonObject
        assertEquals("accepted_observed", result.getValue("execution_outcome").jsonPrimitive.content)
        assertEquals("false", result.getValue("screenChanged").jsonPrimitive.content)
        assertTrue(result.getValue("detail").jsonPrimitive.content.contains("不代表用户任务完成"))
    }

    @Test
    fun `root executor survives failed post observation without claiming completion`() {
        val ledger = PhoneActionReceiptLedger(token)
        val recorder = ledger.recorder()
        recorder.backendResult(true, PhoneActionExecutor.ROOT_INPUT)
        recorder.postObserveFailed("PAGE_UNSTABLE")
        recorder.backendResult(false, PhoneActionExecutor.ACCESSIBILITY)
        val receipt = checkNotNull(ledger.freeze().find(token, key))
        val result = Json.parseToJsonElement((receipt.output().single() as UIMessagePart.Text).text).jsonObject
        assertEquals("root_input", result.getValue("executor").jsonPrimitive.content)
        assertEquals("accepted_unverified", result.getValue("execution_outcome").jsonPrimitive.content)
        assertEquals("false", result.getValue("post_observation_verified").jsonPrimitive.content)
        assertFalse(result.containsKey("screenChanged"))
    }

    @Test
    fun `backend rejection does not claim non execution or invent an observation`() {
        val ledger = PhoneActionReceiptLedger(token)
        val recorder = ledger.recorder()
        recorder.backendResult(false)
        recorder.backendResult(true)
        recorder.observed(true)
        recorder.postObserveFailed("PAGE_UNSTABLE")
        val receipt = checkNotNull(recorder.knownResult())
        assertEquals(PhoneActionReceipt(false), receipt)
        val text = (receipt.output().single() as UIMessagePart.Text).text
        val result = Json.parseToJsonElement(text).jsonObject
        assertEquals("not_accepted", result.getValue("execution_outcome").jsonPrimitive.content)
        assertEquals("false", result.getValue("accepted").jsonPrimitive.content)
        assertFalse(result.containsKey("screenChanged"))
        assertFalse(result.containsKey("execution_started"))
        assertFalse(text.contains("未执行"))
        assertFalse(text.contains("没有执行"))
        assertFalse(text.contains("用户取消"))
        assertTrue(text.contains("不得自动重试"))
    }
}
