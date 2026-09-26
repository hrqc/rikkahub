package me.rerere.rikkahub.ui.pages.extensions.agent

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.mobileagent.DeviceCapabilityRepository
import me.rerere.rikkahub.data.mobileagent.PhoneBackend
import me.rerere.rikkahub.data.mobileagent.PhoneAction
import me.rerere.rikkahub.data.mobileagent.PhoneControlException
import me.rerere.rikkahub.data.mobileagent.PhoneController
import me.rerere.rikkahub.data.mobileagent.PhoneSessionStatus
import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken
import me.rerere.rikkahub.data.mobileagent.PhoneTaskLauncher
import me.rerere.rikkahub.data.mobileagent.RootState
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

data class PhoneTargetApp(val packageName: String, val label: String)

class MobileControlVM(
    val conversationId: String,
    val assistantId: String,
    private val context: Context,
    settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val controller: PhoneController,
    private val backend: PhoneBackend,
    private val capabilityRepository: DeviceCapabilityRepository,
) : ViewModel() {
    private val conversationUuid = Uuid.parse(conversationId)
    private val assistantUuid = Uuid.parse(assistantId)
    val settings = settingsStore.settingsFlow
    val conversation = chatService.getConversationFlow(conversationUuid)
    val session = controller.state
    val backendState = backend.state
    val capabilities = capabilityRepository.capabilities
    val supportsScreenshot get() = backend.supportsScreenshot

    private val _apps = MutableStateFlow<List<PhoneTargetApp>>(emptyList())
    val apps = _apps.asStateFlow()
    private val _appsLoading = MutableStateFlow(false)
    val appsLoading = _appsLoading.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _generating = MutableStateFlow(false)
    val generating = _generating.asStateFlow()
    private val _preparedSessionId = MutableStateFlow<String?>(null)
    val preparedSessionId = _preparedSessionId.asStateFlow()
    private val _modelSessionId = MutableStateFlow<String?>(null)
    val modelSessionId = _modelSessionId.asStateFlow()
    private val _openingTarget = MutableStateFlow(false)
    val openingTarget = _openingTarget.asStateFlow()
    private var generationStateReady = false
    private var cancelGenerationJob: Job? = null
    private var appsJob: Job? = null
    private val taskLauncher = PhoneTaskLauncher(controller, backend)
    private var preparationJob: Job? = null
    private var preparationToken: PhoneSessionToken? = null
    private var preparationSequence = 0L
    private var pendingModelTask: Pair<String, String>? = null

    init {
        chatService.addConversationReference(conversationUuid)
        viewModelScope.launch {
            chatService.getGenerationJobStateFlow(conversationUuid).collect {
                _generating.value = it != null
                generationStateReady = true
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                capabilityRepository.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "设备状态读取失败，请稍后刷新。"
            }
        }
        if (appsJob?.isActive == true) return
        appsJob = viewModelScope.launch {
            _appsLoading.value = true
            try {
                _apps.value = withContext(Dispatchers.IO) {
                    val manager = context.packageManager
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    @Suppress("DEPRECATION")
                    manager.queryIntentActivities(intent, 0)
                        .filter { it.activityInfo?.enabled == true }
                        .mapNotNull { info ->
                            val name = info.activityInfo?.packageName ?: return@mapNotNull null
                            if (name == context.packageName || !backend.isTargetAllowed(name)) return@mapNotNull null
                            PhoneTargetApp(name, info.loadLabel(manager).toString().ifBlank { name })
                        }
                        .distinctBy { it.packageName }
                        .sortedWith(compareBy<PhoneTargetApp> { it.label.lowercase() }.thenBy { it.packageName })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "无法读取可启动应用列表，请刷新后重试。"
            } finally {
                _appsLoading.value = false
            }
        }
    }

    fun start(task: String, targetPackage: String, useRoot: Boolean, allowScreenshots: Boolean) {
        begin(task, targetPackage, useRoot, allowScreenshots, prepareOnly = false)
    }

    fun prepare(targetPackage: String, useRoot: Boolean, allowScreenshots: Boolean) {
        begin("", targetPackage, useRoot, allowScreenshots, prepareOnly = true)
    }

    private fun begin(task: String, targetPackage: String, useRoot: Boolean, allowScreenshots: Boolean, prepareOnly: Boolean) {
        if (_busy.value || _openingTarget.value) return
        _message.value = null
        if (session.value.status.isActive()) {
            _message.value = "已有手机控制任务，请先 STOP，再开始新的任务。"
            return
        }
        if (!validateConversation(requireModel = !prepareOnly)) return
        if (!prepareOnly && task.isBlank()) {
            _message.value = "请先填写要完成的手机操作任务。"
            return
        }
        if (_apps.value.none { it.packageName == targetPackage }) {
            _message.value = "请从列表明确选择一个目标应用。"
            return
        }
        if (useRoot && capabilities.value.root.state != RootState.ROOT_GRANTED) {
            _message.value = "请先在设备能力页手动验证 Root，或关闭本次 Root 增强。"
            return
        }
        if (allowScreenshots && !backend.supportsScreenshot) {
            _message.value = "当前截图后端不可用，请关闭允许截图后继续。"
            return
        }
        var startedToken: PhoneSessionToken? = null
        try {
            val token = controller.start(conversationId, assistantId, targetPackage, useRoot, allowScreenshots)
            startedToken = token
            _preparedSessionId.value = token.sessionId.takeIf { prepareOnly }
            _modelSessionId.value = token.sessionId.takeUnless { prepareOnly }
            pendingModelTask = (token.sessionId to task.trim()).takeUnless { prepareOnly }
            if (!prepareOnly) prepareAndSend(token, task.trim())
        } catch (error: Exception) {
            startedToken?.let { controller.pauseIfCurrent(it, "未能准备模型任务，请检查后恢复") }
            _message.value = safeMessage(error, "未能开始手机任务，请检查状态后重试。")
        }
    }

    fun pause() {
        if (!ownsSession() || _busy.value) return
        try {
            controller.pause()
            cancelPreparation()
            cancelGeneration(pauseOnly = true)
        } catch (error: Exception) {
            _message.value = safeMessage(error, "未能暂停任务，请使用 STOP 停止。")
        }
    }

    fun resume() {
        val sessionId = session.value.token?.sessionId ?: return
        val prepareOnly = _preparedSessionId.value == sessionId
        if (!prepareOnly && _modelSessionId.value != sessionId) {
            _message.value = "当前面板无法确认本次任务的执行方式，请先 STOP 再重新授权。"
            return
        }
        if (!ownsSession() || _busy.value || _openingTarget.value || !validateConversation(requireModel = !prepareOnly)) return
        _message.value = null
        var resumedToken: PhoneSessionToken? = null
        try {
            val token = controller.resume()
            resumedToken = token
            if (prepareOnly) {
                _preparedSessionId.value = token.sessionId
            } else {
                _modelSessionId.value = token.sessionId
                val task = pendingModelTask?.takeIf { it.first == token.sessionId }?.second
                    ?: "继续刚才未完成的手机操作任务。目标应用已在本地打开，请先重新读取当前页面，再决定下一步操作。"
                prepareAndSend(token, task)
            }
        } catch (error: Exception) {
            resumedToken?.let { controller.pauseIfCurrent(it, "未能准备恢复任务，请检查后恢复") }
            _message.value = safeMessage(error, "未能恢复任务，请检查状态后重试。")
        }
    }

    fun stop() {
        // Revocation is synchronous: STOP must not wait for network cancellation or a UI coroutine.
        controller.stopForConversation(conversationId)
        cancelPreparation()
        cancelGeneration(pauseOnly = false)
    }

    private fun prepareAndSend(token: PhoneSessionToken, task: String) {
        val sequence = ++preparationSequence
        preparationToken = token
        _openingTarget.value = true
        preparationJob = viewModelScope.launch {
            try {
                taskLauncher.prepare(token)
                if (!validateConversation()) {
                    controller.pauseIfCurrent(token, "模型或会话状态已变化，未发送模型请求，请检查后恢复")
                    return@launch
                }
                taskLauncher.requireReady(token)
                if (chatService.sendPhoneTask(conversationUuid, token, listOf(UIMessagePart.Text(task))) == null) {
                    throw PhoneControlException("CHAT_BUSY", "聊天或控制授权状态已变化，未发送模型请求，请检查后恢复。")
                }
                if (pendingModelTask?.first == token.sessionId) pendingModelTask = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                controller.pauseIfCurrent(token, "未能准备模型任务，未发送模型请求，请检查后恢复")
                if (session.value.token?.sessionId == token.sessionId) {
                    _message.value = safeMessage(error, "未能准备目标应用，未发送模型请求，请检查后恢复。")
                }
            } finally {
                if (sequence == preparationSequence) {
                    preparationToken = null
                    _openingTarget.value = false
                }
            }
        }
    }

    private fun cancelPreparation() {
        // A coroutine that finishes later cannot clear or revoke a replacement task.
        ++preparationSequence
        preparationToken?.let {
            controller.pauseIfCurrent(it, "目标应用准备已取消，未发送模型请求，请检查后恢复")
        }
        preparationToken = null
        preparationJob?.cancel()
        preparationJob = null
        _openingTarget.value = false
    }

    fun openPreparedTarget() {
        if (!ownsSession() || _busy.value || _openingTarget.value ||
            _preparedSessionId.value != session.value.token?.sessionId
        ) return
        val token = controller.activeToken(conversationId, assistantId) ?: return
        _openingTarget.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                val result = controller.act(token, null, PhoneAction.OpenApp)
                _message.value = result.detail
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = safeMessage(error, "未能打开目标应用，请检查当前状态。")
            } finally {
                _openingTarget.value = false
            }
        }
    }

    private fun cancelGeneration(pauseOnly: Boolean) {
        if (cancelGenerationJob?.isActive == true) return
        _busy.value = true
        cancelGenerationJob = viewModelScope.launch {
            try {
                if (pauseOnly) chatService.pausePhoneGeneration(conversationUuid)
                else chatService.stopGeneration(conversationUuid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "设备操作已撤销，但尚未确认聊天生成结束。请返回聊天检查并停止生成。"
            } finally {
                _busy.value = false
            }
        }
    }

    private fun validateConversation(requireModel: Boolean = true): Boolean {
        val assistant = settings.value.getAssistantById(assistantUuid)
        val model = settings.value.findModelById(assistant?.chatModelId ?: settings.value.chatModelId)
        val error = when {
            conversation.value.assistantId != assistantUuid || assistant == null ->
                "当前会话与助手不匹配，请返回对应聊天后重新进入手机控制。"
            !generationStateReady -> "正在读取会话状态，请稍后再试。"
            _generating.value -> "当前聊天仍在生成，请先暂停或停止生成后再开始手机任务。"
            requireModel && model == null ->
                "请先返回聊天，配置并选择用于此助手的模型。"
            requireModel && model?.abilities?.contains(ModelAbility.TOOL) == false ->
                "当前模型未启用工具调用能力，请在模型设置中启用工具调用，或选择支持工具调用的模型。"
            else -> null
        }
        if (error != null) _message.value = error
        return error == null
    }

    private fun ownsSession(): Boolean = session.value.token?.let {
        it.conversationId == conversationId && it.assistantId == assistantId
    } == true

    fun dismissMessage() { _message.value = null }

    override fun onCleared() {
        cancelPreparation()
        chatService.removeConversationReference(conversationUuid)
        super.onCleared()
    }
}

private fun safeMessage(error: Exception, fallback: String): String =
    if (error is PhoneControlException) error.message ?: fallback else fallback

internal fun PhoneSessionStatus.isActive(): Boolean = this == PhoneSessionStatus.RUNNING ||
    this == PhoneSessionStatus.PAUSED || this == PhoneSessionStatus.WAITING_FOR_FOREGROUND
