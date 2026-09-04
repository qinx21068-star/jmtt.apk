package com.jmreader.notification

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.jmreader.JMApp
import com.jmreader.core.Logger

/**
 * v27.15 「通知栏稍后再看」常驻服务。
 *
 * v27.15.2 修复三个用户反馈问题：
 *
 * 1. **输入后无反馈**：之前 [onStartCommand] 调用 [ReadLaterBatchProcessor.process]
 *    后立即调用 [ensureForeground]，后者用默认文本重建通知，覆盖了 process 设置的
 *    "正在拉取..."反馈。修复：用 [currentFeedback] 持久化反馈文本，ensureForeground 读取它。
 *
 * 2. **批量添加看似失败**：根因同上，反馈被覆盖让用户以为没添加成功（实际已添加）。
 *    配合 [ReadLaterBatchProcessor] 的更细化反馈（成功/部分失败/全失败分别显示）。
 *
 * 3. **划掉后台应用通知消失**：Android 在用户划掉最近任务时会杀掉整个应用进程
 *    （含 ForegroundService）。修复：[onTaskRemoved] 中调度一次延迟自启，
 *    利用 Android 允许 Service 在 onTaskRemoved 里发起 startForegroundService 的窗口。
 *    配合 [START_STICKY] 让系统在内存允许时重启。
 *
 * 生命周期：
 * - 用户开启设置 → [ReadLaterServiceController.start] → [onStartCommand] → [startForeground]
 * - 用户提交输入 → Receiver 转发 → [ACTION_PROCESS_INPUT] → [ReadLaterBatchProcessor.process]
 * - 反馈状态由 [currentFeedback] 持久化，[ensureForeground] 总是读取最新值
 *   （无论是 START_STICKY 重启、新 intent、还是 process 更新）
 */
class ReadLaterForegroundService : Service() {

    companion object {
        /** Service Action：处理通知输入的 JM 号批量添加任务。Intent extra [EXTRA_INPUT] 携带原始输入。 */
        const val ACTION_PROCESS_INPUT = "com.jmreader.READ_LATER_PROCESS_INPUT"
        const val EXTRA_INPUT = "jm_input_text"

        /**
         * v27.15.2：onTaskRemoved 自救延迟。
         *
         * 不能立即重启自己（Android 14+ 会判定为"从后台启动前台服务"被拒），
         * 延迟 1.5s 让系统完成"最近任务清理"动作，再以 startForegroundService 重新拉起。
         * 即使这次自救失败，[START_STICKY] 仍会让系统在合适时机重启。
         */
        private const val RESTART_DELAY_MS = 1500L
    }

    /**
     * 当前反馈文本（null=使用默认"已收藏 N 部"）。
     *
     * v27.15.2：解决"输入后无反馈"的核心。
     * BatchProcessor 每次更新通知前先调用 [setFeedback]，状态会持续保留，
     * 即使 [ensureForeground] 被再次调用（如 START_STICKY 重启、新 process intent）也不会覆盖。
     */
    @Volatile
    private var currentFeedback: String? = null

    /** 反馈自动清除 Handler：5s 后清回默认文本，避免一直显示"已添加 xxx"。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clearFeedbackRunnable = Runnable {
        currentFeedback = null
        ensureForeground()
    }

    override fun onCreate() {
        super.onCreate()
        ReadLaterNotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROCESS_INPUT -> {
                val input = intent.getStringExtra(EXTRA_INPUT).orEmpty()
                if (input.isNotEmpty()) {
                    // v27.15.2：先 ensureForeground（用当前 feedback），再 process。
                    // process 内部会 setFeedback + update 通知，确保反馈不被覆盖。
                    ensureForeground()
                    ReadLaterBatchProcessor.process(this, JMApp.instance.container, input)
                } else {
                    ensureForeground()
                }
            }
            else -> {
                ensureForeground()
            }
        }
        return START_STICKY
    }

    /**
     * v27.15.2 自检修复：Service 已销毁标志。
     * BatchProcessor 的单例 scope 里的协程可能在 Service.onDestroy 后仍在运行并回调
     * setFeedback/ensureForeground。destroyed=true 后这些调用全部变 no-op：
     * - 避免 binder 调用打到已失效的 token（部分定制 ROM 会抛 SecurityException）
     * - 避免 onDestroy 后 mainHandler 再挂新的 postDelayed（泄漏 Runnable）
     */
    @Volatile
    private var destroyed = false

    /**
     * v27.15.2：设置反馈文本并立即刷新通知。
     *
     * @param feedback 反馈内容（如"已添加 3 部到稍后再看"），null 表示回到默认
     * @param autoClearMs 自动清除回默认的延迟（0=不自动清除）
     */
    fun setFeedback(feedback: String?, autoClearMs: Long = 5000L) {
        if (destroyed) return
        mainHandler.removeCallbacks(clearFeedbackRunnable)
        currentFeedback = feedback
        ensureForeground()
        if (autoClearMs > 0 && feedback != null) {
            mainHandler.postDelayed(clearFeedbackRunnable, autoClearMs)
        }
    }

    /** 确保 Foreground 状态 + 通知存在（使用当前 [currentFeedback]）。 */
    private fun ensureForeground() {
        if (destroyed) return
        val count = try {
            JMApp.instance.container.favoritesStore.readLaterCount()
        } catch (e: Throwable) {
            Logger.w("ReadLaterSvc", "读取稍后再看数量失败: ${Logger.brief(e)}")
            0
        }
        val notification: android.app.Notification =
            ReadLaterNotificationHelper.buildNotification(this, count, currentFeedback)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+：必须显式指定 foregroundServiceType，且要在 manifest 声明对应 type
                startForeground(
                    ReadLaterNotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(
                    ReadLaterNotificationHelper.NOTIFICATION_ID,
                    notification,
                )
            }
        } catch (e: Throwable) {
            Logger.w("ReadLaterSvc", "startForeground 失败: ${Logger.brief(e)}")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * v27.15.2：用户从最近任务划掉 App 时自救。
     *
     * Android 在 onTaskRemoved 里有短暂的"前台启动窗口"权限（用户主动操作触发），
     * 延迟 [RESTART_DELAY_MS] 后用 startForegroundService 重新拉起自己。
     * 即使这次重启被系统拒绝，[START_STICKY] 仍会在内存允许时由系统重启。
     *
     * 注意：Android 14+ 对从后台启动前台服务有严格限制，此自救不保证 100% 成功。
     * 引导用户在系统设置里关闭"电池优化"或锁定最近任务可进一步提升保活率。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i("ReadLaterSvc", "onTaskRemoved: 调度 ${RESTART_DELAY_MS}ms 后自救重启")
        mainHandler.postDelayed({
            try {
                val restart = Intent(applicationContext, ReadLaterForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(restart)
                } else {
                    applicationContext.startService(restart)
                }
                Logger.i("ReadLaterSvc", "onTaskRemoved 自救: 已发起 startForegroundService")
            } catch (e: Throwable) {
                // 期望内的失败：Android 14+ 可能拒绝从后台启动前台服务
                Logger.w("ReadLaterSvc", "onTaskRemoved 自救失败（系统限制）: ${Logger.brief(e)}")
            }
        }, RESTART_DELAY_MS)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // v27.15.2 自检修复：先置 destroyed 标志（BatchProcessor 协程的回调变 no-op），
        // 再取消 BatchProcessor 里 pending 的批量任务，切断对已销毁 Service 实例的持有。
        destroyed = true
        mainHandler.removeCallbacks(clearFeedbackRunnable)
        ReadLaterBatchProcessor.cancelAll()
        super.onDestroy()
    }
}
