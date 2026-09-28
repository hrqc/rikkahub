package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Full-tree pure-builder tests, without Android dispatch or live authorization. */
class PhoneSearchEntryPolicyTest {
    private val root = emptyList<Int>()
    private val bar = listOf(0)
    private val parent = listOf(0, 0)
    private val entry = listOf(0, 0, 0)
    private val term = entry + 0
    private val camera = listOf(0, 0, 1)
    private val search = listOf(0, 0, 2)
    private val searchLabel = search + 0
    private val entryScope = PhoneClickRevalidationScope.JD_SEARCH_ENTRY
    private val navigationScope = PhoneClickRevalidationScope.JD_SEARCH_NAVIGATION

    private fun signature(
        role: String,
        bounds: PhoneBounds,
        text: String = "",
        description: String = "",
        clickable: Boolean = false,
        viewId: String = "",
    ) = AndroidNodeSignature(
        windowId = 7, packageName = "com.jingdong.app.mall", uniqueId = null,
        role = role, viewId = viewId, text = text, description = description,
        left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom,
        enabled = true, clickable = clickable, longClickable = false, editable = false,
        scrollable = false, password = false, sensitive = false, truncated = false,
        inspectionIncomplete = false, requiresUserConfirmation = false,
        contentFingerprint = phoneClickProofHash("$text\n$description"),
    )

    private fun scene() = linkedMapOf(
        root to signature("android.widget.FrameLayout", PhoneBounds(0, 0, 1_000, 2_000)),
        bar to signature("android.widget.RelativeLayout", PhoneBounds(0, 100, 1_000, 200),
            description = "搜索栏", viewId = "com.jingdong.app.mall:id/b2q"),
        parent to signature("android.view.ViewGroup", PhoneBounds(20, 100, 980, 200), clickable = true),
        entry to signature("android.view.ViewGroup", PhoneBounds(140, 100, 450, 200), clickable = true),
        term to signature("android.widget.TextView", PhoneBounds(140, 125, 440, 175),
            text = "推荐词甲", description = "推荐词甲"),
        camera to signature("android.widget.Button", PhoneBounds(750, 100, 850, 200),
            description = "拍照购", clickable = true),
        search to signature("android.widget.Button", PhoneBounds(850, 100, 960, 200),
            description = "搜索", clickable = true),
        searchLabel to signature("android.widget.TextView", PhoneBounds(875, 125, 935, 175), text = "搜索"),
    )

    // Reader emits geometry/flags from the same fresh signature; keep that relationship in fixtures.
    private fun nodes(signatures: Map<List<Int>, AndroidNodeSignature>, firstId: Int = 10) =
        signatures.entries.mapIndexed { index, (path, value) ->
            path to PhoneNode(
                id = "n${firstId + index}", role = value.role, viewId = value.viewId,
                text = value.text, description = value.description,
                bounds = PhoneBounds(value.left, value.top, value.right, value.bottom),
                clickable = value.clickable, longClickable = value.longClickable,
                editable = value.editable, scrollable = value.scrollable, enabled = value.enabled,
                password = value.password, requiresUserConfirmation = value.requiresUserConfirmation,
            )
        }.toMap()

    private fun proofs(
        signatures: Map<List<Int>, AndroidNodeSignature> = scene(),
        preview: Map<List<Int>, PhoneNode> = nodes(signatures),
        sensitive: Boolean = false,
        truncated: Boolean = false,
    ) = buildPhoneClickRevalidationProofs(signatures, preview, sensitive, truncated)

    private fun match(
        scope: PhoneClickRevalidationScope,
        fresh: List<PhoneClickRevalidationProof>,
    ) = matchPhoneClickRevalidation(
        proofs().single { it.scope == scope }, fresh, sensitive = false, truncated = false,
    )

    private fun assertEntryRejected(signatures: Map<List<Int>, AndroidNodeSignature>) {
        assertNull(match(entryScope, proofs(signatures)))
    }

    @Test
    fun `entry and navigation scopes coexist and both provide eligible anchors`() {
        val built = proofs()
        assertEquals(setOf(entryScope, navigationScope), built.map { it.scope }.toSet())
        assertEquals(2, built.size)
        assertTrue(hasEligiblePhoneClickRevalidationProof(built))
        assertEquals(built.single { it.scope == entryScope }, match(entryScope, built))
        assertEquals(built.single { it.scope == navigationScope }, match(navigationScope, built))
    }

    @Test
    fun `recommendation text description hash and right edges may change with fresh preview ids`() {
        val original = scene()
        val freshTree = original + mapOf(
            entry to original.getValue(entry).copy(right = 620),
            term to original.getValue(term).copy(text = "更长的推荐词乙", description = "更长的推荐词乙",
                contentFingerprint = phoneClickProofHash("更长的推荐词乙\n更长的推荐词乙"), right = 610),
        )
        val fresh = proofs(freshTree, nodes(freshTree, firstId = 100))
        assertEquals("n103", match(entryScope, fresh)?.nodeId)
        assertEquals("n106", match(navigationScope, fresh)?.nodeId)
        assertTrue(hasEligiblePhoneClickRevalidationProof(fresh))
    }

