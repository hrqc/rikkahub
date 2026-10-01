package me.rerere.rikkahub.data.mobileagent

import java.security.MessageDigest
import android.view.accessibility.AccessibilityEvent

/** Only a target content/text event preserves a dirty reference; it never preserves execution. */
internal fun shouldClearPhoneClickAnchor(
    eventType: Int,
    eventWindowId: Int,
    targetWindowId: Int?,
    verifiedControlOverlay: Boolean,
): Boolean {
    if (verifiedControlOverlay) return false
    if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return true
    return eventWindowId == targetWindowId && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
        eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
}

/** A scope is assigned by host-owned platform rules, never by a model or arbitrary nonempty label. */
internal enum class PhoneClickRevalidationScope { UNKNOWN, JD_SEARCH_NAVIGATION, JD_SEARCH_ENTRY }

/** Hashes are private comparison data. They carry no permission and are never exported to the model. */
internal data class PhoneClickRevalidationProof(
    val nodeId: String,
    val path: List<Int>,
    val signatureFingerprint: String,
    val subtreeFingerprint: String,
    val ancestorFingerprint: String,
    val contextFingerprint: String,
    val scope: PhoneClickRevalidationScope = PhoneClickRevalidationScope.UNKNOWN,
    val hasSemanticLabel: Boolean = true,
    val truncated: Boolean = false,
    val restricted: Boolean = false,
    val failure: PhoneClickProofRejection? = null,
)

/** Candidates must come from the complete tree, including otherwise omitted preview nodes. */
internal fun matchPhoneClickRevalidation(
    anchor: PhoneClickRevalidationProof,
    candidates: List<PhoneClickRevalidationProof>,
    sensitive: Boolean,
    truncated: Boolean,
): PhoneClickRevalidationProof? {
    if (sensitive || truncated || anchor.scope == PhoneClickRevalidationScope.UNKNOWN) return null
    fun eligible(proof: PhoneClickRevalidationProof) = proof.nodeId.isNotBlank() && proof.hasSemanticLabel &&
        !proof.truncated && !proof.restricted && proof.signatureFingerprint.isNotBlank() &&
        proof.subtreeFingerprint.isNotBlank() && proof.ancestorFingerprint.isNotBlank() && proof.contextFingerprint.isNotBlank()
    if (!eligible(anchor)) return null
    // Do not hide a second matching platform control merely because it is restricted or omitted.
    val fresh = candidates.filter { it.scope == anchor.scope }.singleOrNull() ?: return null
    return fresh.takeIf {
        eligible(it) && it.path == anchor.path && it.signatureFingerprint == anchor.signatureFingerprint &&
            it.subtreeFingerprint == anchor.subtreeFingerprint && it.ancestorFingerprint == anchor.ancestorFingerprint &&
            it.contextFingerprint == anchor.contextFingerprint
    }
}

/** Unknown controls remain on the existing strict snapshot path. No semantic guessing. */
internal data class PhoneClickRevalidationContext(val scope: PhoneClickRevalidationScope, val fingerprint: String)

