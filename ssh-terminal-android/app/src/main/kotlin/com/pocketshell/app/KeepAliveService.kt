package com.pocketshell.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** 有活动会话时以前台服务运行，避免切到后台后进程被系统回收导致 SSH 断线 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("sessions", "SSH 会话", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "sessions")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("PocketShell")
            .setContentText("${SessionManager.sessions.size} 个 SSH 会话保持连接中")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, n)
            }
        } catch (_: Exception) {
            stopSelf()
        }
        if (SessionManager.sessions.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    /** 从最近任务里划掉应用 = 退出：断开所有会话 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        SessionManager.closeAll()
        stopSelf()
    }
}
