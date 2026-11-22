package com.android_tools.app.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.android_tools.app.MainActivity

/**
 * 通知：本地聊天的新消息 / 好友申请，以及文件同步的传输进度。
 *
 * - 聊天用高优先级渠道（会弹横幅），申请用普通渠道；
 * - 同步进度是一条常驻（ongoing）通知，带百分比进度条，没有传输任务时撤掉。
 */
object Notify {
    private const val CHANNEL_MESSAGE = "chat-message"
    private const val CHANNEL_REQUEST = "chat-request"
    private const val CHANNEL_SYNC = "sync-progress"

    private const val ID_SYNC = 0x5c20
    private var chatSeq = 0x5c30

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGE, "聊天消息", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "收到新的聊天消息时提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REQUEST, "好友申请", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "有人申请添加你为好友时提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "同步进度", NotificationManager.IMPORTANCE_LOW).apply {
                description = "文件传输时显示进度"
                setShowBadge(false)
            },
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 收到新消息 */
    fun chatMessage(context: Context, friendName: String, text: String) {
        ensureChannels(context)
        if (!canPost(context)) return
        val body = text.trim().ifEmpty { "（新消息）" }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(friendName.ifEmpty { "新消息" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
            .build()
        post(context, nextId(), notification)
    }

    /** 收到好友申请 */
    fun chatRequest(context: Context, fromName: String) {
        ensureChannels(context)
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REQUEST)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("新的好友申请")
            .setContentText("${fromName.ifEmpty { "有人" }} 想加你为好友")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context))
            .build()
        post(context, nextId(), notification)
    }

    /** 传输进度：percent < 0 表示不确定进度 */
    fun syncProgress(context: Context, title: String, text: String, percent: Int) {
        ensureChannels(context)
        if (!canPost(context)) return
        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
        if (percent in 0..100) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        post(context, ID_SYNC, builder.build())
    }

    fun clearSync(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_SYNC) }
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun nextId(): Int {
        chatSeq = if (chatSeq >= 0x5cff) 0x5c30 else chatSeq + 1
        return chatSeq
    }

    private fun openApp(context: Context): PendingIntent? {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
