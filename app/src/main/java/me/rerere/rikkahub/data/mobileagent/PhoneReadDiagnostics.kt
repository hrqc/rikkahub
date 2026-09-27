package me.rerere.rikkahub.data.mobileagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

internal enum class ReadDiagnosticProfile(val includeUnimportant: Boolean, val refreshParents: Boolean) {
    DEFAULT(false, false),
    INCLUDE_UNIMPORTANT(true, false),
    REFRESH_PARENTS(false, true),
    INCLUDE_UNIMPORTANT_REFRESH_PARENTS(true, true);

    val nodeLimit: Int get() = PRODUCTION_PHONE_TREE_NODE_LIMIT
    fun applyFlags(flags: Int): Int = if (includeUnimportant) flags or 2 else flags and 2.inv()
}

/** Only the temporary flag bit is restored; unrelated concurrent configuration is preserved. */
internal class ReadDiagnosticFlagLease(
    val token: PhoneSessionToken,
    val runId: String,
    val serviceIdentity: Any,
    val originalFlags: Int,
    val appliedFlags: Int,
) {
    fun restoreFlags(currentService: Any?, currentToken: PhoneSessionToken?, currentRunId: String?, currentFlags: Int): Int? {
        if (currentService !== serviceIdentity || (currentToken != null && currentToken != token) ||
            (currentRunId != null && currentRunId != runId)) return null
        if ((currentFlags and 2) != (appliedFlags and 2)) return null
        return (currentFlags and 2.inv()) or (originalFlags and 2)
    }
}

/** Diagnostic permission is revoked independently of a notification retained while paused. */
internal class ReadDiagnosticAuthority {
    private var token: PhoneSessionToken? = null

    @Synchronized fun activate(token: PhoneSessionToken) { this.token = token }
    @Synchronized fun revoke() { token = null }
    @Synchronized fun allows(candidate: PhoneSessionToken): Boolean = token == candidate

    /** Publish the handle before revocation can stop it; a stale token cannot open a new capture. */
    @Synchronized fun <T> whileAuthorized(candidate: PhoneSessionToken, block: () -> T): T? =
        if (token == candidate) block() else null
}

internal data class ReadDiagnosticWindow(
    val windowId: Int?, val identity: Long, val revision: Long, val windowCount: Int?,
)

internal enum class ReadDiagnosticStop {
    FINISHED, TIME_LIMIT, BYTE_LIMIT, SAMPLE_LIMIT, ATTEMPT_LIMIT,
    SESSION_INVALIDATED, WINDOW_CHANGED, REPLACED,
}

internal enum class ReadDiagnosticOperation { NOT_ATTEMPTED, SUCCEEDED, FAILED }

internal data class ReadDiagnosticSample(val id: String, val index: Int, val startedAt: Long)

internal class ReadDiagnosticAttempt(
    val sample: ReadDiagnosticSample, val index: Int, val startedAt: Long,
    var window: ReadDiagnosticWindow, val tree: ReadDiagnosticTree,
) {
    var rootRefresh = ReadDiagnosticOperation.NOT_ATTEMPTED
    var cacheClear = ReadDiagnosticOperation.NOT_ATTEMPTED
    var profile = ReadDiagnosticProfile.DEFAULT
    var variantRead = false
    var serviceFlags: Int? = null
    var treeNodeLimit = PRODUCTION_PHONE_TREE_NODE_LIMIT
}

