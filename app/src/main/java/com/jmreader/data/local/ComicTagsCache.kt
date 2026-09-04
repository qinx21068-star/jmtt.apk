package com.jmreader.data.local

import android.content.Context
import com.jmreader.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 漫画 tags 持久化缓存：album_id → tags。
 *
 * 解决"禁漫列表接口不返回 tags"导致的 tag 屏蔽失效问题。
 * 列表加载后用缓存即时补全 tags → 即时屏蔽，零网络请求。
 * enrich 成功后写入缓存，下次加载同一本子直接命中。
 *
 * 持久化方式：JSON 文件（filesDir/comic_tags_cache.json）。
 * 启动时一次性加载到内存 [ConcurrentHashMap]，后续读取零 IO。
 * 写入时更新内存 + 节流落盘（避免高频写文件）。
 *
 * 线程安全：[cache] 用 ConcurrentHashMap，落盘由 [flushMutex] 串行化。
 *
 * v27.15.2 自检修复（三项）：
 * 1. **死锁修复**：原 `flush()` 持 flushMutex 后调用同样加锁的 `flushInternal()`，
 *    kotlinx Mutex 非重入 → 永久挂起。现拆为 `flush()`（持锁）+ `writeFileLocked()`（不加锁）。
 * 2. **节流改为延迟调度**：原"2s 内直接 return"在连续 put 间隔 <2s 时永远不落盘，
 *    dirty 数据长时间只存在内存。现改为：2s 内的 put 合并到一次延迟 2s 的落盘任务。
 * 3. **原子写**：原直接 writeText 主文件，进程被杀时文件半截损坏。
 *    现改为 tmp + renameTo 原子替换（与其他 Store 一致）。
 * 4. **App 退出钩子**：`flush()` 会在 MainActivity.onDestroy 被调用（之前从未被调用，
 *    App 被杀时 dirty 数据丢失）。
 */
class ComicTagsCache(
    appContext: Context,
    private val appScope: CoroutineScope,
) {
    private val file = File(appContext.filesDir, "comic_tags_cache.json")
    private val tmpFile = File(appContext.filesDir, "comic_tags_cache.json.tmp")
    private val cache = ConcurrentHashMap<String, List<String>>()

    @Volatile private var loaded = false
    private val flushMutex = Mutex()
    @Volatile private var dirty = false
    @Volatile private var lastFlushMs = 0L
    @Volatile private var flushScheduled = false

    /**
     * 加载磁盘缓存到内存。幂等，多次调用安全。挂起直到加载完成。
     *
     * v27.12 性能修复：文件 IO + JSON 解析切到 [Dispatchers.IO]。
     * 之前在 viewModelScope（Main）上直接 readText + JSONObject 解析，
     * 冷启动时几百 KB 缓存文件会阻塞主线程数百毫秒，是首页卡顿来源之一。
     */
    suspend fun ensureLoaded() {
        if (loaded) return
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (loaded) return@withContext
            synchronized(cache) {
                if (loaded) return@synchronized
                runCatching {
                    if (file.exists()) {
                        val text = file.readText()
                        if (text.isNotBlank()) {
                            val json = JSONObject(text)
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                val id = keys.next()
                                val arr = json.optJSONArray(id)
                                if (arr != null && arr.length() > 0) {
                                    val tags = (0 until arr.length()).mapNotNull { i ->
                                        arr.optString(i).takeIf { it.isNotBlank() }
                                    }
                                    if (tags.isNotEmpty()) cache[id] = tags
                                }
                            }
                        }
                    }
                }.onFailure { e ->
                    Logger.w("TagsCache", "加载缓存失败: ${Logger.brief(e)}")
                }
                loaded = true
                Logger.i("TagsCache", "缓存已加载: ${cache.size} 条")
            }
        }
    }

    /** 查询缓存。未加载返回 null（调用方应先 ensureLoaded）。 */
    fun get(albumId: String): List<String>? = cache[albumId]

    /** 写入缓存（内存 + 节流落盘）。tags 为空则忽略。 */
    fun put(albumId: String, tags: List<String>) {
        if (tags.isEmpty()) return
        cache[albumId] = tags
        dirty = true
        scheduleFlush()
    }

    /** 批量写入。 */
    fun putAll(entries: Map<String, List<String>>) {
        entries.forEach { (id, tags) -> if (tags.isNotEmpty()) cache[id] = tags }
        if (entries.isNotEmpty()) {
            dirty = true
            scheduleFlush()
        }
    }

    /** 缓存条目数（用于日志/调试）。 */
    fun size(): Int = cache.size

    /**
     * 节流落盘（v27.15.2 重写）。
     *
     * 原逻辑"2s 内直接 return"在连续 put（间隔 <2s）时永远不调度落盘，
     * dirty 数据长时间只在内存，App 被杀即丢失。
     *
     * 新逻辑：若距上次落盘 <2s，调度一个 2s 后执行的合并落盘任务（同一时间窗内的
     * 所有 put 共享这一次落盘）；否则立即调度。用 [flushScheduled] 标志防重复调度。
     */
    private fun scheduleFlush() {
        val now = System.currentTimeMillis()
        val delayMs = (2000L - (now - lastFlushMs)).coerceAtLeast(0L)
        if (flushScheduled) return
        flushScheduled = true
        appScope.launch(Dispatchers.IO) {
            try {
                delay(delayMs)
                flush()
            } finally {
                flushScheduled = false
            }
        }
    }

    /**
     * 强制落盘（App 退出前 / 节流任务调用）。
     *
     * v27.15.2 死锁修复：原实现持 flushMutex 后调用同样 withLock(flushMutex) 的
     * flushInternal()，kotlinx Mutex 非重入 → 永久挂起。现拆分：本方法持锁，
     * 实际写文件的 [writeFileLocked] 不加锁（调用方保证已持锁）。
     */
    suspend fun flush() {
        flushMutex.withLock {
            if (!dirty) return@withLock
            writeFileLocked()
        }
    }

    /**
     * 实际写文件。**调用方必须已持有 [flushMutex]**。
     *
     * v27.15.2 原子写：tmp + renameTo，进程被杀时不会留下半截损坏文件。
     */
    private fun writeFileLocked() {
        runCatching {
            val json = JSONObject()
            cache.forEach { (id, tags) ->
                json.put(id, org.json.JSONArray(tags))
            }
            tmpFile.writeText(json.toString())
            if (!tmpFile.renameTo(file)) {
                // rename 失败（跨挂载点等）：先备份原文件再直写，降低损坏风险
                runCatching { if (file.exists()) file.copyTo(File(file.parentFile, "${file.name}.bak"), overwrite = true) }
                file.writeText(json.toString())
                tmpFile.delete()
            }
            dirty = false
            lastFlushMs = System.currentTimeMillis()
            Logger.i("TagsCache", "缓存已落盘: ${cache.size} 条")
        }.onFailure { e ->
            Logger.w("TagsCache", "落盘失败: ${Logger.brief(e)}")
        }
    }
}
