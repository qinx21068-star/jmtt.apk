package com.jmreader.data.local

import android.content.Context
import com.jmreader.data.dto.ComicBriefDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
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

class HistoryStore(context: Context, moshi: Moshi) {

    private val file = File(context.filesDir, "history.json")
    private val type = Types.newParameterizedType(List::class.java, HistoryEntry::class.java)
    private val adapter = moshi.adapter<List<HistoryEntry>>(type)

    private val _items = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val items: StateFlow<List<HistoryEntry>> = _items.asStateFlow()

    /** 串行化写操作，避免并发 upsert/clear 互相覆盖导致丢数据。 */
    private val mutex = Mutex()

    init { load() }

    private fun load() {
        _items.value = runCatching {
            file.takeIf { it.exists() }?.readText()?.let { adapter.fromJson(it) } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    suspend fun upsert(comic: ComicBriefDto, chapterId: String, chapterTitle: String, page: Int) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val list = _items.value.toMutableList()
                list.removeAll { it.comic.id == comic.id }
                list.add(0, HistoryEntry(comic, chapterId, chapterTitle, page, System.currentTimeMillis()))
                // 仅保留最近 200 条
                val trimmed = list.take(200)
                _items.value = trimmed
                runCatching { file.writeText(adapter.toJson(trimmed)) }
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
            runCatching { file.writeText(adapter.toJson(list)) }
        }
    }
}
