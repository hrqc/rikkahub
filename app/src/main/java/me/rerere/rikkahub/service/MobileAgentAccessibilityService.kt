package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.mobileagent.AccessibilityPhoneBackend
import me.rerere.rikkahub.data.mobileagent.PhoneController
import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken
import me.rerere.rikkahub.data.mobileagent.PhoneTaskControl
import org.koin.android.ext.android.inject

/** Only Android's accessibility manager can bind this service; enabling it does not start a session. */
class MobileAgentAccessibilityService : AccessibilityService() {
    private val backend: AccessibilityPhoneBackend by inject()
    private val controller: PhoneController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: MobileAgentOverlay? = null
    private var overlayUpdates: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        backend.onServiceConnected(this)
        closeOverlay()
        overlay = MobileAgentOverlay(
            service = this,
            onPause = { token ->
                if (!controller.requestTaskControl(token, PhoneTaskControl.PAUSE)) {
                    controller.pauseIfCurrent(token, "用户从悬浮窗暂停")
                }
            },
            onResume = { token -> controller.requestTaskControl(token, PhoneTaskControl.RESUME) },
            onStop = { token ->
                if (!controller.requestTaskControl(token, PhoneTaskControl.STOP)) {
                    controller.stopIfCurrent(token, "用户从悬浮窗停止")
                }
            },
            onOpenChat = ::openTaskChat,
            onWindowChanged = backend::setControlOverlayWindowId,
        )
        overlayUpdates = scope.launch {
            combine(controller.state, backend.state) { session, environment -> session to environment }
                .collect { (session, environment) ->
                    overlay?.render(session, environment, session.token?.let(controller.taskControls::canResume) == true)
                }
        }
    }

    private fun openTaskChat(token: PhoneSessionToken) {
        if (controller.state.value.token != token) return
        if (!controller.requestTaskControl(token, PhoneTaskControl.PAUSE)) {
            controller.pauseIfCurrent(token, "返回聊天查看任务")
        }
        runCatching {
            startActivity(Intent(this, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", token.conversationId)
            })
        }
    }

    private fun closeOverlay() {
        overlayUpdates?.cancel()
        overlayUpdates = null
        overlay?.close()
        overlay = null
        backend.setControlOverlayWindowId(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) backend.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        backend.onServiceInterrupted()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        closeOverlay()
        backend.onServiceDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        closeOverlay()
        scope.cancel()
        backend.onServiceDisconnected(this)
        super.onDestroy()
    }
}
