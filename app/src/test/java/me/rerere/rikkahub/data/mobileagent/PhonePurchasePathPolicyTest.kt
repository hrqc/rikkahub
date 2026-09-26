package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.*
import org.junit.Test

class PhonePurchasePathPolicyTest {
    @Test fun `payment label on container also guards an unlabeled actionable descendant`() {
        val paymentContainer = listOf(0)
        assertTrue(isPhonePurchasePathRestricted(listOf(0, 0), listOf(paymentContainer)))
        assertTrue(isPhonePurchasePathRestricted(listOf(0, 1, 2), listOf(paymentContainer)))
    }

    @Test fun `payment label on a leaf guards its clickable wrappers and the leaf itself`() {
        val label = listOf(0, 2, 1)
        assertTrue(isPhonePurchasePathRestricted(listOf(0), listOf(label)))
        assertTrue(isPhonePurchasePathRestricted(listOf(0, 2), listOf(label)))
        assertTrue(isPhonePurchasePathRestricted(label, listOf(label)))
    }

    @Test fun `unrelated sibling controls remain outside a restricted subtree`() {
        val restricted = listOf(listOf(0, 2))
        assertFalse(isPhonePurchasePathRestricted(listOf(0, 1), restricted))
        assertFalse(isPhonePurchasePathRestricted(listOf(1, 2), restricted))
        assertFalse(isPhonePurchasePathRestricted(listOf(0, 1, 2), restricted))
        assertFalse(isPhonePurchasePathRestricted(listOf(0), emptyList()))
    }

    @Test fun `a restricted root conservatively covers all actionable descendants`() {
        assertTrue(isPhonePurchasePathRestricted(emptyList(), listOf(emptyList())))
        assertTrue(isPhonePurchasePathRestricted(listOf(3, 2, 1), listOf(emptyList())))
    }
}
