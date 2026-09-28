package me.rerere.rikkahub.data.mobileagent

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure Root-first eligibility tests; live authorization and backend routing are not exercised here. */
class PhoneRootScrollPolicyTest {
    private val targetPackage = "com.jingdong.app.mall"
    private val windowId = 7
    private val window = PhoneBounds(0, 0, 1_000, 2_000)
    private val node = PhoneNode(
        id = "n4",
        role = "android.widget.ScrollView",
        bounds = PhoneBounds(50, 100, 950, 1_900),
        scrollable = true,
    )
    private val forwardAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    private val backwardAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    private val downAction = android.R.id.accessibilityActionScrollDown
    private val upAction = android.R.id.accessibilityActionScrollUp
    private val forwardSwipe = RootInputAction.Swipe(500, 1_450, 500, 550)
    private val backwardSwipe = RootInputAction.Swipe(500, 550, 500, 1_450)

    private fun signature(preview: PhoneNode = node) = AndroidNodeSignature(
        windowId = windowId, packageName = targetPackage, uniqueId = null,
        role = preview.role, viewId = "", text = "", description = "",
        left = preview.bounds.left, top = preview.bounds.top,
        right = preview.bounds.right, bottom = preview.bounds.bottom,
        enabled = preview.enabled, clickable = false, longClickable = false,
        editable = preview.editable, scrollable = preview.scrollable, password = preview.password,
        sensitive = false, truncated = false, inspectionIncomplete = false,
        requiresUserConfirmation = preview.requiresUserConfirmation,
        contentFingerprint = "0".repeat(64),
    )

    private fun tree(
        preview: PhoneNode = node,
        proof: AndroidNodeSignature = signature(preview),
    ) = AndroidTreeCapture(
        nodes = listOf(preview),
        handles = mapOf(preview.id to AndroidNodeHandle(listOf(0, 4), proof)),
        truncated = false,
        sensitive = false,
        fingerprint = "1".repeat(64),
        previewTruncated = false,
        inspectionIssues = emptyList(),
        restrictedPaths = emptyList(),
        nativeScrollNodeIds = setOf(preview.id),
    )

    private fun plan(
        capture: AndroidTreeCapture = tree(),
        forward: Boolean = true,
        actions: Set<Int> = setOf(forwardAction, backwardAction),
        bounds: PhoneBounds = window,
        packageName: String = targetPackage,
        requestedWindowId: Int = windowId,
        requestedNodeId: String = node.id,
        rows: Int? = null,
        columns: Int? = null,
        obscuring: List<PhoneBounds> = emptyList(),
    ) = phoneRootScrollPlan(
        targetPackage = packageName,
        windowId = requestedWindowId,
        windowBounds = bounds,
        tree = capture,
        nodeId = requestedNodeId,
        forward = forward,
        actionIds = actions,
        collectionRows = rows,
        collectionColumns = columns,
        obscuringBounds = obscuring,
    )

    @Test
    fun `known vertical containers plan one centered swipe in the requested direction`() {
        for (role in listOf("android.widget.ScrollView", "android.widget.ListView",
            "androidx.core.widget.NestedScrollView")) {
            val capture = tree(node.copy(role = role))
            assertEquals(role, forwardSwipe, plan(capture))
            assertEquals(role, backwardSwipe, plan(capture, forward = false))
        }
    }

    @Test
    fun `gesture coordinates use the clipped intersection including window offsets`() {
        val capture = tree(node.copy(bounds = PhoneBounds(-50, 50, 950, 2_600)))
        val shiftedWindow = PhoneBounds(100, 200, 1_100, 2_200)
        assertEquals(RootInputAction.Swipe(525, 1_700, 525, 700), plan(capture, bounds = shiftedWindow))
        assertEquals(RootInputAction.Swipe(525, 700, 525, 1_700),
            plan(capture, bounds = shiftedWindow, forward = false))
    }

    @Test
    fun `small narrow offscreen and malformed node bounds cannot produce gestures`() {
        val rejected = listOf(
            PhoneBounds(0, 0, 1_000, 399),
            PhoneBounds(0, 0, 1_000, 799),
            PhoneBounds(0, 0, 499, 1_800),
            PhoneBounds(1_000, 0, 2_000, 1_800),
            PhoneBounds(0, 2_000, 1_000, 3_800),
            PhoneBounds(500, 100, 500, 1_900),
            PhoneBounds(950, 100, 50, 1_900),
            PhoneBounds(50, 1_900, 950, 100),
        )
        rejected.forEach { bounds ->
            assertNull(bounds.toString(), plan(tree(node.copy(bounds = bounds))))
        }
        // Minimum height is independent of the relative-height requirement.
        assertNull(plan(tree(node.copy(bounds = PhoneBounds(0, 0, 1_000, 399))),
            bounds = PhoneBounds(0, 0, 1_000, 800)))
    }