    @Test
    fun `entry left top and bottom are not normalized along with its right edge`() {
        val tree = scene()
        for (changed in listOf(
            tree.getValue(entry).copy(left = 139),
            tree.getValue(entry).copy(top = 101),
            tree.getValue(entry).copy(bottom = 199),
        )) assertEntryRejected(tree + (entry to changed))
        val movedLeft = proofs(tree + (entry to tree.getValue(entry).copy(left = 139)))
        // Still a valid newly observed entry, but no longer the original anchored target.
        val freshEntry = movedLeft.single { it.scope == entryScope }
        assertNotNull(matchPhoneClickRevalidation(freshEntry, movedLeft, false, false))
    }

    @Test
    fun `entry path class and interaction flags cannot change during revalidation`() {
        val tree = scene()
        val original = tree.getValue(entry)
        for (changed in listOf(
            original.copy(role = "android.widget.FrameLayout"), original.copy(clickable = false),
            original.copy(longClickable = true), original.copy(editable = true),
            original.copy(scrollable = true), original.copy(enabled = false), original.copy(viewId = "other:id/entry"),
        )) assertEntryRejected(tree + (entry to changed))
        val relocated = parent + 3
        val movedTree = tree.filterKeys { it.take(entry.size) != entry } + mapOf(
            relocated to original, (relocated + 0) to tree.getValue(term),
        )
        assertEntryRejected(movedTree)
    }

    @Test
    fun `recommendation requires exactly matching nonblank text and description on the known role`() {
        val tree = scene()
        val original = tree.getValue(term)
        for (changed in listOf(
            original.copy(description = "不同推荐词"), original.copy(text = "", description = ""),
            original.copy(text = " ", description = " "), original.copy(description = ""),
            original.copy(role = "android.view.View"), original.copy(viewId = "other:id/title"),
        )) assertEntryRejected(tree + (term to changed))
    }

    @Test
    fun `recommendation must remain a noninteractive text child`() {
        val tree = scene()
        val original = tree.getValue(term)
        for (changed in listOf(
            original.copy(clickable = true), original.copy(longClickable = true),
            original.copy(editable = true), original.copy(scrollable = true),
        )) assertEntryRejected(tree + (term to changed))
    }

    @Test
    fun `unlabelled extra children deeper descendants and a different child index are rejected`() {
        val tree = scene()
        val unlabelled = signature("android.view.View", PhoneBounds(150, 130, 160, 140))
        assertEntryRejected(tree + ((entry + 1) to unlabelled))
        assertEntryRejected(tree + ((term + 0) to unlabelled))
        assertEntryRejected(tree.filterKeys { it != term } + ((entry + 1) to tree.getValue(term)))
        assertEntryRejected(tree.filterKeys { it != term })
    }

    @Test
    fun `unsafe truncated and transaction recommendation signatures are rejected before normalization`() {
        val tree = scene()
        val original = tree.getValue(term)
        for (changed in listOf(
            original.copy(truncated = true), original.copy(metadataTruncated = true),
            original.copy(inspectionIncomplete = true), original.copy(sensitive = true),
            original.copy(password = true), original.copy(enabled = false),
            original.copy(text = "立即购买", description = "立即购买", requiresUserConfirmation = true,
                contentFingerprint = phoneClickProofHash("立即购买\n立即购买")),
        )) assertEntryRejected(tree + (term to changed))
    }

    @Test
    fun `whole tree sensitivity and incomplete inspection prevent building either scope`() {
        for ((sensitive, truncated) in listOf(true to false, false to true, true to true)) {
            val fresh = proofs(sensitive = sensitive, truncated = truncated)
            assertTrue(fresh.isEmpty())
            assertFalse(hasEligiblePhoneClickRevalidationProof(fresh))
            assertNull(match(entryScope, fresh))
            assertNull(match(navigationScope, fresh))
        }
    }

    @Test
    fun `parent and search bar identities semantics and geometry remain fixed`() {
        val tree = scene()
        val originalParent = tree.getValue(parent)
        for (changed in listOf(
            originalParent.copy(viewId = "other:id/parent"), originalParent.copy(text = "商品"),
            originalParent.copy(description = "搜索"), originalParent.copy(left = 21),
            originalParent.copy(right = 979), originalParent.copy(clickable = false),
        )) assertEntryRejected(tree + (parent to changed))
        val originalBar = tree.getValue(bar)
        for (changed in listOf(
            originalBar.copy(viewId = "com.jingdong.app.mall:id/other"),
            originalBar.copy(description = "搜索区域"), originalBar.copy(text = "商品"),
            originalBar.copy(role = "android.view.ViewGroup"), originalBar.copy(clickable = true),
            originalBar.copy(left = 1), originalBar.copy(top = 99),
        )) assertEntryRejected(tree + (bar to changed))
    }

    @Test
    fun `missing foreign or unsafe ancestors reject the search entry`() {
        val tree = scene()
        for (path in listOf(root, bar, parent)) {
            assertEntryRejected(tree.filterKeys { it != path })
            val original = tree.getValue(path)
            for (changed in listOf(
                original.copy(packageName = "foreign.package"), original.copy(windowId = 8),
                original.copy(sensitive = true), original.copy(metadataTruncated = true),
                original.copy(inspectionIncomplete = true), original.copy(requiresUserConfirmation = true),
            )) assertEntryRejected(tree + (path to changed))
        }
    }