internal fun phoneClickRevalidationContext(
    targetPath: List<Int>,
    signatures: Map<List<Int>, AndroidNodeSignature>,
    onFailure: (PhoneClickProofRejection) -> Unit = {},
): PhoneClickRevalidationContext? {
    fun reject(reason: PhoneClickProofRejection): PhoneClickRevalidationContext? { onFailure(reason); return null }
    val target = signatures[targetPath] ?: return reject(PhoneClickProofRejection.TARGET_STRUCTURE)
    if (targetPath.isEmpty() || !isJdSearchNavigationSignature(target)) return reject(PhoneClickProofRejection.TARGET_STRUCTURE)
    fun safe(node: AndroidNodeSignature): Boolean = phoneClickUnsafeReason(node, target)?.let { onFailure(it); false } ?: true
    val ancestors = (0 until targetPath.size).map { signatures[targetPath.take(it)] ?: return reject(PhoneClickProofRejection.MISSING_ANCESTOR) }
    if (ancestors.any { !safe(it) } || !safe(target)) return null
    val descendants = signatures.filterKeys { it.size > targetPath.size && it.take(targetPath.size) == targetPath }.values
    if (descendants.any { !safe(it) }) return null
    val labelled = descendants.filter { it.text.isNotBlank() || it.description.isNotBlank() }
    val label = labelled.singleOrNull() ?: return reject(PhoneClickProofRejection.DESCENDANT_STRUCTURE)
    if (label.role != "android.widget.TextView" || label.text != "搜索" || label.description.isNotBlank() ||
        label.clickable || label.editable) return reject(PhoneClickProofRejection.DESCENDANT_STRUCTURE)
    val parentPath = targetPath.dropLast(1)
    // This verified sibling identifies the homepage search bar; arbitrary product buttons are unknown.
    val camera = signatures.entries.filter { (path, signature) ->
        path != targetPath && path.size == targetPath.size && path.dropLast(1) == parentPath &&
            signature.packageName == "com.jingdong.app.mall" && signature.role == "android.widget.Button" &&
            signature.description == "拍照购" && signature.text.isBlank()
    }.singleOrNull() ?: return reject(PhoneClickProofRejection.CAMERA_MISSING_OR_AMBIGUOUS)
    if (!safe(camera.value)) return null
    if (!camera.value.clickable) return reject(PhoneClickProofRejection.TARGET_UNAVAILABLE)
    val cameraTree = signatures.entries.filter { (path, _) -> path.take(camera.key.size) == camera.key }
    if (cameraTree.any { !safe(it.value) }) return null
    val parent = ancestors.last().copy(text = "", description = "", contentFingerprint = "")
    val fingerprint = phoneClickProofHash("JD_SEARCH_NAVIGATION:v1\n$parent\n" + cameraTree.joinToString("\n") {
        "${it.key.drop(parentPath.size)}:${it.value}"
    })
    return PhoneClickRevalidationContext(PhoneClickRevalidationScope.JD_SEARCH_NAVIGATION, fingerprint)
}

/** Count even unsafe/omitted duplicates before deciding that the semantic search target is unique. */
internal fun isJdSearchNavigationSignature(signature: AndroidNodeSignature): Boolean =
    signature.packageName == "com.jingdong.app.mall" && signature.role == "android.widget.Button" &&
        signature.description == "搜索" && signature.text.isBlank() && signature.clickable

private fun isJdSearchBar(signature: AndroidNodeSignature): Boolean =
    signature.packageName == "com.jingdong.app.mall" && signature.role == "android.widget.RelativeLayout" &&
        signature.viewId == "com.jingdong.app.mall:id/b2q" && signature.description == "搜索栏" && signature.text.isBlank()

/** Count structural candidates before safety/preview checks so an unsafe duplicate stays ambiguous. */
private fun isJdSearchEntryCandidate(path: List<Int>, signatures: Map<List<Int>, AndroidNodeSignature>): Boolean {
    if (path.size < 2) return false
    val target = signatures[path] ?: return false
    val parent = signatures[path.dropLast(1)] ?: return false
    val bar = signatures[path.dropLast(2)] ?: return false
    return target.packageName == "com.jingdong.app.mall" && target.role == "android.view.ViewGroup" &&
        target.text.isBlank() && target.description.isBlank() && target.clickable &&
        parent.packageName == target.packageName && parent.role == "android.view.ViewGroup" &&
        parent.text.isBlank() && parent.description.isBlank() && parent.clickable && isJdSearchBar(bar)
}

private data class SearchEntryContext(val fingerprint: String, val recommendationPath: List<Int>)

private fun phoneClickUnsafeReason(node: AndroidNodeSignature, target: AndroidNodeSignature): PhoneClickProofRejection? = when {
    node.packageName != target.packageName || node.windowId != target.windowId -> PhoneClickProofRejection.FOREIGN_CONTEXT
    !node.enabled -> PhoneClickProofRejection.DISABLED_CONTEXT
    node.password || node.sensitive -> PhoneClickProofRejection.SENSITIVE_CONTEXT
    node.truncated || node.metadataTruncated || node.inspectionIncomplete -> PhoneClickProofRejection.TRUNCATED_CONTEXT
    node.requiresUserConfirmation -> PhoneClickProofRejection.RESTRICTED_CONTEXT
    else -> null
}