    @Test
    fun `horizontal strips fail even when absolute height and screen coverage pass`() {
        val landscapeWindow = PhoneBounds(0, 0, 2_000, 1_000)
        val capture = tree(node.copy(bounds = PhoneBounds(100, 100, 1_900, 700)))
        assertNull(plan(capture, bounds = landscapeWindow))
        // One extra pixel of height crosses the aspect threshold while retaining safe geometry.
        val tallEnough = tree(node.copy(bounds = PhoneBounds(100, 100, 1_900, 701)))
        assertEquals(RootInputAction.Swipe(1_000, 551, 1_000, 250),
            plan(tallEnough, bounds = landscapeWindow))
    }

    @Test
    fun `invalid windows are rejected and inclusive size thresholds remain usable`() {
        for (bounds in listOf(
            PhoneBounds(-1, 0, 1_000, 2_000), PhoneBounds(0, -1, 1_000, 2_000),
            PhoneBounds(0, 0, 65_536, 2_000), PhoneBounds(0, 0, 1_000, 65_536),
            PhoneBounds(0, 0, 0, 2_000), PhoneBounds(0, 2_000, 1_000, 0),
        )) assertNull(bounds.toString(), plan(bounds = bounds))
        // Exactly half the window width and 40 percent of its height are sufficient.
        val minimum = tree(node.copy(bounds = PhoneBounds(100, 100, 600, 500)))
        assertEquals(RootInputAction.Swipe(350, 400, 350, 200),
            plan(minimum, bounds = PhoneBounds(0, 0, 1_000, 1_000)))
    }

    @Test
    fun `incomplete inspection sensitivity and restrictions reject the entire tree`() {
        val capture = tree()
        val unsafe = listOf(
            capture.copy(truncated = true),
            capture.copy(sensitive = true),
            capture.copy(inspectionIssues = listOf("child_limit")),
            capture.copy(restrictedPaths = listOf(listOf(0, 4, 1))),
            capture.copy(nodes = capture.nodes + node.copy(id = "n5", requiresUserConfirmation = true)),
        )
        unsafe.forEach { assertNull(plan(it)) }
    }

    @Test
    fun `shared coordinate guard blocks legacy fallback when only an omitted node is restricted`() {
        val safePreview = tree().copy(previewTruncated = true)
        assertFalse(hasPhoneScrollTransactionRestriction(safePreview))
        assertEquals(forwardSwipe, plan(safePreview))

        val restrictedOutsidePreview = safePreview.copy(restrictedPaths = listOf(listOf(90, 2, 5)))
        assertFalse(restrictedOutsidePreview.nodes.any { it.requiresUserConfirmation })
        // Both preferred Root planning and the legacy swipe dispatcher use this whole-tree guard.
        assertTrue(hasPhoneScrollTransactionRestriction(restrictedOutsidePreview))
        assertNull(plan(restrictedOutsidePreview))
    }

    @Test
    fun `preview clipping does not hide restrictions found outside the emitted nodes`() {
        val clipped = tree().copy(previewTruncated = true)
        assertEquals(forwardSwipe, plan(clipped))
        // This path has neither a preview node nor an action handle.
        assertNull(plan(clipped.copy(restrictedPaths = listOf(listOf(90, 2, 5)))))
        assertNull(plan(clipped.copy(inspectionIssues = listOf("text_limit"))))
    }

    @Test
    fun `unsafe target preview and signature cannot authorize coordinate scrolling`() {
        for (preview in listOf(
            node.copy(scrollable = false), node.copy(enabled = false),
            node.copy(password = true), node.copy(editable = true),
            node.copy(requiresUserConfirmation = true),
        )) assertNull(plan(tree(preview)))
        val proof = signature()
        for (unsafe in listOf(
            proof.copy(scrollable = false), proof.copy(enabled = false), proof.copy(password = true),
            proof.copy(editable = true), proof.copy(sensitive = true), proof.copy(truncated = true),
            proof.copy(inspectionIncomplete = true), proof.copy(metadataTruncated = true),
            proof.copy(requiresUserConfirmation = true),
        )) assertNull(plan(tree(proof = unsafe)))
    }

