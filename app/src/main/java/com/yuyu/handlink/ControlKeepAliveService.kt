package com.yuyu.handlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 控制端保活服务。
 *
 * 问题：控制端切后台后，进程会被系统冻结/降级，okhttp 的 WS ping 发不出去，
 * 被控端 connectionLostTimeout=30 秒就会判定超时断开，回来时连接已经没了。
 *
 * 解法：连接期间跑一个前台服务，进程保持 foreground 优先级，
 * ping 照常发送，30 秒那条超时就不会误判。用户可见常驻通知，符合规范。
 */
class ControlKeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "ctrl"
        private const val NOTIF_ID = 2

        fun start(ctx: Context) {
            val i = Intent(ctx, ControlKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ControlKeepAliveService::class.java)) } catch (e: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        try { stopForeground(true) } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "远程控制进行中", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("牵手远程")
            .setContentText("正在控制对方手机，保持后台连接")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}