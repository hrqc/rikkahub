package me.rerere.rikkahub.data.mobileagent

import android.view.accessibility.AccessibilityNodeInfo

/** Coordinate scrolling must account for transaction controls omitted from the node preview. */
internal fun hasPhoneScrollTransactionRestriction(tree: AndroidTreeCapture): Boolean =
    tree.restrictedPaths.isNotEmpty() || tree.nodes.any { it.requiresUserConfirmation }

/** Geometry and axis proof only. The backend separately validates the live grant and snapshot. */
internal fun phoneRootScrollPlan(
    targetPackage: String,
    windowId: Int,
    windowBounds: PhoneBounds,
    tree: AndroidTreeCapture,
    nodeId: String,
    forward: Boolean,
    actionIds: Set<Int>,
    collectionRows: Int? = null,
    collectionColumns: Int? = null,
    obscuringBounds: List<PhoneBounds> = emptyList(),
): RootInputAction.Swipe? {
    // Whole-tree restrictions include controls beyond the model's node preview. This first
    // route does not try to thread a coordinate gesture around transaction controls.
    if (targetPackage != "com.jingdong.app.mall" || tree.sensitive || tree.truncated ||
        tree.inspectionIssues.isNotEmpty() || hasPhoneScrollTransactionRestriction(tree)) return null
    val node = tree.nodes.singleOrNull { it.id == nodeId } ?: return null
    val signature = tree.handles[nodeId]?.signature ?: return null
    if (!node.scrollable || !node.enabled || node.password || node.editable ||
        signature.windowId != windowId || signature.packageName != targetPackage ||
        !signature.scrollable || !signature.enabled || signature.password || signature.sensitive ||
        signature.editable || signature.requiresUserConfirmation || signature.truncated ||
        signature.inspectionIncomplete || signature.metadataTruncated || signature.role != node.role ||
        PhoneBounds(signature.left, signature.top, signature.right, signature.bottom) != node.bounds) return null

    val directionalAction = if (forward) android.R.id.accessibilityActionScrollDown else android.R.id.accessibilityActionScrollUp
    val standardAction = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    if (directionalAction !in actionIds && standardAction !in actionIds) return null
    val verticalContainer = node.role in setOf(
        "android.widget.ScrollView", "android.widget.ListView", "androidx.core.widget.NestedScrollView",
    )
    val recycler = node.role in setOf(
        "androidx.recyclerview.widget.RecyclerView", "android.support.v7.widget.RecyclerView",
    )
    // A tall RecyclerView alone does not prove its orientation. A directional action or a
    // definite row-dominant collection is needed; unknown/custom containers keep native routing.
    val verticalCollection = collectionRows != null && collectionColumns != null &&
        collectionRows > 1 && collectionColumns in 1..4 && collectionRows > collectionColumns
    if (!verticalContainer && !(recycler && (directionalAction in actionIds || verticalCollection))) return null

    fun PhoneBounds.valid() = left < right && top < bottom
    if (!windowBounds.valid() || !node.bounds.valid() || windowBounds.left < 0 || windowBounds.top < 0 ||
        windowBounds.right > 65_535 || windowBounds.bottom > 65_535) return null
    val area = PhoneBounds(
        maxOf(windowBounds.left, node.bounds.left), maxOf(windowBounds.top, node.bounds.top),
        minOf(windowBounds.right, node.bounds.right), minOf(windowBounds.bottom, node.bounds.bottom),
    )
    if (!area.valid()) return null
    val width = area.right - area.left
    val height = area.bottom - area.top
    val windowWidth = windowBounds.right - windowBounds.left
    val windowHeight = windowBounds.bottom - windowBounds.top
    if (height < 400 || height * 5L < windowHeight * 2L || width * 2L < windowWidth || height * 3L <= width) return null
    val x = area.left + width / 2
    val upper = area.top + height / 4
    val lower = area.bottom - height / 4
    val gesture = PhoneBounds(x, upper, x + 1, lower + 1)
    if (obscuringBounds.any { it.left < gesture.right && it.right > gesture.left &&
            it.top < gesture.bottom && it.bottom > gesture.top }) return null
    return if (forward) RootInputAction.Swipe(x, lower, x, upper) else RootInputAction.Swipe(x, upper, x, lower)
}
