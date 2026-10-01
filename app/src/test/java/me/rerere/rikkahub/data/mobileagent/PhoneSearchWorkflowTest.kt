package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneSearchWorkflowTest {
    private val query = "双 Type-C 60W 1米 数据线"
    private fun node(id: String, role: String = "android.view.ViewGroup", parent: String? = null,
                     text: String = "", description: String = "", clickable: Boolean = false,
                     editable: Boolean = false, bounds: PhoneBounds = PhoneBounds(100, 120, 700, 180),
                     viewId: String = "") = PhoneNode(id = id, role = role, text = text, description = description, viewId = viewId, bounds = bounds,
        clickable = clickable, editable = editable, enabled = true, parentId = parent)

    private fun page(vararg children: PhoneNode, id: String = "page") = PhoneObservation(
        id, "com.jingdong.app.mall", 7, 10, 1_000,
        listOf(node("root", role = "android.widget.FrameLayout", bounds = PhoneBounds(0, 0, 1000, 2000))) + children,
        truncated = false, sensitive = false, fingerprint = id,
    )

    private fun input(value: String = "", id: String = "input") = page(
        node("editor", "android.widget.EditText", text = value, editable = true, bounds = PhoneBounds(150, 180, 1000, 240)),
        node("camera", "android.widget.Button", parent = "controls", description = "拍照购", clickable = true,
            bounds = PhoneBounds(700, 300, 770, 360)),
        node("submit", "android.view.ViewGroup", parent = "controls", description = "搜索 按钮", clickable = true,
            bounds = PhoneBounds(810, 288, 975, 372)),
        node("module", "android.widget.ScrollView", viewId = "com.jd.lib.search.feature:id/aa1", bounds = PhoneBounds(0, 405, 1000, 1900)), id = id,
    )

    private fun home() = page(
        node("bar", "android.widget.RelativeLayout", description = "搜索栏", viewId = "com.jingdong.app.mall:id/b2q"),
        node("parent", parent = "bar", clickable = true),
        node("entry", parent = "parent", clickable = true),
        node("word", "android.widget.TextView", "entry", text = "动态推荐词", description = "动态推荐词"),
        node("camera", "android.widget.Button", "parent", description = "拍照购", clickable = true),
        node("button", "android.widget.Button", "parent", description = "搜索", clickable = true), id = "home",
    )

    @Test fun `homepage search uses fresh observations for all three actions`() = runBlocking {
        val actions = mutableListOf<Pair<String, PhoneAction>>()
        val pages = ArrayDeque(listOf(input(), input(query, "typed"), page(node("query", text = query), id = "results")))
        val workflow = PhoneSearchWorkflow({ home() }) { before, action ->
            actions += before.id to action
            PhoneActionResult(true, true, "accepted", pages.removeFirst(), executor = PhoneActionExecutor.ACCESSIBILITY)
        }
        val result = workflow.search(query)
        assertEquals("QUERY_SUBMITTED", result.status)
        assertEquals(listOf("home", "input", "typed"), actions.map { it.first })
        assertEquals(listOf(PhoneAction.Click("entry"), PhoneAction.InputText("editor", query), PhoneAction.Click("submit")), actions.map { it.second })
        assertEquals(3, result.steps.size)
        assertTrue(result.queryConfirmed)
        assertFalse(result.shoppingTaskCompleted)
    }

    @Test fun `existing search editor skips homepage navigation`() = runBlocking {
        val actions = mutableListOf<PhoneAction>()
        val workflow = PhoneSearchWorkflow({ input() }) { _, action ->
            actions += action
            PhoneActionResult(true, observation = input(query), detail = "accepted")
        }
        assertEquals("QUERY_SUBMITTED", workflow.search(query).status)
        assertEquals(listOf(PhoneAction.InputText("editor", query), PhoneAction.Click("submit")), actions)
    }

    @Test fun `query already present is not retyped`() = runBlocking {
        val actions = mutableListOf<PhoneAction>()
        val workflow = PhoneSearchWorkflow({ input(query) }) { _, action ->
            actions += action
            PhoneActionResult(true, observation = input(query), detail = "accepted")
        }
        assertEquals("QUERY_SUBMITTED", workflow.search(query).status)
        assertEquals(listOf(PhoneAction.Click("submit")), actions)
    }

    @Test fun `platform accepting input without query echo cannot submit recommendation`() = runBlocking {
        val actions = mutableListOf<PhoneAction>()
        val workflow = PhoneSearchWorkflow({ input("推荐词") }) { _, action ->
            actions += action
            PhoneActionResult(true, observation = input("推荐词"), detail = "accepted")
        }
        assertEquals("QUERY_NOT_CONFIRMED", workflow.search(query).status)
        assertEquals(1, actions.size)
        assertTrue(actions.single() is PhoneAction.InputText)
    }

    @Test fun `accepted action with missing observation is retained and never repeated`() = runBlocking {
        var calls = 0
        val workflow = PhoneSearchWorkflow({ home() }) { _, _ ->
            calls++
            PhoneActionResult(true, detail = "post-observation unavailable", executor = PhoneActionExecutor.ROOT_INPUT)
        }
        val result = workflow.search(query)
        assertEquals("SEARCH_ACTION_UNVERIFIED", result.status)
        assertEquals(1, calls)
        assertTrue(result.steps.single().accepted)
        assertEquals(PhoneActionExecutor.ROOT_INPUT, result.steps.single().executor)
    }

    @Test fun `stale refusal stops instead of repeating action or observation`() = runBlocking {
        var observations = 0
        var actions = 0
        val workflow = PhoneSearchWorkflow({ observations++; home() }) { _, _ ->
            actions++
            throw PhoneControlException("STALE_SNAPSHOT", "changed")
        }
        val result = workflow.search(query)
        assertEquals("SEARCH_STOPPED", result.status)
        assertEquals("STALE_SNAPSHOT", result.errorCode)
        assertEquals(1, observations)
        assertEquals(1, actions)
    }

    @Test fun `wrong package incomplete and sensitive pages never produce action`() = runBlocking {
        val pages = listOf(home().copy(packageName = "other.app"), home().copy(truncated = true),
            home().copy(sensitive = true), home().copy(scrollOnly = true), home().copy(inspectionIssues = listOf("unavailable_child")))
        for (page in pages) {
            val workflow = PhoneSearchWorkflow({ page }) { _, _ -> error("must not act") }
            assertEquals("SEARCH_PAGE_UNAVAILABLE", workflow.search(query).status)
        }
    }

    @Test fun `restricted input and ambiguous editors are not selected`() = runBlocking {
        val source = input()
        val editor = source.nodes.single { it.id == "editor" }
        val restricted = source.copy(nodes = source.nodes.map { if (it.id == editor.id) it.copy(requiresUserConfirmation = true) else it })
        val ambiguous = source.copy(nodes = source.nodes + editor.copy(id = "second"))
        for (page in listOf(restricted, ambiguous)) {
            val workflow = PhoneSearchWorkflow({ page }) { _, _ -> error("must not act") }
            assertEquals("SEARCH_ENTRY_UNRESOLVED", workflow.search(query).status)
        }
    }

    @Test fun `invalid query is rejected before reading phone`() = runBlocking {
        val workflow = PhoneSearchWorkflow({ error("must not observe") }) { _, _ -> error("must not act") }
        for (value in listOf("", "a".repeat(121), "商品\n第二行")) {
            assertEquals("INVALID_QUERY", workflow.search(value).status)
        }
    }

    @Test fun `ordinary editor and unrelated search controls cannot accept query`() = runBlocking {
        val source = input()
        val unrelated = source.copy(nodes = source.nodes.map {
            if (it.id == "submit") it.copy(parentId = "unrelated-panel") else it
        })
        val noModule = source.copy(nodes = source.nodes.filterNot { it.id == "module" })
        val wrongRole = source.copy(nodes = source.nodes.map {
            if (it.id == "editor") it.copy(role = "android.widget.TextView") else it
        })
        for (page in listOf(unrelated, noModule, wrongRole)) {
            val workflow = PhoneSearchWorkflow({ page }) { _, _ -> error("must not enter unrelated field") }
            assertEquals("SEARCH_ENTRY_UNRESOLVED", workflow.search(query).status)
        }
    }

    @Test fun `search failure keeps exact fixed anchor diagnostic`() = runBlocking {
        val diagnostic = PhoneSnapshotRejection(PhoneSnapshotRejectionReason.REVISION_CHANGED, 200,
            revisionChanged = true, windowChanged = false, PhoneClickRevalidationDiagnostic(
                PhoneClickRevalidationStage.NO_ANCHOR, targetProofReason = PhoneClickProofRejection.MISSING_ANCESTOR))
        val workflow = PhoneSearchWorkflow({ home() }) { _, _ ->
            throw PhoneControlException("STALE_SNAPSHOT", "untrusted raw platform data", diagnostic)
        }
        val result = workflow.search(query)
        assertEquals(phoneSnapshotRejectionJson(diagnostic), result.snapshotValidation)
        assertFalse(result.toString().contains("untrusted raw platform data"))
    }

    @Test fun `cancellation is propagated and never replaced by automatic retry`() = runBlocking {
        var calls = 0
        val workflow = PhoneSearchWorkflow({ home() }) { _, _ -> calls++; throw CancellationException("stop") }
        try {
            workflow.search(query)
            fail("expected cancellation")
        } catch (_: CancellationException) { assertEquals(1, calls) }
    }
}
