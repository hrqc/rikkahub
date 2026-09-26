package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.mobileagent.PhoneActivity
import me.rerere.rikkahub.data.mobileagent.PhoneAuditEntry
import me.rerere.rikkahub.data.mobileagent.PhoneBackendState
import me.rerere.rikkahub.data.mobileagent.PhoneSessionState
import me.rerere.rikkahub.data.mobileagent.PhoneSessionStatus
import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken

internal enum class MobileAgentOverlayAction { PAUSE, RESUME, STOP, OPEN_CHAT }

/** This model deliberately contains no page text, task text, model text, or raw error messages. */
internal data class MobileAgentOverlayModel(
    val token: PhoneSessionToken,
    val targetPackage: String,
    val statusLabel: String,
    val phaseLabel: String,
    val elapsedMillis: Long,
    val durationLimitMillis: Long,
    val actionsUsed: Int,
    val actionLimit: Int,
    val observationsUsed: Int,
    val observationLimit: Int,
    val recentEvents: List<String>,
    val canPause: Boolean,
    val canResume: Boolean,
)

/** Pure presentation and callback checks, independently testable without an Android window. */
internal object MobileAgentOverlayPresenter {
    fun present(
        session: PhoneSessionState,
        backend: PhoneBackendState,
        nowMillis: Long,
        canResume: Boolean = true,
    ): MobileAgentOverlayModel? {
        val token = session.token ?: return null
        if (session.status !in setOf(PhoneSessionStatus.RUNNING, PhoneSessionStatus.PAUSED, PhoneSessionStatus.WAITING_FOR_FOREGROUND)) return null
        if (!backend.connected || backend.locked || backend.windowId == null || session.targetPackage.isBlank() ||
            backend.foregroundPackage != session.targetPackage
        ) return null

        val elapsed = (nowMillis - session.startedAtMillis).coerceAtLeast(0)
        val running = session.status == PhoneSessionStatus.RUNNING
        val withinBudget = elapsed < session.durationLimitMillis && session.actionsUsed < session.actionLimit &&
            session.observationsUsed < session.observationLimit
        val status = when (session.status) {
            PhoneSessionStatus.RUNNING -> when (session.activity) {
                PhoneActivity.READY -> "已就绪"
                PhoneActivity.WAITING_MODEL -> "等待 AI"
                PhoneActivity.OBSERVING -> "读取页面"
                PhoneActivity.ACTING -> "操作中"
            }
            PhoneSessionStatus.PAUSED -> "已暂停"
            else -> "等待中"
        }
        val phase = when {
            !running && !canResume -> "请回到聊天继续任务"
            !running && !withinBudget -> "本次额度已用完，请回到聊天"
            session.status == PhoneSessionStatus.PAUSED -> "已暂停，等待你继续"
            session.status == PhoneSessionStatus.WAITING_FOR_FOREGROUND -> "目标已回到前台，等待你继续"
            else -> when (session.activity) {
                PhoneActivity.READY -> "已就绪"
                PhoneActivity.WAITING_MODEL -> "等待 AI"
                PhoneActivity.OBSERVING -> "正在读取页面"
                PhoneActivity.ACTING -> "正在操作"
            }
        }
        return MobileAgentOverlayModel(
            token = token,
            targetPackage = session.targetPackage,
            statusLabel = status,
            phaseLabel = phase,
            elapsedMillis = elapsed,
            durationLimitMillis = session.durationLimitMillis.coerceAtLeast(0),
            actionsUsed = session.actionsUsed.coerceAtLeast(0),
            actionLimit = session.actionLimit.coerceAtLeast(0),
            observationsUsed = session.observationsUsed.coerceAtLeast(0),
            observationLimit = session.observationLimit.coerceAtLeast(0),
            recentEvents = session.audit.takeLast(3).map { eventLabel(it, session.startedAtMillis) },
            canPause = running,
            canResume = !running && canResume && withinBudget,
        )
    }

    fun allows(action: MobileAgentOverlayAction, capturedToken: PhoneSessionToken, current: MobileAgentOverlayModel?): Boolean {
        if (current == null || current.token != capturedToken) return false
        return when (action) {
            MobileAgentOverlayAction.PAUSE -> current.canPause
            MobileAgentOverlayAction.RESUME -> current.canResume
            MobileAgentOverlayAction.STOP, MobileAgentOverlayAction.OPEN_CHAT -> true
        }
    }

    fun duration(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1_000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun eventLabel(entry: PhoneAuditEntry, startedAtMillis: Long): String {
        val operation = when (entry.operation) {
            "start" -> "开始任务"
            "pause" -> "暂停任务"
            "stop" -> "停止任务"
            "resume" -> "继续任务"
            "error" -> "执行受阻"
            "observe", "Observe" -> "读取页面"
            "OpenApp" -> "打开应用"
            "Click" -> "点击"
            "LongClick" -> "长按"
            "InputText" -> "输入文字"
            "Scroll", "Swipe" -> "滚动页面"
            "Back" -> "返回"
            "Screenshot" -> "窗口截图"
            else -> "状态更新"
        }
        // Never pass through detail/result: callers can supply reasons containing arbitrary text.
        val result = if (entry.operation == "error") when (entry.result) {
            "PAGE_UNSTABLE" -> "页面仍在变化"
            "STALE_WINDOW", "STALE_SNAPSHOT", "STALE_NODE" -> "页面已变化，需重新读取"
            "WINDOW_TRANSITION" -> "等待窗口就绪"
            "FOREGROUND_CONFLICT", "FOREGROUND_CHANGED", "TARGET_NOT_FOREGROUND" -> "目标窗口未就绪或有遮挡"
            "USER_REQUIRED", "USER_HANDOVER_REQUIRED", "INCOMPLETE_SCREEN" -> "需要你接手"
            "ACTION_LIMIT", "OBSERVATION_LIMIT", "BUDGET_EXHAUSTED" -> "本次额度已用完"
            "OBSERVATION_UNAVAILABLE", "WINDOW_UNAVAILABLE" -> "暂时无法读取页面"
            "DEVICE_LOCKED" -> "手机已锁定"
            "SESSION_INVALID", "SESSION_INACTIVE" -> "本次授权已失效"
            else -> "操作未完成"
        } else when (entry.result) {
            "用户开启会话" -> "已开启"
            "已读取目标页面" -> "已读取"
            "平台未接受动作" -> "未被接受"
            "页面已变化" -> "页面有变化"
            "页面未确认变化" -> "未确认页面变化"
            "已获取目标窗口截图" -> "已获取"
            else -> "已记录"
        }
        return "${duration(entry.atMillis - startedAtMillis)}  $operation · $result"
    }
}