/** The recommendation is content inside this verified search entry, never a generic product card. */
private fun searchEntryContext(
    path: List<Int>,
    signatures: Map<List<Int>, AndroidNodeSignature>,
    onFailure: (PhoneClickProofRejection) -> Unit,
): SearchEntryContext? {
    fun reject(reason: PhoneClickProofRejection): SearchEntryContext? { onFailure(reason); return null }
    if (!isJdSearchEntryCandidate(path, signatures)) return reject(PhoneClickProofRejection.TARGET_STRUCTURE)
    if (signatures.values.count(::isJdSearchBar) != 1) return reject(PhoneClickProofRejection.SEARCH_BAR_AMBIGUOUS)
    val target = signatures.getValue(path)
    val parentPath = path.dropLast(1)
    val parent = signatures.getValue(parentPath)
    val bar = signatures.getValue(path.dropLast(2))
    fun safe(node: AndroidNodeSignature): Boolean = phoneClickUnsafeReason(node, target)?.let { onFailure(it); false } ?: true
    val ancestors = (0 until path.size).map { signatures[path.take(it)] ?: return reject(PhoneClickProofRejection.MISSING_ANCESTOR) }
    if (ancestors.any { !safe(it) } || !safe(target)) return null
    if (target.viewId.isNotEmpty() ||
        target.longClickable || target.editable || target.scrollable || parent.viewId.isNotEmpty() ||
        parent.longClickable || parent.editable || parent.scrollable || bar.clickable || bar.longClickable ||
        bar.editable || bar.scrollable) return reject(PhoneClickProofRejection.TARGET_STRUCTURE)
    val descendants = signatures.entries.filter { (childPath, _) ->
        childPath.size > path.size && childPath.take(path.size) == path
    }
    // Preserve the complete subtree topology, including unlabelled and invisible descendants.
    val recommendation = descendants.singleOrNull() ?: return reject(PhoneClickProofRejection.DESCENDANT_STRUCTURE)
    val label = recommendation.value
    if (!safe(label)) return null
    if (recommendation.key != path + 0 || label.role != "android.widget.TextView" ||
        label.viewId.isNotEmpty() || label.text.isBlank() || label.description != label.text ||
        label.clickable || label.longClickable || label.editable || label.scrollable) return reject(PhoneClickProofRejection.DESCENDANT_STRUCTURE)
    val search = signatures.entries.filter { (_, value) -> isJdSearchNavigationSignature(value) }.singleOrNull()
        ?: return reject(PhoneClickProofRejection.SEARCH_MISSING_OR_AMBIGUOUS)
    if (search.key.size != path.size || search.key.dropLast(1) != parentPath) return reject(PhoneClickProofRejection.TARGET_STRUCTURE)
    if (phoneClickRevalidationContext(search.key, signatures, onFailure) == null) return null
    val camera = signatures.entries.filter { (childPath, value) ->
        childPath.size == path.size && childPath.dropLast(1) == parentPath &&
            value.packageName == target.packageName && value.role == "android.widget.Button" &&
            value.description == "拍照购" && value.text.isBlank()
    }.singleOrNull() ?: return reject(PhoneClickProofRejection.CAMERA_MISSING_OR_AMBIGUOUS)
    val fixedTrees = signatures.entries.filter { (childPath, _) ->
        childPath.take(search.key.size) == search.key || childPath.take(camera.key.size) == camera.key
    }
    if (fixedTrees.any { !safe(it.value) }) return null
    fun contains(outer: AndroidNodeSignature, inner: AndroidNodeSignature) =
        outer.left < outer.right && outer.top < outer.bottom && inner.left < inner.right && inner.top < inner.bottom &&
            inner.left >= outer.left && inner.right <= outer.right && inner.top >= outer.top && inner.bottom <= outer.bottom
    if (!contains(bar, parent) || !contains(parent, target) || !contains(target, label) ||
        !contains(parent, search.value) || !contains(parent, camera.value) ||
        target.top != parent.top || target.bottom != parent.bottom ||
        target.right > camera.value.left || target.right > search.value.left || camera.value.right > search.value.left) return reject(PhoneClickProofRejection.GEOMETRY_MISMATCH)
    val fingerprint = phoneClickProofHash("JD_SEARCH_ENTRY:v1\n$bar\n$parent\n" + fixedTrees.joinToString("\n") {
        "${it.key.drop(parentPath.size)}:${it.value}"
    })
    return SearchEntryContext(fingerprint, recommendation.key)
}

