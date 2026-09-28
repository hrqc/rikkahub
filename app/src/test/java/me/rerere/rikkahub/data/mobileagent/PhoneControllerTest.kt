package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tools.phoneToolPrefix
import org.junit.Assert.*
import org.junit.Test

class PhoneControllerTest {
    @Test fun `click refresh eligibility cannot extend age change windows or refresh another action`() = runBlocking {
        for (failure in listOf("age", "window", "other_action")) Fixture().use { f ->
            val token = f.start()
            val screen = f.controller.observe(token)
            f.backend.allowClickRevalidation = true
            f.backend.state.value = f.backend.state.value.copy(windowRevision = 2)
            if (failure == "age") f.time += 10_001
            if (failure == "window") f.backend.state.value = f.backend.state.value.copy(windowId = 8)
            try {
                f.controller.act(token, screen.id, if (failure == "other_action") PhoneAction.Back else PhoneAction.Click("button"))
                fail("Expected stale snapshot")
            } catch (error: PhoneControlException) {
                assertEquals("STALE_SNAPSHOT", error.code)
                val metadata = error.snapshotRejection!!
                assertEquals(when (failure) {
                    "age" -> PhoneSnapshotRejectionReason.AGE_LIMIT
                    "window" -> PhoneSnapshotRejectionReason.WINDOW_CHANGED
                    else -> PhoneSnapshotRejectionReason.REVISION_CHANGED
                }, metadata.reason)
                assertTrue(metadata.revisionChanged)
            }
            assertEquals(0, f.backend.actions)
        }
    }

    @Test fun `only backend opted in click can reach execution after a content revision`() = runBlocking {
        for (eligible in listOf(false, true)) Fixture().use { f ->
            val token = f.start()
            val screen = f.controller.observe(token)
            f.backend.allowClickRevalidation = eligible
            f.backend.state.value = f.backend.state.value.copy(windowRevision = 2)
            if (eligible) {
                // This verifies controller delegation, not the Android backend's revalidation proof.
                assertTrue(f.controller.act(token, screen.id, PhoneAction.Click("button")).accepted)
                assertEquals(1, f.backend.actions)
            } else {
                expectCode("STALE_SNAPSHOT") { f.controller.act(token, screen.id, PhoneAction.Click("button")) }
                assertEquals(0, f.backend.actions)
            }
        }
    }

    @Test fun `pre click content changes are not reported as changes caused by revalidated click`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val screen = f.controller.observe(token)
            val ledger = PhoneActionReceiptLedger(token)
            val name = phoneToolPrefix(token) + "click"
            val recorder = ledger.beginCall("message", "call", name)!!.forTool(token, name)!!
            f.backend.allowClickRevalidation = true
            f.backend.state.value = f.backend.state.value.copy(windowRevision = 2)
            f.backend.fingerprint = "fresh-ad-before-dispatch"
            f.backend.beforeActionFingerprint = f.backend.fingerprint
            f.backend.changes = false

            val result = f.controller.act(token, screen.id, PhoneAction.Click("button"), recorder)

