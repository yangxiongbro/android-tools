package com.android_tools.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 同步时的前台服务：只负责挂一个常驻通知，把进程留在前台。
 *
 * 安卓从 12 开始会把后台（cached）进程冻结，蓝牙 socket 虽然还是连着，
 * 但读循环拿不到 CPU，表现就是「显示已连接、却再也收不到文件」。
 * 起一个 foregroundService 就不会被冻结。
 */
class SyncKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 个别机型 / 权限策略下 startForeground 会抛 SecurityException，
        // 这里兜住：宁可退化成普通后台，也不能把应用崩掉
        val ok = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }.isSuccess
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件同步",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "文件同步运行时保持连接" }
            manager.createNotificationChannel(channel)
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            android.app.PendingIntent.getActivity(
                this,
                0,
                it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("文件同步进行中")
            .setContentText("正在与电脑互传文件，点击回到应用")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "file-sync-keepalive"
        private const val NOTIFICATION_ID = 0x5c11

        /** WiFi 与蓝牙可能同时在跑，用引用计数，最后一个停了才关服务 */
        private var holders = 0
        private val lock = Any()

        fun start(context: Context) {
            synchronized(lock) {
                holders += 1
                if (holders > 1) return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SyncKeepAliveService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            val shouldStop = synchronized(lock) {
                holders = (holders - 1).coerceAtLeast(0)
                holders == 0
            }
            if (!shouldStop) return
            runCatching { context.stopService(Intent(context, SyncKeepAliveService::class.java)) }
        }
    }
}
