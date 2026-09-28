package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Runtime token, STOP, window identity and TTL checks belong to backend/controller tests. */
class PhoneClickRevalidationPolicyTest {
    private val anchor = PhoneClickRevalidationProof(
        nodeId = "n72",
        path = listOf(0, 1, 2),
        signatureFingerprint = "1".repeat(64),
        subtreeFingerprint = "2".repeat(64),
        ancestorFingerprint = "3".repeat(64),
        contextFingerprint = "4".repeat(64),
        scope = PhoneClickRevalidationScope.JD_SEARCH_NAVIGATION,
    )

    private fun match(
        old: PhoneClickRevalidationProof = anchor,
        candidates: List<PhoneClickRevalidationProof> = listOf(anchor),
        sensitive: Boolean = false,
        truncated: Boolean = false,
    ) = matchPhoneClickRevalidation(old, candidates, sensitive, truncated)

    @Test
    fun `fresh node numbering may change only when the original path and all proofs match`() {
        val fresh = anchor.copy(nodeId = "n61")
        // The original n72 now names an unrelated node. It must never win by its preview ID.
        val reusedOldId = anchor.copy(path = listOf(0, 5), scope = PhoneClickRevalidationScope.UNKNOWN)
        assertEquals(fresh, match(candidates = listOf(reusedOldId, fresh)))
    }

    @Test
    fun `unknown scope cannot gain revalidation from matching fingerprints or a fresh known scope`() {
        val unknown = anchor.copy(scope = PhoneClickRevalidationScope.UNKNOWN)
        assertNull(match(old = unknown, candidates = listOf(unknown)))
        assertNull(match(old = unknown, candidates = listOf(anchor)))
        assertNull(match(candidates = listOf(unknown)))
    }

    @Test
    fun `whole page sensitivity or incomplete inspection rejects even an unchanged search target`() {
        assertNull(match(sensitive = true))
        assertNull(match(truncated = true))
        assertNull(match(sensitive = true, truncated = true))
    }

    @Test
    fun `empty semantics truncated signatures and restricted nodes reject old and fresh proofs`() {
        val unsafe = listOf(
            anchor.copy(hasSemanticLabel = false),
            anchor.copy(truncated = true),
            anchor.copy(restricted = true),
            anchor.copy(nodeId = ""),
        )
        unsafe.forEach { proof ->
            assertNull(match(old = proof))
            assertNull(match(candidates = listOf(proof)))
            assertNull(match(old = proof, candidates = listOf(proof)))
        }
    }

    @Test
    fun `every independent identity proof must match exactly`() {
        val changed = listOf(
            anchor.copy(signatureFingerprint = "5".repeat(64)),
            anchor.copy(subtreeFingerprint = "6".repeat(64)),
            anchor.copy(ancestorFingerprint = "7".repeat(64)),
            anchor.copy(contextFingerprint = "8".repeat(64)),
            anchor.copy(path = listOf(0, 1, 3)),
        )
        changed.forEach { fresh -> assertNull(match(candidates = listOf(fresh))) }
    }

    @Test
    fun `missing fingerprints never count as equal verified evidence`() {
        val incomplete = listOf(
            anchor.copy(signatureFingerprint = ""),
            anchor.copy(subtreeFingerprint = ""),
            anchor.copy(ancestorFingerprint = ""),
            anchor.copy(contextFingerprint = ""),
            anchor.copy(nodeId = " "),
        )
        incomplete.forEach { proof ->
            assertNull(match(old = proof))
            assertNull(match(candidates = listOf(proof)))
            assertNull(match(old = proof, candidates = listOf(proof)))
        }
    }

    @Test
    fun `same path and button signature cannot hide changed product descendants`() {
        // A recycled container may have identical own properties while its title/price changed.
        val anotherProduct = anchor.copy(subtreeFingerprint = "a".repeat(64))
        assertNull(match(candidates = listOf(anotherProduct)))
    }

    @Test
    fun `unchanged leaf and ancestors cannot hide changed sibling product context`() {
        // A leaf has no changed descendants: the trusted container context is independently needed.
        val changedContext = anchor.copy(contextFingerprint = "b".repeat(64))
        assertNull(match(candidates = listOf(changedContext)))
    }

    @Test
    fun `fresh ancestor changes or newly propagated restrictions reject the target`() {
        assertNull(match(candidates = listOf(anchor.copy(ancestorFingerprint = "c".repeat(64)))))
        assertNull(match(candidates = listOf(anchor.copy(restricted = true))))
    }

