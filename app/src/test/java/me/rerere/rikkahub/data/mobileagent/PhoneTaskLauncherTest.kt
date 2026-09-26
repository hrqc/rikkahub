package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PhoneTaskLauncherTest {
    private class Fixture(timeoutMillis: Long = 1_000) : AutoCloseable {
        val backend = FakeBackend()
        private var sequence = 0
        val controller = PhoneController(
            backend, "com.example.agent", newId = { "session${++sequence}" },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), settleMillis = 0,
        )
        val launcher = PhoneTaskLauncher(controller, backend, timeoutMillis)
        var modelCalls = 0
        fun start() = controller.start("chat", "assistant", "com.example.target")
        suspend fun submit(token: PhoneSessionToken) {
            launcher.prepare(token)
            launcher.requireReady(token)
            modelCalls++
        }
        override fun close() = controller.close()
    }

    private class FakeBackend : PhoneBackend {
        override val state = MutableStateFlow(PhoneBackendState(true, 1, "com.example.agent", 1, false))
        val opened = CompletableDeferred<Unit>()
        var opens = 0
        var reads = 0
        var acceptsOpen = true
        var duringOpen: () -> Unit = {}
        override fun isTargetAllowed(packageName: String) = packageName == "com.example.target"
        override fun showSessionNotice(token: PhoneSessionToken, targetPackage: String, onStop: (PhoneBackendStopReason) -> Unit) = true
        override fun endSessionNotice() = Unit
        override fun invalidate() = Unit
        override suspend fun observe(permit: PhonePermit): PhoneObservation {
            reads++
            error("Preparation must not read the OEM permission window")
        }
        override suspend fun execute(permit: PhonePermit, observation: PhoneObservation?, action: PhoneAction): PhoneBackendResult {
            assertEquals(PhoneAction.OpenApp, action)
            assertTrue(permit.isValid())
            opens++
            state.value = state.value.copy(foregroundPackage = "com.oem.permission", windowId = 2)
            duringOpen()
            opened.complete(Unit)
            return PhoneBackendResult(acceptsOpen, "launch dispatched")
        }
    }

    @Test fun `model submission waits for the target package and window without reopening or reading permission UI`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val task = async(start = CoroutineStart.UNDISPATCHED) { f.submit(token) }
            f.backend.opened.await()
            yield()
            assertEquals(0, f.modelCalls)
            assertFalse(task.isCompleted)
            assertEquals(1, f.backend.opens)
            assertEquals(0, f.backend.reads)
            f.backend.state.value = f.backend.state.value.copy(foregroundPackage = "com.example.target", windowId = null)
            yield()
            assertEquals(0, f.modelCalls)
            assertFalse(task.isCompleted)
            f.backend.state.value = f.backend.state.value.copy(windowId = 3)
            withTimeout(1_000) { task.await() }
            assertEquals(1, f.modelCalls)
            assertEquals(1, f.backend.opens)
            assertEquals(0, f.backend.reads)
        }
    }

    @Test fun `stop lock and service loss during preparation never submit the model task`() = runBlocking {
        listOf("stop", "lock", "disconnect").forEach { cause ->
            Fixture().use { f ->
                val token = f.start()
                val task = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.submit(token) } }
                f.backend.opened.await()
                when (cause) {
                    "stop" -> f.controller.stop()
                    "lock" -> f.backend.state.value = f.backend.state.value.copy(locked = true)
                    else -> f.backend.state.value = f.backend.state.value.copy(connected = false)
                }
                assertTrue(withTimeout(1_000) { task.await() }.isFailure)
                assertEquals(0, f.modelCalls)
                assertEquals(1, f.backend.opens)
                assertEquals(0, f.backend.reads)
                assertNotEquals(PhoneSessionStatus.RUNNING, f.controller.state.value.status)
            }
        }
    }

    @Test fun `preparation timeout pauses with an explicit no model request reason`() = runBlocking {
        Fixture(timeoutMillis = 30).use { f ->
            val result = runCatching { f.submit(f.start()) }
            assertEquals("TARGET_PREPARATION_TIMEOUT", (result.exceptionOrNull() as PhoneControlException).code)
            assertEquals(0, f.modelCalls)
            assertEquals(1, f.backend.opens)
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            assertTrue(f.controller.state.value.detail.contains("等待目标应用超时"))
            assertTrue(f.controller.state.value.detail.contains("未发送模型请求"))
        }
    }

    @Test fun `already paused controller is not automatically resumed after open acceptance`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.duringOpen = { f.controller.pause() }
            val result = runCatching { f.submit(token) }
            assertTrue(result.isFailure)
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            assertEquals(0, f.modelCalls)
            assertEquals(1, f.backend.opens)
        }
    }

    @Test fun `rejected app launch pauses and does not repeatedly open it`() = runBlocking {
        Fixture().use { f ->
            f.backend.acceptsOpen = false
            val result = runCatching { f.submit(f.start()) }
            assertEquals("TARGET_OPEN_REJECTED", (result.exceptionOrNull() as PhoneControlException).code)
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            assertEquals(0, f.modelCalls)
            assertEquals(1, f.backend.opens)
        }
    }

    @Test fun `cancelled preparation cannot pause a newer authorization`() = runBlocking {
        Fixture().use { f ->
            val old = f.start()
            val task = async(start = CoroutineStart.UNDISPATCHED) { f.submit(old) }
            f.backend.opened.await()
            val newer = f.start()
            task.cancelAndJoin()
            assertEquals(newer, f.controller.activeToken("chat", "assistant"))
            assertEquals(0, f.modelCalls)
            assertEquals(listOf("start"), f.controller.state.value.audit.map { it.operation })
        }
    }

    @Test fun `cancelling active preparation revokes it without calling the model`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val task = async(start = CoroutineStart.UNDISPATCHED) { f.submit(token) }
            f.backend.opened.await()
            task.cancelAndJoin()
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            assertEquals(0, f.modelCalls)
            assertNull(f.controller.activeToken("chat", "assistant"))
        }
    }
}
