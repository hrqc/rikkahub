package me.rerere.rikkahub.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.mobileagent.AccessibilityPhoneBackend
import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Separate ID/channel from generation notifications; contains no task, node text, or input text. */
class MobileAgentNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(token: PhoneSessionToken, targetPackage: String): Boolean = runCatching {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.mobile_agent_notification_channel), NotificationManager.IMPORTANCE_LOW))
        if (!canPost()) return false
        val stopIntent = Intent(context, MobileAgentStopReceiver::class.java).apply {
            action = ACTION_STOP
            data = Uri.parse("rikkahub-agent://stop/${token.sessionId}")
            putExtra(EXTRA_SESSION_ID, token.sessionId)
        }
        val stop = PendingIntent.getBroadcast(context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openIntent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", token.conversationId)
        }
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(context.getString(R.string.mobile_agent_notification_title))
            .setContentText(targetPackage)
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "STOP", stop)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        true
    }.getOrDefault(false)

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    companion object {
        const val NOTIFICATION_ID = 2403
        const val CHANNEL_ID = "mobile_agent_control"
        const val ACTION_STOP = "me.rerere.rikkahub.action.MOBILE_AGENT_STOP"
        const val EXTRA_SESSION_ID = "mobile_agent_session_id"
    }
}

/** Non-exported receiver; an immutable notification PendingIntent is the only external entry. */
class MobileAgentStopReceiver : BroadcastReceiver(), KoinComponent {
    private val backend: AccessibilityPhoneBackend by inject()

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != MobileAgentNotifications.ACTION_STOP) return
        val sessionId = intent.getStringExtra(MobileAgentNotifications.EXTRA_SESSION_ID) ?: return
        backend.stopFromNotification(sessionId)
    }
}
