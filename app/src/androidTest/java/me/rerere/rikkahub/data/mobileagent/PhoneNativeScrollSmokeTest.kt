package me.rerere.rikkahub.data.mobileagent

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One production-controller scroll request; explicit USB engineering smoke, never an LLM task. */
@RunWith(AndroidJUnit4::class)
class PhoneNativeScrollSmokeTest {
    @Test
    fun scrollCurrentJdProductListOnce(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("显式 nativeScrollSmoke=true 才允许单次原生滚动", arguments.getString("nativeScrollSmoke") == "true")
        assumeTrue("仅 debug 测试允许本烟测", BuildConfig.DEBUG)
        val started = SystemClock.elapsedRealtime()
        val runId = UUID.randomUUID().toString()
        var controller: PhoneController? = null
        var token: PhoneSessionToken? = null
        var before: PhoneObservation? = null
        var after: PhoneObservation? = null
        var selectedNode: PhoneNode? = null
        var actionRequests = 0
        var accepted: Boolean? = null
        var screenChanged: Boolean? = null
        var outcome = "not_started"
        var exceptionObserved = false
        var harnessFailed = false
        var cleanupOutcome = "no_session"
        val activityChecks = mutableListOf<JsonObject>()

        fun checkActivity(phase: String) {
            val check = inspectProductListActivity(instrumentation)
            activityChecks += buildJsonObject {
                put("phase", phase)
                put("outcome", check.outcome)
                put("stage", check.stage.name)
                put("exceptionType", check.exceptionType?.name)
                put("causeType", check.causeType?.name)
                put("bytesRead", check.bytesRead)
                put("elapsedMs", SystemClock.elapsedRealtime() - started)
            }
            if (check.exceptionType != null || check.causeType != null) exceptionObserved = true
            if (check.outcome != "MATCH") throw SmokeRefusal(check.outcome)
        }

        try {
            if (arguments.getString("targetPackage")?.let { it != TARGET_PACKAGE } == true ||
                arguments.getString("diagnosticVariant") != null) throw SmokeRefusal("INVALID_SMOKE_ARGUMENTS")
            val activeController = GlobalContext.get().get<PhoneController>()
            controller = activeController
            val backend = GlobalContext.get().get<PhoneBackend>()
            val waitMillis = arguments.getString("waitForServiceMillis")?.toLongOrNull()?.coerceIn(1_000, 120_000) ?: 8_000L
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "[NativeScrollSmoke] waiting for authorized service and foreground JD; at most one native scroll request\n")
            })
            val ready = withTimeoutOrNull(waitMillis) {
                while (!backend.state.value.let { it.connected && !it.locked && it.foregroundPackage == TARGET_PACKAGE }) delay(100)
                true
            } == true
            if (!ready) throw SmokeRefusal("FOREGROUND_OR_SERVICE_NOT_READY")
            if (!NotificationManagerCompat.from(instrumentation.targetContext).areNotificationsEnabled()) {
                throw SmokeRefusal("NOTIFICATIONS_UNAVAILABLE")
            }
            val activeToken = activeController.start(
                conversationId = "native-scroll-smoke-$runId", assistantId = "local-native-scroll-smoke",
                targetPackage = TARGET_PACKAGE, useRoot = false, allowScreenshots = false, replaceExisting = false,
            )
            token = activeToken
            delay(300) // Allow the existing STOP overlay to attach; no target-app action.
            requireSmokeSession(activeController, activeToken)
            checkActivity("before_observe")
            val observed = activeController.observe(activeToken)
            before = observed
            if (observed.sensitive || (observed.truncated && (!observed.scrollOnly || !canUseNativeScrollOnly(
                    observed.sensitive, observed.truncated, observed.inspectionIssues, observed.nodes)))) {
                throw SmokeRefusal("OBSERVATION_NOT_ELIGIBLE")
            }
            // Also reject visible verification prompts on a complete page without logging their text.
            if (observed.nodes.any { node -> listOf("京东验证", "安全风险", "请完成验证", "完成安全验证", "滑动验证", "拖动滑块", "点击进行验证").any { marker ->
                    marker in node.text || marker in node.description
                } }) throw SmokeRefusal("VERIFICATION_CONTENT_VISIBLE")
            val candidate = observed.nodes.filter(::isProductScrollNode)
                .maxByOrNull { it.bounds.bottom.toLong() - it.bounds.top }
                ?: throw SmokeRefusal("NO_PRODUCT_SCROLL_NODE")
            selectedNode = candidate
            checkActivity("before_scroll")
            requireSmokeSession(activeController, activeToken)
            // The production backend checks current handles/revision again. With useRoot=false,
            // complete-page Scroll cannot enter its Root/coordinate fallback; partial Scroll has none.
            actionRequests = 1
            val result = activeController.act(activeToken, observed.id, PhoneAction.Scroll(candidate.id, forward = true))
            accepted = result.accepted
            screenChanged = result.screenChanged
            after = result.observation // An accepted action already includes the production after-observe.
            outcome = when {
                !result.accepted -> "NATIVE_SCROLL_REJECTED"
                result.observation == null -> "ACCEPTED_POST_OBSERVATION_UNAVAILABLE"
                result.screenChanged -> "ACCEPTED_SCREEN_CHANGED"
                else -> "ACCEPTED_NO_CONFIRMED_CHANGE"
            }
            if (!result.accepted) {
                // False is never retried. A single read may document the unchanged/changed page.
                requireSmokeSession(activeController, activeToken)
                checkActivity("before_rejected_action_post_observe")
                after = activeController.observe(activeToken)
            }
        } catch (refusal: SmokeRefusal) {
            outcome = refusal.code
        } catch (_: TimeoutCancellationException) {
            outcome = "PRODUCTION_OPERATION_TIMEOUT"
            exceptionObserved = true
        } catch (_: CancellationException) {
            outcome = "SMOKE_CANCELLED"
            exceptionObserved = true
            harnessFailed = true
        } catch (failure: PhoneControlException) {
            outcome = failure.code.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "PRODUCTION_CONTROL_EXCEPTION"
            exceptionObserved = true
        } catch (_: Throwable) {
            outcome = "HARNESS_EXCEPTION"
            exceptionObserved = true
            harnessFailed = true
        } finally {
            val ownedToken = token
            val session = controller?.state?.value?.takeIf { ownedToken != null && it.token?.sessionId == ownedToken.sessionId }
            if (session != null && (session.useRoot || session.allowScreenshots || session.actionsUsed !in 0..1)) harnessFailed = true
            try {
                val currentToken = controller?.state?.value?.token
                if (ownedToken != null && currentToken != null && currentToken.sessionId == ownedToken.sessionId) {
                    controller?.stopIfCurrent(currentToken, "单次原生滚动烟测已结束")
                    cleanupOutcome = "owned_session_stopped"
                } else if (ownedToken != null) cleanupOutcome = "replacement_session_untouched"
            } catch (_: Throwable) {
                cleanupOutcome = "cleanup_failed"
                harnessFailed = true
            }
            val exported = buildJsonObject {
                put("schemaVersion", 1)
                put("recordType", "native_scroll_smoke_result")
                put("diagnosticRunId", runId)
                put("captureMode", "production_controller_native_scroll_smoke")
                put("targetPackage", TARGET_PACKAGE)
                put("expectedActivity", TARGET_ACTIVITY)
                put("activityChecks", JsonArray(activityChecks))
                put("elapsedMs", SystemClock.elapsedRealtime() - started)
                put("outcome", outcome)
                put("exceptionObserved", exceptionObserved)
                put("junitInvariantChecksExpectedToPass", !harnessFailed && actionRequests in 0..1)
                put("actionRequestsIssued", actionRequests)
                put("controllerActionsUsed", session?.actionsUsed)
                put("accepted", accepted)
                put("screenChanged", screenChanged)
                put("engineeringScrollPassed", !harnessFailed && accepted == true && screenChanged == true && after != null)
                put("businessTaskAccepted", false)
                put("useRoot", false)
                put("allowScreenshots", false)
                put("sessionStatusBeforeCleanup", session?.status?.name ?: "none_or_replaced")
                put("cleanupOutcome", cleanupOutcome)
                put("selectedNode", selectedNode?.let { node -> buildJsonObject {
                    put("id", node.id)
                    put("role", node.role)
                    put("viewId", node.viewId)
                    put("bounds", buildJsonObject {
                        put("left", node.bounds.left)
                        put("top", node.bounds.top)
                        put("right", node.bounds.right)
                        put("bottom", node.bounds.bottom)
                    })
                } } ?: JsonNull)
                put("before", before?.let(::observationMetadata) ?: JsonNull)
                put("after", after?.let(::observationMetadata) ?: JsonNull)
            }.toString()
            assertTrue("烟测元数据超过 16 KiB，未输出", exported.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("native_scroll_smoke_json_b64", Base64.encodeToString(exported.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            })
        }
        assertTrue("烟测最多发出一个 controller 动作请求", actionRequests in 0..1)
        assertFalse("烟测工具异常或违反固定边界；请核对元数据", harnessFailed)
        // JUnit completion validates this collector, not platform acceptance, useful scrolling or shopping success.
    }

    private fun requireSmokeSession(controller: PhoneController, token: PhoneSessionToken) {
        val state = controller.state.value
        if (state.token != token || state.status != PhoneSessionStatus.RUNNING || state.targetPackage != TARGET_PACKAGE ||
            state.useRoot || state.allowScreenshots) throw SmokeRefusal("SMOKE_SESSION_INVALIDATED")
    }

    private fun observationMetadata(observation: PhoneObservation): JsonObject = buildJsonObject {
        put("windowId", observation.windowId)
        put("windowRevision", observation.windowRevision)
        put("nodeCount", observation.nodes.size)
        put("scrollableNodeCount", observation.nodes.count(::isNativeScrollOnlyNode))
        put("productScrollNodeCount", observation.nodes.count(::isProductScrollNode))
        put("scrollableNodes", JsonArray(observation.nodes.filter { it.scrollable }.take(10).map { node ->
            buildJsonObject {
                put("role", node.role)
                put("viewId", node.viewId)
                put("bounds", buildJsonObject {
                    put("left", node.bounds.left)
                    put("top", node.bounds.top)
                    put("right", node.bounds.right)
                    put("bottom", node.bounds.bottom)
                })
            }
        }))
        put("scrollOnly", observation.scrollOnly)
        put("truncated", observation.truncated)
        put("sensitive", observation.sensitive)
        put("previewTruncated", observation.previewTruncated)
        // No text, descriptions, raw node signatures, content fingerprints or screenshots.
    }

    private fun isProductScrollNode(node: PhoneNode): Boolean {
        val height = node.bounds.bottom.toLong() - node.bounds.top
        val width = node.bounds.right.toLong() - node.bounds.left
        return isNativeScrollOnlyNode(node) && !node.role.endsWith("HorizontalScrollView", ignoreCase = true) &&
            height >= 400 && height * 3 > width
    }

    private class SmokeRefusal(val code: String) : IllegalStateException(code)
    private enum class ActivityCheckStage { GET_UI_AUTOMATION, EXECUTE_SHELL_COMMAND, READ_DUMP, PARSE_ACTIVITY }
    private enum class ActivityExceptionType { SecurityException, IllegalStateException, UnsupportedOperationException, IOException, OTHER }
    private data class ActivityCheck(
        val outcome: String,
        val bytesRead: Int?,
        val stage: ActivityCheckStage,
        val exceptionType: ActivityExceptionType? = null,
        val causeType: ActivityExceptionType? = null,
    )

    private fun fixedExceptionType(error: Throwable?): ActivityExceptionType = when (error) {
        is SecurityException -> ActivityExceptionType.SecurityException
        is IllegalStateException -> ActivityExceptionType.IllegalStateException
        is UnsupportedOperationException -> ActivityExceptionType.UnsupportedOperationException
        is IOException -> ActivityExceptionType.IOException
        else -> ActivityExceptionType.OTHER
    }

    private fun inspectProductListActivity(instrumentation: Instrumentation): ActivityCheck {
        val stopped = AtomicBoolean(false)
        val pipe = AtomicReference<ParcelFileDescriptor?>(null)
        val stage = AtomicReference(ActivityCheckStage.GET_UI_AUTOMATION)
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "native-scroll-smoke-activity").apply { isDaemon = true } }
        val future = executor.submit<ActivityCheck> {
            fun failed(error: Throwable, bytesRead: Int? = null) = ActivityCheck(
                "ACTIVITY_${stage.get().name}_EXCEPTION", bytesRead, stage.get(), fixedExceptionType(error),
            )
            val automation = try {
                instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    ?: return@submit ActivityCheck("UI_AUTOMATION_UNAVAILABLE", 0, stage.get())
            } catch (error: Throwable) {
                return@submit failed(error, 0)
            }
            if (stopped.get()) return@submit ActivityCheck("ACTIVITY_CHECK_CANCELLED", 0, stage.get())
            stage.set(ActivityCheckStage.EXECUTE_SHELL_COMMAND)
            val descriptor = try {
                automation.executeShellCommand("dumpsys activity activities")
            } catch (error: Throwable) {
                return@submit failed(error, 0)
            }
            pipe.set(descriptor)
            stage.set(ActivityCheckStage.READ_DUMP)
            var bytesRead = 0
            val dump = try {
                if (stopped.get()) {
                    pipe.getAndSet(null)?.close()
                    return@submit ActivityCheck("ACTIVITY_CHECK_CANCELLED", 0, stage.get())
                }
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4_096)
                    while (!stopped.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_DUMP_BYTES) return@submit ActivityCheck("ACTIVITY_DUMP_LIMIT", output.size(), stage.get())
                        output.write(buffer, 0, count)
                        bytesRead = output.size()
                    }
                    if (stopped.get()) return@submit ActivityCheck("ACTIVITY_CHECK_CANCELLED", output.size(), stage.get())
                    output.toString(Charsets.UTF_8.name())
                }
            } catch (error: Throwable) {
                return@submit failed(error, bytesRead)
            } finally {
                pipe.compareAndSet(descriptor, null)
            }
            stage.set(ActivityCheckStage.PARSE_ACTIVITY)
            try {
                val lines = dump.lineSequence()
                    .filter { Regex("^\\s*topResumedActivity\\s*[:=]").containsMatchIn(it) }.toList()
                if (lines.size != 1) return@submit ActivityCheck("TOP_RESUMED_ACTIVITY_UNKNOWN", bytesRead, stage.get())
                val component = Regex("^\\s*topResumedActivity\\s*[:=]\\s*ActivityRecord\\{[^\\r\\n]*?\\s([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)(?:\\s|\\})")
                    .find(lines.single())?.groupValues?.get(1)
                ActivityCheck(when (component) {
                    null -> "TOP_RESUMED_ACTIVITY_UNKNOWN"
                    "$TARGET_PACKAGE/$TARGET_ACTIVITY" -> "MATCH"
                    else -> "TOP_RESUMED_ACTIVITY_MISMATCH"
                }, bytesRead, stage.get())
            } catch (error: Throwable) {
                failed(error, bytesRead)
            }
        }
        return try {
            future.get(ACTIVITY_CHECK_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            ActivityCheck("ACTIVITY_CHECK_TIMEOUT", null, stage.get())
        } catch (error: ExecutionException) {
            ActivityCheck("ACTIVITY_CHECK_EXECUTOR_EXCEPTION", null, stage.get(), fixedExceptionType(error), fixedExceptionType(error.cause))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ActivityCheck("ACTIVITY_CHECK_INTERRUPTED", null, stage.get())
        } finally {
            stopped.set(true)
            runCatching { pipe.getAndSet(null)?.close() }
            future.cancel(true)
            executor.shutdownNow() // Never wait for a stuck platform Binder; no subsequent action follows a failed check.
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "com.jingdong.app.mall"
        private const val TARGET_ACTIVITY = "com.jd.lib.search.view.Activity.ProductListActivity"
        private const val MAX_DUMP_BYTES = 128 * 1024
        private const val ACTIVITY_CHECK_MILLIS = 3_000L
    }
}
