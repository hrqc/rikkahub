package me.rerere.rikkahub.data.mobileagent

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.shopping.ShoppingObservedEvidence

/** A bounded page of retained evidence, never a fresh observation or an action capability. */
@Serializable
data class PhoneObservedContentPage(
    val snapshotId: String,
    val packageName: String,
    val nodes: List<PhoneReadOnlyContentNode>,
    val nextCursor: String?,
    val contentTruncated: Boolean,
    val source: String = "retained_observation",
    val grantsActionPermission: Boolean = false,
)

internal data class StoredPhoneContent(val evidence: ShoppingObservedEvidence, val truncated: Boolean) {
    fun page(cursor: String): PhoneObservedContentPage {
        val offset = cursor.takeIf { it.matches(Regex("0|[1-9][0-9]{0,3}")) }?.toIntOrNull()
            ?: throw PhoneControlException("INVALID_CONTENT_CURSOR", "请使用工具返回的正文分页游标，首次使用0")
        if (offset !in 0..evidence.nodes.size) {
            throw PhoneControlException("INVALID_CONTENT_CURSOR", "正文分页游标超出范围")
        }
        val page = mutableListOf<PhoneReadOnlyContentNode>()
        var characters = 0
        var next = offset
        while (next < evidence.nodes.size && page.size < 40) {
            val node = evidence.nodes[next]
            val size = node.text.length + node.description.length
            if (characters + size > 8_000) break
            page += PhoneReadOnlyContentNode(node.nodeId, node.text, node.description, node.truncated)
            characters += size
            next++
        }
        return PhoneObservedContentPage(evidence.snapshotId, evidence.packageName, page,
            next.takeIf { it < evidence.nodes.size }?.toString(), truncated)
    }
}