            assertTrue(result.accepted)
            assertNotEquals(screen.fingerprint, result.observation!!.fingerprint)
            assertFalse(result.screenChanged)
            assertTrue(recorder.knownResult()!!.observationVerified)
            assertEquals(false, recorder.knownResult()!!.screenChanged)
            assertEquals(1, f.backend.actions)
        }
    }

    @Test fun `click refresh opt in cannot revive another snapshot or an old grant`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val screen = f.controller.observe(token)
            f.backend.allowClickRevalidation = true
            f.backend.state.value = f.backend.state.value.copy(windowRevision = 2)
            expectCode("STALE_SNAPSHOT") { f.controller.act(token, "forged", PhoneAction.Click("button")) }
            f.controller.pause()
            f.controller.resume()
            expectCode("SESSION_INVALID") { f.controller.act(token, screen.id, PhoneAction.Click("button")) }
            assertEquals(0, f.backend.actions)
        }
    }

    @Test fun `limited post action observation cannot mark a receipt as fully verified`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val screen = f.controller.observe(token)
            val ledger = PhoneActionReceiptLedger(token)
            val name = phoneToolPrefix(token) + "click"
            val recorder = ledger.beginCall("message", "call", name)!!.forTool(token, name)!!
            f.backend.afterDispatch = {
                f.backend.truncated = true
                f.backend.scrollOnly = true
                f.backend.inspectionIssues = listOf("unavailable_child")
            }
            val result = f.controller.act(token, screen.id, PhoneAction.Click("button"), recorder)
            assertTrue(result.accepted)
            assertTrue(result.observation!!.scrollOnly)
            assertTrue(recorder.knownResult()!!.accepted)
            assertFalse(recorder.knownResult()!!.observationVerified)
            assertNull(recorder.knownResult()!!.screenChanged)
            assertEquals(1, f.backend.actions)
        }
    }

    @Test fun `accepted receipt survives synchronous cancellation on post observation pause`() = runBlocking {
        for (sensitive in listOf(false, true)) Fixture().use { f ->
            val token = f.start()
            val screen = f.controller.observe(token)
            val ledger = PhoneActionReceiptLedger(token)
            val key = PhoneActionReceiptKey("message", "call", phoneToolPrefix(token) + "click")
            val recorder = ledger.beginCall(key.messageId, key.toolCallId, key.toolName)!!.forTool(token, key.toolName)!!
            f.backend.beforeRead = {
                if (sensitive) { f.backend.sensitive = true; f.backend.text = "private page body" }
                else throw PhoneControlException("PAGE_UNSTABLE", "fixed test error")
            }
            val action = async(start = CoroutineStart.LAZY) {
                f.controller.act(token, screen.id, PhoneAction.Click("button"), recorder)
            }
            val cancellation = launch(Dispatchers.Unconfined) {
                f.controller.state.first { it.status == PhoneSessionStatus.PAUSED }
                action.cancel()
            }
            try { action.await(); fail("Pause must cancel the model job") } catch (_: CancellationException) { }
            cancellation.join()
            val receipt = ledger.freeze().find(token, key)!!
            assertTrue(receipt.accepted)
            assertFalse(receipt.observationVerified)
            assertNull(receipt.screenChanged)
            if (!sensitive) assertEquals("PAGE_UNSTABLE", receipt.postObserveError)
            assertFalse(receipt.output().toString().contains("private page body"))
            assertEquals(1, f.backend.actions)
            assertNull(f.controller.activeToken("chat", "assistant"))
        }
    }

    @Test fun `receipt distinguishes platform rejection from validation before dispatch`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val screen = f.controller.observe(token)
            val ledger = PhoneActionReceiptLedger(token)
            val name = phoneToolPrefix(token) + "click"
            val blocked = ledger.beginCall("message", "blocked", name)!!.forTool(token, name)!!
            expectCode("UNKNOWN_NODE") { f.controller.act(token, screen.id, PhoneAction.Click("absent"), blocked) }
            assertNull(blocked.knownResult())
            assertEquals(0, f.backend.actions)
            f.backend.accepts = false
            val rejected = ledger.beginCall("message", "rejected", name)!!.forTool(token, name)!!
            assertFalse(f.controller.act(token, screen.id, PhoneAction.Click("button"), rejected).accepted)
            assertFalse(rejected.knownResult()!!.accepted)
            assertNull(rejected.knownResult()!!.screenChanged)
            assertEquals(1, f.backend.actions)
        }
    }

    @Test fun `old receipt cannot bind a new grant action and stop does not erase returned acceptance`() = runBlocking {
        Fixture().use { f ->
            val old = f.start()
            val ledger = PhoneActionReceiptLedger(old)
            val name = phoneToolPrefix(old) + "click"
            val recorder = ledger.beginCall("message", "call", name)!!.forTool(old, name)!!
            val screen = f.controller.observe(old)
            var replacement: PhoneSessionToken? = null
            f.backend.afterDispatch = { f.controller.stop(); replacement = f.start() }
            expectCode("SESSION_INVALID") { f.controller.act(old, screen.id, PhoneAction.Click("button"), recorder) }
            assertTrue(recorder.knownResult()!!.accepted)
            assertFalse(recorder.knownResult()!!.observationVerified)
            assertEquals(replacement, f.controller.activeToken("chat", "assistant"))
            f.backend.afterDispatch = {}
            val fresh = replacement!!
            f.controller.act(fresh, f.controller.observe(fresh).id, PhoneAction.Click("button"), recorder)
            assertFalse(recorder.knownResult()!!.observationVerified)
            assertEquals(2, f.backend.actions)
            assertEquals(fresh, f.controller.activeToken("chat", "assistant"))
        }
    }

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
        var scrollOnly = false
        var inspectionIssues = emptyList<String>()
        var previewTruncated = false
        var password = false
        var text = "Safe button"
        var extraNodes = emptyList<PhoneNode>()
        var readOnlyContent: PhoneReadOnlyContent? = null
        var fingerprint = "page"
        var changes = true
        var accepts = true
        var allowClickRevalidation = false
        var beforeActionFingerprint: String? = null
        var reads = 0
        var actions = 0
        var invalidations = 0
        var noticesEnded = 0
        var lastReadPermit: PhonePermit? = null
        var onStop: ((PhoneBackendStopReason) -> Unit)? = null
        var beforeDispatch: suspend (PhonePermit) -> Unit = {}
        var afterDispatch: () -> Unit = {}
        var beforeRead: suspend () -> Unit = {}
        override fun isTargetAllowed(packageName: String) = packageName == "com.example.target"
        override fun canRevalidateClick(token: PhoneSessionToken, observation: PhoneObservation, nodeId: String) = allowClickRevalidation
        override fun showSessionNotice(token: PhoneSessionToken, targetPackage: String, onStop: (PhoneBackendStopReason) -> Unit): Boolean {
            this.onStop = onStop
            return noticeAllowed
        }
        override fun endSessionNotice() { noticesEnded++ }
        override fun invalidate() { invalidations++ }
        override suspend fun observe(permit: PhonePermit): PhoneObservation {
            lastReadPermit = permit
            beforeRead()
            check(permit.isValid())
            reads++
            return PhoneObservation(
                "snapshot$reads", "com.example.target", 7, state.value.windowRevision, now(),
                listOf(PhoneNode("button", text = text, bounds = PhoneBounds(0, 0, 50, 50), clickable = true, password = password),
                    PhoneNode("editor", bounds = PhoneBounds(0, 50, 100, 100), editable = true),
                    PhoneNode("scroll", bounds = PhoneBounds(0, 100, 100, 500), scrollable = true)) + extraNodes,
                truncated, sensitive, fingerprint, previewTruncated = previewTruncated,
                inspectionIssues = inspectionIssues, scrollOnly = scrollOnly,
                readOnlyContent = readOnlyContent,
            )
        }
        override suspend fun execute(permit: PhonePermit, observation: PhoneObservation?, action: PhoneAction): PhoneBackendResult {
            beforeDispatch(permit)
            if (!permit.isValid()) return PhoneBackendResult(false, "revoked")
            actions++
            if (changes) fingerprint = "changed$actions"
            afterDispatch()
            return PhoneBackendResult(accepts, "platform result", if (action == PhoneAction.Screenshot) "content://test/window" else null,
                beforeActionFingerprint = beforeActionFingerprint)
        }
    }

    private inline fun expectCode(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: PhoneControlException) { assertEquals(code, error.code) }
    }

    private val terminalInspectionFailures = mapOf(
        "PAGE_UNSTABLE" to "目标页面在有限次读取后仍不稳定，任务已暂停；请等待页面稳定后手动继续。",
        "INCOMPLETE_SCREEN" to "未能完整检查目标页面，任务已暂停；请检查页面后手动继续。",
    )

    @Test fun `retained content exposes later text without refreshing snapshots or granting actions`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.readOnlyContent = PhoneReadOnlyContent(listOf(PhoneReadOnlyContentNode("button", "Safe button")) +
                List(120) { PhoneReadOnlyContentNode("r$it", "Visible product $it", "Price description $it") })
            val screen = f.controller.observe(token)
            assertNull(screen.readOnlyContent)
            assertTrue(screen.readOnlyContentAvailable)
            assertFalse(screen.readOnlyContentTruncated)
            val all = mutableListOf<PhoneReadOnlyContentNode>()
            var cursor: String? = "0"
            while (cursor != null) {
                val page = f.controller.readObservedContent(token, screen.id, cursor)
                assertTrue(page.nodes.size <= 40)
                assertTrue(page.nodes.sumOf { it.text.length + it.description.length } <= 8_000)
                assertFalse(page.grantsActionPermission)
                all += page.nodes
                cursor = page.nextCursor
            }
            assertEquals(121, all.size)
            assertEquals("Visible product 119", all.last().text)
            assertEquals(1, all.count { it.id == "button" })
            assertEquals(all.map { it.id }, f.controller.shoppingEvidence(token).single().nodes.map { it.nodeId })
            assertEquals(1, f.backend.reads)
            assertEquals(1, f.controller.state.value.observationsUsed)
            assertEquals(0, f.controller.state.value.actionsUsed)
            expectCode("UNKNOWN_NODE") { f.controller.act(token, screen.id, PhoneAction.Click("r119")) }
            assertEquals(0, f.backend.actions)
            f.time += 10_001
            assertEquals(40, f.controller.readObservedContent(token, screen.id).nodes.size)
            expectCode("STALE_SNAPSHOT") { f.controller.act(token, screen.id, PhoneAction.Click("button")) }
        }
    }

    @Test fun `content pagination is bounded and rejects invalid cursor without device access`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.readOnlyContent = PhoneReadOnlyContent(List(300) {
                PhoneReadOnlyContentNode("r$it", "x".repeat(240), "y".repeat(240))
            }, truncated = true)
            val screen = f.controller.observe(token)
            val page = f.controller.readObservedContent(token, screen.id)
            assertEquals(16, page.nodes.size)
            assertEquals("16", page.nextCursor)
            assertTrue(page.contentTruncated)
            assertTrue(screen.readOnlyContentTruncated)
            assertTrue(f.controller.shoppingEvidence(token).single().nodes.sumOf { it.text.length + it.description.length } <= 64_000)
            for (cursor in listOf("-1", "01", "", "1.0", "1000000", "9999")) {
                expectCode("INVALID_CONTENT_CURSOR") { f.controller.readObservedContent(token, screen.id, cursor) }
            }
            assertEquals(1, f.backend.reads)
            assertEquals(0, f.backend.actions)
        }
    }

    @Test fun `partial pages cannot publish or reopen retained content`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val complete = f.controller.observe(token)
            f.backend.truncated = true
            f.backend.scrollOnly = true
            f.backend.inspectionIssues = listOf("unavailable_child")
            f.backend.readOnlyContent = PhoneReadOnlyContent(listOf(PhoneReadOnlyContentNode("r0", "not evidence")))
            val partial = f.controller.observe(token)
            assertFalse(partial.readOnlyContentAvailable)
            assertNull(partial.readOnlyContent)
            expectCode("SCROLL_ONLY") { f.controller.readObservedContent(token, complete.id) }
            expectCode("SCROLL_ONLY") { f.controller.readObservedContent(token, partial.id) }
            assertEquals(listOf(complete.id), f.controller.shoppingEvidence(token).map { it.snapshotId })
        }
    }

    @Test fun `content is revoked by hidden sensitive text pause stop and epoch replacement`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val safe = f.controller.observe(token)
            f.backend.readOnlyContent = PhoneReadOnlyContent(listOf(PhoneReadOnlyContentNode("r0", "京东验证")))
            expectCode("USER_HANDOVER_REQUIRED") { f.controller.observe(token) }
            expectCode("SESSION_INVALID") { f.controller.readObservedContent(token, safe.id) }
            val resumed = f.controller.resume()
            expectCode("CONTENT_UNAVAILABLE") { f.controller.readObservedContent(resumed, safe.id) }
            expectCode("SESSION_INVALID") { f.controller.readObservedContent(token, safe.id) }
            f.backend.readOnlyContent = null
            val current = f.controller.observe(resumed)
            f.controller.stop()
            expectCode("SESSION_INVALID") { f.controller.readObservedContent(resumed, current.id) }
        }
    }

    @Test fun `evicted content cannot be fetched through an old cursor`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val first = f.controller.observe(token)
            repeat(32) { f.controller.observe(token) }
            expectCode("CONTENT_UNAVAILABLE") { f.controller.readObservedContent(token, first.id, "0") }
            assertEquals(33, f.backend.reads)
        }
    }

    @Test fun `field clipping follows evidence and page output without becoming an action`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.readOnlyContent = PhoneReadOnlyContent(listOf(
                PhoneReadOnlyContentNode("r0", "Price prefix", truncated = true),
                PhoneReadOnlyContentNode("r1", "Complete visible text"),
            ), truncated = true)
            val screen = f.controller.observe(token)
            val page = f.controller.readObservedContent(token, screen.id)
            assertTrue(page.contentTruncated)
            assertTrue(page.nodes.first().truncated)
            assertFalse(page.nodes.last().truncated)
            assertTrue(f.controller.shoppingEvidence(token).single().nodes.first().truncated)
            assertFalse(f.controller.shoppingEvidence(token).single().nodes.last().truncated)
            assertNull(screen.readOnlyContent)
            assertEquals(0, f.backend.actions)
        }
    }

    @Test fun `scroll only observation strips product text and actions without adding shopping evidence`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.controller.observe(token)
            val completeEvidence = f.controller.shoppingEvidence(token)
            f.backend.truncated = true
            f.backend.scrollOnly = true
            f.backend.inspectionIssues = listOf("unavailable_child")
            val partial = f.controller.observe(token)
            assertTrue(partial.scrollOnly)
            assertTrue(partial.truncated)
            assertEquals(listOf("scroll"), partial.nodes.map { it.id })
            assertTrue(partial.nodes.all { it.text.isEmpty() && it.description.isEmpty() && !it.clickable && !it.editable })
            assertEquals(completeEvidence, f.controller.shoppingEvidence(token))
            val moved = f.controller.act(token, partial.id, PhoneAction.Scroll("scroll", true))
            assertTrue(moved.accepted)
            assertTrue(moved.screenChanged)
            assertTrue(moved.observation!!.scrollOnly)
            assertEquals(1, f.backend.actions)
            assertEquals(completeEvidence, f.controller.shoppingEvidence(token))
            expectCode("STALE_SNAPSHOT") { f.controller.act(token, partial.id, PhoneAction.Scroll("scroll", true)) }
            f.controller.pause()
            assertTrue(f.controller.shoppingEvidence(token).isEmpty())
        }
    }

    @Test fun `scroll only observations reject every other action before dispatch`() = runBlocking {
        val blocked = listOf(PhoneAction.Click("scroll"), PhoneAction.LongClick("scroll"),
            PhoneAction.InputText("scroll", "test"), PhoneAction.Swipe(PhoneSwipeDirection.UP),
            PhoneAction.Back, PhoneAction.Screenshot, PhoneAction.OpenApp)
        for (action in blocked) Fixture().use { f ->
            val token = f.start(allowScreenshots = true)
            f.backend.truncated = true
            f.backend.scrollOnly = true
            f.backend.inspectionIssues = listOf("unavailable_child")
            val screen = f.controller.observe(token)
            expectCode("SCROLL_ONLY") { f.controller.act(token, screen.id, action) }
            assertEquals(0, f.backend.actions)
            assertTrue(f.controller.shoppingEvidence(token).isEmpty())
        }
    }

    @Test fun `forged scroll capability cannot hide other inspection gaps or sensitive content`() = runBlocking {
        for (issues in listOf(emptyList(), listOf("unavailable_child", "visit_limit"), listOf("foreign_node"))) {
            Fixture().use { f ->
                val token = f.start()
                f.backend.truncated = true
                f.backend.scrollOnly = true
                f.backend.inspectionIssues = issues
                expectCode("INCOMPLETE_SCREEN") { f.controller.observe(token) }
                assertEquals(0, f.backend.actions)
            }
        }
        Fixture().use { f ->
            val token = f.start()
            f.backend.scrollOnly = true
            f.backend.inspectionIssues = listOf("unavailable_child")
            expectCode("INCOMPLETE_SCREEN") { f.controller.observe(token) }
        }
        Fixture().use { f ->
            val token = f.start()
            f.backend.truncated = true
            f.backend.scrollOnly = true
            f.backend.inspectionIssues = listOf("unavailable_child")
            f.backend.text = "支付密码"
            expectCode("USER_HANDOVER_REQUIRED") { f.controller.observe(token) }
        }
    }

    @Test fun `scroll only snapshot remains stale on revision age stop and lock`() = runBlocking {
        for (change in listOf("revision", "age", "stop", "lock")) Fixture().use { f ->
            val token = f.start()
            f.backend.truncated = true
            f.backend.scrollOnly = true
            f.backend.inspectionIssues = listOf("unavailable_child")
            val screen = f.controller.observe(token)
            when (change) {
                "revision" -> f.backend.state.value = f.backend.state.value.copy(windowRevision = 2)
                "age" -> f.time += 10_001
                "stop" -> f.controller.stop()
                "lock" -> f.backend.state.value = f.backend.state.value.copy(locked = true)
            }
            expectCode(if (change in listOf("revision", "age")) "STALE_SNAPSHOT" else "SESSION_INVALID") {
                f.controller.act(token, screen.id, PhoneAction.Scroll("scroll", true))
            }
            assertEquals(0, f.backend.actions)
        }
    }

    @Test fun `failed native scroll is not retried and consumes its partial snapshot`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.truncated = true
            f.backend.scrollOnly = true
            f.backend.inspectionIssues = listOf("unavailable_child")
            f.backend.accepts = false
            val screen = f.controller.observe(token)
            assertFalse(f.controller.act(token, screen.id, PhoneAction.Scroll("scroll", true)).accepted)
            expectCode("STALE_SNAPSHOT") { f.controller.act(token, screen.id, PhoneAction.Scroll("scroll", true)) }
            expectCode("SCROLL_ONLY") { f.controller.act(token, null, PhoneAction.OpenApp) }
            assertEquals(1, f.backend.actions)
            f.controller.pause()
            val resumed = f.controller.resume()
            expectCode("SCROLL_ONLY") { f.controller.act(resumed, null, PhoneAction.OpenApp) }
            f.backend.truncated = false
            f.backend.scrollOnly = false
            f.backend.inspectionIssues = emptyList()
            f.controller.observe(resumed)
            f.backend.accepts = true
            assertTrue(f.controller.act(resumed, null, PhoneAction.OpenApp).accepted)
            assertEquals(2, f.backend.actions)
        }
    }

    @Test fun `five partial scrolls bound a task even when every page changes`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.truncated = true
            f.backend.scrollOnly = true
            f.backend.inspectionIssues = listOf("unavailable_child")
            var screen = f.controller.observe(token)
            repeat(5) {
                screen = f.controller.act(token, screen.id, PhoneAction.Scroll("scroll", true)).observation!!
            }
            expectCode("SCROLL_ONLY_LIMIT") { f.controller.act(token, screen.id, PhoneAction.Scroll("scroll", true)) }
            assertEquals(5, f.backend.actions)
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            val resumed = f.controller.resume()
            screen = f.controller.observe(resumed)
            expectCode("SCROLL_ONLY_LIMIT") { f.controller.act(resumed, screen.id, PhoneAction.Scroll("scroll", true)) }
            assertEquals(5, f.backend.actions)
        }
    }

    @Test fun `native scroll can reach a complete page without promoting earlier partial evidence`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.beforeRead = {
                val partial = f.backend.actions < 2
                f.backend.truncated = partial
                f.backend.scrollOnly = partial
                f.backend.inspectionIssues = if (partial) listOf("unavailable_child") else emptyList()
                f.backend.text = if (partial) "unverified product price" else "verified visible product"
            }
            var screen = f.controller.observe(token)
            screen = f.controller.act(token, screen.id, PhoneAction.Scroll("scroll", true)).observation!!
            assertTrue(screen.scrollOnly)
            assertTrue(f.controller.shoppingEvidence(token).isEmpty())
            screen = f.controller.act(token, screen.id, PhoneAction.Scroll("scroll", true)).observation!!
            assertFalse(screen.scrollOnly)
            assertFalse(screen.truncated)
            assertEquals(listOf(screen.id), f.controller.shoppingEvidence(token).map { it.snapshotId })
            assertEquals("verified visible product", f.controller.shoppingEvidence(token).single().nodes.first().text)
            assertTrue(f.controller.act(token, screen.id, PhoneAction.Click("button")).accepted)
            assertEquals(3, f.backend.actions)
        }
    }

    @Test fun `partial scroll with unchanged content stops without spending all five attempts`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.truncated = true
            f.backend.scrollOnly = true
            f.backend.inspectionIssues = listOf("unavailable_child")
            f.backend.changes = false
            var screen = f.controller.observe(token)
            repeat(3) { screen = f.controller.act(token, screen.id, PhoneAction.Scroll("scroll", true)).observation!! }
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            assertEquals(3, f.backend.actions)
            assertTrue(f.controller.shoppingEvidence(token).isEmpty())
            expectCode("SESSION_INVALID") { f.controller.act(token, screen.id, PhoneAction.Scroll("scroll", true)) }
        }
    }

    @Test fun `shopping evidence is bounded to real observations and current grant`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            assertTrue(f.controller.shoppingEvidence(token).isEmpty())
            repeat(10) { f.controller.observe(token) }
            val evidence = f.controller.shoppingEvidence(token)
            assertEquals(10, evidence.size)
            assertEquals("snapshot1", evidence.first().snapshotId)
            assertEquals("Safe button", evidence.last().nodes.first().text)
            expectCode("STALE_SNAPSHOT") { f.controller.act(token, "snapshot1", PhoneAction.Click("button")) }
            repeat(25) { f.controller.observe(token) }
            assertEquals(32, f.controller.shoppingEvidence(token).size)
            assertEquals("snapshot4", f.controller.shoppingEvidence(token).first().snapshotId)
            f.controller.pause()
            assertTrue(f.controller.shoppingEvidence(token).isEmpty())
            val resumed = f.controller.resume()
            assertTrue(f.controller.shoppingEvidence(resumed).isEmpty())
            f.controller.observe(resumed)
            assertEquals(1, f.controller.shoppingEvidence(resumed).size)
            f.controller.stop()
            assertTrue(f.controller.shoppingEvidence(resumed).isEmpty())
            assertTrue(f.controller.shoppingEvidence(f.start()).isEmpty())
        }
    }

    @Test fun `large page evidence is evicted by text budget before page count limit`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.extraNodes = List(97) { index ->
                PhoneNode("product$index", text = "x".repeat(300), description = "y".repeat(300),
                    bounds = PhoneBounds(0, 100, 100, 500))
            }
            repeat(10) { f.controller.observe(token) }
            val retained = f.controller.shoppingEvidence(token)
            assertTrue(retained.size in 1..9)
            assertFalse(retained.any { it.snapshotId == "snapshot1" })
            assertTrue(retained.sumOf { page -> page.nodes.sumOf { it.text.length + it.description.length } } <= 256_000)
            f.controller.pause()
            assertTrue(f.controller.shoppingEvidence(token).isEmpty())
        }
    }

    @Test fun `live activity follows actual model observation and dispatch lifecycle`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            assertEquals(PhoneActivity.READY, f.controller.state.value.activity)
            f.controller.setModelWorking(token, true)
            assertEquals(PhoneActivity.WAITING_MODEL, f.controller.state.value.activity)
            val reading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.backend.beforeRead = { reading.complete(Unit); release.await() }
            val pending = async { f.controller.observe(token) }
            reading.await()
            assertEquals(PhoneActivity.OBSERVING, f.controller.state.value.activity)
            release.complete(Unit)
            val observation = pending.await()
            assertEquals(PhoneActivity.WAITING_MODEL, f.controller.state.value.activity)
            assertEquals("observe", f.controller.state.value.audit.last().operation)
            f.backend.beforeDispatch = { assertEquals(PhoneActivity.ACTING, f.controller.state.value.activity) }
            f.controller.act(token, observation.id, PhoneAction.Click("button"))
            assertEquals(PhoneActivity.WAITING_MODEL, f.controller.state.value.activity)
            f.controller.setModelWorking(token, false)
            assertEquals(PhoneActivity.READY, f.controller.state.value.activity)
            assertFalse(f.controller.state.value.modelWorking)
        }
    }

    @Test fun `stale generation completion cannot reset resumed activity and stop clears working state`() {
        Fixture().use { f ->
            val old = f.start()
            f.controller.setModelWorking(old, true)
            f.controller.pause()
            assertFalse(f.controller.state.value.modelWorking)
            val current = f.controller.resume()
            f.controller.setModelWorking(current, true)
            f.controller.setModelWorking(old, false)
            assertTrue(f.controller.state.value.modelWorking)
            assertEquals(PhoneActivity.WAITING_MODEL, f.controller.state.value.activity)
            f.controller.stop()
            assertFalse(f.controller.state.value.modelWorking)
            assertEquals(PhoneActivity.READY, f.controller.state.value.activity)
        }
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
            f.backend.onStop!!.invoke(PhoneBackendStopReason.NOTIFICATION_STOP)
            expectCode("SESSION_INVALID") { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) }
            assertEquals(0, f.backend.actions)
            assertEquals(PhoneSessionStatus.STOPPED, f.controller.state.value.status)
            assertEquals("用户从通知停止", f.controller.state.value.detail)
        }
    }

    @Test fun `service stop causes revoke immediately without being attributed to the notification`() = runBlocking {
        val reasons = mapOf(
            PhoneBackendStopReason.SERVICE_INTERRUPTED to "无障碍服务被系统中断，请检查后重新开启任务",
            PhoneBackendStopReason.SERVICE_DISCONNECTED to "无障碍服务已断开",
            PhoneBackendStopReason.SERVICE_REPLACED to "无障碍服务已重新连接，请重新开启任务",
        )
        reasons.forEach { (reason, detail) ->
            Fixture().use { f ->
                val token = f.start()
                val snapshot = f.controller.observe(token)
                val invalidations = f.backend.invalidations
                f.backend.onStop!!.invoke(reason)
                assertNull(f.controller.activeToken("chat", "assistant"))
                assertTrue(f.backend.invalidations > invalidations)
                assertEquals(PhoneSessionStatus.STOPPED, f.controller.state.value.status)
                assertEquals(detail, f.controller.state.value.detail)
                assertEquals(detail, f.controller.state.value.audit.last().result)
                expectCode("SESSION_INVALID") { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) }
                assertEquals(0, f.backend.actions)
                // Later service metadata must not overwrite the original stopping cause.
                f.backend.state.value = f.backend.state.value.copy(connected = false)
                assertEquals(detail, f.controller.state.value.detail)
            }
        }
    }

    @Test fun `resumed sessions retain the actual backend stop cause`() {
        Fixture().use { f ->
            f.start()
            f.controller.pause()
            f.controller.resume()
            f.backend.onStop!!.invoke(PhoneBackendStopReason.SERVICE_INTERRUPTED)
            assertEquals(PhoneSessionStatus.STOPPED, f.controller.state.value.status)
            assertEquals("无障碍服务被系统中断，请检查后重新开启任务", f.controller.state.value.detail)
            assertNull(f.controller.activeToken("chat", "assistant"))
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
            PhoneBackendStopReason.entries.forEach(oldStop)
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

    @Test fun `final inspection failure pauses observation and revokes its permit without exposing platform details`() = runBlocking {
        terminalInspectionFailures.forEach { (code, reason) ->
            Fixture().use { f ->
                val token = f.start()
                f.controller.observe(token)
                assertTrue(f.controller.shoppingEvidence(token).isNotEmpty())
                val invalidations = f.backend.invalidations
                f.backend.beforeRead = { throw PhoneControlException(code, "private platform detail") }

                expectCode(code) { f.controller.observe(token) }

                assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
                assertEquals(reason, f.controller.state.value.detail)
                assertNotEquals(token.epoch, f.controller.state.value.token?.epoch)
                assertNull(f.controller.activeToken("chat", "assistant"))
                assertFalse(f.backend.lastReadPermit!!.isValid())
                assertTrue(f.backend.invalidations > invalidations)
                assertTrue(f.controller.shoppingEvidence(token).isEmpty())
                assertFalse(f.controller.state.value.audit.toString().contains("private"))
                expectCode("SESSION_INVALID") { f.controller.observe(token) }
                assertEquals(0, f.backend.actions)
            }
        }
    }

    @Test fun `inspection failure after dispatch preserves acceptance and pauses without replaying the action`() = runBlocking {
        terminalInspectionFailures.forEach { (code, reason) ->
            Fixture().use { f ->
                val token = f.start()
                val snapshot = f.controller.observe(token)
                var dispatchedPermit: PhonePermit? = null
                f.backend.beforeDispatch = { dispatchedPermit = it }
                f.backend.beforeRead = { throw PhoneControlException(code, "private post action detail") }

                val result = f.controller.act(token, snapshot.id, PhoneAction.Click("button"))

                assertTrue(result.accepted)
                assertFalse(result.screenChanged)
                assertNull(result.observation)
                assertEquals("动作已提交，但未能验证后续页面；请检查并重新观察", result.detail)
                assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
                assertEquals(reason, f.controller.state.value.detail)
                assertFalse(dispatchedPermit!!.isValid())
                assertEquals(1, f.backend.actions)
                assertFalse(f.controller.state.value.audit.toString().contains("private"))
                expectCode("SESSION_INVALID") { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) }

                val resumed = f.controller.resume()
                expectCode("STALE_SNAPSHOT") { f.controller.act(resumed, snapshot.id, PhoneAction.Click("button")) }
                assertEquals(1, f.backend.actions)
                assertEquals(PhoneSessionStatus.RUNNING, f.controller.state.value.status)
            }
        }
    }

    @Test fun `incomplete post dispatch observation stays unverified and cannot be replayed after resume`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val snapshot = f.controller.observe(token)
            f.backend.beforeRead = {
                assertEquals(1, f.backend.actions)
                f.backend.truncated = true
                f.backend.text = "private incomplete result"
            }

            val result = f.controller.act(token, snapshot.id, PhoneAction.Click("button"))

            assertTrue(result.accepted)
            assertFalse(result.screenChanged)
            assertNull(result.observation)
            assertEquals("动作已提交，但未能验证后续页面；请检查并重新观察", result.detail)
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            assertEquals(terminalInspectionFailures.getValue("INCOMPLETE_SCREEN"), f.controller.state.value.detail)
            assertTrue(f.controller.shoppingEvidence(token).isEmpty())
            assertEquals(1, f.controller.state.value.audit.count { it.operation == "observe" })
            assertFalse(f.controller.state.value.audit.toString().contains("private"))
            assertFalse(f.backend.lastReadPermit!!.isValid())
            assertEquals(2, f.backend.reads)
            expectCode("SESSION_INVALID") { f.controller.act(token, snapshot.id, PhoneAction.Click("button")) }

            val resumed = f.controller.resume()
            assertNotEquals(token.epoch, resumed.epoch)
            expectCode("STALE_SNAPSHOT") { f.controller.act(resumed, snapshot.id, PhoneAction.Click("button")) }
            assertTrue(f.controller.shoppingEvidence(resumed).isEmpty())
            assertEquals(1, f.backend.actions)
        }
    }

    @Test(timeout = 5_000)
    fun `late inspection failure cannot pause or invalidate a replacement session or resumed epoch`() = runBlocking {
        for (code in terminalInspectionFailures.keys) {
            for (replaceSession in listOf(false, true)) {
                Fixture().use { f ->
                    val old = f.start()
                    val reading = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    f.backend.beforeRead = {
                        reading.complete(Unit)
                        release.await()
                        throw PhoneControlException(code, "late private failure")
                    }
                    val pending = async { runCatching { f.controller.observe(old) } }
                    reading.await()
                    val oldPermit = f.backend.lastReadPermit!!
                    val current = if (replaceSession) f.start() else {
                        f.controller.pause()
                        f.controller.resume()
                    }
                    val stateBeforeFailure = f.controller.state.value
                    val invalidations = f.backend.invalidations
                    release.complete(Unit)

                    val error = pending.await().exceptionOrNull()
                    assertTrue(error is PhoneControlException)
                    assertEquals(code, (error as PhoneControlException).code)
                    assertEquals(stateBeforeFailure, f.controller.state.value)
                    assertEquals(current, f.controller.activeToken("chat", "assistant"))
                    assertEquals(invalidations, f.backend.invalidations)
                    assertFalse(oldPermit.isValid())
                    assertEquals(0, f.backend.actions)
                    f.backend.beforeRead = {}
                    f.controller.observe(current)
                    assertTrue(f.backend.lastReadPermit!!.isValid())
                }
            }
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
                f.backend.text = when (kind) { "payment" -> "支付密码"; "otp" -> "验证码 123456"; else -> "private text" }
                val token = f.start()
                expectCode("USER_HANDOVER_REQUIRED") { f.controller.observe(token) }
                assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
                assertFalse(f.controller.state.value.audit.toString().contains("123456"))
                assertEquals(0, f.backend.actions)
            }
        }
    }

    @Test fun `explicit platform challenges revoke complete and scroll-only observations and shopping evidence`() = runBlocking {
        val challenges = listOf("京东验证", "请点击下方按钮完成安全验证", "拖动滑块完成验证")
        for (challenge in challenges) for (description in listOf(false, true)) for (partial in listOf(false, true)) {
            Fixture().use { f ->
                val token = f.start()
                val previous = f.controller.observe(token)
                assertTrue(f.controller.shoppingEvidence(token).isNotEmpty())
                f.backend.truncated = partial
                f.backend.scrollOnly = partial
                f.backend.inspectionIssues = if (partial) listOf("unavailable_child") else emptyList()
                f.backend.extraNodes = listOf(PhoneNode("challenge", bounds = PhoneBounds(0, 0, 100, 100),
                    text = if (description) "" else challenge, description = if (description) challenge else "", clickable = true))

                expectCode("USER_HANDOVER_REQUIRED") { f.controller.observe(token) }

                assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
                assertFalse(f.backend.lastReadPermit!!.isValid())
                assertTrue(f.controller.shoppingEvidence(token).isEmpty())
                assertEquals(1, f.controller.state.value.audit.count { it.operation == "observe" })
                assertFalse(f.controller.state.value.audit.toString().contains(challenge))
                expectCode("SESSION_INVALID") { f.controller.act(token, previous.id, PhoneAction.Click("button")) }
                val resumed = f.controller.resume()
                assertTrue(f.controller.shoppingEvidence(resumed).isEmpty())
                expectCode("STALE_SNAPSHOT") { f.controller.act(resumed, previous.id, PhoneAction.Click("button")) }
                assertEquals(0, f.backend.actions)
            }
        }
    }

    @Test fun `challenge appearing after a native scroll pauses without a second action or product evidence`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            val previous = f.controller.observe(token)
            f.backend.beforeRead = { f.backend.text = "京东验证" }

            val result = f.controller.act(token, previous.id, PhoneAction.Scroll("scroll", true))

            assertTrue(result.accepted)
            assertFalse(result.screenChanged)
            assertNull(result.observation)
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            assertEquals(1, f.backend.actions)
            assertTrue(f.controller.shoppingEvidence(token).isEmpty())
            expectCode("SESSION_INVALID") { f.controller.act(token, previous.id, PhoneAction.Scroll("scroll", true)) }
            val resumed = f.controller.resume()
            assertTrue(f.controller.shoppingEvidence(resumed).isEmpty())
            assertEquals(1, f.backend.actions)
        }
    }

    @Test fun `ordinary product verification wording remains readable evidence`() = runBlocking {
        for (text in listOf("产品质量经过测试验证", "实验验证结果", "快速验证产品性能")) Fixture().use { f ->
            val token = f.start()
            f.backend.text = text
            val screen = f.controller.observe(token)
            assertEquals(PhoneSessionStatus.RUNNING, f.controller.state.value.status)
            assertFalse(screen.sensitive)
            assertEquals(text, f.controller.shoppingEvidence(token).single().nodes.first().text)
            assertEquals(0, f.backend.actions)
        }
    }

    @Test fun `coordinate swipe cannot activate payment sliders`() = runBlocking {
        Fixture().use { f ->
            val token = f.start()
            f.backend.text = "滑动确认支付"
            val screen = f.controller.observe(token)
            expectCode("PURCHASE_CONFIRMATION_REQUIRED") {
                f.controller.act(token, screen.id, PhoneAction.Swipe(PhoneSwipeDirection.RIGHT))
            }
            assertEquals(0, f.backend.actions)
            assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
        }
    }

    @Test fun `incomplete observations are never published and revoke previous action and screenshot snapshots`() = runBlocking {
        for (action in listOf(PhoneAction.Click("button"), PhoneAction.Screenshot)) {
            for (hasPreviousObservation in listOf(false, true)) {
                Fixture().use { f ->
                    val token = f.start(allowScreenshots = true)
                    val previous = if (hasPreviousObservation) f.controller.observe(token) else null
                    val publishedCount = f.controller.state.value.audit.count { it.operation == "observe" }
                    f.backend.truncated = true
                    f.backend.text = "private incomplete page"

                    expectCode("INCOMPLETE_SCREEN") { f.controller.observe(token) }

                    assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
                    assertEquals(terminalInspectionFailures.getValue("INCOMPLETE_SCREEN"), f.controller.state.value.detail)
                    assertFalse(f.backend.lastReadPermit!!.isValid())
                    assertTrue(f.controller.shoppingEvidence(token).isEmpty())
                    assertEquals(publishedCount, f.controller.state.value.audit.count { it.operation == "observe" })
                    assertFalse(f.controller.state.value.audit.toString().contains("private"))
                    assertEquals(0, f.backend.actions)
                    expectCode("SESSION_INVALID") { f.controller.act(token, previous?.id, action) }

                    val resumed = f.controller.resume()
                    expectCode("STALE_SNAPSHOT") { f.controller.act(resumed, previous?.id, action) }
                    assertTrue(f.controller.shoppingEvidence(resumed).isEmpty())
                    assertEquals(0, f.backend.actions)
                }
            }
        }
    }

    @Test fun `safe preview compression still permits navigation but final purchase is local guarded`() = runBlocking {
        Fixture().use { f ->
            f.backend.previewTruncated = true
            val token = f.start()
            val screen = f.controller.observe(token)
            assertTrue(screen.previewTruncated)
            assertFalse(screen.truncated)
            assertEquals(PhoneSessionStatus.RUNNING, f.controller.state.value.status)
            assertTrue(f.controller.shoppingEvidence(token).isNotEmpty())
            assertTrue(f.controller.act(token, screen.id, PhoneAction.Scroll("scroll", true)).accepted)
            assertEquals(token, f.controller.activeToken("chat", "assistant"))
            assertEquals(1, f.backend.actions)
        }
        listOf("确认付款", "提交订单", "开通会员", "免费试用", "使用积分").forEach { label ->
            Fixture().use { f ->
                f.backend.text = label
                val token = f.start()
                val screen = f.controller.observe(token)
                assertEquals(label, screen.nodes.first().text)
                expectCode("PURCHASE_CONFIRMATION_REQUIRED") { f.controller.act(token, screen.id, PhoneAction.Click("button")) }
                assertEquals(0, f.backend.actions)
                assertEquals(PhoneSessionStatus.PAUSED, f.controller.state.value.status)
            }
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
