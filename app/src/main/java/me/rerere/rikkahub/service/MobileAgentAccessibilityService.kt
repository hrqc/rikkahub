package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import me.rerere.rikkahub.data.mobileagent.AccessibilityPhoneBackend
import org.koin.android.ext.android.inject

/** Only Android's accessibility manager can bind this service; enabling it does not start a session. */
class MobileAgentAccessibilityService : AccessibilityService() {
    private val backend: AccessibilityPhoneBackend by inject()

    override fun onServiceConnected() {
        super.onServiceConnected()
        backend.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) backend.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        backend.onServiceInterrupted()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        backend.onServiceDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        backend.onServiceDisconnected(this)
        super.onDestroy()
    }
}
