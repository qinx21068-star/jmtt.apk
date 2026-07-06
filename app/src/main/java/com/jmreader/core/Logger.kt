package com.jmreader.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局日志器：同时写入文件 + 内存环形缓冲（供 UI 查看）+ Logcat。
 *
 * 用法：Logger.d("tag", "msg") / Logger.e("tag", "err", e)
 *
 * App 内可通过 [Logger.recent] 拿到最近日志用于展示；
 * 通过 [Logger.logFile] 拿到日志文件路径分享/导出。
 */
object Logger {

    private const val TAG = "JMReader"
    private const val MAX_LINES = 1500          // 内存缓冲上限
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024  // 单文件 2MB 上限

    enum class Level(val label: String) { V("V"), D("D"), I("I"), W("W"), E("E") }

    data class Entry(
        val seq: Long,           // 单调递增，用作 UI 列表稳定 key，避免 time+hashCode 碰撞
        val time: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private val _recent = MutableStateFlow<List<Entry>>(emptyList())
    val recent: StateFlow<List<Entry>> = _recent.asStateFlow()

    private val buffer = ConcurrentLinkedDeque<Entry>()
    private var logFile: File? = null
    private var appContext: Context? = null
    private val seqCounter = AtomicLong(0)

    // SimpleDateFormat 非线程安全，用 ThreadLocal 隔离，避免多线程并发 format 崩溃/乱码
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private fun formatTime(t: Long): String = fmt.get()!!.format(Date(t))

    // 单线程串行落盘，避免 writing 标志丢日志，也避免并发 appendText 交错
    private val writerThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Logger-Writer").apply { isDaemon = true }
    }
    @Volatile private var lastEmitNs = 0L

    /** 必须在 Application.onCreate 中调用一次。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            val dir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
            logFile = File(dir, "jmreader.log")
            // 启动分隔
            appendLog("========== App start ==========")
        }
    }

    fun v(tag: String = TAG, msg: String) = log(Level.V, tag, msg, null)
    fun d(tag: String = TAG, msg: String) = log(Level.D, tag, msg, null)
    fun i(tag: String = TAG, msg: String) = log(Level.I, tag, msg, null)
    fun w(tag: String = TAG, msg: String, t: Throwable? = null) = log(Level.W, tag, msg, t)
    fun e(tag: String = TAG, msg: String, t: Throwable? = null) = log(Level.E, tag, msg, t)

    private fun log(level: Level, tag: String, msg: String, t: Throwable?) {
        val full = buildString {
            append(msg)
            if (t != null) {
                append("\n").append(stackTraceOf(t))
            }
        }
        val entry = Entry(seqCounter.incrementAndGet(), System.currentTimeMillis(), level, tag, full)
        buffer.addLast(entry)
        while (buffer.size > MAX_LINES) buffer.pollFirst()
        // 限频发射快照，避免高频日志每条都 toList() + 重组 UI
        val now = System.nanoTime()
        if (now - lastEmitNs > 80_000_000L) {
            _recent.value = buffer.toList()
            lastEmitNs = now
        }

        // logcat
        when (level) {
            Level.V -> Log.v(tag, full)
            Level.D -> Log.d(tag, full)
            Level.I -> Log.i(tag, full)
            Level.W -> Log.w(tag, full)
            Level.E -> Log.e(tag, full)
        }

        // 文件（异步串行落盘，不丢日志）
        val file = logFile ?: return
        writerThread.execute {
            runCatching {
                appendLogToFile(file, entry, full)
                if (file.length() > MAX_FILE_BYTES) rotate(file)
            }
        }
    }

    /**
     * 同步写一条日志到文件（用于崩溃栈：进程即将被杀，异步线程可能来不及落盘）。
     * 不经过 writerThread 队列，直接写。
     */
    fun writeSync(tag: String, level: Level, msg: String) {
        val file = logFile ?: return
        val entry = Entry(seqCounter.incrementAndGet(), System.currentTimeMillis(), level, tag, msg)
        buffer.addLast(entry)
        runCatching { appendLogToFile(file, entry, msg) }
    }

    private fun appendLog(line: String) {
        val file = logFile ?: return
        runCatching {
            file.appendText("${formatTime(System.currentTimeMillis())} I $TAG : $line\n")
        }
    }

    private fun appendLogToFile(file: File, e: Entry, full: String) {
        runCatching {
            file.appendText("${formatTime(e.time)} ${e.level.label} ${e.tag} : $full\n")
        }
    }

    private fun rotate(file: File) {
        runCatching {
            val lines = file.readLines()
            val keep = lines.takeLast(lines.size / 2)
            file.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }

    fun filePath(): String? = logFile?.absolutePath

    fun clear() {
        buffer.clear()
        _recent.value = emptyList()
        logFile?.let { runCatching { it.writeText("") } }
    }

    /** 导出当前日志为文本（用于分享）。 */
    fun export(): String = buffer.joinToString("\n") { e ->
        "${formatTime(e.time)} ${e.level.label} ${e.tag} : ${e.message}"
    }

    private fun stackTraceOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /** 把异常转成简短字符串（不打印完整栈）。 */
    fun brief(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"

    /**
     * 把底层错误转成对用户友好的提示，便于自查。
     * 区分网络不通 / 后端未配 / 后端报错 / 超时。
     */
    fun friendlyError(raw: String): String {
        val msg = raw.trim()
        return when {
            // 域名全失败：reqApi 已给出多行诊断（首轮原因/是否刷新/重试原因/建议），整段透传
            msg.contains("所有域名均请求失败", true) -> msg
            // 未配置后端：ensureApi 抛出的清晰提示，直接透传
            msg.contains("未配置", true) -> msg
            msg.contains("Unable to resolve host", true) ||
            msg.contains("Connection refused", true) ||
            msg.contains("failed to connect", true) -> "无法连接后端：请检查后端是否已启动、地址是否正确（设置 → 后端地址）。\n详情：$msg"
            msg.contains("ETIMEDOUT", true) ||
            msg.contains("timeout", true) -> "请求超时：后端响应过慢或网络不通。\n详情：$msg"
            msg.contains("401", true) -> "未登录或登录已失效：请到设置登录。\n详情：$msg"
            msg.contains("403", true) -> "访问被拒：可能是 IP 地区被禁漫限制，或请求频率过高。\n详情：$msg"
            msg.contains("404", true) -> "资源未找到：可能是该本子已下架，或请求路径有误。\n详情：$msg"
            msg.startsWith("HTTP ") -> "后端返回错误：$msg"
            else -> msg.ifEmpty { "未知错误" }
        }
    }
}
