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
): PhoneClickRevalidationContext? {
    val target = signatures[targetPath] ?: return null
    if (targetPath.isEmpty() || !isJdSearchNavigationSignature(target)) return null
    fun safe(node: AndroidNodeSignature) = node.packageName == target.packageName && node.windowId == target.windowId &&
        node.enabled && !node.password && !node.sensitive &&
        !node.truncated && !node.metadataTruncated && !node.inspectionIncomplete && !node.requiresUserConfirmation
    val ancestors = (0 until targetPath.size).map { signatures[targetPath.take(it)] ?: return null }
    if (ancestors.any { !safe(it) } || !safe(target)) return null
    val descendants = signatures.filterKeys { it.size > targetPath.size && it.take(targetPath.size) == targetPath }.values
    if (descendants.any { !safe(it) }) return null
    val labelled = descendants.filter { it.text.isNotBlank() || it.description.isNotBlank() }
    val label = labelled.singleOrNull() ?: return null
    if (label.role != "android.widget.TextView" || label.text != "搜索" || label.description.isNotBlank() ||
        label.clickable || label.editable) return null
    val parentPath = targetPath.dropLast(1)
    // This verified sibling identifies the homepage search bar; arbitrary product buttons are unknown.
    val camera = signatures.entries.filter { (path, signature) ->
        path != targetPath && path.size == targetPath.size && path.dropLast(1) == parentPath &&
            signature.packageName == "com.jingdong.app.mall" && signature.role == "android.widget.Button" &&
            signature.description == "拍照购" && signature.text.isBlank()
    }.singleOrNull() ?: return null
    if (!safe(camera.value) || !camera.value.clickable) return null
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

/** The recommendation is content inside this verified search entry, never a generic product card. */
private fun searchEntryContext(
    path: List<Int>,
    signatures: Map<List<Int>, AndroidNodeSignature>,
): SearchEntryContext? {
    if (!isJdSearchEntryCandidate(path, signatures) || signatures.values.count(::isJdSearchBar) != 1) return null
    val target = signatures.getValue(path)
    val parentPath = path.dropLast(1)
    val parent = signatures.getValue(parentPath)
    val bar = signatures.getValue(path.dropLast(2))
    fun safe(node: AndroidNodeSignature) = node.packageName == target.packageName && node.windowId == target.windowId &&
        node.enabled && !node.password && !node.sensitive && !node.truncated && !node.metadataTruncated &&
        !node.inspectionIncomplete && !node.requiresUserConfirmation
    val ancestors = (0 until path.size).map { signatures[path.take(it)] ?: return null }
    if (ancestors.any { !safe(it) } || !safe(target) || target.viewId.isNotEmpty() ||
        target.longClickable || target.editable || target.scrollable || parent.viewId.isNotEmpty() ||
        parent.longClickable || parent.editable || parent.scrollable || bar.clickable || bar.longClickable ||
        bar.editable || bar.scrollable) return null
    val descendants = signatures.entries.filter { (childPath, _) ->
        childPath.size > path.size && childPath.take(path.size) == path
    }
    // Preserve the complete subtree topology, including unlabelled and invisible descendants.
    val recommendation = descendants.singleOrNull() ?: return null
    val label = recommendation.value
    if (recommendation.key != path + 0 || !safe(label) || label.role != "android.widget.TextView" ||
        label.viewId.isNotEmpty() || label.text.isBlank() || label.description != label.text ||
        label.clickable || label.longClickable || label.editable || label.scrollable) return null
    val search = signatures.entries.filter { (_, value) -> isJdSearchNavigationSignature(value) }.singleOrNull()
        ?: return null
    if (search.key.size != path.size || search.key.dropLast(1) != parentPath ||
        phoneClickRevalidationContext(search.key, signatures) == null) return null
    val camera = signatures.entries.filter { (childPath, value) ->
        childPath.size == path.size && childPath.dropLast(1) == parentPath &&
            value.packageName == target.packageName && value.role == "android.widget.Button" &&
            value.description == "拍照购" && value.text.isBlank()
    }.singleOrNull() ?: return null
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
        target.right > camera.value.left || target.right > search.value.left || camera.value.right > search.value.left) return null
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
                PhoneClickRevalidationProof("", path, "", "", "", "", scope, restricted = true)
            }
        } else scopedCandidates.map { (path, signature) ->
            val ancestors = (0 until path.size).mapNotNull { signatures[path.take(it)] }
            val navigation = if (scope == PhoneClickRevalidationScope.JD_SEARCH_NAVIGATION) {
                phoneClickRevalidationContext(path, signatures)
            } else null
            val entry = if (scope == PhoneClickRevalidationScope.JD_SEARCH_ENTRY) searchEntryContext(path, signatures) else null
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
            )
        }
    }
}

/** Different trusted scopes may coexist; each one must remain unique in the complete tree. */
internal fun hasEligiblePhoneClickRevalidationProof(proofs: List<PhoneClickRevalidationProof>): Boolean =
    proofs.any { matchPhoneClickRevalidation(it, proofs, sensitive = false, truncated = false) != null }

internal fun phoneClickProofHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
