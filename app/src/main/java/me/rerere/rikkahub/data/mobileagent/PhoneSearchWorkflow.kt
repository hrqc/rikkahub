package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class PhoneSearchStep(val operation: String, val accepted: Boolean, val executor: PhoneActionExecutor?)

@Serializable
internal data class PhoneSearchResult(
    val status: String,
    val detail: String,
    val steps: List<PhoneSearchStep> = emptyList(),
    val observation: PhoneObservation? = null,
    val queryConfirmed: Boolean = false,
    val shoppingTaskCompleted: Boolean = false,
    val errorCode: String? = null,
    @SerialName("snapshot_validation") val snapshotValidation: JsonObject? = null,
)

/** Local composition of the existing guarded operations; it grants no new execution authority. */
internal class PhoneSearchWorkflow(
    private val observe: suspend () -> PhoneObservation,
    private val act: suspend (PhoneObservation, PhoneAction) -> PhoneActionResult,
) {
    constructor(controller: PhoneController, token: PhoneSessionToken) : this(
        observe = { controller.observe(token) },
        act = { observation, action -> controller.act(token, observation.id, action) },
    )

    suspend fun search(query: String): PhoneSearchResult {
        val value = query.trim()
        if (value.isBlank() || value.length > 120 || value.any { it.code < 32 }) {
            return PhoneSearchResult("INVALID_QUERY", "查询词须为 1–120 个字符，不能含控制字符。")
        }
        val steps = mutableListOf<PhoneSearchStep>()
        var current: PhoneObservation? = null
        var stage = "OBSERVE"
        fun stopped(code: String, detail: String) = PhoneSearchResult(code, detail, steps.toList(), current)
        suspend fun dispatch(operation: String, action: PhoneAction): Boolean {
            // Each action still validates its own fresh snapshot, authorization, budget and window.
            stage = operation
            val result = act(checkNotNull(current), action)
            steps += PhoneSearchStep(operation, result.accepted, result.executor)
            current = result.observation
            return result.accepted && current?.let(::isCompleteJdSearchObservation) == true
        }
        try {
            current = observe()
            if (!isCompleteJdSearchObservation(checkNotNull(current))) {
                return stopped("SEARCH_PAGE_UNAVAILABLE", "当前不是可完整读取的京东普通页面，请检查观察结果。")
            }
            if (jdSearchEditor(checkNotNull(current)) == null) {
                val entry = jdHomeSearchEntry(checkNotNull(current))
                    ?: return stopped("SEARCH_ENTRY_UNRESOLVED", "没有找到可核对的普通搜索入口；未猜测坐标或点击其他控件。")
                if (!dispatch("OPEN_SEARCH", PhoneAction.Click(entry.id))) {
                    return stopped("SEARCH_ACTION_UNVERIFIED", "搜索入口动作未被接受或后续页面未确认；本次不重放。")
                }
            }
            val editor = jdSearchEditor(checkNotNull(current))
                ?: return stopped("SEARCH_INPUT_UNRESOLVED", "打开后没有找到与搜索按钮对应的唯一输入框。")
            if (normalizePhoneSearchQuery(editor.text) != normalizePhoneSearchQuery(value)) {
                if (!dispatch("INPUT_QUERY", PhoneAction.InputText(editor.id, value))) {
                    return stopped("SEARCH_ACTION_UNVERIFIED", "输入动作未被接受或后续页面未确认；本次不重放。")
                }
            }
            val confirmedEditor = jdSearchEditor(checkNotNull(current))
            if (confirmedEditor == null || normalizePhoneSearchQuery(confirmedEditor.text) != normalizePhoneSearchQuery(value)) {
                return stopped("QUERY_NOT_CONFIRMED", "输入框尚未确认显示用户查询词；未提交推荐词。")
            }
            val submit = jdSearchSubmit(checkNotNull(current), confirmedEditor)
                ?: return stopped("SEARCH_SUBMIT_UNRESOLVED", "没有找到与查询框对应的唯一搜索按钮。")
            if (!dispatch("SUBMIT_QUERY", PhoneAction.Click(submit.id))) {
                return stopped("SEARCH_ACTION_UNVERIFIED", "搜索提交未被接受或后续页面未确认；先观察实际结果，不重复提交。")
            }
            val queryVisible = checkNotNull(current).nodes.any {
                isTopSearchNode(it, checkNotNull(current)) && normalizePhoneSearchQuery(it.text) == normalizePhoneSearchQuery(value)
            }
            return PhoneSearchResult("QUERY_SUBMITTED", "已按已核对的查询词提交搜索；请依据返回页面核对搜索结果，再采集商品。",
                steps.toList(), current, queryConfirmed = queryVisible)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PhoneControlException) {
            // A backend exception may follow dispatch. Never retry it or label it 'not executed'.
            return PhoneSearchResult("SEARCH_STOPPED", "本地搜索在 $stage 阶段停止。已记录步骤不重放；重新观察后核对当前页面。",
                steps.toList(), errorCode = failure.code.takeIf { it.matches(Regex("[A-Za-z_]{1,64}")) },
                snapshotValidation = failure.snapshotRejection?.let(::phoneSnapshotRejectionJson))
        }
    }
}

