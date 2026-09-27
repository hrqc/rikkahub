package me.rerere.rikkahub.ui.pages.extensions.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.mobileagent.DeviceCapabilityRepository
import me.rerere.rikkahub.data.mobileagent.PhoneBackend

class DeviceCapabilitiesVM(
    private val repository: DeviceCapabilityRepository,
    phoneBackend: PhoneBackend,
) : ViewModel() {
    val capabilities = repository.capabilities

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshPending = false
    private var rootJob: Job? = null

    init {
        viewModelScope.launch {
            phoneBackend.state.map { it.connected }.distinctUntilChanged().collect { refresh() }
        }
    }

    fun refresh() {
        refreshPending = true
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _refreshing.value = true
            try {
                do {
                    refreshPending = false
                    repository.refresh()
                } while (refreshPending)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _message.value = "刷新设备能力失败，请重试。"
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun requestRoot() {
        if (rootJob?.isActive == true || capabilities.value.rootProbeRunning) return
        _message.value = null
        rootJob = viewModelScope.launch {
            try {
                repository.requestRoot()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _message.value = "Root 检测失败，请重试。"
            }
        }
    }

    fun setRootUsageEnabled(enabled: Boolean) {
        try {
            repository.setRootUsageEnabled(enabled)
        } catch (_: Exception) {
            _message.value = "未能保存 Root 使用偏好，请重试。"
        }
    }

    fun cancelRootProbe(showMessage: Boolean = true) {
        val ownedJob = rootJob
        val wasRunning = ownedJob?.isActive == true
        // Cancelling this request propagates to its probe. Page disposal must never cancel
        // the shared repository's probe owned by a newer chat or control-panel task.
        ownedJob?.cancel()
        if (showMessage && wasRunning) {
            _message.value = "已取消 Root 检测。"
        } else if (showMessage && capabilities.value.rootProbeRunning) {
            _message.value = "当前 Root 检测由其他任务发起，请在对应任务中暂停或停止。"
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    override fun onCleared() {
        // 页面退出及 ViewModel 销毁都终止本页发起的探测，避免 su 进程留在后台等待。
        cancelRootProbe(showMessage = false)
        super.onCleared()
    }
}
