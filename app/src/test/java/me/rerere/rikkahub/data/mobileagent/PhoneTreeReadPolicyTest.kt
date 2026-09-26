package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTreeReadPolicyTest {
    @Test
    fun `password fields are never read even through a getter`() {
        val sample = samplePhoneNodeText(protected = true) { error("Password getter must remain unread") }
        assertEquals("", sample.value)
        assertTrue(sample.sensitive)
    }

    @Test
    fun `sensitive marker after public preview is still detected`() {
        val sample = samplePhoneNodeText(false) { "a".repeat(300) + "确认付款" }
        assertTrue(sample.sensitive)
        assertTrue(sample.truncated)
        assertEquals("", sample.value)
    }

    @Test
    fun `oversize text is rejected without materializing the char sequence`() {
        val sample = samplePhoneNodeText(false) {
            object : CharSequence {
                override val length = 100_000
                override fun get(index: Int): Char = error("Too large to inspect")
                override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Too large to inspect")
                override fun toString(): String = error("Too large to copy")
            }
        }
        assertTrue(sample.sensitive)
        assertTrue(sample.truncated)
        assertEquals("", sample.value)
    }

    @Test
    fun `ordinary text is bounded while null remains empty`() {
        val sample = samplePhoneNodeText(false) { "x".repeat(300) }
        assertEquals(240, sample.value.length)
        assertTrue(sample.truncated)
        assertFalse(sample.sensitive)
        assertEquals(PhoneTextSample("", false, false), samplePhoneNodeText(false) { null })
    }

    @Test
    fun `invisible containers consume visit budget even without emitted nodes`() {
        val budget = PhoneTreeReadBudget(0)
        repeat(256) { assertTrue(budget.visit(0, 0)) }
        assertFalse(budget.visit(0, 0))
        assertTrue(budget.truncated)
        assertEquals(256, budget.visits)
    }

    @Test
    fun `depth elapsed time node count and total text independently limit a tree`() {
        assertFalse(PhoneTreeReadBudget(0).visit(25, 0))
        assertFalse(PhoneTreeReadBudget(0).visit(0, 1_501))
        val nodes = PhoneTreeReadBudget(0)
        repeat(100) { assertTrue(nodes.include(0)) }
        assertFalse(nodes.include(0))
        assertTrue(nodes.truncated)
        val text = PhoneTreeReadBudget(0)
        assertTrue(text.include(12_000))
        assertFalse(text.include(1))
        assertTrue(text.truncated)
    }
}
