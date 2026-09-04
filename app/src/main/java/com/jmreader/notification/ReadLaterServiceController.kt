package com.jmreader.notification

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * v27.15 启停 [ReadLaterForegroundService] 的工具。
 *
 * 用法：
 * - 用户开启设置 → 调用 [start]
 * - 用户关闭设置 或 用户拒绝通知权限 → 调用 [stop]
 *
 * 在 [com.jmreader.MainActivity] 的 settings flow collector 里根据 readLaterNotificationEnabled + 权限状态调用。
 */
object ReadLaterServiceController {

    /**
     * 启动 ForegroundService。
     * Android 8+ 必须用 startForegroundService；Android 14+ 在 manifest 声明了 type 后调用方法不变。
     *
     * 注意：调用方需先确保已授予 [android.Manifest.permission.POST_NOTIFICATIONS]（Android 13+），
     * 否则 startForeground 不会崩，但通知不可见，体验上等同未启用。
     */
    fun start(context: Context) {
        // v27.15.2 自检修复：记录启动失败日志。
        // Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
        // 之前静默吞掉，用户开关打开但通知不出现且排查无线索。
        runCatching {
            val intent = Intent(context, ReadLaterForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { e ->
            com.jmreader.core.Logger.w("ReadLaterSvc", "启动常驻通知服务失败: ${com.jmreader.core.Logger.brief(e)}")
        }
    }

    /** 停止 Service，通知自动消失（通知 id 与 ForegroundService 绑定）。 */
    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, ReadLaterForegroundService::class.java)) }
            .onFailure { e ->
                com.jmreader.core.Logger.w("ReadLaterSvc", "停止常驻通知服务失败: ${com.jmreader.core.Logger.brief(e)}")
            }
    }

    /**
     * 检查当前是否已授予通知权限（Android 13+）。
     * Android 12 及以下默认有权限（无需运行时申请）。
     */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
