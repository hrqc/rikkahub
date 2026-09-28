package me.rerere.rikkahub.data.mobileagent

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit local read-only smoke. Reports metadata only; never a shopping/business acceptance. */
@RunWith(AndroidJUnit4::class)
class PhoneObservedContentSmokeTest {
    @Test
    fun readCurrentJdObservedContentWithoutActions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("必须显式 observedContentSmoke=true 才运行", arguments.getString("observedContentSmoke") == "true")
        assumeTrue("仅 debug 构建允许只读烟测", BuildConfig.DEBUG)
        val startedAt = SystemClock.elapsedRealtime()
        val runId = UUID.randomUUID().toString()
        var controller: PhoneController? = null
        var token: PhoneSessionToken? = null
        var observed: PhoneObservation? = null
        var outcome = "NOT_STARTED"
        var harnessFailed = false
        var readValidated = false
        var cleanupOutcome = "NO_SESSION"
        var controllerActions: Int? = null
        var observationRequests = 0
        var observationsAfterCapture: Int? = null
        var observationsAfterPagination: Int? = null
        var contentNodeCount = 0
        var contentCharacters = 0
        var extraReadonlyNodeCount = 0
        var truncatedContentNodeCount = 0
        var quoteEvidenceChecks = 0
        var untruncatedQuoteEvidenceChecks = 0
        var safeJsonChecked = false
        val pages = mutableListOf<JsonObject>()