internal fun isCompleteJdSearchObservation(observation: PhoneObservation): Boolean =
    observation.packageName == "com.jingdong.app.mall" && !observation.sensitive && !observation.truncated &&
        !observation.scrollOnly && observation.inspectionIssues.isEmpty()

internal fun normalizePhoneSearchQuery(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()

private fun ordinarySearchNode(node: PhoneNode): Boolean = node.enabled && !node.password && !node.requiresUserConfirmation

private fun isTopSearchNode(node: PhoneNode, observation: PhoneObservation): Boolean {
    val bottom = observation.nodes.maxOfOrNull { it.bounds.bottom } ?: return false
    return node.bounds.left >= 0 && node.bounds.top >= 0 && node.bounds.right > node.bounds.left &&
        node.bounds.bottom > node.bounds.top && node.bounds.bottom * 3L <= bottom
}

internal fun jdSearchSubmit(observation: PhoneObservation, editor: PhoneNode): PhoneNode? {
    // Observed JD search module: a wide EditText row above a camera/Search control row.
    // A generic top-of-page editor beside an unrelated search button is not a query field.
    if (editor.role != "android.widget.EditText" || observation.nodes.none {
            it.viewId.startsWith("com.jd.lib.search.feature:id/") && ordinarySearchNode(it)
        }) return null
    val camera = observation.nodes.filter { ordinarySearchNode(it) && it.clickable &&
        it.role == "android.widget.Button" && it.description == "拍照购" && isTopSearchNode(it, observation)
    }.singleOrNull() ?: return null
    return observation.nodes.filter {
        ordinarySearchNode(it) && it.role == "android.view.ViewGroup" && it.clickable &&
            !it.editable && !it.scrollable && it.description.trim() == "搜索 按钮" &&
            it.parentId != null && it.parentId == camera.parentId && isTopSearchNode(it, observation) &&
            editor.bounds.left < camera.bounds.left && editor.bounds.right >= it.bounds.right &&
            camera.bounds.right <= it.bounds.left &&
            editor.bounds.bottom <= it.bounds.top &&
            it.bounds.top - editor.bounds.bottom <= (editor.bounds.bottom - editor.bounds.top) * 2 &&
            camera.bounds.top >= it.bounds.top && camera.bounds.bottom <= it.bounds.bottom
    }.singleOrNull()
}

internal fun jdSearchEditor(observation: PhoneObservation): PhoneNode? = observation.nodes.filter {
    ordinarySearchNode(it) && it.editable && isTopSearchNode(it, observation) &&
        jdSearchSubmit(observation, it) != null
}.singleOrNull()

/** The observed JD homepage entry, including its fixed search-bar and camera siblings. */
internal fun jdHomeSearchEntry(observation: PhoneObservation): PhoneNode? {
    val bars = observation.nodes.filter { it.viewId == "com.jingdong.app.mall:id/b2q" && it.description == "搜索栏" &&
        ordinarySearchNode(it) && !it.clickable && isTopSearchNode(it, observation) }
    val bar = bars.singleOrNull() ?: return null
    val parent = observation.nodes.filter { it.parentId == bar.id && it.role == "android.view.ViewGroup" &&
        it.text.isBlank() && it.description.isBlank() && it.clickable && ordinarySearchNode(it) }.singleOrNull() ?: return null
    val siblings = observation.nodes.filter { it.parentId == parent.id }
    if (siblings.count { it.description == "搜索" && it.clickable && ordinarySearchNode(it) } != 1 ||
        siblings.count { it.description == "拍照购" && it.clickable && ordinarySearchNode(it) } != 1) return null
    return siblings.filter { candidate ->
        candidate.role == "android.view.ViewGroup" && candidate.text.isBlank() && candidate.description.isBlank() &&
            candidate.clickable && !candidate.editable && !candidate.scrollable && ordinarySearchNode(candidate) &&
            observation.nodes.filter { it.parentId == candidate.id }.singleOrNull()?.let {
                ordinarySearchNode(it) && it.role == "android.widget.TextView" && it.text.isNotBlank() &&
                    it.description == it.text && !it.clickable && !it.editable
            } == true
    }.singleOrNull()
}
