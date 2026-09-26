package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.mobileagent.PhoneActivity
import me.rerere.rikkahub.data.mobileagent.PhoneAuditEntry
import me.rerere.rikkahub.data.mobileagent.PhoneBackendState
import me.rerere.rikkahub.data.mobileagent.PhoneSessionState
import me.rerere.rikkahub.data.mobileagent.PhoneSessionStatus
import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAgentOverlayModelTest {
    private val token = PhoneSessionToken("chat", "assistant", "session", 1)
    private val session = PhoneSessionState(
        token = token,
        targetPackage = "com.example.target",
        status = PhoneSessionStatus.RUNNING,
        startedAtMillis = 1_000,
    )
    private val backend = PhoneBackendState(
        connected = true,
        foregroundPackage = session.targetPackage,
        windowId = 10,
        locked = false,
    )

    @Test fun `active overlay requires a known unlocked target window`() {
        assertNotNull(present())
        listOf(
            backend.copy(connected = false),
            backend.copy(locked = true),
            backend.copy(windowId = null),
            backend.copy(foregroundPackage = null),
            backend.copy(foregroundPackage = "com.android.permissioncontroller"),
            backend.copy(foregroundPackage = "com.example.other"),
        ).forEach { assertNull(MobileAgentOverlayPresenter.present(session, it, 2_000)) }
        assertNull(present(session.copy(token = null)))
        assertNull(present(session.copy(targetPackage = "")))
        listOf(PhoneSessionStatus.IDLE, PhoneSessionStatus.STOPPED, PhoneSessionStatus.EXPIRED).forEach {
            assertNull(present(session.copy(status = it)))
        }
    }

    @Test fun `running phase comes from actual activity instead of arbitrary detail`() {
        mapOf(
            PhoneActivity.READY to ("已就绪" to "已就绪"),
            PhoneActivity.WAITING_MODEL to ("等待 AI" to "等待 AI"),
            PhoneActivity.OBSERVING to ("读取页面" to "正在读取页面"),
            PhoneActivity.ACTING to ("操作中" to "正在操作"),
        ).forEach { (activity, labels) ->
            val model = present(session.copy(activity = activity, detail = "PRIVATE page or model reasoning"))!!
            assertEquals(labels.first, model.statusLabel)
            assertEquals(labels.second, model.phaseLabel)
            assertFalse(model.toString().contains("PRIVATE"))
            assertTrue(model.canPause)
            assertFalse(model.canResume)
        }
    }

    @Test fun `pause overrides stale activity and allows only explicit resume`() {
        val paused = present(session.copy(status = PhoneSessionStatus.PAUSED, activity = PhoneActivity.ACTING))!!
        assertEquals("已暂停", paused.statusLabel)
        assertEquals("已暂停，等待你继续", paused.phaseLabel)
        assertFalse(paused.canPause)
        assertTrue(paused.canResume)
        val waiting = present(session.copy(status = PhoneSessionStatus.WAITING_FOR_FOREGROUND))!!
        assertEquals("等待中", waiting.statusLabel)
        assertTrue(waiting.canResume)
    }

    @Test fun `missing chat owner disables resume but preserves stop and return to chat`() {
        val model = MobileAgentOverlayPresenter.present(
            session.copy(status = PhoneSessionStatus.PAUSED), backend, 2_000, canResume = false,
        )!!
        assertFalse(model.canResume)
        assertEquals("请回到聊天继续任务", model.phaseLabel)
        assertFalse(MobileAgentOverlayPresenter.allows(MobileAgentOverlayAction.RESUME, token, model))
        assertTrue(MobileAgentOverlayPresenter.allows(MobileAgentOverlayAction.STOP, token, model))
        assertTrue(MobileAgentOverlayPresenter.allows(MobileAgentOverlayAction.OPEN_CHAT, token, model))
    }

    @Test fun `old rendered token cannot control a resumed or replacement session`() {
        val changedEpoch = present(session.copy(token = token.copy(epoch = 2)))!!
        val replacement = present(session.copy(token = token.copy(sessionId = "replacement")))!!
        MobileAgentOverlayAction.entries.forEach { action ->
            assertFalse(MobileAgentOverlayPresenter.allows(action, token, changedEpoch))
            assertFalse(MobileAgentOverlayPresenter.allows(action, token, replacement))
            assertFalse(MobileAgentOverlayPresenter.allows(action, token, null))
        }
        assertTrue(MobileAgentOverlayPresenter.allows(MobileAgentOverlayAction.PAUSE, token, present()))
        assertFalse(MobileAgentOverlayPresenter.allows(MobileAgentOverlayAction.RESUME, token, present()))
    }

    @Test fun `recent audit uses bounded local labels and never raw operation or result`() {
        val model = present(session.copy(audit = listOf(
            PhoneAuditEntry(1_000, "start", "用户开启会话"),
            PhoneAuditEntry(2_000, "Click", "页面已变化"),
            PhoneAuditEntry(3_000, "InputText", "PRIVATE recipient and message"),
            PhoneAuditEntry(4_000, "PRIVATE hidden thought", "SECRET 123456"),
        )))!!
        assertEquals(3, model.recentEvents.size)
        assertEquals("0:01  点击 · 页面有变化", model.recentEvents[0])
        assertEquals("0:02  输入文字 · 已记录", model.recentEvents[1])
        assertEquals("0:03  状态更新 · 已记录", model.recentEvents[2])
        assertFalse(model.toString().contains("PRIVATE"))
        assertFalse(model.toString().contains("SECRET"))
    }

    @Test fun `used budgets prevent resume while allowing stop`() {
        listOf(
            session.copy(status = PhoneSessionStatus.PAUSED, actionsUsed = session.actionLimit),
            session.copy(status = PhoneSessionStatus.PAUSED, observationsUsed = session.observationLimit),
            session.copy(status = PhoneSessionStatus.PAUSED, durationLimitMillis = 1_000),
        ).forEach {
            val model = present(it)!!
            assertFalse(model.canResume)
            assertTrue(MobileAgentOverlayPresenter.allows(MobileAgentOverlayAction.STOP, token, model))
        }
    }

    @Test fun `observations and known errors show fixed useful labels`() {
        val model = present(session.copy(audit = listOf(
            PhoneAuditEntry(2_000, "observe", "已读取目标页面"),
            PhoneAuditEntry(3_000, "error", "PAGE_UNSTABLE"),
            PhoneAuditEntry(4_000, "error", "PRIVATE network error and content"),
        )))!!
        assertEquals("0:01  读取页面 · 已读取", model.recentEvents[0])
        assertEquals("0:02  执行受阻 · 页面仍在变化", model.recentEvents[1])
        assertEquals("0:03  执行受阻 · 操作未完成", model.recentEvents[2])
        assertFalse(model.toString().contains("PRIVATE"))
    }

    @Test fun `elapsed uses session start and never renders a negative duration`() {
        assertEquals(1_000L, present()!!.elapsedMillis)
        assertEquals(0L, present(session.copy(startedAtMillis = 3_000))!!.elapsedMillis)
        assertEquals("0:00", MobileAgentOverlayPresenter.duration(-1))
        assertEquals("1:05", MobileAgentOverlayPresenter.duration(65_000))
        assertEquals("61:01", MobileAgentOverlayPresenter.duration(3_661_000))
    }

    private fun present(state: PhoneSessionState = session) = MobileAgentOverlayPresenter.present(state, backend, 2_000)
}