/** Builds comparisons from the inspected full tree. Safety is checked before any normalization. */
internal fun buildPhoneClickRevalidationProofs(
    signatures: Map<List<Int>, AndroidNodeSignature>,
    nodesByPath: Map<List<Int>, PhoneNode>,
    sensitive: Boolean,
    truncated: Boolean,
): List<PhoneClickRevalidationProof> {
    if (sensitive || truncated) return emptyList()
    val candidates = listOf(
        PhoneClickRevalidationScope.JD_SEARCH_NAVIGATION to signatures.filterValues(::isJdSearchNavigationSignature),
        PhoneClickRevalidationScope.JD_SEARCH_ENTRY to signatures.filterKeys { isJdSearchEntryCandidate(it, signatures) },
    )
    return candidates.flatMap { (scope, scopedCandidates) ->
        if (scopedCandidates.size > 1) {
            // Two candidates prove ambiguity even when one is unsafe or omitted from the preview.
            scopedCandidates.keys.take(2).map { path ->
                PhoneClickRevalidationProof("", path, "", "", "", "", scope, restricted = true,
                    failure = PhoneClickProofRejection.SCOPE_AMBIGUOUS)
            }
        } else scopedCandidates.map { (path, signature) ->
            val ancestors = (0 until path.size).mapNotNull { signatures[path.take(it)] }
            var failure: PhoneClickProofRejection? = null
            val rejected: (PhoneClickProofRejection) -> Unit = { if (failure == null) failure = it }
            val navigation = if (scope == PhoneClickRevalidationScope.JD_SEARCH_NAVIGATION) {
                phoneClickRevalidationContext(path, signatures, rejected)
            } else null
            val entry = if (scope == PhoneClickRevalidationScope.JD_SEARCH_ENTRY) searchEntryContext(path, signatures, rejected) else null
            fun normalized(nodePath: List<Int>, value: AndroidNodeSignature): AndroidNodeSignature = when {
                entry == null -> value
                nodePath == path -> value.copy(right = 0)
                nodePath == entry.recommendationPath -> value.copy(text = "", description = "", contentFingerprint = "", right = 0)
                else -> value
            }
            val contextFingerprint = navigation?.fingerprint ?: entry?.fingerprint.orEmpty()
            val node = nodesByPath[path]
            PhoneClickRevalidationProof(
                nodeId = node?.id.orEmpty(), path = path,
                signatureFingerprint = phoneClickProofHash(normalized(path, signature).toString()),
                subtreeFingerprint = phoneClickProofHash(signatures.entries.filter { (childPath, _) -> childPath.take(path.size) == path }
                    .joinToString("\n") { (childPath, value) -> "${childPath.drop(path.size)}:${normalized(childPath, value)}" }),
                ancestorFingerprint = phoneClickProofHash(ancestors.joinToString("\n") {
                    it.copy(text = "", description = "", contentFingerprint = "").toString()
                }),
                contextFingerprint = contextFingerprint, scope = scope,
                hasSemanticLabel = signature.text.isNotBlank() || signature.description.isNotBlank() || entry != null,
                truncated = signature.truncated || signature.inspectionIncomplete || signature.metadataTruncated,
                restricted = contextFingerprint.isBlank() || ancestors.size != path.size || node == null ||
                    node.requiresUserConfirmation || !node.enabled || !node.clickable || node.password ||
                    ancestors.any { it.sensitive || it.inspectionIncomplete || it.requiresUserConfirmation || !it.enabled },
                failure = failure ?: when {
                    ancestors.size != path.size -> PhoneClickProofRejection.MISSING_ANCESTOR
                    node == null -> PhoneClickProofRejection.PREVIEW_UNAVAILABLE
                    node.requiresUserConfirmation -> PhoneClickProofRejection.RESTRICTED_CONTEXT
                    !node.enabled || !node.clickable || node.password -> PhoneClickProofRejection.TARGET_UNAVAILABLE
                    else -> null
                },
            )
        }
    }
}

