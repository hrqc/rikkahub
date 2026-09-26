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
        var sessionStarted = false
        try {
            val token = controller.start(conversationId, assistantId, targetPackage, useRoot, allowScreenshots)
            sessionStarted = true
            _preparedSessionId.value = token.sessionId.takeIf { prepareOnly }
            _modelSessionId.value = token.sessionId.takeUnless { prepareOnly }
            if (!prepareOnly) chatService.sendMessage(conversationUuid, listOf(UIMessagePart.Text(task.trim())))
        } catch (error: Exception) {
            if (sessionStarted) controller.stopForConversation(conversationId)
            _message.value = safeMessage(error, "未能开始手机任务，请检查状态后重试。")
        }
    }

    fun pause() {
        if (!ownsSession() || _busy.value) return
        try {
            controller.pause()
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
        var resumed = false
        try {
            val token = controller.resume()
            resumed = true
            if (prepareOnly) {
                _preparedSessionId.value = token.sessionId
            } else {
                _modelSessionId.value = token.sessionId
                chatService.sendMessage(
                    conversationUuid,
                    listOf(UIMessagePart.Text("继续刚才未完成的手机操作任务。请先重新打开已授权的目标应用并读取当前页面，再决定下一步操作。")),
                )
            }
        } catch (error: Exception) {
            if (resumed) controller.stopForConversation(conversationId)
            _message.value = safeMessage(error, "未能恢复任务，请检查状态后重试。")
        }
    }

    fun stop() {
        // Revocation is synchronous: STOP must not wait for network cancellation or a UI coroutine.
        controller.stopForConversation(conversationId)
        cancelGeneration(pauseOnly = false)
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
        val error = when {
            conversation.value.assistantId != assistantUuid || assistant == null ->
                "当前会话与助手不匹配，请返回对应聊天后重新进入手机控制。"
            !generationStateReady -> "正在读取会话状态，请稍后再试。"
            _generating.value -> "当前聊天仍在生成，请先暂停或停止生成后再开始手机任务。"
            requireModel && settings.value.findModelById(assistant.chatModelId ?: settings.value.chatModelId) == null ->
                "请先返回聊天，配置并选择用于此助手的模型。"
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
        chatService.removeConversationReference(conversationUuid)
        super.onCleared()
    }
}

private fun safeMessage(error: Exception, fallback: String): String =
    if (error is PhoneControlException) error.message ?: fallback else fallback

internal fun PhoneSessionStatus.isActive(): Boolean = this == PhoneSessionStatus.RUNNING ||
    this == PhoneSessionStatus.PAUSED || this == PhoneSessionStatus.WAITING_FOR_FOREGROUND
