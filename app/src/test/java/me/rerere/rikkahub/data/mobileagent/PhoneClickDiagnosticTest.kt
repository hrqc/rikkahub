package me.rerere.rikkahub.data.mobileagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diagnostics use actual full-tree proofs; no synthetic comparison hashes or Android dispatch. */
class PhoneClickDiagnosticTest {
    private val root = emptyList<Int>()
    private val wrapper = listOf(2)
    private val bar = wrapper + 0
    private val parent = bar + 0
    private val entry = parent + 0
    private val term = entry + 0
    private val camera = parent + 1
    private val search = parent + 2
    private val searchLabel = search + 0
    private val entryScope = PhoneClickRevalidationScope.JD_SEARCH_ENTRY
    private val navigationScope = PhoneClickRevalidationScope.JD_SEARCH_NAVIGATION
    private val token = PhoneSessionToken("private-conversation", "private-assistant", "private-session", 1)
    private val observationId = "private-observation"

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
        wrapper to signature("android.widget.FrameLayout", PhoneBounds(0, 0, 1_000, 2_000)),
        bar to signature("android.widget.RelativeLayout", PhoneBounds(0, 100, 1_000, 200),
            description = "搜索栏", viewId = "com.jingdong.app.mall:id/b2q"),
        parent to signature("android.view.ViewGroup", PhoneBounds(20, 100, 980, 200), clickable = true),
        entry to signature("android.view.ViewGroup", PhoneBounds(140, 100, 450, 200), clickable = true),
        term to signature("android.widget.TextView", PhoneBounds(140, 125, 440, 175),
            text = "private-recommendation", description = "private-recommendation"),
        camera to signature("android.widget.Button", PhoneBounds(750, 100, 850, 200),
            description = "拍照购", clickable = true),
        search to signature("android.widget.Button", PhoneBounds(850, 100, 960, 200),
            description = "搜索", clickable = true),
        searchLabel to signature("android.widget.TextView", PhoneBounds(875, 125, 935, 175), text = "搜索"),
    )

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
    ) = buildPhoneClickRevalidationProofs(signatures, preview, sensitive = false, truncated = false)

    private fun summary(proofs: List<PhoneClickRevalidationProof>, nodeId: String = "unused-node") =
        PhoneClickDiagnosticHistory().apply { built(token, observationId, proofs) }
            .describe(token, observationId, nodeId)!!

    private fun assertRejected(
        reason: PhoneClickProofRejection,
        proof: PhoneClickRevalidationProof,
        all: List<PhoneClickRevalidationProof>,
    ) {
        assertEquals(reason, proof.failure)
        assertNull(matchPhoneClickRevalidation(proof, all, sensitive = false, truncated = false))
        assertEquals(reason, summary(all, proof.nodeId).targetProofReason)
    }

    @Test
    fun `healthy proof summary distinguishes candidates from surviving anchors`() {
        val built = proofs()
        val diagnostic = summary(built, built.single { it.scope == entryScope }.nodeId)
        assertEquals(2, diagnostic.candidateCount)
        assertEquals(2, diagnostic.eligibleCount)
        assertTrue(diagnostic.proofReasons.isEmpty())
        assertNull(diagnostic.targetProofReason)
        // This history reports build metadata only, never grants current backend eligibility.
        assertFalse(diagnostic.eligible)
        assertFalse(diagnostic.anchorPresent)
        assertEquals(PhoneClickRevalidationStage.NO_ANCHOR, diagnostic.stage)
    }

    @Test
    fun `hidden native ancestor reports its safety failure for both scopes`() {
        val tree = scene()
        val original = tree.getValue(wrapper)
        val cases = listOf(
            original.copy(packageName = "foreign.private.package") to PhoneClickProofRejection.FOREIGN_CONTEXT,
            original.copy(windowId = 8) to PhoneClickProofRejection.FOREIGN_CONTEXT,
            original.copy(enabled = false) to PhoneClickProofRejection.DISABLED_CONTEXT,
            original.copy(password = true) to PhoneClickProofRejection.SENSITIVE_CONTEXT,
            original.copy(sensitive = true) to PhoneClickProofRejection.SENSITIVE_CONTEXT,
            original.copy(truncated = true) to PhoneClickProofRejection.TRUNCATED_CONTEXT,
            original.copy(metadataTruncated = true) to PhoneClickProofRejection.TRUNCATED_CONTEXT,
            original.copy(inspectionIncomplete = true) to PhoneClickProofRejection.TRUNCATED_CONTEXT,
            original.copy(requiresUserConfirmation = true) to PhoneClickProofRejection.RESTRICTED_CONTEXT,
        )
        for ((changed, reason) in cases) {
            val completeTree = tree + (wrapper to changed)
            val built = proofs(completeTree, nodes(completeTree).filterKeys { it != wrapper })
            assertEquals(2, built.size)
            built.forEach { assertRejected(reason, it, built) }
            assertEquals(0, summary(built).eligibleCount)
            assertEquals(listOf(reason), summary(built).proofReasons)
        }
    }

    @Test
    fun `missing native ancestor is not repaired by a complete preview`() {
        val tree = scene()
        val built = proofs(tree.filterKeys { it != wrapper }, nodes(tree))
        assertEquals(2, built.size)
        built.forEach { assertRejected(PhoneClickProofRejection.MISSING_ANCESTOR, it, built) }
    }

    @Test
    fun `extra unlabelled descendant outside preview reports entry structure failure`() {
        val hidden = entry + 1
        val tree = scene() + (hidden to signature("android.view.View", PhoneBounds(150, 120, 160, 130)))
        val built = proofs(tree, nodes(tree).filterKeys { it != hidden })
        assertRejected(PhoneClickProofRejection.DESCENDANT_STRUCTURE,
            built.single { it.scope == entryScope }, built)
        assertNull(built.single { it.scope == navigationScope }.failure)
        assertEquals(1, summary(built).eligibleCount)
    }

    @Test
    fun `extra labelled search descendant reports failure in both dependent scopes`() {
        val hidden = search + 1
        val tree = scene() + (hidden to signature("android.widget.TextView",
            PhoneBounds(880, 125, 900, 150), text = "private-extra-label"))
        val built = proofs(tree, nodes(tree).filterKeys { it != hidden })
        built.forEach { assertRejected(PhoneClickProofRejection.DESCENDANT_STRUCTURE, it, built) }
    }

    @Test
    fun `recommendation metadata truncation is diagnosed before dynamic text normalization`() {
        val tree = scene()
        val changed = tree + (term to tree.getValue(term).copy(metadataTruncated = true,
            text = "another-private-term", description = "another-private-term"))
        val built = proofs(changed)
        assertRejected(PhoneClickProofRejection.TRUNCATED_CONTEXT,
            built.single { it.scope == entryScope }, built)
        assertEquals(1, summary(built).eligibleCount)
    }

    @Test
    fun `unsafe omitted duplicate remains an ambiguous candidate`() {
        val tree = scene()
        val duplicate = parent + 3
        val changed = tree + mapOf(
            duplicate to tree.getValue(entry).copy(requiresUserConfirmation = true),
            duplicate + 0 to tree.getValue(term),
        )
        val built = proofs(changed, nodes(changed).filterKeys { it.take(duplicate.size) != duplicate })
        val entries = built.filter { it.scope == entryScope }
        assertEquals(2, entries.size)
        entries.forEach {
            assertEquals(PhoneClickProofRejection.SCOPE_AMBIGUOUS, it.failure)
            assertNull(matchPhoneClickRevalidation(it, built, false, false))
        }
        val diagnostic = summary(built)
        assertEquals(3, diagnostic.candidateCount)
        assertEquals(1, diagnostic.eligibleCount)
        assertEquals(listOf(PhoneClickProofRejection.SCOPE_AMBIGUOUS), diagnostic.proofReasons)
    }

    @Test
    fun `qualified native control omitted from preview reports preview failure`() {
        val tree = scene()
        val built = proofs(tree, nodes(tree).filterKeys { it != entry })
        assertRejected(PhoneClickProofRejection.PREVIEW_UNAVAILABLE,
            built.single { it.scope == entryScope }, built)
        assertEquals(1, summary(built).eligibleCount)
    }

    @Test
    fun `preview confirmation restriction remains visible after native qualification`() {
        val tree = scene()
        val preview = nodes(tree)
        val built = proofs(tree, preview + (entry to preview.getValue(entry).copy(requiresUserConfirmation = true)))
        assertRejected(PhoneClickProofRejection.RESTRICTED_CONTEXT,
            built.single { it.scope == entryScope }, built)
    }

    @Test
    fun `overlapping fixed control has a distinct geometry failure`() {
        val tree = scene()
        val built = proofs(tree + (entry to tree.getValue(entry).copy(right = 800)))
        assertRejected(PhoneClickProofRejection.GEOMETRY_MISMATCH,
            built.single { it.scope == entryScope }, built)
    }

    @Test
    fun `no candidate has a fixed reason rather than page content`() {
        val tree = scene().filterKeys { it != search && it != entry }
        val diagnostic = summary(proofs(tree))
        assertEquals(0, diagnostic.candidateCount)
        assertEquals(0, diagnostic.eligibleCount)
        assertEquals(listOf(PhoneClickProofRejection.NO_CANDIDATE), diagnostic.proofReasons)
        assertNull(diagnostic.targetProofReason)
    }

    @Test
    fun `diagnostic summary exports no text identifiers paths or hashes from actual proofs`() {
        val original = scene()
        val privateText = "private-page-body-needle"
        val privateDescription = "private-page-description-needle"
        val privateUniqueId = "private-native-unique-id"
        val tree = original + (wrapper to original.getValue(wrapper).copy(text = privateText,
            description = privateDescription, uniqueId = privateUniqueId, metadataTruncated = true))
        val preview = nodes(tree)
        val privateNodeId = "private-node-identifier"
        val built = proofs(tree, preview + (entry to preview.getValue(entry).copy(id = privateNodeId)))
        val exported = summary(built, privateNodeId).toString()
        val forbidden = listOf(privateText, privateDescription, privateUniqueId, privateNodeId,
            "private-recommendation", token.conversationId, token.assistantId, token.sessionId,
            observationId, "com.jingdong.app.mall", "android.widget", entry.toString()) +
            built.flatMap { listOf(it.signatureFingerprint, it.subtreeFingerprint, it.ancestorFingerprint,
                it.contextFingerprint) }.filter { it.isNotBlank() }
        forbidden.forEach { assertFalse("Diagnostic leaked private comparison data", exported.contains(it)) }
        assertTrue(exported.contains(PhoneClickProofRejection.TRUNCATED_CONTEXT.name))
    }

    @Test
    fun `old token or observation cannot read or clear a newer build record`() {
        val history = PhoneClickDiagnosticHistory()
        val built = proofs()
        val nodeId = built.single { it.scope == entryScope }.nodeId
        val newToken = token.copy(sessionId = "new-session", epoch = 2)
        val newObservation = "new-observation"
        history.built(token, observationId, built)
        history.built(newToken, newObservation, built)
        history.cleared(token, observationId, PhoneClickAnchorClearReason.INVALIDATED, null)
        history.cleared(newToken, observationId, PhoneClickAnchorClearReason.OPEN_APP, null)
        assertNull(history.describe(token, observationId, nodeId))
        assertNull(history.describe(token, newObservation, nodeId))
        assertNull(history.describe(newToken, observationId, nodeId))
        val current = history.describe(newToken, newObservation, nodeId)!!
        assertNull(current.lastClearReason)
        assertNull(current.lastClearEvent)
        assertEquals(2, current.eligibleCount)
        history.cleared(newToken, newObservation, PhoneClickAnchorClearReason.ACCESSIBILITY_EVENT,
            PhoneClickAnchorEvent.FOCUSED)
        history.cleared(token, observationId, PhoneClickAnchorClearReason.INVALIDATED, null)
        val cleared = history.describe(newToken, newObservation, nodeId)!!
        assertEquals(PhoneClickAnchorClearReason.ACCESSIBILITY_EVENT, cleared.lastClearReason)
        assertEquals(PhoneClickAnchorEvent.FOCUSED, cleared.lastClearEvent)
    }

    @Test
    fun `new build clears earlier reason and reset forgets the current record`() {
        val history = PhoneClickDiagnosticHistory()
        val tree = scene()
        val rejected = proofs(tree + (wrapper to tree.getValue(wrapper).copy(enabled = false)))
        history.built(token, observationId, rejected)
        history.cleared(token, observationId, PhoneClickAnchorClearReason.ACCESSIBILITY_EVENT,
            PhoneClickAnchorEvent.WINDOWS_CHANGED)
        history.built(token, observationId, proofs())
        val diagnostic = history.describe(token, observationId, "unused-node")!!
        assertNull(diagnostic.lastClearReason)
        assertNull(diagnostic.lastClearEvent)
        assertTrue(diagnostic.proofReasons.isEmpty())
        assertEquals(2, diagnostic.eligibleCount)
        history.reset()
        assertNull(history.describe(token, observationId, "unused-node"))
    }

    @Test
    fun `history defensively caps retained targets from multiple real builder results`() {
        val tree = scene()
        val built = (0 until 3).flatMap { proofs(tree, nodes(tree, firstId = 100 * it)) }
        assertEquals(6, built.size)
        val diagnostic = summary(built)
        assertEquals(4, diagnostic.candidateCount)
        assertEquals(0, diagnostic.eligibleCount)
        assertTrue(diagnostic.proofReasons.size <= 4)
    }

    @Test
    fun `clearing an absent record cannot manufacture diagnostic evidence`() {
        val history = PhoneClickDiagnosticHistory()
        history.cleared(token, observationId, PhoneClickAnchorClearReason.ACCESSIBILITY_EVENT,
            PhoneClickAnchorEvent.OTHER)
        assertNull(history.describe(token, observationId, "unused-node"))
        history.built(token, observationId, proofs())
        assertNotNull(history.describe(token, observationId, "unused-node"))
    }
}
