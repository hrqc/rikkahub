package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.mobileagent.PhoneBackend
import me.rerere.rikkahub.data.mobileagent.PhoneControlException
import me.rerere.rikkahub.data.mobileagent.PhoneController
import me.rerere.rikkahub.data.mobileagent.PhoneIntentBinding
import me.rerere.rikkahub.data.mobileagent.PhoneIntentGuard
import me.rerere.rikkahub.data.mobileagent.PhoneIntentProposal
import me.rerere.rikkahub.data.mobileagent.PhoneIntentStore
import me.rerere.rikkahub.data.mobileagent.PhoneSessionStatus
import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken
import me.rerere.rikkahub.data.mobileagent.PhoneTargetApp
import me.rerere.rikkahub.data.mobileagent.PhoneTaskLauncher
import java.util.concurrent.atomic.AtomicReference

data class PhoneChatContext(val conversationId: String, val assistantId: String, val selectedAssistantId: String, val supportsTools: Boolean)

/** The coordinator can be tested without Android, network providers or a real ChatService. */
interface PhoneChatHost {
    val context: StateFlow<PhoneChatContext>
    suspend fun awaitGenerationIdle()
    fun sendExecution(binding: PhoneIntentBinding, token: PhoneSessionToken): Job?
    fun invalidatePendingInputs()
}

enum class PhoneChatPhase { HIDDEN, CONFIRM, PREPARING, RUNNING, PAUSED, ENDED, ERROR }

data class PhoneChatControlState(
    val phase: PhoneChatPhase = PhoneChatPhase.HIDDEN,
    val detail: String = "",
    val targetLabel: String = "",
    val originalText: String = "",
    val proposalId: String? = null,
    val candidates: List<PhoneTargetApp> = emptyList(),
    val interpretedTask: String = "",
)

