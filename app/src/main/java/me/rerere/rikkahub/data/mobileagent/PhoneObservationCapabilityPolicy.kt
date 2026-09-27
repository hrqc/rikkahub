package me.rerere.rikkahub.data.mobileagent

/** This capability does not classify unavailable descendants as safe or inspected. */
fun canUseNativeScrollOnly(
    sensitive: Boolean,
    truncated: Boolean,
    inspectionIssues: List<String>,
    nodes: List<PhoneNode>,
): Boolean = shouldRetryPhoneInspection(sensitive, truncated, inspectionIssues) &&
    nodes.none { it.password || PhoneContentPolicy.isSensitive(it.text + "\n" + it.description) } &&
    nodes.any(::isNativeScrollOnlyNode)

internal fun isNativeScrollOnlyNode(node: PhoneNode): Boolean =
    node.scrollable && node.enabled && !node.password && !node.requiresUserConfirmation &&
        node.bounds.right > node.bounds.left && node.bounds.bottom > node.bounds.top &&
        !PhoneContentPolicy.isSensitive(node.text + "\n" + node.description) &&
        !PhonePurchasePolicy.requiresUser(node.text + "\n" + node.description)

internal fun nativeScrollOnlyPreview(nodes: List<PhoneNode>): List<PhoneNode> = nodes
    .filter(::isNativeScrollOnlyNode)
    .map { it.copy(parentId = null, text = "", description = "", clickable = false, longClickable = false, editable = false) }

/** Native scrolling does not activate a transaction control in an unrelated descendant. */
internal fun isNativeScrollPathRestricted(path: List<Int>, restrictedPaths: List<List<Int>>): Boolean =
    restrictedPaths.any { restricted -> path.take(restricted.size) == restricted }

/** Stored only in the backend; a model-supplied scrollOnly bit cannot create this capability. */
internal enum class PhoneSnapshotCapability { FULL, NATIVE_SCROLL_ONLY, NONE }

internal fun nativeScrollOnlyRequestAllowed(
    storedCapability: PhoneSnapshotCapability,
    storedObservation: PhoneObservation,
    requestedObservation: PhoneObservation?,
    action: PhoneAction,
): Boolean = storedCapability == PhoneSnapshotCapability.NATIVE_SCROLL_ONLY &&
    storedObservation == requestedObservation && storedObservation.scrollOnly &&
    canUseNativeScrollOnly(storedObservation.sensitive, storedObservation.truncated,
        storedObservation.inspectionIssues, storedObservation.nodes) &&
    action is PhoneAction.Scroll && storedObservation.nodes.any { it.id == action.nodeId && isNativeScrollOnlyNode(it) }