    @Test
    fun `matching preview id or matching signatures at a new path never relocates a click`() {
        val moved = anchor.copy(path = listOf(0, 2, 2))
        assertNull(match(candidates = listOf(moved)))
        assertNull(match(candidates = listOf(moved.copy(nodeId = "n61"))))
        val changedAtOriginalPath = anchor.copy(signatureFingerprint = "d".repeat(64))
        assertNull(match(candidates = listOf(changedAtOriginalPath)))
    }

    @Test
    fun `missing or duplicated candidates never yield a unique target`() {
        assertNull(match(candidates = emptyList()))
        assertNull(match(candidates = listOf(anchor, anchor)))
        assertNull(match(candidates = listOf(anchor, anchor.copy(nodeId = "n61"))))
    }

    @Test
    fun `a second trusted search candidate elsewhere makes the full tree scope ambiguous`() {
        val second = anchor.copy(nodeId = "n61", path = listOf(0, 4))
        assertNull(match(candidates = listOf(anchor, second)))
        // Do not hide a duplicate by filtering out its unsafe properties before counting scope.
        assertNull(match(candidates = listOf(anchor, second.copy(restricted = true))))
        assertNull(match(candidates = listOf(anchor, second.copy(truncated = true))))
        // Empty IDs represent inspected candidates outside the 100-node action preview.
        assertNull(match(candidates = listOf(anchor, second.copy(nodeId = ""))))
    }

    @Test
    fun `unrelated unknown scopes do not invent or modify the one trusted search candidate`() {
        val fresh = anchor.copy(nodeId = "n61")
        val unrelated = anchor.copy(nodeId = "n90", path = listOf(4), scope = PhoneClickRevalidationScope.UNKNOWN)
        val candidates = listOf(unrelated, fresh)
        assertEquals(fresh, match(candidates = candidates))
        assertEquals(listOf(unrelated, fresh), candidates)
        assertEquals("n72", anchor.nodeId)
    }

    private val searchPath = listOf(0, 1)
    private val photoPath = listOf(0, 0)
    private val recommendationPath = listOf(0, 2, 0)

    private fun signature(
        role: String = "android.view.ViewGroup",
        text: String = "",
        description: String = "",
        clickable: Boolean = false,
    ) = AndroidNodeSignature(
        windowId = 7, packageName = "com.jingdong.app.mall", uniqueId = null,
        role = role, viewId = "", text = text, description = description,
        left = 0, top = 0, right = 100, bottom = 100, enabled = true,
        clickable = clickable, longClickable = false, editable = false, scrollable = false,
        password = false, sensitive = false, truncated = false, inspectionIncomplete = false,
        requiresUserConfirmation = false, contentFingerprint = "0".repeat(64),
    )

    private fun searchTree() = linkedMapOf(
        emptyList<Int>() to signature("android.widget.FrameLayout"),
        listOf(0) to signature(),
        photoPath to signature("android.widget.Button", description = "拍照购", clickable = true),
        (photoPath + 0) to signature("android.widget.TextView", text = "拍照购"),
        searchPath to signature("android.widget.Button", description = "搜索", clickable = true),
        (searchPath + 0) to signature("android.widget.TextView", text = "搜索"),
        listOf(0, 2) to signature(clickable = true),
        recommendationPath to signature("android.widget.TextView", text = "轮换推荐词A"),
    )

    @Test
    fun `verified native JD search bar resolves while empty recommendation container does not`() {
        val tree = searchTree()
        assertEquals(PhoneClickRevalidationScope.JD_SEARCH_NAVIGATION,
            phoneClickRevalidationContext(searchPath, tree)?.scope)
        assertNull(phoneClickRevalidationContext(listOf(0, 2), tree))
        assertNull(phoneClickRevalidationContext(photoPath, tree))
    }

    @Test
    fun `scope classifier refuses other packages roles labels and disabled search controls`() {
        val tree = searchTree()
        val original = tree.getValue(searchPath)
        val unsupported = listOf(
            original.copy(packageName = "com.example.other"),
            original.copy(role = "android.view.ViewGroup"),
            original.copy(description = "查看"),
            original.copy(description = "选择"),
            original.copy(description = "评价"),
            original.copy(description = "搜索商品"),
            original.copy(description = " 搜索 "),
            original.copy(text = "商品信息"),
            original.copy(clickable = false),
            original.copy(enabled = false),
            original.copy(truncated = true),
            original.copy(metadataTruncated = true),
            original.copy(inspectionIncomplete = true),
            original.copy(sensitive = true),
            original.copy(password = true),
            original.copy(requiresUserConfirmation = true),
        )
        unsupported.forEach { changed ->
            assertNull(phoneClickRevalidationContext(searchPath, tree + (searchPath to changed)))
        }
    }

