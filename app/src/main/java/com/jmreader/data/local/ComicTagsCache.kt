package com.jmreader.data.local

import android.content.Context
import com.jmreader.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * 线程安全：[cache] 用 ConcurrentHashMap，[flush] 用 Mutex 串行化文件写入。
 */
class ComicTagsCache(
    appContext: Context,
    private val appScope: CoroutineScope,
) {
    private val file = File(appContext.filesDir, "comic_tags_cache.json")
    private val cache = ConcurrentHashMap<String, List<String>>()

    @Volatile private var loaded = false
    private val flushMutex = Mutex()
    @Volatile private var dirty = false
    @Volatile private var lastFlushMs = 0L

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

    /** 节流落盘：至少间隔 2s 才写一次文件，避免高频 IO。 */
    private fun scheduleFlush() {
        val now = System.currentTimeMillis()
        if (now - lastFlushMs < 2000L) return
        lastFlushMs = now
        appScope.launch(Dispatchers.IO) {
            flushInternal()
        }
    }

    /** 强制落盘（如 App 退出前）。 */
    suspend fun flush() = flushMutex.withLock {
        if (!dirty) return@withLock
        flushInternal()
    }

    private suspend fun flushInternal() = flushMutex.withLock {
        if (!dirty) return@withLock
        runCatching {
            val json = JSONObject()
            cache.forEach { (id, tags) ->
                json.put(id, org.json.JSONArray(tags))
            }
            file.writeText(json.toString())
            dirty = false
            Logger.i("TagsCache", "缓存已落盘: ${cache.size} 条")
        }.onFailure { e ->
            Logger.w("TagsCache", "落盘失败: ${Logger.brief(e)}")
        }
    }
}
