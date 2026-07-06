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

/**
 * 本地收藏（离线可见，与站点收藏互相独立）。
 * 用 JSON 文件持久化，避免引入 Room/数据库。
 */
class FavoritesStore(context: Context, moshi: Moshi) {

    private val file = File(context.filesDir, "favorites.json")
    private val type = Types.newParameterizedType(List::class.java, ComicBriefDto::class.java)
    private val adapter = moshi.adapter<List<ComicBriefDto>>(type)

    private val _items = MutableStateFlow<List<ComicBriefDto>>(emptyList())
    val items: StateFlow<List<ComicBriefDto>> = _items.asStateFlow()

    /** 串行化写操作，避免并发 toggle 互相覆盖导致状态不一致。 */
    private val mutex = Mutex()

    init { load() }

    private fun load() {
        _items.value = runCatching {
            file.takeIf { it.exists() }?.readText()?.let { adapter.fromJson(it) } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    suspend fun toggle(item: ComicBriefDto): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _items.value.toMutableList()
            val idx = list.indexOfFirst { it.id == item.id }
            val nowFavorite: Boolean
            if (idx >= 0) { list.removeAt(idx); nowFavorite = false }
            else { list.add(0, item); nowFavorite = true }
            _items.value = list
            persist(list)
            nowFavorite
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _items.value.filterNot { it.id == id }
            _items.value = list
            persist(list)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _items.value = emptyList()
            runCatching { file.delete() }
        }
    }

    fun isFavorite(id: String): Boolean = _items.value.any { it.id == id }

    private fun persist(list: List<ComicBriefDto>) {
        runCatching { file.writeText(adapter.toJson(list)) }
    }
}
