package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTreeReadPolicyTest {
    @Test fun `changes after preview remain visible to freshness checks`() {
        val before = samplePhoneNodeText(false) { "x".repeat(300) + "99元" }
        val after = samplePhoneNodeText(false) { "x".repeat(300) + "199元" }
        assertEquals(before.value, after.value)
        assertFalse(before.contentFingerprint == after.contentFingerprint)
    }

    @Test
    fun `password fields are never read even through a getter`() {
        val sample = samplePhoneNodeText(protected = true) { error("Password getter must remain unread") }
        assertEquals("", sample.value)
        assertTrue(sample.sensitive)
    }

    @Test
    fun `sensitive marker after public preview is still detected`() {
        val sample = samplePhoneNodeText(false) { "a".repeat(300) + "支付密码" }
        assertTrue(sample.sensitive)
        assertTrue(sample.truncated)
        assertEquals("", sample.value)
    }

    @Test fun `explicit platform challenges are redacted even beyond the public preview`() {
        for (challenge in listOf("京东验证", "请点击下方按钮完成安全验证", "拖动滑块完成验证")) {
            for (prefix in listOf("", "商品介绍".repeat(100))) {
                val sample = samplePhoneNodeText(false) { prefix + challenge }
                assertTrue(sample.sensitive)
                assertFalse(sample.inspectionIncomplete)
                assertEquals(prefix.isNotEmpty(), sample.truncated)
                assertEquals("", sample.value)
                assertEquals("", sample.contentFingerprint)
            }
        }
    }

    @Test fun `ordinary verification vocabulary is not a platform challenge`() {
        listOf("产品质量经过测试验证", "实验验证结果", "快速验证产品性能").forEach { text ->
            val sample = samplePhoneNodeText(false) { text }
            assertFalse(sample.sensitive)
            assertFalse(sample.inspectionIncomplete)
            assertEquals(text, sample.value)
        }
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
        assertFalse(sample.sensitive)
        assertTrue(sample.inspectionIncomplete)
        assertTrue(sample.truncated)
        assertEquals("", sample.value)
    }

    @Test
    fun `ordinary text is bounded while null remains empty`() {
        val sample = samplePhoneNodeText(false) { "x".repeat(300) }
        assertEquals(240, sample.value.length)
        assertTrue(sample.truncated)
        assertFalse(sample.sensitive)
        assertFalse(sample.inspectionIncomplete)
        assertEquals(PhoneTextSample("", false, false), samplePhoneNodeText(false) { null })
    }

    @Test
    fun `invisible containers consume visit budget even without emitted nodes`() {
        val budget = PhoneTreeReadBudget(0)
        repeat(768) { assertTrue(budget.visit(0, 0)) }
        assertFalse(budget.visit(0, 0))
        assertTrue(budget.truncated)
        assertEquals(768, budget.visits)
    }

    @Test fun `production read flags require all three bits while allowing unrelated flags`() {
        assertTrue(hasProductionPhoneReadFlags(82))
        assertTrue(hasProductionPhoneReadFlags(82 or 128 or 512))
        assertFalse(hasProductionPhoneReadFlags(null))
        assertFalse(hasProductionPhoneReadFlags(0))
        listOf(2, 16, 64).forEach { requiredBit ->
            assertFalse(hasProductionPhoneReadFlags((82 and requiredBit.inv()) or 128 or 512))
        }
    }

    @Test
    fun `depth elapsed time node count and total text independently limit a tree`() {
        assertFalse(PhoneTreeReadBudget(0).visit(41, 0))
        assertFalse(PhoneTreeReadBudget(0).visit(0, 2_001))
        val nodes = PhoneTreeReadBudget(0)
        repeat(100) { assertTrue(nodes.include(0)) }
        assertFalse(nodes.include(0))
        assertFalse(nodes.truncated)
        assertTrue(nodes.previewTruncated)
        assertTrue(nodes.visit(10, 0)) // Preview omission never ends the safety walk.
        val text = PhoneTreeReadBudget(0)
        assertTrue(text.include(12_000))
        assertFalse(text.include(1))
        assertFalse(text.truncated)
        assertTrue(text.previewTruncated)
    }

    @Test fun `payment action hidden beyond preview still requires user but can be read`() {
        val sample = samplePhoneNodeText(false) { "商品说明".repeat(100) + "确认付款" }
        assertFalse(sample.sensitive)
        assertFalse(sample.inspectionIncomplete)
        assertTrue(sample.requiresUserConfirmation)
        assertTrue(sample.truncated)
        assertTrue(PhonePurchasePolicy.requiresUser("确 认 支 付"))
        assertTrue(PhonePurchasePolicy.requiresUser("立即开通会员"))
        assertTrue(PhonePurchasePolicy.requiresUser("免费试用"))
        listOf("去支付", "确认并支付", "Pay ¥99.00", "抵扣200积分", "立即兑换").forEach {
            assertTrue(it, PhonePurchasePolicy.requiresUser(it))
        }
        assertFalse(PhonePurchasePolicy.requiresUser("免费领取商品券 满200减20"))
    }
}
