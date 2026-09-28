package me.rerere.rikkahub.data.mobileagent

/** Bounded text already sampled by the safety walk; never reads Android nodes or creates handles. */
internal class PhoneReadOnlyContentCollector {
    companion object {
        const val MAX_NODES = 768
        // Match the project's String.length budgets, not UTF-8 byte sizes.
        const val MAX_CHARS = 64_000
        const val MAX_FIELD_CHARS = 240
        private val PREVIEW_NODE_ID = Regex("n[0-9]+")
    }

    private val previewNodes = mutableListOf<PhoneReadOnlyContentNode>()
    private val extraNodes = ArrayDeque<PhoneReadOnlyContentNode>()
    private var chars = 0
    private var nextReadOnlyId = 0
    private var extrasClosed = false
    private var previewClosed = false
    private var truncated = false

    fun add(
        previewNodeId: String?,
        visible: Boolean,
        password: Boolean,
        sensitive: Boolean,
        text: String,
        description: String,
        fieldsTruncated: Boolean = false,
    ) {
        if (!visible || password || sensitive) return
        val nodeTruncated = fieldsTruncated || text.length > MAX_FIELD_CHARS || description.length > MAX_FIELD_CHARS
        truncated = truncated || nodeTruncated
        val boundedText = text.take(MAX_FIELD_CHARS)
        val boundedDescription = description.take(MAX_FIELD_CHARS)
        if (boundedText.isBlank() && boundedDescription.isBlank()) return
        val length = boundedText.length + boundedDescription.length

        if (previewNodeId != null) {
            require(previewNodeId.matches(PREVIEW_NODE_ID))
            if (previewClosed) return
            // A short preview node can arrive after the preview's text budget omitted a larger
            // node. Keep every published n* and trim only the r* tail, never holes in its prefix.
            while (extraNodes.isNotEmpty() && exceedsBudget(length)) {
                val removed = extraNodes.removeLast()
                chars -= removed.text.length + removed.description.length
                truncated = true
                extrasClosed = true
            }
            if (exceedsBudget(length)) {
                truncated = true
                previewClosed = true
                extrasClosed = true
                return
            }
            previewNodes += PhoneReadOnlyContentNode(previewNodeId, boundedText, boundedDescription, nodeTruncated)
        } else {
            if (extrasClosed) return
            if (exceedsBudget(length)) {
                truncated = true
                extrasClosed = true
                return
            }
            extraNodes.addLast(PhoneReadOnlyContentNode("r${nextReadOnlyId++}", boundedText, boundedDescription, nodeTruncated))
        }
        chars += length
    }

    private fun exceedsBudget(additionalChars: Int): Boolean =
        previewNodes.size + extraNodes.size >= MAX_NODES || chars + additionalChars > MAX_CHARS

    fun finish(inspectionComplete: Boolean, sensitive: Boolean): PhoneReadOnlyContent? {
        if (!inspectionComplete || sensitive) return null
        return PhoneReadOnlyContent(previewNodes.toList() + extraNodes.toList(), truncated)
    }
}
