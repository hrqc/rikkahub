package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.*
import org.junit.Test

class PhoneObservationCapabilityPolicyTest {
    private val list = PhoneNode(id = "list", bounds = PhoneBounds(0, 0, 100, 100), scrollable = true)
    private fun allowed(nodes: List<PhoneNode> = listOf(list), issues: List<String> = listOf("unavailable_child"),
        sensitive: Boolean = false, truncated: Boolean = true) = canUseNativeScrollOnly(sensitive, truncated, issues, nodes)
    private fun observation() = PhoneObservation("snapshot", "test.target", 1, 1, 123,
        listOf(list), truncated = true, sensitive = false, fingerprint = "known-tree",
        inspectionIssues = listOf("unavailable_child"), scrollOnly = true)

    @Test fun `only sole unavailable children with a usable scroll node receive limited capability`() {
        assertTrue(allowed())
        assertTrue(allowed(issues = listOf("unavailable_child", "unavailable_child")))
        assertFalse(allowed(truncated = false))
        assertFalse(allowed(sensitive = true))
        assertFalse(allowed(issues = emptyList()))
        listOf("visit_limit", "time_limit", "foreign_node", "text_limit", "depth_limit", "children_limit", "node_refresh_failed", "unknown").forEach { issue ->
            assertFalse(issue, allowed(issues = listOf(issue)))
            assertFalse(issue, allowed(issues = listOf("unavailable_child", issue)))
        }
    }

    @Test fun `missing disabled password transaction and zero sized scroll nodes are denied`() {
        assertFalse(allowed(nodes = emptyList()))
        listOf(list.copy(scrollable = false), list.copy(enabled = false), list.copy(password = true),
            list.copy(requiresUserConfirmation = true), list.copy(text = "立即付款"),
            list.copy(description = "提交订单"), list.copy(bounds = PhoneBounds(0, 0, 0, 100))).forEach { node ->
            assertFalse(node.toString(), allowed(nodes = listOf(node)))
        }
        assertFalse(allowed(nodes = listOf(list, list.copy(id = "password", scrollable = false, password = true))))
        assertFalse(allowed(nodes = listOf(list, list.copy(id = "sensitive", scrollable = false, description = "请输入验证码"))))
    }

    @Test fun `another transaction control does not turn safe sibling scrolling into a click capability`() {
        assertTrue(allowed(nodes = listOf(list, list.copy(id = "pay", scrollable = false, text = "立即付款"))))
        assertTrue(isNativeScrollPathRestricted(listOf(1, 2), listOf(listOf(1))))
        assertTrue(isNativeScrollPathRestricted(listOf(1), listOf(listOf(1))))
        assertTrue(isNativeScrollPathRestricted(listOf(1), listOf(emptyList())))
        assertFalse(isNativeScrollPathRestricted(listOf(1), listOf(listOf(2))))
        assertFalse(isNativeScrollPathRestricted(listOf(1), listOf(listOf(1, 2))))
    }

    @Test fun `partial preview removes product text and all non-scroll interaction claims`() {
        val original = list.copy(parentId = "root", text = "商品 A 99 元", description = "商品列表",
            clickable = true, longClickable = true, editable = true)
        val output = nativeScrollOnlyPreview(listOf(original, original.copy(id = "pay", requiresUserConfirmation = true),
            original.copy(id = "button", scrollable = false)))
        assertEquals(1, output.size)
        with(output.single()) {
            assertEquals("list", id)
            assertEquals("", text)
            assertEquals("", description)
            assertNull(parentId)
            assertFalse(clickable)
            assertFalse(longClickable)
            assertFalse(editable)
            assertTrue(scrollable)
        }
        assertEquals("商品 A 99 元", original.text)
    }

    @Test fun `stored restricted capability accepts only a scroll of an exposed node`() {
        val obs = observation()
        assertTrue(nativeScrollOnlyRequestAllowed(PhoneSnapshotCapability.NATIVE_SCROLL_ONLY, obs, obs, PhoneAction.Scroll("list", true)))
        assertTrue(nativeScrollOnlyRequestAllowed(PhoneSnapshotCapability.NATIVE_SCROLL_ONLY, obs, obs, PhoneAction.Scroll("list", false)))
        val rejected = listOf(PhoneAction.Click("list"), PhoneAction.LongClick("list"), PhoneAction.InputText("list", "value"),
            PhoneAction.Scroll("unexposed", true), PhoneAction.Swipe(PhoneSwipeDirection.UP), PhoneAction.Back,
            PhoneAction.Screenshot, PhoneAction.OpenApp)
        rejected.forEach { action ->
            assertFalse(action.toString(), nativeScrollOnlyRequestAllowed(PhoneSnapshotCapability.NATIVE_SCROLL_ONLY, obs, obs, action))
        }
    }

    @Test fun `caller cannot forge scroll-only authority or substitute a snapshot`() {
        val obs = observation()
        val action = PhoneAction.Scroll("list", true)
        listOf(PhoneSnapshotCapability.FULL, PhoneSnapshotCapability.NONE).forEach { capability ->
            assertFalse(nativeScrollOnlyRequestAllowed(capability, obs, obs, action))
        }
        listOf(null, obs.copy(scrollOnly = false), obs.copy(id = "other"), obs.copy(windowRevision = 2),
            obs.copy(nodes = listOf(list.copy(id = "forged")))).forEach { request ->
            assertFalse(nativeScrollOnlyRequestAllowed(PhoneSnapshotCapability.NATIVE_SCROLL_ONLY, obs, request, action))
        }
        val full = obs.copy(truncated = false, inspectionIssues = emptyList(), scrollOnly = false)
        assertFalse(nativeScrollOnlyRequestAllowed(PhoneSnapshotCapability.FULL, full, full.copy(scrollOnly = true), action))
        assertFalse(nativeScrollOnlyRequestAllowed(PhoneSnapshotCapability.NATIVE_SCROLL_ONLY, full, full, action))
    }
}
