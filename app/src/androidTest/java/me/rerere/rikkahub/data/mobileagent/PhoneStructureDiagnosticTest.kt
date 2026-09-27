package me.rerere.rikkahub.data.mobileagent

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Separate local-only structural export. Never exports node text or executable snapshots. */
@RunWith(AndroidJUnit4::class)
class PhoneStructureDiagnosticTest {
    @Test fun exportCurrentJdStructureWithoutActions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("必须显式启用结构导出", arguments.getString("exportStructure") == "true")
        val profile = ReadDiagnosticProfile.entries.singleOrNull { it.name == arguments.getString("diagnosticVariant") }
        assertTrue("必须显式指定诊断读取配置", profile != null)
        val controller = GlobalContext.get().get<PhoneController>()
        val backend = GlobalContext.get().get<PhoneBackend>() as AccessibilityPhoneBackend
        val waitMillis = arguments.getString("waitForServiceMillis")?.toLongOrNull()?.coerceIn(1_000, 120_000) ?: 8_000L
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "[StructureDiagnostic] waiting for authorized service and foreground JD; no actions or text export\n")
        })
        assertTrue("前台或服务未就绪", withTimeoutOrNull(waitMillis) {
            while (!backend.state.value.let { it.connected && !it.locked && it.foregroundPackage == TARGET }) delay(100)
            true
        } == true)
        val token = controller.start("structure-${UUID.randomUUID()}", "local-structure-diagnostic", TARGET,
            useRoot = false, allowScreenshots = false, replaceExisting = false)
        var handle: ReadDiagnosticCapture? = null
        var outcome = "not_started"
        var relativeFile: String? = null
        var sha256: String? = null
        var byteCount: Int? = null
        var actionsUsed: Int? = null
        var createdFile: File? = null
        var fileCommitted = false
        try {
            delay(300)
            val active = backend.beginReadDiagnostics(token, checkNotNull(profile))
            handle = active
            val result = backend.captureReadDiagnosticStructure(active)
            outcome = result.first
            val contents = result.second
            if (contents != null) {
                outcome = "FILE_WRITE_FAILED"
                assertEquals("导出前授权已改变", token, controller.state.value.token)
                assertEquals(PhoneSessionStatus.RUNNING, controller.state.value.status)
                val bytes = contents.toByteArray(Charsets.UTF_8)
                assertTrue("结构导出超过上限", bytes.size <= 256 * 1024)
                val directory = File(instrumentation.targetContext.cacheDir, "phone-structure-debug")
                assertTrue("本地目录不可用", directory.isDirectory || directory.mkdirs())
                val retainedBytes = directory.listFiles().orEmpty().sumOf { it.length() }
                assertTrue("结构缓存达到上限，保留既有记录", retainedBytes + bytes.size <= 4 * 1024 * 1024)
                val file = File(directory, "structure-${UUID.randomUUID()}.json")
                assertTrue("不能覆盖已有记录", file.createNewFile())
                createdFile = file
                file.writeBytes(bytes)
                currentCoroutineContext().ensureActive()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                currentCoroutineContext().ensureActive()
                if (controller.state.value.token != token || controller.state.value.status != PhoneSessionStatus.RUNNING) {
                    outcome = "SESSION_INVALIDATED"
                } else {
                    relativeFile = "cache/phone-structure-debug/${file.name}"
                    sha256 = digest
                    byteCount = bytes.size
                    fileCommitted = true
                    outcome = result.first
                }
            } else if (outcome in setOf("COMPLETE", "INCOMPLETE")) outcome = "STRUCTURE_EXPORT_UNAVAILABLE"
        } catch (cancelled: CancellationException) {
            outcome = "CANCELLED"
            throw cancelled
        } catch (_: IOException) {
            outcome = "FILE_WRITE_FAILED"
        } catch (failure: PhoneControlException) {
            outcome = failure.code.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "CONTROL_EXCEPTION"
        } finally {
            actionsUsed = controller.state.value.takeIf { it.token?.sessionId == token.sessionId }?.actionsUsed
            try { handle?.let(backend::finishReadDiagnostics) }
            finally {
                try {
                    val current = controller.state.value.token
                    if (current?.sessionId == token.sessionId) controller.stopIfCurrent(checkNotNull(current), "只读结构导出已结束")
                } finally {
                    // Only remove this invocation's new, uncommitted file. Preserve earlier captures.
                    if (!fileCommitted) createdFile?.let { assertTrue("未提交结构文件清理失败", !it.exists() || it.delete()) }
                }
            }
            val metadata = buildJsonObject {
                put("schemaVersion", 1)
                put("recordType", "structure_export_result")
                put("outcome", outcome)
                put("profile", profile?.name)
                put("relativeCacheFile", relativeFile)
                put("sha256", sha256)
                put("bytes", byteCount)
                put("controllerActionsUsed", actionsUsed)
                put("businessTaskAccepted", false)
                put("containsText", false)
            }.toString()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("structure_export_result_b64", Base64.encodeToString(metadata.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            })
        }
        actionsUsed?.let { assertEquals("结构导出不得执行手机动作", 0, it) }
    }

    companion object { private const val TARGET = "com.jingdong.app.mall" }
}
