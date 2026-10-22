package dev.agentbridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager

class AgentNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(MONITOR_CHANNEL, "Agent monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the encrypted Agent Bridge connection active"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(HELP_CHANNEL, "Agent needs help", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Approval and intervention requests from a managed agent"
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(WAKE_CHANNEL, "Wake me for agent help", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Repeating audible agent intervention requests enabled by the user"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
            },
        )
    }

    fun monitoring(): Notification = Notification.Builder(context, MONITOR_CHANNEL)
        .setSmallIcon(dev.agentbridge.android.R.drawable.ic_agent_bridge)
        .setContentTitle("Agent Bridge")
        .setContentText("Monitoring managed agents for help requests")
        .setOngoing(true)
        .setContentIntent(openAppIntent())
        .addAction(
            Notification.Action.Builder(
                null,
                "Stop",
                PendingIntent.getService(
                    context,
                    2,
                    Intent(context, RelayMonitorService::class.java).setAction(RelayMonitorService.ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build(),
        )
        .build()

    fun showHelp(helpId: String, reason: String, wake: Boolean) {
        val channel = if (wake) WAKE_CHANNEL else HELP_CHANNEL
        manager.notify(
            helpId.hashCode(),
            Notification.Builder(context, channel)
                .setSmallIcon(dev.agentbridge.android.R.drawable.ic_agent_bridge)
                .setContentTitle("Агенту нужна помощь")
                .setContentText(reason.take(180))
                .setStyle(Notification.BigTextStyle().bigText(reason.take(500)))
                .setCategory(if (wake) Notification.CATEGORY_ALARM else Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    fun cancelHelp(helpId: String) {
        manager.cancel(helpId.hashCode())
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val MONITOR_CHANNEL = "agent_monitor"
        const val HELP_CHANNEL = "agent_help"
        const val WAKE_CHANNEL = "agent_help_wake"
        const val MONITOR_NOTIFICATION_ID = 1001
    }
}
