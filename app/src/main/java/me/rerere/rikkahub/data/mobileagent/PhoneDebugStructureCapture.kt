package me.rerere.rikkahub.data.mobileagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Structural scalar fields only. No text, description, image, node object or caller-provided JSON. */
internal data class PhoneDebugNodeShape(
    val className: String,
    val viewId: String,
    val bounds: PhoneBounds,
    val visible: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val childCount: Int,
    val clickable: Boolean = false,
    val focused: Boolean = false,
    val emittedNodeId: String? = null,
)

/**
 * One read attempt's metadata, separate from production ReadDiagnostics. The backend must own the
 * DEBUG/token/window checks and call abort on invalidation. A successful export grants no action
 * rights and does not establish a product scope. Selected paths annotate roots without filtering
 * the recorded structure, so their real ancestors and sibling boundaries remain available.
 */
internal class PhoneDebugStructureCapture(
    selectedPaths: List<String> = emptyList(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxNodes: Int = 768,
    private val maxBytes: Int = 256 * 1024,
    private val maxDurationMillis: Long = 15_000,
) {
    val structureCaptureId: String = UUID.randomUUID().toString()
    private val startedAt = now()
    private val selectedPaths = selectedPaths.toList()
    private val nodes = linkedMapOf<String, NodeRecord>()
    private val gaps = mutableListOf<JsonObject>()
    private var payloadBytes = 0L
    private var closed = false

    /** Fixed local codes only; callers must not substitute platform exception messages. */
    @Volatile var refusalReason: String? = null
        private set

    init {
        require(maxNodes in 1..768 && maxBytes in 1..256 * 1024 && maxDurationMillis in 1..15_000)
        require(selectedPaths.size <= 3 && selectedPaths.distinct().size == selectedPaths.size)
        require(selectedPaths.all { parsePath(it) != null })
    }

    /** Call for every visited node in parent-before-child order, before public-preview filtering. */
    @Synchronized fun recordNode(path: List<Int>, shape: PhoneDebugNodeShape): Boolean {
        if (!canRecord()) return false
        if (nodes.size >= maxNodes) return refuse("NODE_LIMIT")
        if (!validPath(path) || !validShape(shape)) return refuse("INVALID_NODE")
        val copiedPath = path.toList()
        val key = pathString(copiedPath)
        if (key in nodes) return refuse("DUPLICATE_NODE_PATH")
        val parentKey = copiedPath.takeIf { it.isNotEmpty() }?.dropLast(1)?.let(::pathString)
        if (parentKey != null) {
            val parent = nodes[parentKey] ?: return refuse("PARENT_NOT_RECORDED")
            val index = copiedPath.last()
            if (index >= parent.shape.childCount || index in parent.missingIndices) return refuse("INCONSISTENT_CHILD")
        }
        val record = buildJsonObject {
            put("path", key)
            put("parentPath", parentKey?.let(::JsonPrimitive) ?: JsonNull)
            put("childIndex", copiedPath.lastOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("class", shape.className); put("resourceId", shape.viewId)
            put("bounds", JsonArray(listOf(shape.bounds.left, shape.bounds.top, shape.bounds.right, shape.bounds.bottom).map(::JsonPrimitive)))
            put("visible", shape.visible); put("enabled", shape.enabled); put("scrollable", shape.scrollable)
            put("clickable", shape.clickable); put("focused", shape.focused); put("childCount", shape.childCount)
            put("emittedNodeId", shape.emittedNodeId?.let(::JsonPrimitive) ?: JsonNull)
        }
        if (!reserve(record)) return false
        nodes[key] = NodeRecord(copiedPath, shape, record)
        parentKey?.let { nodes.getValue(it).seenIndices += copiedPath.last() }
        return true
    }

    @Synchronized fun recordGap(parentPath: List<Int>, index: Int): Boolean {
        if (!canRecord()) return false
        if (!validPath(parentPath) || index < 0) return refuse("INVALID_GAP")
        val parentKey = pathString(parentPath)
        val parent = nodes[parentKey] ?: return refuse("GAP_PARENT_NOT_RECORDED")
        if (index >= parent.shape.childCount || index in parent.seenIndices || index in parent.missingIndices) return refuse("INVALID_GAP")
        val gap = buildJsonObject { put("parentPath", parentKey); put("unavailableChildIndex", index) }
        if (!reserve(gap)) return false
        parent.missingIndices += index
        gaps += gap
        return true
    }

    /**
     * Completeness comes from the already checked tree, never from the absence of recorded gaps.
     * Returns one bounded JSON document (budget includes its final newline), or refuses everything.
     */
    @Synchronized fun finish(
        captureId: String,
        sourceProfile: String,
        windowId: Int,
        revision: Long,
        treeTruncated: Boolean,
        issues: List<String>,
        windowIdentity: Long? = null,
        actualServiceFlags: Int? = null,
        treeNodeLimit: Int? = null,
    ): String? {
        if (!canRecord()) return null
        if (!captureId.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
            !sourceProfile.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) || windowId < 0 || revision < 0 ||
            (windowIdentity != null && windowIdentity < 0) || (treeNodeLimit != null && treeNodeLimit !in 1..768) ||
            issues.size > 32 || issues.any { !it.matches(Regex("[A-Za-z0-9_]{1,64}")) }) {
            refuse("INVALID_FINISH_METADATA")
            return null
        }
        if ("/" !in nodes || selectedPaths.any { it !in nodes }) {
            refuse("MISSING_ROOT_OR_SELECTION")
            return null
        }
        // Reject contradictory caller status; do not turn a gap-free prefix into a complete tree.
        if (!treeTruncated && (issues.isNotEmpty() || gaps.isNotEmpty() ||
                nodes.values.any { it.seenIndices.size != it.shape.childCount })) {
            refuse("INCONSISTENT_TREE_STATUS")
            return null
        }
        val exported = buildJsonObject {
            put("schemaVersion", 1); put("recordType", "debug_structure_export")
            put("structureCaptureId", structureCaptureId); put("captureId", captureId)
            put("sourceProfile", sourceProfile); put("windowId", windowId); put("revision", revision)
            put("windowIdentity", windowIdentity?.let(::JsonPrimitive) ?: JsonNull)
            put("actualServiceFlags", actualServiceFlags?.let(::JsonPrimitive) ?: JsonNull)
            put("treeNodeLimit", treeNodeLimit?.let(::JsonPrimitive) ?: JsonNull)
            put("treeStatusSource", "caller_verified_tree")
            put("treeTruncated", treeTruncated)
            put("inspectionIssues", JsonArray(issues.map(::JsonPrimitive)))
            put("selectedPaths", JsonArray(selectedPaths.map(::JsonPrimitive)))
            put("selectionFiltersStructure", false)
            put("productScopeVerified", false); put("contentIncluded", false)
            put("collectorNodeCount", nodes.size); put("collectorGapCount", gaps.size)
            put("collectorLimits", buildJsonObject {
                put("nodes", maxNodes); put("bytesIncludingNewline", maxBytes); put("durationMs", maxDurationMillis)
            })
            put("nodes", JsonArray(nodes.values.map { it.json }))
            put("gaps", JsonArray(gaps.toList()))
        }.toString()
        if (exported.toByteArray(Charsets.UTF_8).size.toLong() + 1 > maxBytes) {
            refuse("BYTE_LIMIT")
            return null
        }
        if (!canRecord()) return null
        closed = true
        clear()
        return exported
    }

    @Synchronized fun abort() {
        if (!closed) refuse("ABORTED")
    }

    private fun canRecord(): Boolean {
        if (closed) return false
        val elapsed = now() - startedAt
        return when {
            elapsed < 0 -> refuse("CLOCK_INVALID")
            elapsed >= maxDurationMillis -> refuse("TIME_LIMIT")
            else -> true
        }
    }

    private fun reserve(record: JsonObject): Boolean {
        val size = record.toString().toByteArray(Charsets.UTF_8).size.toLong() + 1
        if (payloadBytes + size > maxBytes) return refuse("BYTE_LIMIT")
        payloadBytes += size
        return true
    }

    private fun refuse(reason: String): Boolean {
        if (!closed) refusalReason = reason
        closed = true
        clear()
        return false
    }

    private fun clear() { nodes.clear(); gaps.clear(); payloadBytes = 0 }

    private data class NodeRecord(
        val path: List<Int>,
        val shape: PhoneDebugNodeShape,
        val json: JsonObject,
        val seenIndices: MutableSet<Int> = mutableSetOf(),
        val missingIndices: MutableSet<Int> = mutableSetOf(),
    )

    companion object {
        private fun validPath(path: List<Int>) = path.size <= 40 && path.all { it >= 0 }
        private fun pathString(path: List<Int>) = "/" + path.joinToString("/")

        private fun parsePath(path: String): List<Int>? {
            if (path == "/") return emptyList()
            if (path.length > 441 || !path.matches(Regex("/(?:0|[1-9][0-9]*)(?:/(?:0|[1-9][0-9]*))*"))) return null
            val parts = path.drop(1).split('/')
            if (parts.size > 40) return null
            return parts.map { it.toIntOrNull() ?: return null }.takeIf(::validPath)
        }

        private fun validShape(shape: PhoneDebugNodeShape): Boolean =
            shape.childCount >= 0 && shape.className.length <= 160 && shape.viewId.length <= 256 &&
                shape.className.matches(Regex("[A-Za-z0-9_.$]*")) &&
                shape.viewId.matches(Regex("[A-Za-z0-9_.$:/-]*")) &&
                (shape.emittedNodeId == null || shape.emittedNodeId.matches(Regex("[A-Za-z0-9_-]{1,160}")))
    }
}