/** No node, text, description, image, exception message or model output is accepted by this model. */
internal class ReadDiagnosticCapture(
    val token: PhoneSessionToken,
    val targetPackage: String,
    val initialWindow: ReadDiagnosticWindow,
    serviceFlags: Int?, serviceEventTypes: Int?, apiLevel: Int, appVersion: String,
    private val now: () -> Long,
    private val wallTime: () -> Long,
    private val maxBytes: Int = 128 * 1024,
    val profile: ReadDiagnosticProfile = ReadDiagnosticProfile.DEFAULT,
) {
    val runId: String = UUID.randomUUID().toString()
    private val startedAt = now()
    private val lines = mutableListOf<String>()
    private var bytes = 0
    private var samples = 0
    private var attempts = 0
    private var events = 0
    private var eventsDropped = 0
    private var stopReason: ReadDiagnosticStop? = null
    private var stoppedAt: Long? = null
    private var taken = false

    init {
        require(maxBytes in 4096..128 * 1024)
        append("begin") {
            put("source", "production_accessibility_backend")
            put("diagnosticProfile", profile.name)
            put("apiLevel", apiLevel)
            put("appVersion", diagnosticLabel(appVersion, 96))
            put("serviceFlags", serviceFlags?.let(::JsonPrimitive) ?: JsonNull)
            put("serviceEventTypes", serviceEventTypes?.let(::JsonPrimitive) ?: JsonNull)
            put("window", diagnosticWindow(initialWindow))
            put("activityName", JsonNull)
            put("activitySource", "unavailable")
            put("screenHash", JsonNull)
            put("screenHashReason", "not_enabled")
            put("layoutState", "unknown")
            put("traversal", "depth_first")
            put("limits", buildJsonObject {
                put("durationMs", 15_000); put("samples", 7); put("attempts", 32)
                put("events", 128); put("gapsPerAttempt", 20); put("exportBytes", maxBytes)
                put("treeNodes", PRODUCTION_PHONE_TREE_NODE_LIMIT); put("treeDepth", 40); put("treeTimeMs", 2_000)
                put("variantTreeNodes", profile.nodeLimit)
                put("childrenPerNode", 128)
            })
        }
    }

    @Synchronized fun isRecording(): Boolean {
        if (stopReason == null && now() - startedAt >= 15_000) stop(ReadDiagnosticStop.TIME_LIMIT)
        return stopReason == null && !taken
    }

    @Synchronized fun beginSample(): ReadDiagnosticSample? {
        if (!isRecording()) return null
        if (samples >= 7) { stop(ReadDiagnosticStop.SAMPLE_LIMIT); return null }
        val sample = ReadDiagnosticSample(UUID.randomUUID().toString(), ++samples, now())
        append("sample_start") { put("sampleId", sample.id); put("sample", sample.index) }
        return sample.takeIf { isRecording() }
    }

    @Synchronized fun beginAttempt(sample: ReadDiagnosticSample, window: ReadDiagnosticWindow): ReadDiagnosticAttempt? {
        if (!isRecording()) return null
        if (attempts >= 32) { stop(ReadDiagnosticStop.ATTEMPT_LIMIT); return null }
        return ReadDiagnosticAttempt(sample, ++attempts, now(), window, ReadDiagnosticTree(::isRecording))
    }

    @Synchronized fun endAttempt(
        attempt: ReadDiagnosticAttempt, window: ReadDiagnosticWindow, outcome: String,
        inspectionIssues: List<String>, truncated: Boolean?, previewTruncated: Boolean?, sensitive: Boolean?,
    ) {
        if (!isRecording()) return
        append("attempt") {
            put("sampleId", attempt.sample.id); put("sample", attempt.sample.index); put("attempt", attempt.index)
            put("startedElapsedMs", attempt.startedAt); put("durationMs", now() - attempt.startedAt)
            put("windowBefore", diagnosticWindow(attempt.window)); put("windowAfter", diagnosticWindow(window))
            put("rootRefresh", attempt.rootRefresh.name); put("cacheClear", attempt.cacheClear.name)
            put("readMode", if (attempt.variantRead) "diagnostic_variant" else "normal_observe")
            put("diagnosticProfile", attempt.profile.name)
            put("serviceFlags", attempt.serviceFlags?.let(::JsonPrimitive) ?: JsonNull)
            put("treeNodeLimit", attempt.treeNodeLimit)
            put("outcome", diagnosticCode(outcome))
            put("truncated", truncated?.let(::JsonPrimitive) ?: JsonNull)
            put("previewTruncated", previewTruncated?.let(::JsonPrimitive) ?: JsonNull)
            put("sensitive", sensitive?.let(::JsonPrimitive) ?: JsonNull)
            put("inspectionIssues", JsonArray(inspectionIssues.take(12).map { JsonPrimitive(diagnosticLabel(it, 48)) }))
            put("tree", attempt.tree.finish(outcome, inspectionIssues))
        }
    }

    @Synchronized fun endSample(sample: ReadDiagnosticSample, outcome: String, snapshotId: String?, window: ReadDiagnosticWindow) {
        if (!isRecording()) return
        append("sample_end") {
            put("sampleId", sample.id); put("sample", sample.index)
            put("startedElapsedMs", sample.startedAt); put("durationMs", now() - sample.startedAt)
            put("outcome", diagnosticCode(outcome))
            put("snapshotId", snapshotId?.let { JsonPrimitive(diagnosticLabel(it, 64)) } ?: JsonNull)
            put("window", diagnosticWindow(window))
        }
        if (samples >= 7) stop(ReadDiagnosticStop.SAMPLE_LIMIT)
        else if (attempts >= 32) stop(ReadDiagnosticStop.ATTEMPT_LIMIT)
    }

    @Synchronized fun event(type: Int, windowId: Int, contentChanges: Int, windowChanges: Int, eventUptimeMs: Long, window: ReadDiagnosticWindow) {
        if (!isRecording()) return
        if (events >= 128) { eventsDropped++; return }
        events++
        append("event") {
            put("event", events); put("eventType", type); put("eventWindowId", windowId)
            put("contentChangeTypes", contentChanges); put("windowChanges", windowChanges)
            put("eventUptimeMs", eventUptimeMs); put("eventClock", "uptimeMillis")
            put("window", diagnosticWindow(window))
        }
    }

    @Synchronized fun stop(reason: ReadDiagnosticStop) {
        if (stopReason == null) { stopReason = reason; stoppedAt = now() }
    }

    /** Retained data remains available after STOP/expiry, but may be taken only once. */
    @Synchronized fun finish(): List<String> {
        if (taken) return emptyList()
        isRecording()
        stop(ReadDiagnosticStop.FINISHED)
        val summary = record("end") {
            put("reason", checkNotNull(stopReason).name); put("stoppedElapsedMs", stoppedAt)
            put("samples", samples); put("attempts", attempts); put("events", events)
            put("eventsDropped", eventsDropped); put("truncated", stopReason == ReadDiagnosticStop.BYTE_LIMIT)
        }
        // append() reserves space for this bounded terminal record.
        val result = lines.toList() + summary
        lines.clear(); bytes = 0; taken = true
        return result
    }

    private fun append(type: String, fields: JsonObjectBuilder.() -> Unit) {
        if (stopReason != null || taken) return
        val line = record(type, fields)
        val size = line.toByteArray(Charsets.UTF_8).size + 1
        if (bytes + size > maxBytes - 2048) { stop(ReadDiagnosticStop.BYTE_LIMIT); return }
        lines += line; bytes += size
    }

    private fun record(type: String, fields: JsonObjectBuilder.() -> Unit): String = buildJsonObject {
        put("schemaVersion", 1); put("recordType", type); put("diagnosticRunId", runId)
        put("taskId", diagnosticLabel(token.sessionId, 64)); put("epoch", token.epoch)
        put("package", diagnosticLabel(targetPackage, 256))
        put("timestampMs", wallTime()); put("receivedElapsedMs", now()); put("clock", "elapsedRealtime")
        fields()
    }.toString()
}