    @Test
    fun `foreign identities missing handles and mismatched target signatures are rejected`() {
        val capture = tree()
        assertNull(plan(packageName = "com.example.other"))
        assertNull(plan(requestedWindowId = windowId + 1))
        assertNull(plan(requestedNodeId = "n99"))
        assertNull(plan(capture.copy(handles = emptyMap())))
        assertNull(plan(capture.copy(nodes = listOf(node, node))))
        val proof = signature()
        for (mismatch in listOf(
            proof.copy(packageName = "com.example.other"), proof.copy(windowId = windowId + 1),
            proof.copy(role = "android.widget.ListView"), proof.copy(left = proof.left + 1),
            proof.copy(bottom = proof.bottom - 1),
        )) assertNull(plan(tree(proof = mismatch)))
    }

    @Test
    fun `tall RecyclerView shape and horizontal or unknown collections do not prove a vertical axis`() {
        val capture = tree(node.copy(role = "androidx.recyclerview.widget.RecyclerView"))
        assertNull(plan(capture))
        for ((rows, columns) in listOf(
            null to 1, 20 to null, 0 to 0, 1 to 10, 2 to 3, 4 to 4, 20 to 0, 20 to 5,
        )) assertNull("rows=$rows columns=$columns", plan(capture, rows = rows, columns = columns))
        assertNull(plan(capture, actions = setOf(forwardAction, android.R.id.accessibilityActionScrollRight)))
    }

    @Test
    fun `RecyclerView directional actions prove only their requested vertical direction`() {
        for (role in listOf("androidx.recyclerview.widget.RecyclerView", "android.support.v7.widget.RecyclerView")) {
            val capture = tree(node.copy(role = role))
            assertEquals(forwardSwipe, plan(capture, actions = setOf(downAction)))
            assertEquals(backwardSwipe, plan(capture, forward = false, actions = setOf(upAction)))
            assertNull(plan(capture, actions = setOf(forwardAction, upAction)))
            assertNull(plan(capture, forward = false, actions = setOf(backwardAction, downAction)))
        }
    }

    @Test
    fun `row dominant bounded RecyclerView collections support standard scrolling actions`() {
        for (role in listOf("androidx.recyclerview.widget.RecyclerView", "android.support.v7.widget.RecyclerView")) {
            val capture = tree(node.copy(role = role))
            for ((rows, columns) in listOf(2 to 1, 12 to 4)) {
                assertEquals(forwardSwipe, plan(capture, rows = rows, columns = columns))
                assertEquals(backwardSwipe, plan(capture, forward = false, rows = rows, columns = columns))
            }
        }
    }

    @Test
    fun `axis evidence cannot replace the requested scroll action`() {
        val recycler = tree(node.copy(role = "androidx.recyclerview.widget.RecyclerView"))
        for (capture in listOf(tree(), recycler)) {
            assertNull(plan(capture, actions = emptySet(), rows = 20, columns = 1))
            assertNull(plan(capture, actions = setOf(backwardAction, upAction), rows = 20, columns = 1))
            assertNull(plan(capture, forward = false, actions = setOf(forwardAction, downAction),
                rows = 20, columns = 1))
        }
    }

    @Test
    fun `custom and horizontal containers remain unsupported even with vertical action metadata`() {
        for (role in listOf("com.example.CustomList", "android.widget.HorizontalScrollView", "android.view.ViewGroup")) {
            val capture = tree(node.copy(role = role))
            assertNull(plan(capture, actions = setOf(downAction), rows = 20, columns = 1))
            assertNull(plan(capture, forward = false, actions = setOf(upAction), rows = 20, columns = 1))
        }
    }

    @Test
    fun `overlays crossing either endpoint or the gesture line reject while adjacent overlays do not`() {
        for (overlay in listOf(
            PhoneBounds(490, 900, 510, 1_000),
            PhoneBounds(490, 540, 510, 551),
            PhoneBounds(490, 1_450, 510, 1_460),
        )) {
            assertNull(overlay.toString(), plan(obscuring = listOf(overlay)))
            assertNull(overlay.toString(), plan(forward = false, obscuring = listOf(overlay)))
        }
        val adjacent = listOf(
            PhoneBounds(0, 550, 500, 1_451), PhoneBounds(501, 550, 1_000, 1_451),
            PhoneBounds(490, 0, 510, 550), PhoneBounds(490, 1_451, 510, 2_000),
        )
        assertEquals(forwardSwipe, plan(obscuring = adjacent))
        assertEquals(backwardSwipe, plan(forward = false, obscuring = adjacent))
        assertNull(plan(obscuring = adjacent + PhoneBounds(500, 1_000, 501, 1_001)))
    }
}
