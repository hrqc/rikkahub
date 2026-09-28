package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.rikkahub.data.mobileagent.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class PhoneChatCoordinatorTest {
    private class Fixture(
        val apps: List<PhoneTargetApp> = listOf(PhoneTargetApp("com.example.calc", "计算器")),
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        selectRoot: suspend () -> Boolean = { false },
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val backend = Backend()
        val controller = PhoneController(backend, "com.example.agent", scope = scope, settleMillis = 0)
        val intents = PhoneIntentStore()
        val host = Host()
        val coordinator = PhoneChatCoordinator(
            scope, host, controller, backend, intents,
            loadTargets = { apps }, resolveTargets = { name, available -> PhoneTargetAppRepository.resolve(name, available) },
            preparationTimeoutMillis = 1_000,
            selectRootForTask = selectRoot,
        )
        private var sequence = 0
        init {
            host.invalidate = { intents.invalidate("chat"); controller.stopForConversation("chat") }
            coordinator.setVisible(true)
        }
        fun propose(text: String, target: String = "计算器", summary: String = "模型提供的摘要"): PhoneIntentProposal? {
            val binding = PhoneIntentBinding("binding${++sequence}", "chat", "assistant", "user$sequence", text)
            intents.begin(binding)
            val proposal = intents.propose(binding, target, summary)
            intents.complete(binding)
            return proposal
        }
        suspend fun await(phase: PhoneChatPhase) = withTimeout(1_000) { coordinator.state.first { it.phase == phase } }
        override fun close() {
            coordinator.setVisible(false)
            controller.close()
            scope.cancel()
        }
    }

    /** Main.immediate starts inline, but yield posts until the current UI callback returns. */
    private class QueuedMainDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun isDispatchNeeded(context: CoroutineContext) = false
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    private class Host : PhoneChatHost {
        override val context = MutableStateFlow(PhoneChatContext("chat", "assistant", "assistant", true))
        var priorJob: Job? = null
        val submitted = mutableListOf<Pair<PhoneIntentBinding, PhoneSessionToken>>()
        val runs = mutableListOf<CompletableJob>()
        var invalidate: () -> Unit = {}
        var invalidations = 0
        override suspend fun awaitGenerationIdle() { priorJob?.join() }
        override fun invalidatePendingInputs() { invalidations++; invalidate() }
        override fun sendExecution(binding: PhoneIntentBinding, token: PhoneSessionToken): Job {
            submitted += binding to token
            return Job().also(runs::add)
        }
    }

    private class Backend : PhoneBackend {
        override val state = MutableStateFlow(PhoneBackendState(true, 1, "com.example.agent", 1, false))
        var opens = 0
        var observations = 0
        override fun isTargetAllowed(packageName: String) = packageName.startsWith("com.example.")
        override fun showSessionNotice(token: PhoneSessionToken, targetPackage: String, onStop: (PhoneBackendStopReason) -> Unit) = true
        override fun endSessionNotice() = Unit
        override fun invalidate() = Unit
        override suspend fun observe(permit: PhonePermit): PhoneObservation {
            observations++
            return PhoneObservation("snapshot$observations", permit.targetPackage, 2, 1, System.currentTimeMillis(), emptyList(), false, false, "page")
        }
        override suspend fun execute(permit: PhonePermit, observation: PhoneObservation?, action: PhoneAction): PhoneBackendResult {
            assertEquals(PhoneAction.OpenApp, action)
            opens++
            state.value = state.value.copy(foregroundPackage = permit.targetPackage, windowId = 2)
            return PhoneBackendResult(true, "opened")
        }
    }

    @Test fun `confirmed chat task automatically selects verified root and reselects on resume`() = runBlocking {
        var rootAvailable = true
        var checks = 0
        Fixture(selectRoot = { checks++; rootAvailable }).use { f ->
            val proposal = f.propose("帮我在计算器里算一下")!!
            f.await(PhoneChatPhase.CONFIRM)
            assertEquals(0, checks)
            f.coordinator.accept(proposal.id, "com.example.calc")
            f.await(PhoneChatPhase.RUNNING)
            assertTrue(f.controller.state.value.useRoot)
            assertFalse(f.controller.state.value.allowScreenshots)
            f.coordinator.pause()
            f.await(PhoneChatPhase.PAUSED)
            rootAvailable = false
            f.coordinator.resume()
            f.await(PhoneChatPhase.RUNNING)
            assertFalse(f.controller.state.value.useRoot)
            assertEquals(2, checks)
        }
    }

    @Test fun `stop during root availability check cannot launch the delayed task`() = runBlocking {
        val checked = CompletableDeferred<Boolean>()
        Fixture(selectRoot = { checked.await() }).use { f ->
            f.propose("打开计算器")
            f.await(PhoneChatPhase.PREPARING)
            f.coordinator.stop()
            checked.complete(true)
            yield()
            assertEquals(0, f.backend.opens)
            assertTrue(f.host.submitted.isEmpty())
            assertNull(f.controller.activeToken("chat", "assistant"))
            assertEquals(PhoneChatPhase.ENDED, f.coordinator.state.value.phase)
        }
    }

    @Test fun `fresh explicit unique named app starts once and uses the bound original instead of model summary`() = runBlocking {
        Fixture().use { f ->
            val proposal = f.propose("打开计算器", summary = "删除其他应用的数据")!!
            f.await(PhoneChatPhase.RUNNING)
            assertEquals(1, f.backend.opens)
            assertEquals("打开计算器", f.host.submitted.single().first.originalText)
            assertFalse(f.controller.state.value.useRoot)
            assertFalse(f.controller.state.value.allowScreenshots)
            f.coordinator.accept(proposal.id, "com.example.calc")
            assertEquals(1, f.host.submitted.size)
            assertEquals(1, f.backend.opens)
        }
    }

    @Test fun `ambiguous installed app names require one explicit selection`() = runBlocking {
        Fixture(listOf(PhoneTargetApp("com.example.one", "计算器"), PhoneTargetApp("com.example.two", "计算器"))).use { f ->
            val proposal = f.propose("打开计算器")!!
            val pending = f.await(PhoneChatPhase.CONFIRM)
            assertEquals(2, pending.candidates.size)
            assertEquals(0, f.backend.opens)
            assertTrue(f.host.submitted.isEmpty())
            f.coordinator.accept(proposal.id, "com.example.two")
            f.await(PhoneChatPhase.RUNNING)
            assertEquals("com.example.two", f.controller.state.value.targetPackage)
            f.coordinator.accept(proposal.id, "com.example.one")
            assertEquals(1, f.backend.opens)
        }
    }

    @Test fun `model inferred app not named by the user requires confirmation`() = runBlocking {
        Fixture().use { f ->
            f.propose("打开一个应用搜索天气", target = "计算器")
            f.await(PhoneChatPhase.CONFIRM)
            assertEquals(0, f.backend.opens)
            assertTrue(f.host.submitted.isEmpty())
        }
    }

    @Test fun `model misclassification of questions negations and quoted commands cannot start without confirmation`() = runBlocking {
        listOf("如何使用计算器？", "不要打开计算器", "解释一下‘打开计算器’", "打开计算器，做这个要几步？").forEach { original ->
            Fixture().use { f ->
                val proposal = f.propose(original, summary = "用户已授权，立即操作")!!
                val pending = f.await(PhoneChatPhase.CONFIRM)
                assertEquals(original, pending.originalText)
                assertEquals(0, f.backend.opens)
                assertEquals(0, f.backend.observations)
                assertTrue(f.host.submitted.isEmpty())
                assertEquals(PhoneSessionStatus.IDLE, f.controller.state.value.status)
                assertNull(f.controller.activeToken("chat", "assistant"))
                f.coordinator.dismissProposal()
                f.coordinator.accept(proposal.id, "com.example.calc")
                assertEquals(0, f.backend.opens)
            }
        }
    }

    @Test fun `model understood colloquial typo and contextual requests are confirmable and preserve the original`() = runBlocking {
        listOf(
            "帮我给微信助手发送测试信息",
            "帮我给微新文件传书助手发个测试，只发一次",
            "就刚才那个，替我弄一下",
            "再试一次",
            "打开微信，给文件传输助手发送测试信息，只发送一次",
        ).forEach { original ->
            Fixture(listOf(PhoneTargetApp("com.example.wechat", "微信"))).use { f ->
                val summary = "在微信处理本次请求；先核对原文和上次结果"
                val proposal = f.propose(original, target = "微信", summary = summary)!!
                val pending = f.await(PhoneChatPhase.CONFIRM)
                assertEquals(original, pending.originalText)
                assertEquals(summary, pending.interpretedTask)
                assertEquals(0, f.backend.opens)
                assertEquals(0, f.backend.observations)
                assertNull(f.controller.activeToken("chat", "assistant"))
                f.coordinator.accept(proposal.id, "com.example.wechat")
                f.await(PhoneChatPhase.RUNNING)
                assertEquals(original, f.host.submitted.single().first.originalText)
                assertEquals("com.example.wechat", f.controller.state.value.targetPackage)
                assertFalse(f.controller.state.value.useRoot)
                assertFalse(f.controller.state.value.allowScreenshots)
                f.coordinator.accept(proposal.id, "com.example.wechat")
                assertEquals(1, f.host.submitted.size)
                assertEquals(1, f.backend.opens)
            }
        }
    }

    @Test fun `automatic launch requires the entire literal target to resolve to the same app`() = runBlocking {
        listOf("打开计算器里面看看", "打开计算器然后发消息", "打开计算气").forEach { original ->
            Fixture().use { f ->
                f.propose(original, target = "计算器")
                f.await(PhoneChatPhase.CONFIRM)
                assertEquals(0, f.backend.opens)
                assertTrue(f.host.submitted.isEmpty())
            }
        }
        Fixture(listOf(PhoneTargetApp("com.example.calc", "计算器"), PhoneTargetApp("com.example.clock", "时钟"))).use { f ->
            f.propose("打开计算器", target = "时钟")
            f.await(PhoneChatPhase.CONFIRM)
            assertEquals(0, f.backend.opens)
        }
    }

    @Test fun `proposal completion waits for its chat generation before opening the app`() = runBlocking {
        Fixture().use { f ->
            val previous = Job()
            f.host.priorJob = previous
            f.propose("打开计算器")
            assertEquals(0, f.backend.opens)
            assertTrue(f.host.submitted.isEmpty())
            previous.complete()
            f.await(PhoneChatPhase.RUNNING)
            assertEquals(1, f.backend.opens)
        }
    }

    @Test fun `task generation completion revokes only its own grant`() = runBlocking {
        Fixture().use { f ->
            f.propose("打开计算器")
            f.await(PhoneChatPhase.RUNNING)
            val generation = f.host.runs.single()
            generation.complete()
            f.await(PhoneChatPhase.ENDED)
            assertEquals(PhoneSessionStatus.STOPPED, f.controller.state.value.status)
            assertNull(f.controller.activeToken("chat", "assistant"))
        }
    }

    @Test fun `failed or cancelled generation pauses instead of reporting normal completion`() = runBlocking {
        for (failed in listOf(true, false)) Fixture().use { f ->
            f.propose("打开计算器")
            f.await(PhoneChatPhase.RUNNING)
            val generation = f.host.runs.single()
            if (failed) generation.completeExceptionally(IllegalStateException("synthetic generation failure"))
            else generation.cancel()
            val state = f.await(PhoneChatPhase.PAUSED)
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            assertTrue(state.detail.contains("结果可能未知"))
            assertFalse(state.detail.contains("本轮模型执行已结束"))
            assertNull(f.controller.activeToken("chat", "assistant"))
            assertEquals(1, f.host.submitted.size)
        }
    }

    @Test fun `late failure of an old generation cannot pause a replacement phone session`() = runBlocking {
        Fixture().use { f ->
            f.propose("打开计算器")
            f.await(PhoneChatPhase.RUNNING)
            val oldGeneration = f.host.runs.single()
            val replacement = f.controller.start("other-chat", "assistant", "com.example.calc")
            oldGeneration.completeExceptionally(IllegalStateException("late old failure"))
            yield()
            assertEquals(PhoneSessionStatus.RUNNING, f.controller.state.value.status)
            assertEquals(replacement, f.controller.activeToken("other-chat", "assistant"))
        }
    }

    @Test fun `pause retains original task and resume uses a fresh token`() = runBlocking {
        Fixture().use { f ->
            f.propose("打开计算器")
            f.await(PhoneChatPhase.RUNNING)
            val oldToken = f.host.submitted.single().second
            f.coordinator.pause()
            f.await(PhoneChatPhase.PAUSED)
            assertTrue(f.host.runs.first().isCancelled)
            f.coordinator.resume()
            f.await(PhoneChatPhase.RUNNING)
            assertEquals(2, f.host.submitted.size)
            val resumed = f.host.submitted.last()
            assertEquals("打开计算器", resumed.first.originalText)
            assertNotEquals(oldToken.epoch, resumed.second.epoch)
        }
    }

    @Test fun `registered overlay controls resume the model task with a fresh token and stop it`() = runBlocking {
        Fixture().use { f ->
            f.propose("打开计算器")
            f.await(PhoneChatPhase.RUNNING)
            val oldToken = f.host.submitted.single().second
            assertTrue(f.controller.requestTaskControl(oldToken, PhoneTaskControl.PAUSE))
            f.await(PhoneChatPhase.PAUSED)
            val pausedToken = f.controller.state.value.token!!
            assertTrue(f.host.runs.first().isCancelled)
            assertFalse(f.controller.requestTaskControl(oldToken, PhoneTaskControl.RESUME))
            assertTrue(f.controller.taskControls.canResume(pausedToken))
            assertTrue(f.controller.requestTaskControl(pausedToken, PhoneTaskControl.RESUME))
            f.await(PhoneChatPhase.RUNNING)
            assertEquals(2, f.host.submitted.size)
            val resumedToken = f.host.submitted.last().second
            assertNotEquals(oldToken.epoch, resumedToken.epoch)
            assertEquals("打开计算器", f.host.submitted.last().first.originalText)
            assertTrue(f.controller.requestTaskControl(resumedToken, PhoneTaskControl.STOP))
            f.await(PhoneChatPhase.ENDED)
            assertTrue(f.host.runs.last().isCancelled)
            assertFalse(f.controller.taskControls.canResume(f.controller.state.value.token!!))
        }
    }

    @Test fun `closing the task removes its overlay resume route`() = runBlocking {
        Fixture().use { f ->
            f.propose("打开计算器")
            f.await(PhoneChatPhase.RUNNING)
            f.coordinator.pause()
            f.await(PhoneChatPhase.PAUSED)
            val pausedToken = f.controller.state.value.token!!
            assertTrue(f.controller.taskControls.canResume(pausedToken))
            f.coordinator.setVisible(false)
            assertFalse(f.controller.taskControls.canResume(pausedToken))
            assertFalse(f.controller.requestTaskControl(pausedToken, PhoneTaskControl.RESUME))
            assertEquals(1, f.host.submitted.size)
        }
    }

    @Test fun `overlay revoke is synchronous but generation cancellation and resumed send leave the callback stack`() = runBlocking {
        val dispatcher = QueuedMainDispatcher()
        Fixture(dispatcher = dispatcher).use { f ->
            f.propose("打开计算器")
            dispatcher.drain() // combine also yields while starting the initial proposal.
            f.await(PhoneChatPhase.RUNNING)
            val original = f.host.submitted.single().second
            val oldJob = f.host.runs.single()
            var insideDispatch = false
            oldJob.invokeOnCompletion { assertFalse("Completion ran under the controller callback", insideDispatch) }

            insideDispatch = true
            assertTrue(f.controller.requestTaskControl(original, PhoneTaskControl.PAUSE))
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            assertNull(f.controller.activeToken("chat", "assistant"))
            assertFalse(oldJob.isCancelled)
            insideDispatch = false
            dispatcher.drain()
            assertTrue(oldJob.isCancelled)

            val paused = f.controller.state.value.token!!
            assertTrue(f.controller.requestTaskControl(paused, PhoneTaskControl.RESUME))
            assertEquals(paused, f.controller.state.value.token)
            assertEquals(1, f.host.submitted.size)
            dispatcher.drain()
            assertEquals(2, f.host.submitted.size)
            val resumed = f.host.submitted.last().second
            val resumedJob = f.host.runs.last()
            resumedJob.invokeOnCompletion { assertFalse("STOP completed under the controller callback", insideDispatch) }
            insideDispatch = true
            assertTrue(f.controller.requestTaskControl(resumed, PhoneTaskControl.STOP))
            assertEquals(PhoneSessionStatus.STOPPED, f.controller.state.value.status)
            assertFalse(resumedJob.isCancelled)
            insideDispatch = false
            dispatcher.drain()
            assertTrue(resumedJob.isCancelled)
        }
    }

    @Test fun `deferred cancellation captures old jobs and cannot cancel a replacement task`() = runBlocking {
        val dispatcher = QueuedMainDispatcher()
        Fixture(dispatcher = dispatcher).use { f ->
            f.propose("打开计算器")
            dispatcher.drain()
            f.await(PhoneChatPhase.RUNNING)
            val oldToken = f.host.submitted.single().second
            val oldJob = f.host.runs.single()
            assertTrue(f.controller.requestTaskControl(oldToken, PhoneTaskControl.STOP))
            assertFalse(oldJob.isCancelled)
            // A different entry can register its replacement before this owner's posted cleanup.
            val replacement = f.controller.start("chat", "assistant", "com.example.calc")
            val replacementJob = f.host.sendExecution(f.host.submitted.first().first.copy(id = "replacement"), replacement)
            dispatcher.drain()
            assertTrue(oldJob.isCancelled)
            assertFalse(replacementJob.isCancelled)
            assertEquals(replacement, f.controller.activeToken("chat", "assistant"))
        }
    }

    @Test fun `new input and stale callbacks cannot stop a replacement task`() = runBlocking {
        Fixture().use { f ->
            f.propose("打开计算器")
            f.await(PhoneChatPhase.RUNNING)
            val oldJob = f.host.runs.single()
            f.coordinator.abandonCurrent()
            val replacement = f.controller.start("other-chat", "other-assistant", "com.example.calc")
            oldJob.complete()
            f.coordinator.stop()
            assertEquals(replacement, f.controller.activeToken("other-chat", "other-assistant"))
            assertEquals(1, f.host.submitted.size)
        }
    }

    @Test fun `switching assistant or leaving chat invalidates pending confirmation`() = runBlocking {
        listOf("assistant", "chat").forEach { kind ->
            Fixture(listOf(PhoneTargetApp("com.example.one", "计算器"), PhoneTargetApp("com.example.two", "计算器"))).use { f ->
                val proposal = f.propose("打开计算器")!!
                f.await(PhoneChatPhase.CONFIRM)
                if (kind == "assistant") f.host.context.value = f.host.context.value.copy(selectedAssistantId = "other")
                else f.coordinator.setVisible(false)
                f.coordinator.accept(proposal.id, "com.example.one")
                assertEquals(0, f.backend.opens)
                assertTrue(f.host.submitted.isEmpty())
            }
        }
    }

    @Test fun `leaving before a proposal publishes prevents it starting on return`() = runBlocking {
        Fixture().use { f ->
            val binding = PhoneIntentBinding("unpublished", "chat", "assistant", "user", "打开计算器")
            f.intents.begin(binding)
            f.intents.propose(binding, "计算器", "打开")
            f.coordinator.setVisible(false)
            f.intents.complete(binding)
            f.coordinator.setVisible(true)
            yield()
            assertFalse(f.intents.isCurrent(binding))
            assertEquals(0, f.backend.opens)
            assertTrue(f.host.submitted.isEmpty())
        }
    }

    @Test fun `switching assistant after consumption while waiting for generation invalidates the launch`() = runBlocking {
        Fixture().use { f ->
            val prior = Job()
            f.host.priorJob = prior
            f.propose("打开计算器")
            f.await(PhoneChatPhase.PREPARING)
            f.host.context.value = f.host.context.value.copy(selectedAssistantId = "other")
            f.await(PhoneChatPhase.HIDDEN)
            f.host.context.value = f.host.context.value.copy(selectedAssistantId = "assistant")
            prior.complete()
            yield()
            assertEquals(0, f.backend.opens)
            assertTrue(f.host.submitted.isEmpty())
        }
    }

    @Test fun `late disposal of an older visible owner does not invalidate its replacement`() = runBlocking {
        Fixture().use { old ->
            Fixture().use { newer ->
                val invalidations = old.host.invalidations
                newer.propose("打开计算器")
                newer.await(PhoneChatPhase.RUNNING)
                val token = newer.host.submitted.single().second
                old.coordinator.setVisible(false)
                assertEquals(invalidations, old.host.invalidations)
                assertEquals(token, newer.controller.activeToken("chat", "assistant"))
            }
        }
    }
}
