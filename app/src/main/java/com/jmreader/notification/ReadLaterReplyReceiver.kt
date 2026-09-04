package com.jmreader.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.RemoteInput
import com.jmreader.core.Logger

/**
 * v27.15 通知栏输入框回车 → 接收输入 → 转发给 [ReadLaterForegroundService] 处理。
 *
 * v27.15.2：Service 的 [ReadLaterForegroundService.setFeedback] 会持久化反馈状态，
 * 避免被 ensureForeground 覆盖（修复用户反馈"输入后无提示"）。
 *
 * Receiver 只做：取出输入文本 → 校验非空 → 转发给 Service。重活全在 Service 的独立协程里。
 * Service 已是 ForegroundService（START_STICKY），即使 App 被杀也能跑完。
 */
class ReadLaterReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReadLaterNotificationHelper.ACTION_REPLY) return
        val input = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ReadLaterNotificationHelper.REMOTE_INPUT_KEY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (input.isEmpty()) return

        // 转发给 Service 处理（不阻塞 Receiver，立即可返回）
        val serviceIntent = Intent(context, ReadLaterForegroundService::class.java).apply {
            action = ReadLaterForegroundService.ACTION_PROCESS_INPUT
            putExtra(ReadLaterForegroundService.EXTRA_INPUT, input)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }.onFailure { e ->
            Logger.e("ReadLaterReceiver", "转发给 Service 失败", e)
            // 兜底：直接在通知里显示错误（不依赖 Service）
            // 用户至少知道输入被接收了，下次再试
            val count = try {
                com.jmreader.JMApp.instance.container.favoritesStore.readLaterCount()
            } catch (_: Throwable) { 0 }
            ReadLaterNotificationHelper.update(
                context,
                count,
                "启动处理服务失败：${e.message ?: "未知错误"}，请重试",
            )
        }
    }
}
