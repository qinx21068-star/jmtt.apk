package com.jmreader.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.jmreader.MainActivity
import com.jmreader.R

/**
 * v27.15 通知栏「稍后再看」通知工具。
 *
 * - 创建低优先级通知通道（不打扰：静音、不弹横幅，仅状态栏图标 + 下拉可见）。
 * - 构建 Notification：标题 + 当前稍后再看数量 + RemoteInput 输入框 + 点击打开 MainActivity。
 * - 暴露 [buildNotification] 让 Service 拼装并 startForeground；暴露 [update] 让 Receiver 在写入后刷新文本。
 */
object ReadLaterNotificationHelper {

    /** 通道 id（低打扰：importance=LOW，静音不弹横幅）。 */
    const val CHANNEL_ID = "jm_read_later"

    /** 通知 id（与 ForegroundService 关联，startForeground 用同一 id 才能让通知常驻不消失）。 */
    const val NOTIFICATION_ID = 0xA115

    /** RemoteInput 结果 key，Receiver 通过 [RemoteInput.getResultsFromIntent] 取出输入文本。 */
    const val REMOTE_INPUT_KEY = "jm_id_input"

    /** Receiver Action：通知里输入框回车后系统广播的 action。 */
    const val ACTION_REPLY = "com.jmreader.READ_LATER_REPLY"

    /** Service Action：点击通知主体（非输入框）后系统广播给 Service 的 action。 */
    const val ACTION_OPEN_APP = "com.jmreader.READ_LATER_OPEN_APP"

    /**
     * 创建通知通道。重复调用安全（系统幂等）。
     * 必须在 Android O+ 调用，否则通知不会显示。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "稍后再看",
            NotificationManager.IMPORTANCE_LOW, // 静音，不打扰
        ).apply {
            description = "通知栏快捷输入 JM 号加入稍后再看列表"
            setShowBadge(false) // 桌面图标不显示角标
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * 构建通知（不立即发出）。
     *
     * @param readLaterCount 当前「稍后再看」条目数，用于副标题实时显示
     * @param contentText    可选：上次操作后的临时反馈（如"已添加：xxx"，3 秒后被 [update] 覆盖回默认副标题）
     */
    fun buildNotification(
        context: Context,
        readLaterCount: Int,
        contentText: String? = null,
    ): NotificationCompat.Builder {
        ensureChannel(context)
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            // 已在 launcher Intent，无需额外 flags；点击会把现有 task 推到前台
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openAppPi = PendingIntent.getActivity(
            context, 0, mainIntent, pendingFlags,
        )
        // RemoteInput：通知底部输入框，回车后触发 ACTION_REPLY broadcast
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel("输入 JM 号（多个用空格或逗号分隔）")
            .build()
        val replyIntent = Intent(context, ReadLaterReplyReceiver::class.java).apply {
            action = ACTION_REPLY
        }
        // 关键修复：RemoteInput 要求 PendingIntent 必须是 MUTABLE（系统会把输入文本写入它的 Intent extras）。
        // Android 12+ 强制要求显式指定 mutability，否则抛 IllegalArgumentException。
        val replyPi = PendingIntent.getBroadcast(
            context, 1, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            "添加 JM 号",
            replyPi,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false) // 不用系统建议回复
            .build()

        val text = contentText ?: "已收藏 $readLaterCount 部 · 点击打开 App"

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save) // 系统图标，避免引入新资源
            .setContentTitle("JMReader · 稍后再看")
            .setContentText(text)
            .setOngoing(true) // 常驻，用户无法手动划掉
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW) // 与通道 importance 一致
            .setContentIntent(openAppPi)
            .addAction(replyAction)
            // ForegroundService 必须显式声明 type（Android 14+）
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    /**
     * 通知已发出后，更新通知文本（不重新 startForeground）。
     * Receiver 写入稍后再看后用此刷新数量。
     */
    fun update(context: Context, readLaterCount: Int, contentText: String? = null) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = buildNotification(context, readLaterCount, contentText).build()
        nm.notify(NOTIFICATION_ID, n)
    }
}
