package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableJob
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

class PhoneChatCoordinatorTest {
    private class Fixture(val apps: List<PhoneTargetApp> = listOf(PhoneTargetApp("com.example.calc", "计算器"))) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val backend = Backend()
        val controller = PhoneController(backend, "com.example.agent", scope = scope, settleMillis = 0)
        val intents = PhoneIntentStore()
        val host = Host()
        val coordinator = PhoneChatCoordinator(
            scope, host, controller, backend, intents,
            loadTargets = { apps }, resolveTargets = { name, available -> PhoneTargetAppRepository.resolve(name, available) },
            preparationTimeoutMillis = 1_000,
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

    @Test fun `informational question never starts a grant`() = runBlocking {
        Fixture().use { f ->
            f.propose("如何使用计算器？")
            assertEquals(0, f.backend.opens)
            assertTrue(f.host.submitted.isEmpty())
            assertEquals(PhoneSessionStatus.IDLE, f.controller.state.value.status)
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
