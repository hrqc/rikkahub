package me.rerere.rikkahub.data.mobileagent

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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
)

internal data class AndroidNodeHandle(val path: List<Int>, val signature: AndroidNodeSignature)

internal data class AndroidTreeCapture(
    val nodes: List<PhoneNode>,
    val handles: Map<String, AndroidNodeHandle>,
    val truncated: Boolean,
    val sensitive: Boolean,
    val fingerprint: String,
)

/** Reads only the already authorized root supplied by the backend. Never queries windows itself. */
internal class AccessibilityTreeReader {
    companion object {
        const val MAX_CHILDREN = 64
    }

    fun capture(root: AccessibilityNodeInfo, targetPackage: String, isValid: () -> Boolean): AndroidTreeCapture {
        val nodes = mutableListOf<PhoneNode>()
        val handles = linkedMapOf<String, AndroidNodeHandle>()
        val budget = PhoneTreeReadBudget(SystemClock.elapsedRealtime())
        var sensitive = false

        fun check() {
            if (!isValid()) throw TreeReadAborted()
        }

        fun visit(node: AccessibilityNodeInfo, path: List<Int>, parentId: String?, depth: Int) {
            check()
            if (!budget.visit(depth, SystemClock.elapsedRealtime())) return
            // Embedded nodes from a different package are never included or descended into.
            if (node.packageName?.toString() != targetPackage) {
                budget.markTruncated()
                return
            }
            val signature = signature(node)
            sensitive = sensitive || signature.sensitive
            if (signature.truncated) budget.markTruncated()
            val hasContent = signature.text.isNotBlank() || signature.description.isNotBlank()
            val include = node.isVisibleToUser && (hasContent || signature.clickable || signature.longClickable ||
                signature.editable || signature.scrollable || signature.password || depth == 0)
            var effectiveParent = parentId
            if (include) {
                val textSize = signature.text.length + signature.description.length
                if (!budget.include(textSize)) return
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
                )
                handles[id] = AndroidNodeHandle(path, signature)
            }
            // Descendants may repeat the password text without setting isPassword themselves.
            if (signature.sensitive) return
            val children = node.childCount
            if (children > MAX_CHILDREN) budget.markTruncated()
            for (index in 0 until minOf(children, MAX_CHILDREN)) {
                check()
                if (!budget.canContinue(SystemClock.elapsedRealtime())) break
                val child = child(node, index) ?: continue
                try {
                    visit(child, path + index, effectiveParent, depth + 1)
                } finally {
                    recycleNode(child)
                }
            }
        }

        visit(root, emptyList(), null, 0)
        check()
        val fingerprint = digest(nodes.joinToString("\n") { it.toString() })
        return AndroidTreeCapture(nodes, handles, budget.truncated, sensitive, fingerprint)
    }

    /** Returns an owned fresh node; the caller must recycle it on API < 33. */
    fun resolve(root: AccessibilityNodeInfo, handle: AndroidNodeHandle, isValid: () -> Boolean): AccessibilityNodeInfo? {
        @Suppress("DEPRECATION")
        var current = AccessibilityNodeInfo.obtain(root)
        try {
            for (index in handle.path) {
                if (!isValid()) throw TreeReadAborted()
                if (index !in 0 until current.childCount) return null
                val next = child(current, index) ?: return null
                recycleNode(current)
                current = next
            }
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
