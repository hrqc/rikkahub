package me.rerere.rikkahub.data.mobileagent

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID

/** Explicit USB-only observation of an already foreground target. Never opens or operates that app. */
@RunWith(AndroidJUnit4::class)
class PhoneDynamicUiDiagnosticTest {
    @Test
    fun captureCurrentForegroundWithoutActions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("显式传入 diagnostic=true 才采样", arguments.getString("diagnostic") == "true")
        val target = arguments.getString("targetPackage").orEmpty()
        val variantName = arguments.getString("diagnosticVariant")
        val variant = variantName?.let { name ->
            ReadDiagnosticProfile.entries.singleOrNull { it.name == name }
                ?: error("Unsupported diagnostic variant")
        }
        assertTrue("目标必须是明确指定的测试应用", target in setOf(
            "com.jingdong.app.mall", "com.heytap.browser", "com.taobao.taobao",
            "com.xunmeng.pinduoduo", "com.sankuai.meituan", instrumentation.context.packageName,
        ))
        val controller = GlobalContext.get().get<PhoneController>()
        val backend = GlobalContext.get().get<PhoneBackend>() as AccessibilityPhoneBackend
        val waitMillis = arguments.getString("waitForServiceMillis")?.toLongOrNull()
            ?.coerceIn(1_000, 120_000) ?: 8_000L
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "[DynamicUiDiagnostic] waiting for already-authorized accessibility; no page actions\n")
        })
        assertTrue("无障碍未连接，未执行采样", withTimeoutOrNull(waitMillis) {
            while (!backend.state.value.connected) delay(100)
            true
        } == true)
        assertTrue("锁屏时不采样", !backend.state.value.locked)
        assertTrue("通知不可用，未建立诊断会话",
            NotificationManagerCompat.from(instrumentation.targetContext).areNotificationsEnabled())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "[DynamicUiDiagnostic] waiting for the specified foreground window metadata\n")
        })
        assertTrue("目标窗口元数据尚未就绪，未执行采样", withTimeoutOrNull(waitMillis) {
            while (backend.state.value.connected && !backend.state.value.locked &&
                backend.state.value.foregroundPackage != target) delay(100)
            backend.state.value.connected && !backend.state.value.locked &&
                backend.state.value.foregroundPackage == target
        } == true)
        assertEquals("请先把指定普通页面放在前台；测试不会打开目标", target, backend.state.value.foregroundPackage)

        // The controller retains the same foreground, token, STOP and privacy checks as a real task.
        val token = controller.start(
            conversationId = "diagnostic-${UUID.randomUUID()}", assistantId = "local-diagnostic",
            targetPackage = target, useRoot = false, allowScreenshots = false, replaceExisting = false,
        )
        var capture: ReadDiagnosticCapture? = null
        val schedule = mutableListOf<String>()
        var successfulObservations = 0
        var scrollOnlyObservations = 0
        var successfulVariantReads = 0
        var outcome = "not_started"
        var diagnosticActions: Int? = null
        try {
            delay(300) // Let the existing status overlay attach before establishing the time origin.
            assertEquals("诊断开始前任务已失效", token, controller.state.value.token)
            assertEquals(PhoneSessionStatus.RUNNING, controller.state.value.status)
            val activeCapture = backend.beginReadDiagnostics(token, variant ?: ReadDiagnosticProfile.DEFAULT)
            capture = activeCapture
            outcome = "running"
            val started = SystemClock.elapsedRealtime()
            val offsets = listOf(0L, 100L, 250L, 500L, 1_000L, 2_000L, 3_500L)
            for ((index, offset) in offsets.withIndex()) {
                val beforeWait = SystemClock.elapsedRealtime() - started
                if (beforeWait < offset) delay(offset - beforeWait)
                val actual = SystemClock.elapsedRealtime() - started
                val next = offsets.getOrNull(index + 1) ?: 5_500L
                val skipped = actual >= next
                schedule += buildJsonObject {
                    put("schemaVersion", 1)
                    put("recordType", "schedule")
                    put("diagnosticRunId", activeCapture.runId)
                    put("sampleIndex", index)
                    put("timeOrigin", "diagnostic_start_not_scroll")
                    put("clock", "elapsedRealtime")
                    put("plannedOffsetMs", offset)
                    put("actualOffsetMs", actual)
                    put("decision", if (skipped) "skipped_overdue" else "observe")
                }.toString()
                if (skipped) continue
                if (controller.state.value.token != token || controller.state.value.status != PhoneSessionStatus.RUNNING) {
                    outcome = "session_invalidated"
                    break
                }
                try {
                    if (variant != null) {
                        val result = backend.captureReadDiagnosticVariant(activeCapture)
                        if (result == "COMPLETE") successfulVariantReads++
                        else if (result !in setOf("INCOMPLETE", "INVALIDATED")) {
                            outcome = result
                            break
                        }
                        // Diagnostic variants collect metadata only; they never publish phone observations.
                        continue
                    }
                    val observed = controller.observe(token)
                    if (observed.scrollOnly) {
                        scrollOnlyObservations++
                        outcome = "scroll_only_available"
                        break // This diagnostic never exercises even the restricted scroll action.
                    }
                    if (observed.sensitive || observed.truncated) {
                        outcome = "incomplete_or_sensitive"
                        break
                    }
                    successfulObservations++
                } catch (error: PhoneControlException) {
                    outcome = error.code
                    break // No restart, resume or action replay to fill the remaining time slots.
                }
            }
            if (outcome == "running") outcome = "schedule_completed"
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } catch (failure: Throwable) {
            outcome = "diagnostic_failed"
            throw failure
        } finally {
            try {
                val session = controller.state.value.takeIf { it.token?.sessionId == token.sessionId }
                diagnosticActions = session?.actionsUsed
                if (session == null) outcome = "session_replaced"
                val lines = capture?.let(backend::finishReadDiagnostics).orEmpty()
                val exportedLines = lines + schedule + buildJsonObject {
                    put("schemaVersion", 1)
                    put("recordType", "test_outcome")
                    put("diagnosticRunId", capture?.runId.orEmpty())
                    put("packageName", target)
                    put("observationSuccesses", successfulObservations)
                    put("scrollOnlyObservations", scrollOnlyObservations)
                    put("diagnosticReadSuccesses", successfulVariantReads)
                    put("captureMode", if (variant == null) "production_observe" else "read_only_variant")
                    put("diagnosticVariant", variant?.name ?: "none")
                    put("outcome", outcome)
                    put("sessionStatusBeforeCleanup", session?.status?.name ?: "replaced")
                    put("actionsUsed", diagnosticActions)
                    put("businessTaskAccepted", false)
                }.toString()
                assertTrue("完整诊断导出超过 128 KiB，未发送记录",
                    exportedLines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 } <= 128 * 1024)
                exportedLines.forEach { line ->
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("dynamic_ui_jsonl_b64", Base64.encodeToString(line.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                    })
                }
            } finally {
                // Even failed exports must release this session; never stop its replacement.
                if (controller.state.value.token?.sessionId == token.sessionId) {
                    controller.stopForConversation(token.conversationId)
                }
            }
        }
        assertTrue("诊断未建立，不能记作已采样", capture != null)
        diagnosticActions?.let { assertEquals("只读诊断不能派发任何动作", 0, it) }
        // A completed capture may contain a real PAGE_UNSTABLE failure. It is diagnostic evidence, not a pass for that page.
    }
}
