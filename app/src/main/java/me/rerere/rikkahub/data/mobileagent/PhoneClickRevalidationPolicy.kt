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
internal enum class PhoneClickRevalidationScope { UNKNOWN, JD_SEARCH_NAVIGATION }

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

internal fun phoneClickProofHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
