package com.jmreader.data.local

import android.content.Context
import com.jmreader.data.dto.ComicBriefDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 浏览历史：记录用户点进过详情页的漫画（不需要阅读，打开详情即记录）。
 * 与 [HistoryStore]（阅读历史，含页码）区分：
 * - BrowseHistoryStore：浏览过 = 打开过详情页，用于"看过的作品"列表
 * - HistoryStore：阅读过 = 真正翻过页，用于阅读进度恢复
 *
 * 用 JSON 文件持久化，仅保留最近 200 条，按最近浏览排序。
 *
 * @param scope App 级协程 scope（由 [com.jmreader.data.AppContainer.appScope] 注入）。
 */
data class BrowseEntry(
    val comic: ComicBriefDto,
    val updatedAt: Long,
)

class BrowseHistoryStore(context: Context, moshi: Moshi, scope: CoroutineScope) {

    private val file = File(context.filesDir, "browse_history.json")
    private val type = Types.newParameterizedType(List::class.java, BrowseEntry::class.java)
    private val adapter = moshi.adapter<List<BrowseEntry>>(type)

    private val _items = MutableStateFlow<List<BrowseEntry>>(emptyList())
    val items: StateFlow<List<BrowseEntry>> = _items.asStateFlow()

    /** 串行化所有读写（含 load），避免竞态导致内存被旧值覆盖。 */
    private val mutex = Mutex()

    private val loaded = CompletableDeferred<Unit>()

    /** 异步加载，避免 App 启动时主线程同步 IO。 */
    init {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val loadedList = runCatching {
                    file.takeIf { it.exists() }?.readText()?.let { adapter.fromJson(it) } ?: emptyList()
                }.getOrDefault(emptyList())
                _items.value = loadedList
            }
            loaded.complete(Unit)
        }
    }

    /** 挂起直到首次加载完成。 */
    suspend fun ensureLoaded() { loaded.await() }

    /** 记录一次浏览（去重，已存在的提到最前）。 */
    suspend fun upsert(comic: ComicBriefDto) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _items.value.toMutableList()
            list.removeAll { it.comic.id == comic.id }
            list.add(0, BrowseEntry(comic, System.currentTimeMillis()))
            val trimmed = list.take(200)
            _items.value = trimmed
            persist(trimmed)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _items.value = emptyList()
            runCatching { file.delete() }
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _items.value.toMutableList()
            list.removeAll { it.comic.id == id }
            _items.value = list
            persist(list)
        }
    }

    private fun persist(list: List<BrowseEntry>) {
        runCatching {
            val tmp = File(file.parentFile, "browse_history.json.tmp")
            tmp.writeText(adapter.toJson(list))
            if (!tmp.renameTo(file)) {
                file.writeText(adapter.toJson(list))
                tmp.delete()
            }
        }
    }
}