/** Owns only this visible chat's proposal and grant; model output never creates a grant by itself. */
class PhoneChatCoordinator(
    private val scope: CoroutineScope,
    private val host: PhoneChatHost,
    private val controller: PhoneController,
    backend: PhoneBackend,
    private val intents: PhoneIntentStore,
    private val loadTargets: suspend () -> List<PhoneTargetApp>,
    private val resolveTargets: (String, List<PhoneTargetApp>) -> List<PhoneTargetApp>,
    private val preparationTimeoutMillis: Long = 60_000,
) {
    private val launcher = PhoneTaskLauncher(controller, backend, preparationTimeoutMillis)
    private val visible = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(PhoneChatControlState())
    val state: StateFlow<PhoneChatControlState> = mutableState.asStateFlow()
    private var proposal: PhoneIntentProposal? = null
    private var proposalWork: Job? = null
    private var executionWork: Job? = null
    private var currentTask: Task? = null
    private var pendingLaunch: PendingLaunch? = null
    private var sequence = 0L
    private var lastProposalId: String? = null
    private var observedContext = host.context.value

    private data class PendingLaunch(val binding: PhoneIntentBinding, val packageName: String)

    private class Task(val binding: PhoneIntentBinding, val target: PhoneTargetApp, val token: PhoneSessionToken) {
        var generation: Job? = null
    }

    init {
        scope.launch {
            combine(visible, host.context, intents.proposals) { showing, context, proposals ->
                Triple(showing, context, proposals[context.conversationId])
            }.collect { (showing, context, incoming) ->
                val assistantChanged = context.assistantId != observedContext.assistantId ||
                    context.selectedAssistantId != observedContext.selectedAssistantId
                observedContext = context
                if (showing && visibleOwner.get() === this@PhoneChatCoordinator && assistantChanged) {
                    host.invalidatePendingInputs()
                    abandonCurrent("助手已切换，手机控制已停止")
                }
                if (currentTask?.let { !matches(it.binding, context) } == true ||
                    proposal?.let { !matches(it.binding, context) } == true ||
                    pendingLaunch?.let { !matches(it.binding, context) } == true
                ) abandonCurrent("聊天或助手已切换，手机控制已停止")
                if (showing && incoming != null && incoming.id != lastProposalId && matches(incoming.binding, context)) {
                    lastProposalId = incoming.id
                    consider(incoming)
                }
            }
        }
        scope.launch {
            controller.state.collect { session ->
                val task = currentTask ?: return@collect
                if (session.token?.sessionId != task.token.sessionId) return@collect
                when (session.status) {
                    PhoneSessionStatus.PAUSED, PhoneSessionStatus.WAITING_FOR_FOREGROUND -> {
                        task.generation?.cancel()
                        mutableState.value = taskState(task, PhoneChatPhase.PAUSED, session.detail)
                    }
                    PhoneSessionStatus.STOPPED, PhoneSessionStatus.EXPIRED -> {
                        task.generation?.cancel()
                        mutableState.value = taskState(task, PhoneChatPhase.ENDED, session.detail)
                    }
                    else -> Unit
                }
            }
        }
    }

    fun setVisible(value: Boolean) {
        if (value) {
            val previous = visibleOwner.getAndSet(this)
            if (previous !== this) previous?.hide(invalidatePending = true)
            visible.value = true
        } else {
            hide(invalidatePending = visibleOwner.compareAndSet(this, null))
        }
    }

    private fun hide(invalidatePending: Boolean) {
        visible.value = false
        // Also revoke the queued input/binding whose model proposal has not published yet.
        // A delayed disposal of an older page must not invalidate a newer visible owner.
        if (invalidatePending) host.invalidatePendingInputs()
        abandonCurrent("已离开当前聊天，手机控制已停止")
    }

    /** Call for a fresh input, edit or regeneration before it reaches the generation service. */
    fun abandonCurrent(reason: String = "已收到新的聊天操作，手机控制已停止") {
        ++sequence
        proposalWork?.cancel()
        executionWork?.cancel()
        proposalWork = null
        executionWork = null
        proposal?.let { intents.invalidate(it.binding) }
        proposal = null
        pendingLaunch?.let { intents.invalidate(it.binding) }
        pendingLaunch = null
        currentTask?.let { task ->
            intents.invalidate(task.binding)
            task.generation?.cancel()
            matchingToken(task)?.let { controller.stopIfCurrent(it, reason) }
        }
        currentTask = null
        mutableState.value = PhoneChatControlState()
    }

    private fun consider(incoming: PhoneIntentProposal) {
        if (!isCurrent(incoming.binding)) return
        proposalWork?.cancel()
        val expected = ++sequence
        proposal = incoming
        proposalWork = scope.launch {
            try {
                val allApps = loadTargets()
                if (expected != sequence || !isCurrent(incoming.binding)) return@launch
                val matches = resolveTargets(incoming.targetAppName, allApps)
                val candidates = matches.ifEmpty { allApps }
                // The model interprets intent. Local parsing only permits the small shortcut
                // for opening an exactly named app; every other proposal remains confirmable.
                val literalTarget = PhoneIntentGuard.automaticTargetName(incoming.binding.originalText)
                    ?.let { resolveTargets(it, allApps).singleOrNull() }
                val automatic = matches.size == 1 && literalTarget?.packageName == matches.single().packageName
                if (automatic) {
                    accept(incoming.id, matches.single().packageName)
                } else {
                    mutableState.value = PhoneChatControlState(
                        phase = PhoneChatPhase.CONFIRM,
                        detail = if (matches.isEmpty()) "请确认任务，并选择本次要操作的应用。" else "请确认任务和目标应用。",
                        originalText = incoming.binding.originalText,
                        proposalId = incoming.id,
                        candidates = candidates,
                        interpretedTask = incoming.summary,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expected == sequence) mutableState.value = PhoneChatControlState(PhoneChatPhase.ERROR, "无法读取目标应用，请稍后重试。")
            }
        }
    }

    fun accept(proposalId: String, packageName: String) {
        val pending = proposal?.takeIf { it.id == proposalId && isCurrent(it.binding) } ?: return
        // Consume before launching any asynchronous work; repeated clicks cannot issue another grant.
        val accepted = intents.consume(pending.binding.conversationId, proposalId) ?: return
        proposal = null
        launchAccepted(PendingLaunch(accepted.binding, packageName))
    }

    private fun launchAccepted(accepted: PendingLaunch) {
        val expected = ++sequence
        pendingLaunch = accepted
        mutableState.value = PhoneChatControlState(
            PhoneChatPhase.PREPARING, "正在准备本次操作，等待聊天与目标应用就绪。", originalText = accepted.binding.originalText,
        )
        executionWork = scope.launch {
            try {
                host.awaitGenerationIdle()
                val apps = loadTargets()
                val target = apps.singleOrNull { it.packageName == accepted.packageName }
                    ?: throw PhoneControlException("TARGET_UNAVAILABLE", "所选应用已不可用，请重新发送任务。")
                if (expected != sequence || !isCurrent(accepted.binding)) return@launch
                requireModel()
                val token = controller.start(
                    accepted.binding.conversationId, accepted.binding.assistantId, target.packageName,
                    useRoot = false, allowScreenshots = false, replaceExisting = false,
                )
                val task = Task(accepted.binding, target, token)
                pendingLaunch = null
                currentTask = task
                runTask(task)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (expected == sequence && currentTask == null) {
                    pendingLaunch = null
                    mutableState.value = PhoneChatControlState(PhoneChatPhase.ERROR, message(error))
                }
            }
        }
    }

    fun dismissProposal() {
        proposal?.let { intents.invalidate(it.binding) }
        proposal = null
        ++sequence
        proposalWork?.cancel()
        mutableState.value = PhoneChatControlState()
    }

    fun pause() {
        val task = currentTask
        if (task == null) {
            val pending = pendingLaunch ?: return
            ++sequence
            executionWork?.cancel()
            mutableState.value = PhoneChatControlState(PhoneChatPhase.PAUSED, "任务尚未开始，已暂停。", originalText = pending.binding.originalText)
            return
        }
        matchingToken(task)?.let { controller.pauseIfCurrent(it, "用户暂停，请确认当前页面后继续") }
        task.generation?.cancel()
        executionWork?.cancel()
    }

    fun resume() {
        pendingLaunch?.let {
            if (state.value.phase == PhoneChatPhase.PAUSED && isCurrent(it.binding)) launchAccepted(it)
            return
        }
        val previous = currentTask ?: return
        if (!isCurrent(previous.binding) || matchingToken(previous) == null) return
        if (controller.state.value.status !in setOf(PhoneSessionStatus.PAUSED, PhoneSessionStatus.WAITING_FOR_FOREGROUND)) return
        val expected = ++sequence
        executionWork?.cancel()
        executionWork = scope.launch {
            try {
                previous.generation?.join()
                if (expected != sequence || !isCurrent(previous.binding) || matchingToken(previous) == null) return@launch
                requireModel()
                val task = Task(previous.binding, previous.target, controller.resume())
                currentTask = task
                runTask(task)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (expected == sequence) mutableState.value = taskState(previous, PhoneChatPhase.PAUSED, message(error))
            }
        }
    }

    fun stop() {
        val task = currentTask
        ++sequence
        proposalWork?.cancel()
        proposal?.let { intents.invalidate(it.binding) }
        proposal = null
        pendingLaunch?.let { intents.invalidate(it.binding) }
        pendingLaunch = null
        task?.let {
            intents.invalidate(it.binding)
            matchingToken(it)?.let { token -> controller.stopIfCurrent(token) }
            it.generation?.cancel()
            mutableState.value = taskState(it, PhoneChatPhase.ENDED, "手机控制已停止")
        }
        executionWork?.cancel()
        currentTask = null
        if (task == null) mutableState.value = PhoneChatControlState(PhoneChatPhase.ENDED, "手机控制已停止")
    }

    private suspend fun runTask(task: Task) {
        mutableState.value = taskState(task, PhoneChatPhase.PREPARING, "正在打开目标应用，准备好后开始执行。系统许可弹窗需要你确认。")
        try {
            launcher.prepare(task.token)
            if (currentTask !== task || !isCurrent(task.binding)) {
                controller.stopIfCurrent(task.token, "本次聊天授权已失效，手机控制已停止")
                return
            }
            requireModel()
            launcher.requireReady(task.token)
            val generation = host.sendExecution(task.binding, task.token)
                ?: throw PhoneControlException("CHAT_BUSY", "当前聊天状态已改变，任务已暂停，请检查后继续。")
            task.generation = generation
            mutableState.value = taskState(task, PhoneChatPhase.RUNNING, "正在执行，可随时暂停或停止。")
            generation.join()
            if (currentTask !== task) return
            val session = controller.state.value
            if (session.token?.sessionId != task.token.sessionId) return
            if (session.status in setOf(PhoneSessionStatus.PAUSED, PhoneSessionStatus.WAITING_FOR_FOREGROUND)) {
                mutableState.value = taskState(task, PhoneChatPhase.PAUSED, session.detail)
            } else {
                controller.stopIfCurrent(task.token, "本轮模型执行已结束，手机控制授权已撤销")
                mutableState.value = taskState(task, PhoneChatPhase.ENDED, "本轮执行已结束，请查看聊天结果。")
                intents.invalidate(task.binding)
            }
        } catch (cancelled: CancellationException) {
            controller.pauseIfCurrent(task.token, "任务准备已取消，请检查后继续")
            throw cancelled
        } catch (error: Exception) {
            controller.pauseIfCurrent(task.token, "未能继续执行，请检查后手动恢复")
            if (currentTask === task) mutableState.value = taskState(
                task,
                if (controller.state.value.status in setOf(PhoneSessionStatus.STOPPED, PhoneSessionStatus.EXPIRED)) PhoneChatPhase.ENDED else PhoneChatPhase.PAUSED,
                message(error),
            )
        }
    }

    private fun requireModel() {
        if (!host.context.value.supportsTools) throw PhoneControlException("MODEL_UNAVAILABLE", "请先选择支持工具调用的聊天模型。")
    }

    private fun isCurrent(binding: PhoneIntentBinding) = visible.value && intents.isCurrent(binding) && matches(binding, host.context.value)

    private fun matches(binding: PhoneIntentBinding, context: PhoneChatContext) =
        binding.conversationId == context.conversationId && binding.assistantId == context.assistantId &&
            binding.assistantId == context.selectedAssistantId

    private fun matchingToken(task: Task) = controller.state.value.token?.takeIf {
        it.sessionId == task.token.sessionId && it.conversationId == task.binding.conversationId && it.assistantId == task.binding.assistantId
    }

    private fun taskState(task: Task, phase: PhoneChatPhase, detail: String) = PhoneChatControlState(
        phase, detail, task.target.label, task.binding.originalText,
    )

    private fun message(error: Exception) = if (error is PhoneControlException) error.message ?: "手机控制暂不可用。" else "手机控制暂不可用，请检查后重试。"

    private companion object {
        val visibleOwner = AtomicReference<PhoneChatCoordinator?>(null)
    }
}