        try {
            if (arguments.getString("targetPackage")?.let { it != TARGET } == true ||
                arguments.getString("diagnosticVariant") != null) throw SmokeRefusal("INVALID_SMOKE_ARGUMENTS")
            val activeController = GlobalContext.get().get<PhoneController>()
            controller = activeController
            val backend = GlobalContext.get().get<PhoneBackend>()
            val waitMillis = arguments.getString("waitForServiceMillis")?.toLongOrNull()?.coerceIn(1_000, 120_000) ?: 8_000L
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "[ObservedContentSmoke] waiting for authorized service and foreground JD; no actions or text export\n")
            })
            val ready = withTimeoutOrNull(waitMillis) {
                while (!backend.state.value.let { it.connected && !it.locked && it.foregroundPackage == TARGET }) delay(100)
                true
            } == true
            if (!ready) throw SmokeRefusal("FOREGROUND_OR_SERVICE_NOT_READY")
            if (!NotificationManagerCompat.from(instrumentation.targetContext).areNotificationsEnabled()) {
                throw SmokeRefusal("NOTIFICATIONS_UNAVAILABLE")
            }
            val activeToken = activeController.start(
                conversationId = "observed-content-smoke-$runId", assistantId = "local-observed-content-smoke",
                targetPackage = TARGET, useRoot = false, allowScreenshots = false, replaceExisting = false,
            )
            token = activeToken
            delay(300) // Let the existing STOP overlay attach; no target-app action.
            requireSession(activeController, backend, activeToken)
            observationRequests = 1
            val current = activeController.observe(activeToken)
            observed = current
            if (current.sensitive || current.truncated || current.scrollOnly) throw SmokeRefusal("OBSERVATION_NOT_COMPLETE_SAFE")
            requireSession(activeController, backend, activeToken)
            invariant(current.packageName == TARGET, "OBSERVATION_TARGET_MISMATCH")
            invariant(current.readOnlyContent == null, "RAW_BODY_RETURNED_BY_CONTROLLER")
            invariant(current.nodes.size <= 100, "PREVIEW_NODE_LIMIT_EXCEEDED")
            if (!current.readOnlyContentAvailable) throw SmokeRefusal("CONTENT_UNAVAILABLE")
            observationsAfterCapture = activeController.state.value.observationsUsed
            val evidence = activeController.shoppingEvidence(activeToken).singleOrNull { it.snapshotId == current.id }
                ?: throw SmokeInvariant("OBSERVED_EVIDENCE_MISSING")
            invariant(evidence.packageName == TARGET, "EVIDENCE_TARGET_MISMATCH")
            invariant(evidence.nodes.map { it.nodeId }.distinct().size == evidence.nodes.size, "DUPLICATE_EVIDENCE_NODE")
            val evidenceById = evidence.nodes.associateBy { it.nodeId }
            val previewById = current.nodes.associateBy { it.id }
            val collected = mutableListOf<PhoneReadOnlyContentNode>()
            val seenIds = mutableSetOf<String>()
            val seenCursors = mutableSetOf<String>()
            var cursor = "0"
            while (true) {
                currentCoroutineContext().ensureActive()
                requireSession(activeController, backend, activeToken)
                invariant(pages.size < MAX_PAGES, "PAGE_LIMIT_EXCEEDED")
                invariant(seenCursors.add(cursor), "CURSOR_CYCLE")
                val page = activeController.readObservedContent(activeToken, current.id, cursor)
                invariant(page.snapshotId == current.id && page.packageName == TARGET, "PAGE_SOURCE_MISMATCH")
                invariant(page.source == "retained_observation" && !page.grantsActionPermission, "PAGE_CAPABILITY_MISMATCH")
                invariant(page.nodes.size <= 40, "PAGE_NODE_LIMIT_EXCEEDED")
                val characters = page.nodes.sumOf { it.text.length + it.description.length }
                invariant(characters <= 8_000, "PAGE_CHARACTER_LIMIT_EXCEEDED")
                invariant(page.contentTruncated == current.readOnlyContentTruncated, "CONTENT_TRUNCATION_MISMATCH")
                for (node in page.nodes) {
                    invariant(node.id.matches(NODE_ID) && seenIds.add(node.id), "INVALID_OR_DUPLICATE_CONTENT_NODE")
                    invariant(node.text.length <= 240 && node.description.length <= 240, "CONTENT_FIELD_LIMIT_EXCEEDED")
                    val source = evidenceById[node.id] ?: throw SmokeInvariant("CONTENT_NODE_NOT_IN_EVIDENCE")
                    invariant(source.text == node.text && source.description == node.description && source.truncated == node.truncated,
                        "CONTENT_EVIDENCE_VALUE_MISMATCH")
                    if (node.id.startsWith("n")) {
                        val preview = previewById[node.id] ?: throw SmokeInvariant("PREVIEW_ID_NOT_PRESERVED")
                        invariant(preview.text == node.text && preview.description == node.description, "PREVIEW_TEXT_MISMATCH")
                    } else {
                        invariant(node.id !in previewById, "READ_ONLY_ID_IN_ACTION_PREVIEW")
                        extraReadonlyNodeCount++
                    }
                    if (node.truncated) truncatedContentNodeCount++
                    val quote = node.text.takeIf { it.isNotBlank() }?.take(80)
                        ?: node.description.takeIf { it.isNotBlank() }?.take(80)
                    if (quote != null) {
                        invariant(source.text.contains(quote) || source.description.contains(quote), "QUOTE_NOT_IN_SOURCE_NODE")
                        quoteEvidenceChecks++
                        if (!node.truncated) untruncatedQuoteEvidenceChecks++
                    }
                    collected += node
                }
                contentNodeCount = collected.size
                contentCharacters += characters
                invariant(contentNodeCount <= 768 && contentCharacters <= 64_000, "CONTENT_TOTAL_LIMIT_EXCEEDED")
                pages += buildJsonObject {
                    put("page", pages.size + 1)
                    put("nodes", page.nodes.size)
                    put("characters", characters)
                    put("contentTruncated", page.contentTruncated)
                    put("hasNextCursor", page.nextCursor != null)
                }
                val next = page.nextCursor ?: break
                invariant(page.nodes.isNotEmpty() && next != cursor, "PAGE_DID_NOT_ADVANCE")
                cursor = next
            }
            requireSession(activeController, backend, activeToken)
            observationsAfterPagination = activeController.state.value.observationsUsed
            invariant(observationsAfterCapture == observationsAfterPagination, "PAGINATION_PERFORMED_NEW_OBSERVATION")
            invariant(collected.map { it.id } == evidence.nodes.map { it.nodeId }, "PAGINATION_OMITTED_OR_REORDERED_EVIDENCE")
            invariant(activeController.shoppingEvidence(activeToken).singleOrNull { it.snapshotId == current.id } == evidence,
                "EVIDENCE_CHANGED_DURING_PAGINATION")
            val json = Json { encodeDefaults = true }
            val safeWire = json.encodeToString(current)
            val attachedWire = json.encodeToString(current.copy(readOnlyContent = PhoneReadOnlyContent(collected, current.readOnlyContentTruncated)))
            invariant(!Json.parseToJsonElement(safeWire).jsonObject.containsKey("readOnlyContent") && safeWire == attachedWire,
                "TRANSIENT_BODY_SERIALIZED")
            safeJsonChecked = true
            currentCoroutineContext().ensureActive()
            requireSession(activeController, backend, activeToken)
            if (collected.isEmpty()) throw SmokeRefusal("COMPLETE_NO_BODY")
            readValidated = true
            outcome = "COMPLETE_CONTENT_VALIDATED"
        } catch (refusal: SmokeRefusal) {
            outcome = refusal.code
        } catch (failure: SmokeInvariant) {
            outcome = failure.code
            harnessFailed = true
        } catch (_: TimeoutCancellationException) {
            outcome = "PRODUCTION_OPERATION_TIMEOUT"
        } catch (_: CancellationException) {
            outcome = "SMOKE_CANCELLED"
        } catch (failure: PhoneControlException) {
            outcome = failure.code.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "PRODUCTION_CONTROL_EXCEPTION"
        } catch (_: Throwable) {
            outcome = "HARNESS_EXCEPTION"
            harnessFailed = true
        } finally {
            val ownedToken = token
            val ownedState = controller?.state?.value?.takeIf { ownedToken != null && it.token?.sessionId == ownedToken.sessionId }
            controllerActions = ownedState?.actionsUsed
            if (readValidated && (ownedState?.token != ownedToken || ownedState?.status != PhoneSessionStatus.RUNNING)) {
                readValidated = false
                outcome = "SMOKE_SESSION_INVALIDATED"
            }
            if (ownedState != null && (ownedState.useRoot || ownedState.allowScreenshots || ownedState.actionsUsed != 0)) {
                outcome = "READ_ONLY_BOUNDARY_VIOLATED"
                harnessFailed = true
            }
            try {
                val current = controller?.state?.value?.token
                if (ownedToken != null && current != null && current.sessionId == ownedToken.sessionId) {
                    controller?.stopIfCurrent(current, "只读正文分页烟测已结束")
                    cleanupOutcome = "OWNED_SESSION_STOPPED"
                } else if (ownedToken != null) {
                    cleanupOutcome = "REPLACEMENT_SESSION_UNTOUCHED"
                    readValidated = false
                    outcome = "SMOKE_SESSION_INVALIDATED"
                }
            } catch (_: Throwable) {
                cleanupOutcome = "CLEANUP_FAILED"
                harnessFailed = true
            }
        }

        val engineeringPassed = readValidated && !harnessFailed && outcome == "COMPLETE_CONTENT_VALIDATED" && controllerActions == 0
        val report = buildJsonObject {
            put("schemaVersion", 1)
            put("recordType", "observed_content_smoke_result")
            put("diagnosticRunId", runId)
            put("captureMode", "production_controller_retained_content")
            put("targetPackage", TARGET)
            put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
            put("outcome", outcome)
            put("engineeringReadPassed", engineeringPassed)
            put("businessTaskAccepted", false)
            put("containsText", false)
            put("useRoot", false)
            put("allowScreenshots", false)
            put("actionRequestsIssued", 0)
            put("actions", controllerActions)
            put("observationRequestsIssued", observationRequests)
            put("controllerObservationsAfterCapture", observationsAfterCapture)
            put("controllerObservationsAfterPagination", observationsAfterPagination)
            put("previewNodeCount", observed?.nodes?.size)
            put("previewTextNodeCount", observed?.nodes?.count { it.text.isNotBlank() || it.description.isNotBlank() })
            put("contentNodeCount", contentNodeCount)
            put("contentCharacters", contentCharacters)
            put("extraReadonlyNodeCount", extraReadonlyNodeCount)
            put("truncatedContentNodeCount", truncatedContentNodeCount)
            put("previewTruncated", observed?.previewTruncated)
            put("inspectionTruncated", observed?.truncated)
            put("scrollOnly", observed?.scrollOnly)
            put("sensitive", observed?.sensitive)
            put("contentTruncated", observed?.readOnlyContentTruncated)
            put("pageCount", pages.size)
            put("pages", JsonArray(pages))
            put("quoteEvidenceChecks", quoteEvidenceChecks)
            put("untruncatedQuoteEvidenceChecks", untruncatedQuoteEvidenceChecks)
            put("safeJsonTransientBodyAbsent", safeJsonChecked)
            put("beyondPreviewValidated", engineeringPassed && extraReadonlyNodeCount > 0)
            put("over100ContentNodesValidated", engineeringPassed && contentNodeCount > 100)
            put("cleanupOutcome", cleanupOutcome)
        }.toString()

        var createdFile: File? = null
        var fileCommitted = false
        var relativeFile: String? = null
        var sha256: String? = null
        var bytesWritten: Int? = null
        var exportOutcome = "METADATA_WRITE_FAILED"
        try {
            val bytes = report.toByteArray(Charsets.UTF_8)
            invariant(bytes.size <= 16 * 1024, "METADATA_SIZE_LIMIT")
            val directory = File(instrumentation.targetContext.cacheDir, "phone-observed-content-smoke")
            invariant(directory.isDirectory || directory.mkdirs(), "METADATA_DIRECTORY_UNAVAILABLE")
            invariant(directory.listFiles().orEmpty().sumOf { it.length() } + bytes.size <= 4 * 1024 * 1024, "METADATA_CACHE_LIMIT")
            val file = File(directory, "observed-content-$runId.json")
            invariant(file.createNewFile(), "METADATA_FILE_ALREADY_EXISTS")
            createdFile = file
            file.writeBytes(bytes)
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            bytesWritten = bytes.size
            relativeFile = "cache/phone-observed-content-smoke/${file.name}"
            fileCommitted = true
            exportOutcome = "METADATA_WRITTEN"
        } catch (_: Throwable) {
            harnessFailed = true
        } finally {
            if (!fileCommitted) {
                relativeFile = null
                sha256 = null
                bytesWritten = null
                try {
                    createdFile?.let { if (it.exists() && !it.delete()) exportOutcome = "UNCOMMITTED_METADATA_CLEANUP_FAILED" }
                } catch (_: Throwable) { exportOutcome = "UNCOMMITTED_METADATA_CLEANUP_FAILED" }
            }
            val result = buildJsonObject {
                put("schemaVersion", 1)
                put("recordType", "observed_content_smoke_export")
                put("outcome", outcome)
                put("exportOutcome", exportOutcome)
                put("engineeringReadPassed", engineeringPassed && fileCommitted && !harnessFailed)
                put("businessTaskAccepted", false)
                put("containsText", false)
                put("relativeCacheFile", relativeFile)
                put("sha256", sha256)
                put("bytes", bytesWritten)
            }.toString()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("observed_content_smoke_result_b64", Base64.encodeToString(result.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            })
        }
        assertFalse("只读烟测不变量或元数据保存失败；请检查固定结果码", harnessFailed)
        assumeTrue("未取得完整安全正文，只保留诊断结果，不计为烟测通过", engineeringPassed && fileCommitted)
    }

    private fun requireSession(controller: PhoneController, backend: PhoneBackend, token: PhoneSessionToken) {
        val state = controller.state.value
        val environment = backend.state.value
        if (state.token != token || state.status != PhoneSessionStatus.RUNNING || state.targetPackage != TARGET ||
            state.useRoot || state.allowScreenshots || !environment.connected || environment.locked || environment.foregroundPackage != TARGET) {
            throw SmokeRefusal("SMOKE_SESSION_INVALIDATED")
        }
        invariant(state.actionsUsed == 0, "READ_ONLY_BOUNDARY_VIOLATED")
    }

    private fun invariant(value: Boolean, fixedCode: String) {
        if (!value) throw SmokeInvariant(fixedCode)
    }

    private class SmokeRefusal(val code: String) : IllegalStateException(code)
    private class SmokeInvariant(val code: String) : IllegalStateException(code)

    companion object {
        private const val TARGET = "com.jingdong.app.mall"
        private const val MAX_PAGES = 24
        private val NODE_ID = Regex("[nr][0-9]+")
    }
}