/** Different trusted scopes may coexist; each one must remain unique in the complete tree. */
internal fun hasEligiblePhoneClickRevalidationProof(proofs: List<PhoneClickRevalidationProof>): Boolean =
    proofs.any { matchPhoneClickRevalidation(it, proofs, sensitive = false, truncated = false) != null }

internal fun phoneClickProofHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun phoneClickAnchorEvent(type: Int): PhoneClickAnchorEvent = when (type) {
    AccessibilityEvent.TYPE_WINDOWS_CHANGED -> PhoneClickAnchorEvent.WINDOWS_CHANGED
    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> PhoneClickAnchorEvent.WINDOW_STATE_CHANGED
    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> PhoneClickAnchorEvent.CONTENT_CHANGED
    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> PhoneClickAnchorEvent.TEXT_CHANGED
    AccessibilityEvent.TYPE_VIEW_SCROLLED -> PhoneClickAnchorEvent.SCROLLED
    AccessibilityEvent.TYPE_VIEW_CLICKED -> PhoneClickAnchorEvent.CLICKED
    AccessibilityEvent.TYPE_VIEW_FOCUSED -> PhoneClickAnchorEvent.FOCUSED
    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> PhoneClickAnchorEvent.TEXT_SELECTION_CHANGED
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> PhoneClickAnchorEvent.ACCESSIBILITY_FOCUSED
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> PhoneClickAnchorEvent.ACCESSIBILITY_FOCUS_CLEARED
    else -> PhoneClickAnchorEvent.OTHER
}

/** One observation's bounded metadata. Never retains an extra tree or exports its identity keys. */
internal class PhoneClickDiagnosticHistory {
    private data class Target(val id: String, val failure: PhoneClickProofRejection?, val eligible: Boolean)
    private data class Record(
        val token: PhoneSessionToken, val observationId: String, val targets: List<Target>,
        val clearReason: PhoneClickAnchorClearReason? = null, val clearEvent: PhoneClickAnchorEvent? = null,
    )
    private var record: Record? = null

    @Synchronized fun built(token: PhoneSessionToken, observationId: String, proofs: List<PhoneClickRevalidationProof>) {
        record = Record(token, observationId, proofs.take(4).map {
            Target(it.nodeId, it.failure, matchPhoneClickRevalidation(it, proofs, false, false) != null)
        })
    }

    @Synchronized fun cleared(token: PhoneSessionToken, observationId: String, reason: PhoneClickAnchorClearReason, event: PhoneClickAnchorEvent?) {
        val current = record ?: return
        if (current.token == token && current.observationId == observationId) {
            record = current.copy(clearReason = reason, clearEvent = event)
        }
    }

    @Synchronized fun reset() { record = null }

    @Synchronized fun describe(token: PhoneSessionToken, observationId: String, nodeId: String): PhoneClickRevalidationDiagnostic? {
        val current = record?.takeIf { it.token == token && it.observationId == observationId } ?: return null
        val target = current.targets.singleOrNull { it.id == nodeId }
        return PhoneClickRevalidationDiagnostic(
            stage = PhoneClickRevalidationStage.NO_ANCHOR,
            candidateCount = current.targets.size, eligibleCount = current.targets.count { it.eligible },
            targetProofReason = target?.failure,
            proofReasons = current.targets.mapNotNull { it.failure }.distinct().take(4).ifEmpty {
                if (current.targets.isEmpty()) listOf(PhoneClickProofRejection.NO_CANDIDATE) else emptyList()
            },
            lastClearReason = current.clearReason, lastClearEvent = current.clearEvent,
        )
    }
}
