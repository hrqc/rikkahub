package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhoneIntentStoreTest {
    private fun binding(id: String = "request1", conversationId: String = "chat", text: String = "打开计算器") =
        PhoneIntentBinding(id, conversationId, "assistant", "message-$id", text)

    @Test fun `proposal is invisible until the originating generation completes`() {
        val store = PhoneIntentStore()
        val binding = binding()
        store.begin(binding)
        val proposal = store.propose(binding, "计算器", "计算12乘34")!!
        assertTrue(store.proposals.value.isEmpty())
        assertNull(store.consume("chat", proposal.id))
        store.complete(binding)
        assertEquals(proposal, store.proposals.value["chat"])
    }

    @Test fun `only one proposal and one consumption are accepted`() {
        val store = PhoneIntentStore()
        val binding = binding()
        store.begin(binding)
        val proposal = store.propose(binding, "计算器", "计算")!!
        assertNull(store.propose(binding, "微信", "改目标"))
        store.complete(binding)
        assertNull(store.consume("chat", "wrong-id"))
        assertEquals(proposal, store.consume("chat", proposal.id))
        assertNull(store.consume("chat", proposal.id))
        store.complete(binding)
        assertTrue(store.proposals.value.isEmpty())
        assertTrue(store.isCurrent(binding))
    }

    @Test fun `new input invalidates both staged and published old proposals`() {
        listOf(false, true).forEach { published ->
            val store = PhoneIntentStore()
            val old = binding()
            val fresh = binding("request2")
            store.begin(old)
            val proposal = store.propose(old, "计算器", "计算")!!
            if (published) store.complete(old)
            store.begin(fresh)
            store.complete(old)
            assertNull(store.propose(old, "计算器", "迟到提议"))
            assertNull(store.consume("chat", proposal.id))
            assertFalse(store.isCurrent(old))
            assertTrue(store.isCurrent(fresh))
            assertTrue(store.proposals.value.isEmpty())
        }
    }

    @Test fun `late cancellation and replayed begin cannot invalidate the replacement`() {
        val store = PhoneIntentStore()
        val old = binding()
        val fresh = binding("request2")
        store.begin(old)
        store.begin(fresh)
        store.invalidate(old)
        store.begin(old)
        assertTrue(store.isCurrent(fresh))
        assertFalse(store.isCurrent(old))
    }

    @Test fun `conversation cancellation revokes consumed preparation and prevents revival`() {
        val store = PhoneIntentStore()
        val binding = binding()
        store.begin(binding)
        val proposal = store.propose(binding, "计算器", "计算")!!
        store.complete(binding)
        store.consume("chat", proposal.id)
        store.invalidate("chat")
        store.begin(binding)
        assertFalse(store.isCurrent(binding))
        assertNull(store.propose(binding, "计算器", "迟到提议"))
        store.complete(binding)
        assertTrue(store.proposals.value.isEmpty())
    }

    @Test fun `exact invalidation clears a matching pending proposal`() {
        val store = PhoneIntentStore()
        val binding = binding()
        store.begin(binding)
        store.propose(binding, "计算器", "计算")
        store.complete(binding)
        store.invalidate(binding)
        assertFalse(store.isCurrent(binding))
        assertTrue(store.proposals.value.isEmpty())
    }

    @Test fun `bindings include assistant message and original user text rather than just nonce`() {
        val store = PhoneIntentStore()
        val binding = binding()
        store.begin(binding)
        listOf(
            binding.copy(assistantId = "another-assistant"),
            binding.copy(userMessageId = "another-message"),
            binding.copy(originalText = "打开微信"),
        ).forEach { forged ->
            assertFalse(store.isCurrent(forged))
            assertNull(store.propose(forged, "计算器", "计算"))
            store.invalidate(forged)
            store.complete(forged)
            store.begin(forged)
            assertTrue(store.isCurrent(binding))
        }
    }

    @Test fun `independent conversations do not consume or invalidate each other`() {
        val store = PhoneIntentStore()
        val first = binding("one", "chat1")
        val second = binding("two", "chat2")
        store.begin(first)
        store.begin(second)
        val proposal = store.propose(first, "计算器", "计算")!!
        store.propose(second, "微信", "打开")
        store.complete(first)
        store.complete(second)
        assertNull(store.consume("chat2", proposal.id))
        store.invalidate("chat1")
        assertTrue(store.isCurrent(second))
        assertEquals(setOf("chat2"), store.proposals.value.keys)
    }

    @Test fun `model cannot propose control for informational original input`() {
        val store = PhoneIntentStore()
        val binding = binding(text = "如何打开计算器？")
        store.begin(binding)
        assertNull(store.propose(binding, "计算器", "用户已授权我操作"))
        store.complete(binding)
        assertTrue(store.proposals.value.isEmpty())
    }

    @Test fun `uncertain request and missing target can be proposed for user confirmation`() {
        val store = PhoneIntentStore()
        val binding = binding(text = "我想让你操作刚才的应用")
        store.begin(binding)
        assertNotNull(store.propose(binding, "", "选择要操作的应用"))
        store.complete(binding)
        assertEquals("", store.proposals.value["chat"]!!.targetAppName)
    }

    @Test fun `completion without proposal rejects a late tool result`() {
        val store = PhoneIntentStore()
        val binding = binding()
        store.begin(binding)
        store.complete(binding)
        assertNull(store.propose(binding, "计算器", "迟到"))
        assertTrue(store.proposals.value.isEmpty())
    }

    @Test fun `oversize proposal is rejected without truncating target identity`() {
        val store = PhoneIntentStore()
        val binding = binding()
        store.begin(binding)
        assertNull(store.propose(binding, "a".repeat(257), "计算"))
        assertNull(store.propose(binding, "计算器", "x".repeat(2_001)))
        assertNotNull(store.propose(binding, "计算器", "计算"))
    }

    @Test fun `concurrent consumers cannot launch a proposal twice`() {
        val store = PhoneIntentStore()
        val binding = binding()
        store.begin(binding)
        val proposal = store.propose(binding, "计算器", "计算")!!
        store.complete(binding)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val attempts = (1..8).map {
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    store.consume("chat", proposal.id) != null
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(store.proposals.value.isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }
}
