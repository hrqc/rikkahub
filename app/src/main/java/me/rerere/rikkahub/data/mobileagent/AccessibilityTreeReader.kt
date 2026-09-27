package me.rerere.rikkahub.data.mobileagent

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import me.rerere.rikkahub.BuildConfig
import java.security.MessageDigest

internal class TreeReadAborted : IllegalStateException("界面读取已失效。")

internal data class AndroidNodeSignature(
    val windowId: Int,
    val packageName: String,
    val uniqueId: String?,
    val role: String,
    val viewId: String,
    val text: String,
    val description: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val enabled: Boolean,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val sensitive: Boolean,
    val truncated: Boolean,
    val inspectionIncomplete: Boolean,
    val requiresUserConfirmation: Boolean,
    val contentFingerprint: String,
)

internal data class AndroidNodeHandle(val path: List<Int>, val signature: AndroidNodeSignature)

internal data class AndroidTreeCapture(
    val nodes: List<PhoneNode>,
    val handles: Map<String, AndroidNodeHandle>,
    val truncated: Boolean,
    val sensitive: Boolean,
    val fingerprint: String,
    val previewTruncated: Boolean,
    val inspectionIssues: List<String>,
    val restrictedPaths: List<List<Int>>,
    val nativeScrollNodeIds: Set<String>,
)

/** Reads only the already authorized root supplied by the backend. Never queries windows itself. */
internal class AccessibilityTreeReader {
    companion object {
        const val MAX_CHILDREN = 128
    }

