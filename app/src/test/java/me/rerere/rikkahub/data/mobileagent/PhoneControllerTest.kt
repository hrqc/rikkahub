package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneControllerTest {
    private class Fixture(foregroundGraceMillis: Long = 750) : AutoCloseable {
        var time = 1_000L
        val backend = FakeBackend { time }
        private var sequence = 0
        val controller = PhoneController(
            backend, "com.example.agent", now = { time }, newId = { "session${++sequence}" },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), settleMillis = 0,
            foregroundGraceMillis = foregroundGraceMillis,
        )
        fun start(allowScreenshots: Boolean = false) = controller.start("chat", "assistant", "com.example.target", allowScreenshots = allowScreenshots)
        override fun close() = controller.close()
    }

    private class FakeBackend(private val now: () -> Long) : PhoneBackend {
        override val state = MutableStateFlow(PhoneBackendState(true, 1, "com.example.target", 7, false))
        override val supportsScreenshot = true
        var noticeAllowed = true
        var sensitive = false
        var truncated = false
        var password = false
        var text = "Safe button"
        var fingerprint = "page"
        var changes = true
        var accepts = true
        var reads = 0
        var actions = 0
        var invalidations = 0
        var noticesEnded = 0
        var onStop: (() -> Unit)? = null
        var beforeDispatch: suspend (PhonePermit) -> Unit = {}
        var beforeRead: suspend () -> Unit = {}
        override fun isTargetAllowed(packageName: String) = packageName == "com.example.target"
        override fun showSessionNotice(token: PhoneSessionToken, targetPackage: String, onStop: () -> Unit): Boolean {
            this.onStop = onStop
            return noticeAllowed
        }
        override fun endSessionNotice() { noticesEnded++ }
        override fun invalidate() { invalidations++ }
        override suspend fun observe(permit: PhonePermit): PhoneObservation {
            beforeRead()
            check(permit.isValid())
            reads++
            return PhoneObservation(
                "snapshot$reads", "com.example.target", 7, state.value.windowRevision, now(),
                listOf(PhoneNode("button", text = text, bounds = PhoneBounds(0, 0, 50, 50), clickable = true, password = password),
                    PhoneNode("editor", bounds = PhoneBounds(0, 50, 100, 100), editable = true),
                    PhoneNode("scroll", bounds = PhoneBounds(0, 100, 100, 500), scrollable = true)),
                truncated, sensitive, fingerprint,
            )
        }
        override suspend fun execute(permit: PhonePermit, observation: PhoneObservation?, action: PhoneAction): PhoneBackendResult {
            beforeDispatch(permit)
            if (!permit.isValid()) return PhoneBackendResult(false, "revoked")
            actions++
            if (changes) fingerprint = "changed$actions"
            return PhoneBackendResult(accepts, "platform result", if (action == PhoneAction.Screenshot) "content://test/window" else null)
        }
    }

    private inline fun expectCode(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: PhoneControlException) { assertEquals(code, error.code) }
    }

    @Test fun `ordinary chat has no phone token and rejected observations never read backend`() = runBlocking {
        Fixture().use { f ->
            assertNull(f.controller.activeToken("chat", "assistant"))
            expectCode("SESSION_INVALID") { f.controller.observe(PhoneSessionToken("chat", "assistant", "forged", 1)) }
            assertEquals(0, f.backend.reads)
        }
    }

    @Test fun `tokens cannot move across conversations or assistants`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            assertNull(f.controller.activeToken("other", "assistant"))
            assertNull(f.controller.activeToken("chat", "other"))
            expectCode("SESSION_INVALID") { f.controller.observe(token.copy(conversationId = "other")) }
            expectCode("SESSION_INVALID") { f.controller.observe(token.copy(assistantId = "other")) }
            assertEquals(0, f.backend.reads)
        }
    }

    @Test fun `start requires service unlocked device and a usable stop notification`() {
        Fixture().use { f ->
            f.backend.state.value = f.backend.state.value.copy(connected = false)
            expectCode("SERVICE_DISCONNECTED") { f.start() }
            f.backend.state.value = f.backend.state.value.copy(connected = true, locked = true)
            expectCode("DEVICE_LOCKED") { f.start() }
            f.backend.state.value = f.backend.state.value.copy(locked = false)
            f.backend.noticeAllowed = false
            expectCode("STOP_NOTIFICATION_REQUIRED") { f.start() }
            assertNull(f.controller.activeToken("chat", "assistant"))
        }
    }

    @Test fun `agent cannot target its own control UI or an unlisted app`() {
        Fixture().use { f ->
            expectCode("TARGET_NOT_ALLOWED") { f.controller.start("chat", "assistant", "com.example.agent") }
            expectCode("TARGET_NOT_ALLOWED") { f.controller.start("chat", "assistant", "com.example.other") }
        }
    }

    @Test fun `stop notification immediately revokes captured permits`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val snapshot = f.controller.observe(token)
            f.backend.onStop!!.invoke()
            expectCode("SESSION_INVALID") { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) }
            assertEquals(0, f.backend.actions)
            assertEquals(PhoneSessionStatus.STOPPED, f.controller.state.value.status)
        }
    }

    @Test fun `pending dispatch rechecks its permit after stop`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val snapshot = f.controller.observe(token)
            val reachedDispatch = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.backend.beforeDispatch = { permit ->
                reachedDispatch.complete(Unit)
                release.await()
                assertFalse(permit.isValid())
            }
            val action = async { runCatching { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) } }
            reachedDispatch.await()
            f.controller.stop()
            release.complete(Unit)
            assertTrue(action.await().isFailure)
            assertEquals(0, f.backend.actions)
        }
    }

    @Test fun `pause and resume cannot revive pending calls or snapshots`() = runBlocking {
        Fixture().use { f ->
            val oldToken = f.start()
            val snapshot = f.controller.observe(oldToken)
            f.controller.pause()
            val resumed = f.controller.resume()
            assertNotEquals(oldToken.epoch, resumed.epoch)
            expectCode("SESSION_INVALID") { f.controller.observe(oldToken) }
            expectCode("STALE_SNAPSHOT") { f.controller.act(resumed, snapshot.id, PhoneAction.Click("button")) }
        }
    }

    @Test fun `new session cannot execute a prior session approval`() = runBlocking {
        Fixture().use { f ->
            val old = f.start()
            val snapshot = f.controller.observe(old)
            val newer = f.start()
            assertNotEquals(old.sessionId, newer.sessionId)
            expectCode("SESSION_INVALID") { f.controller.act(old, snapshot.id, PhoneAction.Click("button")) }
        }
    }

    @Test fun `old stop callback and cancelled dispatch cannot affect replacement session`() = runBlocking {
        Fixture().use { f ->
            val old = f.start()
            val oldStop = f.backend.onStop!!
            val snapshot = f.controller.observe(old)
            val dispatched = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.backend.beforeDispatch = { dispatched.complete(Unit); release.await() }
            val pending = async { f.controller.act(old, snapshot.id, PhoneAction.Click("button")) }
            dispatched.await()
            val newer = f.start()
            val invalidations = f.backend.invalidations
            oldStop()
            pending.cancel()
            pending.join()
            assertEquals(newer, f.controller.activeToken("chat", "assistant"))
            assertEquals(invalidations, f.backend.invalidations)
            assertEquals(0, f.controller.state.value.actionsUsed)
            assertEquals(listOf("start"), f.controller.state.value.audit.map { it.operation })
        }
    }

    @Test fun `backend handover pauses both observation and post action verification`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.beforeRead = { throw PhoneControlException("USER_REQUIRED", "private platform detail") }
            expectCode("USER_REQUIRED") { f.controller.observe(token) }
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            assertFalse(f.controller.state.value.audit.toString().contains("private"))
        }
        Fixture().use { f ->
            val token = f.start()
            val snapshot = f.controller.observe(token)
            f.backend.beforeRead = { throw PhoneControlException("USER_REQUIRED", "private platform detail") }
            val result = f.controller.act(token, snapshot.id, PhoneAction.Click("button"))
            assertTrue(result.accepted)
            assertFalse(result.screenChanged)
            assertNull(result.observation)
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
        }
    }

    @Test fun `snapshot revision and age are checked before every action`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val snapshot = f.controller.observe(token)
            f.backend.state.value = f.backend.state.value.copy(windowRevision = 2)
            expectCode("STALE_SNAPSHOT") { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) }
            val fresh = f.controller.observe(token)
            f.time += 10_001
            expectCode("STALE_SNAPSHOT") { f.controller.act(token, fresh.id, PhoneAction.Click("button")) }
            assertEquals(0, f.backend.actions)
        }
    }

    @Test fun `a consumed snapshot cannot dispatch twice`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val snapshot = f.controller.observe(token)
            assertTrue(f.controller.act(token, snapshot.id, PhoneAction.Click("button")).screenChanged)
            expectCode("STALE_SNAPSHOT") { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) }
            assertEquals(1, f.backend.actions)
        }
    }

    @Test fun `foreground switch and lock revoke actions before dispatch`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val snapshot = f.controller.observe(token)
            f.backend.state.value = f.backend.state.value.copy(foregroundPackage = "com.example.other")
            expectCode("SESSION_INVALID") { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) }
            assertEquals(PhoneSessionStatus.WAITING_FOR_FOREGROUND, f.controller.state.value.status)
            f.backend.state.value = f.backend.state.value.copy(foregroundPackage = "com.example.target")
            val resumed = f.controller.resume()
            f.backend.state.value = f.backend.state.value.copy(locked = true)
            expectCode("SESSION_INVALID") { f.controller.observe(resumed) }
            assertEquals(0, f.backend.actions)
        }
    }

    @Test fun `service reconnect does not restore old authorization`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.state.value = f.backend.state.value.copy(connected = false)
            f.backend.state.value = f.backend.state.value.copy(connected = true)
            expectCode("SESSION_INVALID") { f.controller.observe(token) }
            assertEquals(PhoneSessionStatus.STOPPED, f.controller.state.value.status)
        }
    }

    @Test fun `same app window transition blocks actions without revoking session`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val before = f.controller.observe(token)
            f.backend.state.value = f.backend.state.value.copy(foregroundPackage = null, windowRevision = 2)
            expectCode("WINDOW_TRANSITION") { f.controller.act(token, before.id, PhoneAction.Click("button")) }
            assertEquals(0, f.backend.actions)
            assertEquals(PhoneSessionStatus.RUNNING, f.controller.state.value.status)
            f.backend.state.value = f.backend.state.value.copy(foregroundPackage = "com.example.target", windowRevision = 3)
            expectCode("STALE_SNAPSHOT") { f.controller.act(token, before.id, PhoneAction.Click("button")) }
            assertEquals(token, f.controller.activeToken("chat", "assistant"))
            assertTrue(f.controller.act(token, f.controller.observe(token).id, PhoneAction.Click("button")).accepted)
        }
    }

    @Test fun `unknown foreground pauses after grace and a known other app pauses immediately`() = runBlocking {
        Fixture(foregroundGraceMillis = 30).use { f ->
            val token = f.start()
            f.controller.observe(token)
            f.backend.state.value = f.backend.state.value.copy(foregroundPackage = null)
            delay(100)
            assertEquals(PhoneSessionStatus.WAITING_FOR_FOREGROUND, f.controller.state.value.status)
            assertEquals(0, f.backend.actions)
        }
        Fixture().use { f ->
            val token = f.start()
            f.controller.observe(token)
            f.backend.state.value = f.backend.state.value.copy(foregroundPackage = null)
            f.backend.state.value = f.backend.state.value.copy(foregroundPackage = "com.example.other")
            assertEquals(PhoneSessionStatus.WAITING_FOR_FOREGROUND, f.controller.state.value.status)
        }
    }

    @Test fun `sensitive and password pages never return their contents`() = runBlocking {
        for (kind in listOf("flag", "password", "payment", "otp")) {
            Fixture().use { f ->
                f.backend.sensitive = kind == "flag"
                f.backend.password = kind == "password"
                f.backend.text = when (kind) { "payment" -> "确认付款"; "otp" -> "验证码 123456"; else -> "private text" }
                val token = f.start()
                expectCode("USER_HANDOVER_REQUIRED") { f.controller.observe(token) }
                assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
                assertFalse(f.controller.state.value.audit.toString().contains("123456"))
                assertEquals(0, f.backend.actions)
            }
        }
    }

    @Test fun `truncated observations cannot be used for actions or screenshots`() = runBlocking {
        Fixture().use { f ->
            f.backend.truncated = true
            val token = f.start(allowScreenshots = true)
            val snapshot = f.controller.observe(token)
            expectCode("INCOMPLETE_SCREEN") { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) }
            expectCode("INCOMPLETE_SCREEN") { f.controller.act(token, snapshot.id, PhoneAction.Screenshot) }
        }
    }

    @Test fun `screenshots require separate user opt in`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val snapshot = f.controller.observe(token)
            expectCode("SCREENSHOT_NOT_ALLOWED") { f.controller.act(token, snapshot.id, PhoneAction.Screenshot) }
            assertEquals(0, f.backend.actions)
        }
    }

    @Test fun `input only targets an editable node and audit never stores its text`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val snapshot = f.controller.observe(token)
            expectCode("NODE_NOT_EDITABLE") { f.controller.act(token, snapshot.id, PhoneAction.InputText("button", "private test")) }
            assertTrue(f.controller.act(token, snapshot.id, PhoneAction.InputText("editor", "private test")).accepted)
            assertFalse(f.controller.state.value.audit.toString().contains("private test"))
        }
    }

    @Test fun `three unchanged repeats pause rather than loop`() = runBlocking {
        Fixture().use { f ->
            f.backend.changes = false
            val token = f.start()
            repeat(3) {
                val snapshot = f.controller.observe(token)
                assertFalse(f.controller.act(token, snapshot.id, PhoneAction.Click("button")).screenChanged)
            }
            assertEquals(3, f.backend.actions)
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
        }
    }

    @Test fun `system acceptance alone is not reported as a changed screen`() = runBlocking {
        Fixture().use { f ->
            f.backend.changes = false
            val token = f.start()
            val result = f.controller.act(token, f.controller.observe(token).id, PhoneAction.Click("button"))
            assertTrue(result.accepted)
            assertFalse(result.screenChanged)
        }
    }

    @Test fun `action and observation budgets prevent additional work`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            repeat(30) { f.controller.act(token, f.controller.observe(token).id, PhoneAction.Click("button")) }
            val snapshot = f.controller.observe(token)
            expectCode("ACTION_LIMIT") { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) }
            assertEquals(30, f.backend.actions)
        }
        Fixture().use { f ->
            val token = f.start()
            repeat(90) { f.controller.observe(token) }
            expectCode("OBSERVATION_LIMIT") { f.controller.observe(token) }
            assertEquals(90, f.backend.reads)
        }
    }

    @Test fun `elapsed budget cannot be extended by pausing or changing clock backwards`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.controller.pause()
            f.time += 300_001
            expectCode("BUDGET_EXHAUSTED") { f.controller.resume() }
            expectCode("SESSION_INVALID") { f.controller.observe(token) }
            assertEquals(PhoneSessionStatus.EXPIRED, f.controller.state.value.status)
        }
        Fixture().use { f ->
            val token = f.start()
            f.time--
            expectCode("SESSION_INVALID") { f.controller.observe(token) }
        }
    }
}
