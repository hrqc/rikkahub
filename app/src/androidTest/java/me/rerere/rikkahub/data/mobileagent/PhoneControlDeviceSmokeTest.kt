package me.rerere.rikkahub.data.mobileagent

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.mobileagent.fixture.PhoneFixtureActivity
import me.rerere.rikkahub.service.MobileAgentNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.koin.core.context.GlobalContext
import java.util.UUID

/** Run only against the bundled synthetic fixture after the user manually enables prerequisites. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PhoneControlDeviceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext
    private val fixturePackage get() = instrumentation.context.packageName
    private val controller get() = GlobalContext.get().get<PhoneController>()
    private val backend get() = GlobalContext.get().get<PhoneBackend>()

    @Test
    fun fixtureActionsAndStop() = runBlocking {
        runFixtureActions(useRoot = false)
    }

    @Test
    fun fixtureRootActionsAndStop() = runBlocking {
        assumeTrue(
            "Root 动作测试需显式传入 root=true；默认只测试无障碍路径",
            InstrumentationRegistry.getArguments().getString("root") == "true",
        )
        runFixtureActions(useRoot = true)
    }

    private suspend fun runFixtureActions(useRoot: Boolean) {
        requirePrerequisites()
        if (useRoot) requireExplicitRootProbe()
        report(if (useRoot) "starting Root fixture actions" else "starting accessibility fixture actions")
        launchFixture()

        var token: PhoneSessionToken? = null
        try {
            val activeToken = controller.start(
                conversationId = "device-smoke-${UUID.randomUUID()}",
                assistantId = "device-smoke-assistant",
                targetPackage = fixturePackage,
                useRoot = useRoot,
                allowScreenshots = false,
            )
            token = activeToken
            awaitObservation(activeToken, "首页") { it.hasText("手机控制测试首页") }

            performNodeAction(activeToken, "点击", { it.text == "点击测试" && it.clickable }) { PhoneAction.Click(it.id) }
            awaitObservation(activeToken, "点击结果") { it.hasText("点击次数：1") }
            report("click verified")

            performNodeAction(activeToken, "长按", { it.text == "点击测试" && it.clickable }) { PhoneAction.LongClick(it.id) }
            awaitObservation(activeToken, "长按结果") { it.hasText("长按次数：1") }
            report("long click verified")

            performNodeAction(activeToken, "中文输入", { it.editable && it.description == "测试输入框" }) {
                PhoneAction.InputText(it.id, "手机控制中文测试")
            }
            awaitObservation(activeToken, "中文输入结果") { screen ->
                screen.nodes.any { it.editable && it.text == "手机控制中文测试" }
            }
            report("Chinese input verified")

            performNodeAction(activeToken, "滚动", { it.scrollable && it.description == "测试滚动区域" }) {
                PhoneAction.Scroll(it.id, true)
            }
            awaitObservation(activeToken, "滚动结果") { screen ->
                screen.nodes.any { it.text.startsWith("滚动位置：") && it.text != "滚动位置：0" }
            }
            report("scroll verified")

            performNodeAction(activeToken, "进入第二页", { it.text == "打开测试第二页" && it.clickable }) { PhoneAction.Click(it.id) }
            awaitObservation(activeToken, "第二页") { it.hasText("测试第二页") }
            performNodeAction(activeToken, "返回", { it.text == "测试第二页" }) { PhoneAction.Back }
            awaitObservation(activeToken, "返回首页") { it.hasText("手机控制测试首页") }
            report("Back verified inside fixture package")

            if (useRoot) {
                val manager = targetContext.getSystemService(NotificationManager::class.java)
                val notification = manager.activeNotifications.firstOrNull {
                    it.id == MobileAgentNotifications.NOTIFICATION_ID
                }?.notification ?: throw AssertionError("本应用的手机控制通知不存在")
                val stop = notification.actions.orEmpty().firstOrNull { it.title?.toString() == "STOP" }
                    ?.actionIntent ?: throw AssertionError("控制通知缺少 STOP 动作")
                assertEquals("只能发送本应用创建的 STOP PendingIntent", targetContext.packageName, stop.creatorPackage)
                stop.send()
                val stopped = withTimeoutOrNull(3_000) {
                    while (controller.state.value.status != PhoneSessionStatus.STOPPED) delay(50)
                    true
                } == true
                assertTrue("真实通知 STOP 未在 3 秒内结束测试会话", stopped)
                assertEquals(activeToken.sessionId, controller.state.value.token?.sessionId)
                report("notification STOP PendingIntent and receiver verified")
            } else {
                controller.stopForConversation(activeToken.conversationId)
                report("direct controller STOP verified")
            }
            assertEquals(PhoneSessionStatus.STOPPED, controller.state.value.status)
            try {
                controller.observe(activeToken)
                fail("STOP 后旧 token 不得继续读取界面")
            } catch (error: PhoneControlException) {
                assertEquals("SESSION_INVALID", error.code)
            }
            assertEquals(null, controller.activeToken(activeToken.conversationId, activeToken.assistantId))
            report("STOP invalidated old token; device smoke completed")
        } finally {
            token?.let { started ->
                if (controller.state.value.token?.sessionId == started.sessionId) {
                    controller.stopForConversation(started.conversationId)
                }
            }
        }
    }

    @Test
    fun fixtureWindowScreenshotRemainsLocal() = runBlocking {
        requirePrerequisites()
        assumeTrue("受限目标窗口截图需要 API 34+；没有读取全屏或其他应用", Build.VERSION.SDK_INT >= 34 && backend.supportsScreenshot)
        launchFixture()
        var token: PhoneSessionToken? = null
        try {
            val activeToken = controller.start(
                conversationId = "device-screenshot-${UUID.randomUUID()}",
                assistantId = "device-smoke-assistant",
                targetPackage = fixturePackage,
                useRoot = false,
                allowScreenshots = true,
            )
            token = activeToken
            var screenshot: PhoneActionResult? = null
            for (attempt in 0 until 3) {
                val observation = awaitObservation(activeToken, "截图前确认目标首页") { it.hasText("手机控制测试首页") }
                try {
                    screenshot = controller.act(activeToken, observation.id, PhoneAction.Screenshot)
                    break
                } catch (error: PhoneControlException) {
                    if (error.code !in transientCodes) throw AssertionError("目标窗口截图失败：${error.code}; backend=[${backendMetadata()}]")
                }
                delay(250)
            }
            val result = screenshot ?: throw AssertionError("截图快照持续过期，已停止重试。backend=[${backendMetadata()}]")
            assertTrue("平台未接受截图，不能声称测试成功。backend=[${backendMetadata()}]", result.accepted)
            val uri = Uri.parse(result.screenshotUri ?: throw AssertionError("截图结果缺少本地 URI"))
            assertEquals("截图必须保留在本地 ContentProvider", "content", uri.scheme)
            assertEquals("截图只能由主应用私有文件提供器提供", "${targetContext.packageName}.fileprovider", uri.authority)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val stream = targetContext.contentResolver.openInputStream(uri)
                ?: throw AssertionError("本地截图 URI 无法读取")
            stream.use { BitmapFactory.decodeStream(it, null, options) }
            assertTrue("截图必须是尺寸有效的图片", options.outWidth > 0 && options.outHeight > 0)
            assertEquals("截图期间只能观察测试 APK", fixturePackage, backend.state.value.foregroundPackage)
            report("API 34+ fixture window screenshot decoded locally; no upload or model request")
        } finally {
            token?.let { started ->
                if (controller.state.value.token?.sessionId == started.sessionId) {
                    controller.stopForConversation(started.conversationId)
                }
            }
        }
    }

    /** root=true is the user's explicit authorization for the existing fixed, read-only UID probe. */
    private suspend fun requireExplicitRootProbe() {
        val repository = GlobalContext.get().get<DeviceCapabilityRepository>()
        if (repository.capabilities.value.root.state != RootState.ROOT_GRANTED) {
            assumeTrue("已有 Root 检测正在进行，请完成后重跑", !repository.capabilities.value.rootProbeRunning)
            report("root=true: checking existing Root authorization with the fixed read-only UID probe")
            val completed = try {
                withTimeoutOrNull(20_000) {
                    repository.requestRoot()
                    true
                } == true
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: Exception) {
                false
            }
            assumeTrue("只读 Root 检测未完成或超时；没有修改 SukiSU 授权设置", completed)
        }
        val root = repository.capabilities.value.root
        assumeTrue("Root 未确认（${root.state}）：${root.detail}；没有修改授权设置", root.state == RootState.ROOT_GRANTED)
    }

    private suspend fun launchFixture() {
        val waitMillis = InstrumentationRegistry.getArguments().getString("waitForFixtureMillis")
            ?.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(60_000L) ?: 8_000L
        assertTrue("测试目标必须是独立测试 APK", fixturePackage != targetContext.packageName)
        assertTrue("测试 APK 的 launcher Activity 必须可见", backend.isTargetAllowed(fixturePackage))
        val fixtureComponent = ComponentName(fixturePackage, PhoneFixtureActivity::class.java.name)
        @Suppress("DEPRECATION")
        val activityInfo = runCatching { targetContext.packageManager.getActivityInfo(fixtureComponent, 0) }.getOrNull()
        val resolved = activityInfo?.let {
            "package=${it.packageName}, name=${it.name}, process=${it.processName}, " +
                "uid=${it.applicationInfo?.uid}, exported=${it.exported}, enabled=${it.enabled}"
        } ?: "ActivityInfo unavailable"
        val before = backendMetadata()
        val startedAt = SystemClock.elapsedRealtime()
        val launchMetadata = "mainPackage=${targetContext.packageName}; testPackage=$fixturePackage; " +
            "requested=${fixtureComponent.flattenToShortString()}; resolved=[$resolved]; before=[$before]"
        // Launch only our explicit fixture component; never use cross-package startActivitySync.
        try {
            targetContext.startActivity(Intent().apply {
                component = fixtureComponent
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        } catch (error: Exception) {
            throw AssertionError("fixture 启动调用失败：${error.javaClass.simpleName}; $launchMetadata; after=[${backendMetadata()}]")
        }
        report("waiting up to ${waitMillis}ms for fixture foreground; any system confirmation remains manual")
        val fixtureReady = withTimeoutOrNull(waitMillis) {
            while (backend.state.value.foregroundPackage != fixturePackage) delay(200)
            true
        } == true
        val windowDiagnostics = if (fixtureReady) "" else try {
            withTimeoutOrNull(2_000) {
                (backend as? AccessibilityPhoneBackend)?.diagnosticMetadata()
            } ?: "metadata unavailable or timed out"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            "metadata failed (${error.javaClass.simpleName})"
        }
        assertTrue(
            "测试 APK 未被后端识别为前台；没有操作其他应用。$launchMetadata; " +
                "after=[${backendMetadata()}]; elapsedMillis=${SystemClock.elapsedRealtime() - startedAt}; " +
                "windowDiagnostics=[$windowDiagnostics]",
            fixtureReady,
        )
    }

    private suspend fun requirePrerequisites() {
        val waitMillis = InstrumentationRegistry.getArguments().getString("waitForServiceMillis")
            ?.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(120_000L) ?: 8_000L
        if (!backend.state.value.connected) {
            report("waiting up to ${waitMillis}ms for the user to enable accessibility; no permission changes")
        }
        val connected = withTimeoutOrNull(waitMillis) {
            while (!backend.state.value.connected) delay(200)
            true
        } == true
        assumeTrue("请先手动开启 Mobile Agent 无障碍服务，再重跑；测试不会授权。backend=[${backendMetadata()}]", connected)
        assumeTrue("请先解锁设备；测试不会操作锁屏", !backend.state.value.locked)
        assumeTrue("请先允许主应用通知；测试不会请求或授予权限", NotificationManagerCompat.from(targetContext).areNotificationsEnabled())
        assumeTrue("已有控机会话，请用户先自行结束；测试不会替换它", controller.state.value.status in setOf(
            PhoneSessionStatus.IDLE, PhoneSessionStatus.STOPPED, PhoneSessionStatus.EXPIRED,
        ))
    }

    private suspend fun awaitObservation(
        token: PhoneSessionToken,
        step: String,
        matches: (PhoneObservation) -> Boolean,
    ): PhoneObservation {
        repeat(6) {
            val foregroundPackage = backend.state.value.foregroundPackage
            if (foregroundPackage == null) {
                // A window transition may briefly have no active package. Wait without reading or dispatching.
                delay(250)
                return@repeat
            }
            assertEquals("测试不能进入其他应用：$step; backend=[${backendMetadata()}]", fixturePackage, foregroundPackage)
            try {
                val screen = controller.observe(token)
                assertEquals(fixturePackage, screen.packageName)
                assertFalse("fixture 界面应可完整读取：$step", screen.truncated || screen.sensitive)
                if (matches(screen)) return screen
            } catch (error: PhoneControlException) {
                if (error.code !in transientCodes) throw AssertionError("$step 失败：${error.code}; backend=[${backendMetadata()}]")
            }
            delay(250)
        }
        throw AssertionError("$step 在有限次数重新观察后仍未满足；没有盲目重复动作。backend=[${backendMetadata()}]")
    }

    private suspend fun performNodeAction(
        token: PhoneSessionToken,
        step: String,
        matches: (PhoneNode) -> Boolean,
        action: (PhoneNode) -> PhoneAction,
    ) {
        repeat(3) {
            val observation = awaitObservation(token, step) { it.nodes.any(matches) }
            val node = observation.nodes.first(matches)
            try {
                val result = controller.act(token, observation.id, action(node))
                assertTrue("平台未接受动作，不能声称测试成功：$step; backend=[${backendMetadata()}]", result.accepted)
                // Verify visible postconditions separately; accepted/screenChanged is not success.
                return
            } catch (error: PhoneControlException) {
                if (error.code !in transientCodes) throw AssertionError("$step 失败：${error.code}; backend=[${backendMetadata()}]")
            }
            delay(250)
        }
        throw AssertionError("$step 的快照持续过期，已停止重试。backend=[${backendMetadata()}]")
    }

    /** Metadata only: never include node text, descriptions, input, or image data in diagnostics. */
    private fun backendMetadata(): String = backend.state.value.let {
        val session = controller.state.value
        "connected=${it.connected}, foregroundPackage=${it.foregroundPackage}, windowId=${it.windowId}, " +
            "windowRevision=${it.windowRevision}, locked=${it.locked}, " +
            "sessionStatus=${session.status}, sessionDetail=${session.detail}"
    }

    private fun PhoneObservation.hasText(value: String) = nodes.any { it.text == value }

    private fun report(step: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "[PhoneControlDeviceSmoke] $step\n") })
    }

    private companion object {
        val transientCodes = setOf("STALE_WINDOW", "STALE_SNAPSHOT", "OBSERVATION_UNAVAILABLE", "WINDOW_TRANSITION")
    }
}
