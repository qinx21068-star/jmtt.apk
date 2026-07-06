package com.jmreader.core

import android.os.Build
import android.os.Process
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.system.exitProcess

/**
 * 全局未捕获异常处理器：记录到 [Logger]，再交给默认处理器杀进程。
 * 避免静默闪退 —— 至少能在日志里看到崩溃栈。
 */
class CrashHandler : Thread.UncaughtExceptionHandler {

    private val previous: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching {
            // 一次性写入：设备信息 + 线程 + 完整栈，避免重复落盘
            val log = StringBuilder()
            log.append("Uncaught exception on thread ").append(t.name).append('\n')
            log.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            log.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            log.append(sw)
            // 同步写盘：进程即将被杀，异步线程可能来不及落盘
            Logger.writeSync("Crash", Logger.Level.E, log.toString())
        }
        // 给文件 flush 一点时间
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        previous?.uncaughtException(t, e)
        // 兜底：确保进程退出，避免卡在半死状态
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    companion object {
        /** 协程异常处理器：在 viewModelScope 等里未捕获时记录。 */
        val coroutineHandler = CoroutineExceptionHandler { _, e ->
            Logger.e("Coroutine", "未捕获协程异常: ${Logger.brief(e)}", e)
        }
    }
}