    fun capture(
        root: AccessibilityNodeInfo,
        targetPackage: String,
        diagnostics: ReadDiagnosticTree? = null,
        diagnosticProfile: ReadDiagnosticProfile? = null,
        debugStructure: PhoneDebugStructureCapture? = null,
        isValid: () -> Boolean,
    ): AndroidTreeCapture {
        require(diagnosticProfile == null || diagnostics != null)
        require(debugStructure == null || (BuildConfig.DEBUG && diagnosticProfile != null && diagnostics != null))
        val nodes = mutableListOf<PhoneNode>()
        val handles = linkedMapOf<String, AndroidNodeHandle>()
        val budget = PhoneTreeReadBudget(SystemClock.elapsedRealtime(), diagnosticProfile?.nodeLimit ?: 512)
        val rootWindowId = if (diagnosticProfile != null) root.windowId else null
        val restrictedPaths = mutableListOf<List<Int>>()
        val nativeScrollNodeIds = mutableSetOf<String>()
        val inspectedSignatures = mutableListOf<AndroidNodeSignature>()
        var sensitive = false

        fun check() {
            if (!isValid()) throw TreeReadAborted()
        }

        fun visit(node: AccessibilityNodeInfo, path: List<Int>, parentId: String?, depth: Int) {
            check()
            if (!budget.visit(depth, SystemClock.elapsedRealtime())) return
            diagnostics?.visited()
            // Embedded nodes from a different package are never included or descended into.
            if (node.packageName?.toString() != targetPackage) {
                diagnostics?.foreign(path)
                budget.markTruncated("foreign_node")
                return
            }
            if (diagnosticProfile?.refreshParents == true && !node.isPassword &&
                !(Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) {
                if (node.windowId != rootWindowId) throw TreeReadAborted()
                val refreshed = node.refresh()
                check()
                diagnostics?.nodeRefresh(refreshed)
                if (!refreshed) {
                    budget.markTruncated("node_refresh_failed")
                    return
                }
                if (node.windowId != rootWindowId || node.packageName?.toString() != targetPackage) throw TreeReadAborted()
            }
            val signature = signature(node)
            inspectedSignatures += signature
            sensitive = sensitive || signature.sensitive
            if (signature.truncated) budget.markPreviewTruncated()
            if (signature.inspectionIncomplete) budget.markTruncated("text_limit")
            if (signature.requiresUserConfirmation) restrictedPaths += path
            val hasContent = signature.text.isNotBlank() || signature.description.isNotBlank()
            val isVisible = node.isVisibleToUser
            val include = isVisible && (hasContent || signature.clickable || signature.longClickable ||
                signature.editable || signature.scrollable || signature.password || depth == 0)
            var effectiveParent = parentId
            var wasEmitted = false
            if (include && budget.include(signature.text.length + signature.description.length)) {
                wasEmitted = true
                val id = "n${nodes.size}"
                effectiveParent = id
                nodes += PhoneNode(
                    id = id,
                    parentId = parentId,
                    role = signature.role,
                    viewId = signature.viewId,
                    text = signature.text,
                    description = signature.description,
                    bounds = PhoneBounds(signature.left, signature.top, signature.right, signature.bottom),
                    clickable = signature.clickable,
                    longClickable = signature.longClickable,
                    editable = signature.editable,
                    scrollable = signature.scrollable,
                    enabled = signature.enabled,
                    password = signature.password,
                    requiresUserConfirmation = signature.requiresUserConfirmation,
                )
                handles[id] = AndroidNodeHandle(path, signature)
                if (signature.scrollable && node.actionList.any {
                        it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }) nativeScrollNodeIds += id
            }
            // Descendants may repeat the password text without setting isPassword themselves.
            if (signature.sensitive) {
                diagnostics?.protectedSubtree(path, isVisible, wasEmitted)
                return
            }
            val children = node.childCount
            debugStructure?.recordNode(path, PhoneDebugNodeShape(
                className = signature.role, viewId = signature.viewId,
                bounds = PhoneBounds(signature.left, signature.top, signature.right, signature.bottom),
                visible = isVisible, enabled = signature.enabled, scrollable = signature.scrollable,
                childCount = children, clickable = signature.clickable, focused = node.isFocused,
                emittedNodeId = if (wasEmitted) effectiveParent else null,
            ))
            diagnostics?.node(path, signature.role, signature.viewId,
                PhoneBounds(signature.left, signature.top, signature.right, signature.bottom),
                isVisible, children, signature.scrollable, node.isFocused, wasEmitted)
            if (children > MAX_CHILDREN) budget.markTruncated("children_limit")
            for (index in 0 until minOf(children, MAX_CHILDREN)) {
                check()
                if (!budget.canContinue(SystemClock.elapsedRealtime())) break
                val child = child(node, index)
                if (child == null) {
                    debugStructure?.recordGap(path, index)
                    diagnostics?.gap(path, signature.role, signature.viewId,
                        PhoneBounds(signature.left, signature.top, signature.right, signature.bottom),
                        isVisible, children, index)
                    budget.markTruncated("unavailable_child")
                    continue
                }
                try {
                    visit(child, path + index, effectiveParent, depth + 1)
                } finally {
                    recycleNode(child)
                }
            }
        }

        visit(root, emptyList(), null, 0)
        check()
        val guardedNodes = nodes.map { node ->
            val path = handles.getValue(node.id).path
            node.copy(requiresUserConfirmation = node.requiresUserConfirmation ||
                ((node.clickable || node.longClickable || node.editable) && isPhonePurchasePathRestricted(path, restrictedPaths)))
        }
        // Preview omission must not omit safety inspection or freshness of the remaining tree.
        val fingerprint = digest(inspectedSignatures.joinToString("\n") { it.toString() })
        return AndroidTreeCapture(guardedNodes, handles, budget.truncated, sensitive, fingerprint,
            budget.previewTruncated, budget.issues.toList(), restrictedPaths.toList(), nativeScrollNodeIds.toSet())
    }

    /** Returns an owned fresh node; the caller must recycle it on API < 33. */
    fun resolve(
        root: AccessibilityNodeInfo,
        handle: AndroidNodeHandle,
        strictNativeScroll: Boolean = false,
        isValid: () -> Boolean,
    ): AccessibilityNodeInfo? {
        @Suppress("DEPRECATION")
        var current = AccessibilityNodeInfo.obtain(root)
        try {
            fun permittedAncestor(): Boolean {
                if (!isValid()) throw TreeReadAborted()
                if (!current.refresh()) return false
                if (!isValid()) throw TreeReadAborted()
                if (current.windowId != handle.signature.windowId ||
                    current.packageName?.toString() != handle.signature.packageName) return false
                val currentSignature = signature(current)
                return !currentSignature.sensitive && !currentSignature.inspectionIncomplete &&
                    !currentSignature.requiresUserConfirmation && currentSignature.enabled
            }
            for (index in handle.path) {
                if (!isValid()) throw TreeReadAborted()
                if (strictNativeScroll && !permittedAncestor()) return null
                if (index !in 0 until current.childCount) return null
                val next = child(current, index) ?: return null
                recycleNode(current)
                current = next
            }
            if (strictNativeScroll && !permittedAncestor()) return null
            if (!isValid() || !current.refresh() || !current.isVisibleToUser || signature(current) != handle.signature) {
                return null
            }
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(current)
        } finally {
            recycleNode(current)
        }
    }

    private fun signature(node: AccessibilityNodeInfo): AndroidNodeSignature {
        val password = node.isPassword
        val protected = password || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
        val text = samplePhoneNodeText(protected) { node.text }
        val description = samplePhoneNodeText(protected) { node.contentDescription }
        val sensitive = protected || text.sensitive || description.sensitive
        val bounds = Rect().also(node::getBoundsInScreen)
        return AndroidNodeSignature(
            windowId = node.windowId,
            packageName = bounded(node.packageName, 256),
            uniqueId = if (Build.VERSION.SDK_INT >= 33) node.uniqueId?.take(256) else null,
            role = bounded(node.className, 160),
            viewId = node.viewIdResourceName?.take(256).orEmpty(),
            text = if (sensitive) "" else text.value,
            description = if (sensitive) "" else description.value,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            enabled = node.isEnabled,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            password = password,
            sensitive = sensitive,
            truncated = text.truncated || description.truncated,
            inspectionIncomplete = text.inspectionIncomplete || description.inspectionIncomplete,
            requiresUserConfirmation = text.requiresUserConfirmation || description.requiresUserConfirmation,
            contentFingerprint = text.contentFingerprint + ":" + description.contentFingerprint,
        )
    }

    private fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= 33) node.getChild(index, 0) else node.getChild(index)

    private fun bounded(value: CharSequence?, length: Int): String =
        value?.subSequence(0, minOf(value.length, length))?.toString().orEmpty()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

@Suppress("DEPRECATION")
internal fun recycleNode(node: AccessibilityNodeInfo) {
    if (Build.VERSION.SDK_INT < 33) node.recycle()
}