    @Test
    fun `search subtree must have exactly the known text semantic and no substitute product meaning`() {
        val tree = searchTree()
        val childPath = searchPath + 0
        assertNull(phoneClickRevalidationContext(searchPath, tree.filterKeys { it != childPath }))
        for (body in listOf("查看商品", "领取优惠券", "选择规格", "")) {
            assertNull(phoneClickRevalidationContext(searchPath,
                tree + (childPath to tree.getValue(childPath).copy(text = body))))
        }
        assertNull(phoneClickRevalidationContext(searchPath,
            tree + ((searchPath + 1) to signature("android.widget.TextView", text = "搜索"))))
        assertNull(phoneClickRevalidationContext(searchPath,
            tree + ((searchPath + 1) to signature("android.widget.TextView", text = "商品标题"))))
    }

    @Test
    fun `missing duplicate misplaced or unsafe photo shopping sibling cannot anchor search`() {
        val tree = searchTree()
        assertNull(phoneClickRevalidationContext(searchPath, tree.filterKeys { it.take(photoPath.size) != photoPath }))
        assertNull(phoneClickRevalidationContext(searchPath,
            tree + (listOf(0, 3) to tree.getValue(photoPath))))
        assertNull(phoneClickRevalidationContext(searchPath,
            tree.filterKeys { it != photoPath } + (listOf(9, 0) to tree.getValue(photoPath))))
        val photo = tree.getValue(photoPath)
        for (changed in listOf(photo.copy(enabled = false), photo.copy(clickable = false),
            photo.copy(truncated = true), photo.copy(metadataTruncated = true), photo.copy(sensitive = true),
            photo.copy(requiresUserConfirmation = true))) {
            assertNull(phoneClickRevalidationContext(searchPath, tree + (photoPath to changed)))
        }
    }

    @Test
    fun `missing unsafe or foreign ancestor rejects even the exact search button`() {
        val tree = searchTree()
        for (path in listOf(emptyList(), listOf(0))) {
            assertNull(phoneClickRevalidationContext(searchPath, tree.filterKeys { it != path }))
            val parent = tree.getValue(path)
            for (changed in listOf(parent.copy(enabled = false), parent.copy(truncated = true), parent.copy(metadataTruncated = true),
                parent.copy(inspectionIncomplete = true), parent.copy(sensitive = true), parent.copy(password = true),
                parent.copy(requiresUserConfirmation = true), parent.copy(packageName = "foreign.package"),
                parent.copy(windowId = 8))) {
                assertNull(phoneClickRevalidationContext(searchPath, tree + (path to changed)))
            }
        }
    }

    @Test
    fun `truncated metadata anywhere in search or fixed sibling subtree rejects the trusted context`() {
        val tree = searchTree()
        for (path in listOf(searchPath + 0, photoPath + 0)) {
            assertNull(phoneClickRevalidationContext(searchPath,
                tree + (path to tree.getValue(path).copy(metadataTruncated = true))))
        }
    }

    @Test
    fun `external recommendation text does not change verified search context`() {
        val tree = searchTree()
        val original = checkNotNull(phoneClickRevalidationContext(searchPath, tree))
        val changed = tree + (recommendationPath to tree.getValue(recommendationPath)
            .copy(text = "轮换推荐词B", contentFingerprint = "1".repeat(64)))
        assertEquals(original, phoneClickRevalidationContext(searchPath, changed))
    }

    @Test
    fun `fixed sibling subtree and parent structure remain part of the search context`() {
        val tree = searchTree()
        val original = checkNotNull(phoneClickRevalidationContext(searchPath, tree))
        val photoChild = photoPath + 0
        val changedSibling = tree + (photoChild to tree.getValue(photoChild)
            .copy(text = "固定锚点子树变化", contentFingerprint = "2".repeat(64)))
        val siblingContext = phoneClickRevalidationContext(searchPath, changedSibling)
        // A changed trusted context may be rejected immediately or produce a different hash.
        org.junit.Assert.assertTrue(siblingContext == null || siblingContext.fingerprint != original.fingerprint)
        val changedParent = tree + (listOf(0) to tree.getValue(listOf(0)).copy(right = 101))
        val parentContext = phoneClickRevalidationContext(searchPath, changedParent)
        org.junit.Assert.assertTrue(parentContext == null || parentContext.fingerprint != original.fingerprint)
    }
}
