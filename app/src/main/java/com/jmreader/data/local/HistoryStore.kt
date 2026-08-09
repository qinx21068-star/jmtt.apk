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

/** 阅读历史：记录最近阅读的漫画与上次的章节/页码。 */
data class HistoryEntry(
    val comic: ComicBriefDto,
    val chapterId: String,
    val chapterTitle: String,
    val page: Int,
    val updatedAt: Long,
)

class HistoryStore(context: Context, moshi: Moshi, scope: CoroutineScope) {

    private val file = File(context.filesDir, "history.json")
    private val type = Types.newParameterizedType(List::class.java, HistoryEntry::class.java)
    private val adapter = moshi.adapter<List<HistoryEntry>>(type)

    private val _items = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val items: StateFlow<List<HistoryEntry>> = _items.asStateFlow()

    /** 串行化所有读写（含 load 与 upsert/clear/remove），避免竞态导致内存被旧值覆盖。 */
    private val mutex = Mutex()

    /** 加载完成信号。 [ensureLoaded] 挂起等待，避免在空初值上做"最近章节"判断。 */
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

    suspend fun upsert(comic: ComicBriefDto, chapterId: String, chapterTitle: String, page: Int) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val list = _items.value.toMutableList()
                // 关键修复（Bug 34）：之前 removeAll { it.comic.id == comic.id } 会删除该漫画所有章节历史，
                // 只留最新一条。但 ReaderViewModel.load 按 (comicId, chapterId) 双键查进度，
                // 详情页"继续阅读"按 comicId 取最近章节。旧实现导致：
                // 读第1章到第5页 → 切第2章 → 第1章进度丢失 → 再点第1章从头开始。
                // 现在只删除同漫画同章节的旧记录，保留其他章节进度。
                list.removeAll { it.comic.id == comic.id && it.chapterId == chapterId }
                list.add(0, HistoryEntry(comic, chapterId, chapterTitle, page, System.currentTimeMillis()))
                // 每漫画最多保留 20 条章节进度，避免单本漫画章节过多挤占其他漫画的历史空间。
                // 整体上限 200 条保持不变。
                val byComic = list.groupBy { it.comic.id }
                    .mapValues { (_, v) -> v.take(20) }
                    .values.flatten()
                val trimmed = byComic.take(200)
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

    /** 删除单条（用户在历史列表里清掉某条用）。 */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _items.value.filterNot { it.comic.id == id }
            _items.value = list
            persist(list)
        }
    }

    private fun persist(list: List<HistoryEntry>) {
        // 临时文件 + renameTo 原子替换，避免 load 读到写一半的 JSON
        runCatching {
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(adapter.toJson(list))
            if (!tmp.renameTo(file)) {
                file.writeText(adapter.toJson(list))
                tmp.delete()
            }
        }
    }
}
