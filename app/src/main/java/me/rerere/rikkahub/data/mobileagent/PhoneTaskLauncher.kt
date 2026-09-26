package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Opens the selected app locally before a caller may submit a model task. */
class PhoneTaskLauncher(
    private val controller: PhoneController,
    private val backend: PhoneBackend,
    private val timeoutMillis: Long = 60_000,
) {
    init { require(timeoutMillis in 1..60_000) }

    suspend fun prepare(token: PhoneSessionToken) {
        try {
            val ready = withTimeoutOrNull(timeoutMillis) {
                requireActive(token)
                // Exactly one launch. An OEM confirmation belongs to the user; while it is
                // visible, only passive window metadata is consulted, never its node tree.
                val result = controller.act(token, null, PhoneAction.OpenApp)
                if (!result.accepted) {
                    throw PhoneControlException("TARGET_OPEN_REJECTED", "未能打开目标应用，未发送模型请求，请检查后恢复。")
                }
                // OpenApp may have been accepted before its post-action observation succeeds.
                // Keep waiting only if the original authorization is still running.
                combine(controller.state, backend.state) { session, environment ->
                    requireActive(token, session, environment)
                    environment.foregroundPackage == session.targetPackage && environment.windowId != null
                }.first { it }
                requireReady(token)
                true
            } ?: false
            if (!ready) {
                controller.pauseIfCurrent(token, "等待目标应用超时，未发送模型请求，请检查后恢复")
                throw PhoneControlException("TARGET_PREPARATION_TIMEOUT", "等待目标应用超时，未发送模型请求，请检查后恢复。")
            }
        } catch (cancelled: CancellationException) {
            controller.pauseIfCurrent(token, "目标应用准备已取消，未发送模型请求，请检查后恢复")
            throw cancelled
        } catch (error: Exception) {
            controller.pauseIfCurrent(token, "目标应用尚未准备就绪，未发送模型请求，请检查后恢复")
            throw error
        }
    }

    /** Call again immediately before submitting the model message, without suspending in between. */
    suspend fun requireReady(token: PhoneSessionToken) {
        currentCoroutineContext().ensureActive()
        val session = controller.state.value
        val environment = backend.state.value
        requireActive(token, session, environment)
        if (environment.foregroundPackage != session.targetPackage || environment.windowId == null) {
            controller.pauseIfCurrent(token, "目标窗口已改变，未发送模型请求，请检查后恢复")
            throw PhoneControlException("TARGET_NOT_READY", "目标窗口已改变，未发送模型请求，请检查后恢复。")
        }
    }

    private fun requireActive(
        token: PhoneSessionToken,
        session: PhoneSessionState = controller.state.value,
        environment: PhoneBackendState = backend.state.value,
    ) {
        if (session.token != token || session.status != PhoneSessionStatus.RUNNING ||
            controller.activeToken(token.conversationId, token.assistantId) != token
        ) {
            throw PhoneControlException("SESSION_INVALID", "手机控制已暂停或停止，未发送模型请求，请检查后手动恢复。")
        }
        if (!environment.connected) {
            throw PhoneControlException("SERVICE_DISCONNECTED", "无障碍服务已断开，未发送模型请求。")
        }
        if (environment.locked) {
            throw PhoneControlException("DEVICE_LOCKED", "手机已锁定，未发送模型请求，请解锁后恢复。")
        }
    }
}