    @Test
    fun `camera identity geometry and complete fixed subtree remain part of entry evidence`() {
        val tree = scene()
        val original = tree.getValue(camera)
        for (changed in listOf(
            original.copy(description = "扫一扫"), original.copy(left = 751), original.copy(right = 849),
            original.copy(contentFingerprint = phoneClickProofHash("camera changed")),
            original.copy(metadataTruncated = true), original.copy(clickable = false),
        )) assertEntryRejected(tree + (camera to changed))
        assertEntryRejected(tree.filterKeys { it != camera })
        assertEntryRejected(tree + ((camera + 0) to signature("android.widget.TextView",
            PhoneBounds(760, 125, 840, 175), text = "拍照购")))
    }

    @Test
    fun `search button and its fixed label subtree cannot change with the recommendation`() {
        val tree = scene()
        val original = tree.getValue(search)
        for (changed in listOf(
            original.copy(left = 851), original.copy(description = "查找"),
            original.copy(contentFingerprint = phoneClickProofHash("search changed")),
        )) assertEntryRejected(tree + (search to changed))
        val label = tree.getValue(searchLabel)
        for (changed in listOf(
            label.copy(text = "查找"), label.copy(right = 934),
            label.copy(contentFingerprint = phoneClickProofHash("label changed")),
            label.copy(metadataTruncated = true),
        )) assertEntryRejected(tree + (searchLabel to changed))
        assertEntryRejected(tree + ((search + 1) to signature("android.view.View", PhoneBounds(900, 130, 910, 140))))
    }

    @Test
    fun `recommendation and entry must stay contained and clear of the right side controls`() {
        val tree = scene()
        val label = tree.getValue(term)
        for (changed in listOf(
            label.copy(left = 139), label.copy(right = 451), label.copy(top = 99),
            label.copy(bottom = 201), label.copy(right = label.left),
        )) assertEntryRejected(tree + (term to changed))
        val target = tree.getValue(entry)
        for (changed in listOf(
            target.copy(left = 19), target.copy(right = 981), target.copy(right = 751),
            target.copy(right = target.left),
        )) assertEntryRejected(tree + (entry to changed))
        assertEntryRejected(tree + (camera to tree.getValue(camera).copy(right = 851)))
    }

    @Test
    fun `duplicate entry candidates remain ambiguous even unsafe or omitted while navigation stays valid`() {
        val tree = scene()
        val second = parent + 3
        val duplicate = tree.getValue(entry).copy(left = 460, right = 700)
        for (unsafe in listOf(false, true)) {
            val duplicated = tree + mapOf(
                second to duplicate.copy(enabled = !unsafe),
                (second + 0) to tree.getValue(term).copy(left = 460, right = 690),
            )
            for (omit in listOf(false, true)) {
                val preview = nodes(duplicated).filterKeys { !omit || it.take(second.size) != second }
                val fresh = proofs(duplicated, preview)
                assertEquals(2, fresh.count { it.scope == entryScope })
                assertNull(match(entryScope, fresh))
                assertNotNull(match(navigationScope, fresh))
                assertTrue(hasEligiblePhoneClickRevalidationProof(fresh))
            }
        }
    }

    @Test
    fun `duplicate search bars or search buttons prevent entry qualification`() {
        val tree = scene()
        assertEntryRejected(tree + (listOf(1) to tree.getValue(bar)))
        val duplicateSearch = parent + 3
        val duplicated = tree + (duplicateSearch to tree.getValue(search))
        val fresh = proofs(duplicated, nodes(duplicated).filterKeys { it != duplicateSearch })
        assertNull(match(entryScope, fresh))
        assertNull(match(navigationScope, fresh))
        assertFalse(hasEligiblePhoneClickRevalidationProof(fresh))
    }

    @Test
    fun `an empty parent or arbitrary product container is never promoted to search entry`() {
        assertFalse(proofs().any { it.path == parent && it.scope == entryScope })
        val tree = scene()
        val product = tree + (bar to tree.getValue(bar).copy(viewId = "product:id/card", description = "商品推荐"))
        assertFalse(proofs(product).any { it.scope == entryScope })
        val noBar = tree.filterKeys { it != bar }
        assertFalse(proofs(noBar).any { it.scope == entryScope })
    }

    @Test
    fun `navigation retains strict target and descendant fingerprints without entry normalization`() {
        val tree = scene()
        for ((path, changed) in listOf(
            search to tree.getValue(search).copy(contentFingerprint = phoneClickProofHash("new raw target hash")),
            search to tree.getValue(search).copy(right = 961),
            searchLabel to tree.getValue(searchLabel).copy(contentFingerprint = phoneClickProofHash("new raw child hash")),
            searchLabel to tree.getValue(searchLabel).copy(right = 936),
        )) assertNull(match(navigationScope, proofs(tree + (path to changed))))
    }
}