/** Owned by one read attempt; only bounded scalar structure enters the hash and gap records. */
internal class ReadDiagnosticTree(private val enabled: () -> Boolean = { true }) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val gaps = mutableListOf<JsonObject>()
    private var visits = 0
    private var inspected = 0
    private var visible = 0
    private var emitted = 0
    private var protectedCount = 0
    private var foreign = 0
    private var missing = 0
    private var scrollable = 0
    private var nodeRefreshSucceeded = 0
    private var nodeRefreshFailed = 0
    private var rootChildren: Int? = null
    private var rootHash: String? = null
    private var focusedPath: String? = null
    private var result: JsonObject? = null

    private fun canRecord() = result == null && enabled()

    fun nodeRefresh(succeeded: Boolean) {
        if (!canRecord()) return
        if (succeeded) nodeRefreshSucceeded++ else nodeRefreshFailed++
    }
    fun visited() { if (canRecord()) visits++ }
    fun protectedSubtree(path: List<Int>, isVisible: Boolean = false, wasEmitted: Boolean = false) {
        if (!canRecord()) return
        protectedCount++
        if (isVisible) visible++
        if (wasEmitted) emitted++
        hash(buildJsonObject { put("path", diagnosticPath(path)); put("protected", true) })
    }
    fun foreign(path: List<Int>) { if (canRecord()) { foreign++; hash(buildJsonObject { put("path", diagnosticPath(path)); put("foreign", true) }) } }

    fun node(path: List<Int>, role: String, viewId: String, bounds: PhoneBounds, isVisible: Boolean,
             children: Int, isScrollable: Boolean, isFocused: Boolean, wasEmitted: Boolean) {
        if (!canRecord()) return
        inspected++
        if (isVisible) visible++
        if (wasEmitted) emitted++
        if (isScrollable) scrollable++
        if (isFocused && focusedPath == null) focusedPath = diagnosticPath(path)
        val node = buildJsonObject {
            put("path", diagnosticPath(path)); put("class", diagnosticLabel(role, 160)); put("resourceId", diagnosticLabel(viewId, 256))
            put("bounds", diagnosticBounds(bounds)); put("visible", isVisible); put("childCount", children)
            put("scrollable", isScrollable); put("focused", isFocused)
        }
        if (path.isEmpty()) { rootChildren = children; rootHash = sha256(node.toString()) }
        hash(node)
    }

    fun gap(path: List<Int>, role: String, viewId: String, bounds: PhoneBounds, isVisible: Boolean, children: Int, index: Int) {
        if (!canRecord()) return
        missing++
        val entry = buildJsonObject {
            put("parentPath", diagnosticPath(path)); put("class", diagnosticLabel(role, 160)); put("resourceId", diagnosticLabel(viewId, 256))
            put("parentBounds", diagnosticBounds(bounds)); put("parentVisible", isVisible)
            put("childCount", children); put("unavailableChildIndex", index)
        }
        hash(entry)
        if (gaps.size < 20) gaps += entry
    }

    fun finish(outcome: String, issues: List<String>): JsonObject {
        result?.let { return it }
        hash(buildJsonObject { put("outcome", diagnosticCode(outcome)); put("issues", JsonArray(issues.take(12).map { JsonPrimitive(diagnosticLabel(it, 48)) })) })
        return buildJsonObject {
            put("hashSchema", "structural_v1"); put("structureHash", digest.digest().hex())
            put("rootNodeHash", rootHash?.let(::JsonPrimitive) ?: JsonNull)
            put("rootChildCount", rootChildren?.let(::JsonPrimitive) ?: JsonNull)
            put("visitedNodeCount", visits); put("visibleNodeCount", visible); put("emittedNodeCount", emitted)
            put("inspectedNodeCount", inspected); put("totalNodeCountKnown", false)
            put("protectedSubtrees", protectedCount); put("foreignSubtrees", foreign)
            put("unavailableChildCount", missing); put("scrollableNodeCount", scrollable)
            put("nodeRefreshSucceeded", nodeRefreshSucceeded); put("nodeRefreshFailed", nodeRefreshFailed)
            put("focusedNodePath", focusedPath?.let(::JsonPrimitive) ?: JsonNull)
            put("gaps", JsonArray(gaps)); put("gapsOmitted", missing - gaps.size)
        }.also { result = it }
    }

    private fun hash(value: JsonObject) { digest.update(value.toString().toByteArray(Charsets.UTF_8)); digest.update('\n'.code.toByte()) }
}

private fun diagnosticWindow(value: ReadDiagnosticWindow) = buildJsonObject {
    put("windowId", value.windowId?.let(::JsonPrimitive) ?: JsonNull)
    put("identity", value.identity); put("revision", value.revision)
    put("windowCount", value.windowCount?.let(::JsonPrimitive) ?: JsonNull)
}
private fun diagnosticBounds(value: PhoneBounds) = JsonArray(listOf(value.left, value.top, value.right, value.bottom).map(::JsonPrimitive))
private fun diagnosticPath(path: List<Int>) = "/" + path.take(41).joinToString("/")
private fun diagnosticLabel(value: String, length: Int) = value.take(length).replace(Regex("[\\r\\n\\t]"), " ")
private fun diagnosticCode(value: String) = value.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "UNKNOWN"
private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
