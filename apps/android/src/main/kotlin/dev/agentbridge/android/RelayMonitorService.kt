package dev.agentbridge.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class RelayMonitorService : Service() {
    override fun onCreate() {
        super.onCreate()
        val notification = AgentNotifications(this).monitoring()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                AgentNotifications.MONITOR_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(AgentNotifications.MONITOR_NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AndroidRemoteBridge.active?.close()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "dev.agentbridge.android.STOP_MONITORING"
    }
}
