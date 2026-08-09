package com.jmreader.data.local

import android.content.Context
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.FavoriteEntry
import com.jmreader.data.dto.FavoriteFolder
import com.jmreader.data.dto.FavoriteStoreData
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 本地收藏（离线可见，与站点收藏互相独立）。
 * 用 JSON 文件持久化，避免引入 Room/数据库。
 *
 * v27.5 #15：支持「收藏夹分组」。文件格式升级为 v2：
 * ```
 * {
 *   "version": 2,
 *   "folders": [{"id":"...","name":"...","createdAt":...}],
 *   "entries": [{"comic":{...},"folderId":null}]
 * }
 * ```
 * 兼容老格式：若文件是 JSON 数组（[ComicBriefDto]），自动迁移为 v2，所有条目 folderId=null。
 *
 * @param scope App 级协程 scope（由 [com.jmreader.data.AppContainer.appScope] 注入），
 *   替代之前裸 `CoroutineScope(...)`。Store 自身不持有可取消 scope，遵循结构化并发。
 */
class FavoritesStore(context: Context, moshi: Moshi, scope: CoroutineScope) {

    private val file = File(context.filesDir, "favorites.json")
    private val comicListType = Types.newParameterizedType(List::class.java, ComicBriefDto::class.java)
    private val comicListAdapter = moshi.adapter<List<ComicBriefDto>>(comicListType)
    private val dataAdapter = moshi.adapter(FavoriteStoreData::class.java)

    /** 内部条目列表（含 folderId）。所有读写均持 [mutex]，保证与 [entries] 一致。 */
    private val _entries = MutableStateFlow<List<FavoriteEntry>>(emptyList())
    val entries: StateFlow<List<FavoriteEntry>> = _entries.asStateFlow()

    /** 分组列表。 */
    private val _folders = MutableStateFlow<List<FavoriteFolder>>(emptyList())
    val folders: StateFlow<List<FavoriteFolder>> = _folders.asStateFlow()

    /**
     * 兼容老调用方：纯 ComicBriefDto 列表。从 [entries] 派生（共享单一上游订阅），
     * 收藏顺序与 entries 一致（新收藏插在头部）。
     *
     * v27.5 性能优化：之前 _items 是独立的 MutableStateFlow，每次 toggle/remove/deleteFolder
     * 都要同步更新 _entries 和 _items 两个 flow，双倍 emit。现在改为从 entries map 派生，
     * stateIn(scope, Eagerly) 共享单一上游订阅，所有订阅者拿到同一份 StateFlow。
     */
    val items: StateFlow<List<ComicBriefDto>> = entries
        .map { list -> list.map { it.comic } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 串行化所有读写（含 load 与 toggle），避免 load 与 toggle 竞态导致内存被旧值覆盖。 */
    private val mutex = Mutex()

    /**
     * 加载完成信号。load 完成前 [isFavoriteAwait] 会挂起，避免在空初值上返回错误结果。
     * 取消（关闭 App）时通过 scope cancel 自动传播，不需要额外处理。
     */
    private val loaded = CompletableDeferred<Unit>()

    /**
     * 异步加载：App 启动时在 [scope] 中触发（不再裸建 scope）。
     * 加载在 [mutex] 内执行，确保与 [toggle]/[remove]/[clear] 串行——
     * 之前 load 不持锁导致：(1) 读到 upsert 写一半的 JSON 解析失败丢数据；
     * (2) upsert 写完内存后 load 又把旧值赋回 _items，覆盖最新状态。
     *
     * v27.5：自动识别 v1/v2 格式，v1（纯数组）迁移为 v2，所有条目 folderId=null。
     */
    init {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val (folders, entries) = runCatching { loadFromFile() }.getOrDefault(emptyList<FavoriteFolder>() to emptyList())
                _folders.value = folders
                _entries.value = entries
            }
            loaded.complete(Unit)
        }
    }

    /** 解析文件：v2 (对象) / v1 (数组)。失败返回空。 */
    private fun loadFromFile(): Pair<List<FavoriteFolder>, List<FavoriteEntry>> {
        if (!file.exists()) return emptyList<FavoriteFolder>() to emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList<FavoriteFolder>() to emptyList()
        val trimmed = text.trim()
        // v1：JSON 数组（List<ComicBriefDto>）
        if (trimmed.startsWith("[")) {
            val comics = runCatching { comicListAdapter.fromJson(text) }.getOrNull().orEmpty()
            // v27.5 稳定性加固：过滤 id 为空的条目，避免多条 id="" 在 LazyColumn 中 key 撞车崩溃。
            // 同时去掉 tags 中的重复项，避免 TagChipsRow LazyRow key 重复崩溃。
            val entries = comics
                .filter { it.id.isNotBlank() }
                .map { FavoriteEntry(comic = dedupTags(it), folderId = null) }
            // 立即落盘迁移到 v2，避免下次启动再次走 v1 分支
            runCatching { persist(emptyList(), entries) }
            return emptyList<FavoriteFolder>() to entries
        }
        // v2：JSON 对象，用 Moshi 直接反序列化 FavoriteStoreData
        val data = runCatching { dataAdapter.fromJson(text) }.getOrNull()
        if (data == null) {
            // v27.5 稳定性加固：反序列化失败时备份原文件，便于人工排查/恢复。
            // 之前直接返回空列表，用户感受为"收藏全没了"，且无法找回原始数据。
            com.jmreader.core.Logger.w("Favorites", "favorites.json 反序列化失败，备份原文件")
            runCatching {
                val bak = File(file.parentFile, "favorites.json.bak")
                file.copyTo(bak, overwrite = true)
            }
            return emptyList<FavoriteFolder>() to emptyList()
        }
        // v27.5 稳定性加固：过滤 id 为空的条目 + tags 去重，避免 LazyColumn/LazyRow key 重复崩溃。
        val entries = data.entries
            .filter { it.comic.id.isNotBlank() }
            .map { if (it.comic.tags.size != it.comic.tags.distinct().size) it.copy(comic = dedupTags(it.comic)) else it }
        val folders = data.folders.filter { it.id.isNotBlank() }
        return folders to entries
    }

    /** 去重 tags，保留顺序。防止 API 返回重复 tag 导致 TagChipsRow LazyRow key 撞车崩溃。 */
    private fun dedupTags(c: com.jmreader.data.dto.ComicBriefDto): com.jmreader.data.dto.ComicBriefDto {
        if (c.tags.isEmpty() || c.tags.size == c.tags.distinct().size) return c
        val seen = HashSet<String>(c.tags.size)
        val out = ArrayList<String>(c.tags.size)
        for (t in c.tags) {
            if (seen.add(t)) out.add(t)
        }
        return c.copy(tags = out)
    }

    /** 挂起直到首次加载完成。供 [isFavoriteAwait] 等同步接口的 suspend 版本使用。 */
    suspend fun ensureLoaded() { loaded.await() }

    /**
     * 切换收藏状态。
     * - 未收藏 → 加入收藏，可指定 [folderId]（默认 null=未分组）
     * - 已收藏 → 取消收藏
     * @return 收藏后状态（true=已收藏，false=已取消）
     */
    suspend fun toggle(item: ComicBriefDto, folderId: String? = null): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _entries.value.toMutableList()
            val idx = list.indexOfFirst { it.comic.id == item.id }
            val nowFavorite: Boolean
            if (idx >= 0) {
                list.removeAt(idx); nowFavorite = false
            } else {
                // v27.5 稳定性加固：写入前对 tags 去重，避免 API 偶发返回重复 tag
                // 导致后续渲染 TagChipsRow 时 LazyRow key 重复崩溃。
                // 即使 id 为空也允许收藏（LazyColumn 已用 index 兜底 key）。
                val safeItem = dedupTags(item)
                list.add(0, FavoriteEntry(comic = safeItem, folderId = folderId))
                nowFavorite = true
            }
            _entries.value = list
            persist(_folders.value, list)
            nowFavorite
        }
    }

    /** 移动已收藏的条目到指定分组。条目不存在则忽略。 */
    suspend fun moveToFolder(comicId: String, folderId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _entries.value.toMutableList()
            val idx = list.indexOfFirst { it.comic.id == comicId }
            if (idx < 0) return@withContext
            val cur = list[idx]
            if (cur.folderId == folderId) return@withContext
            list[idx] = cur.copy(folderId = folderId)
            _entries.value = list
            persist(_folders.value, list)
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _entries.value.filterNot { it.comic.id == id }
            _entries.value = list
            persist(_folders.value, list)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _entries.value = emptyList()
            runCatching { file.delete() }
        }
    }

    // ===================== 分组管理 =====================

    /**
     * 创建新分组。重名也允许（由 UI 提示用户）。
     * @return 创建出的 [FavoriteFolder]
     */
    suspend fun createFolder(name: String): FavoriteFolder = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        val folder = FavoriteFolder(id = UUID.randomUUID().toString(), name = trimmed.ifBlank { "新分组" })
        mutex.withLock {
            val list = _folders.value + folder
            _folders.value = list
            persist(list, _entries.value)
        }
        folder
    }

    /** 重命名分组。分组不存在则忽略。 */
    suspend fun renameFolder(id: String, newName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _folders.value.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@withContext
            list[idx] = list[idx].copy(name = newName.trim().ifBlank { list[idx].name })
            _folders.value = list
            persist(list, _entries.value)
        }
    }

    /**
     * 删除分组。组内条目 [moveToFolderId]：
     * - null=移到「未分组」
     * - 非 null=移到指定分组（需存在）
     */
    suspend fun deleteFolder(id: String, moveToFolderId: String? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val folders = _folders.value.filterNot { it.id == id }
            val target = moveToFolderId?.let { tid -> folders.firstOrNull { it.id == tid }?.id }
            val entries = _entries.value.map { e ->
                if (e.folderId == id) e.copy(folderId = target) else e
            }
            _folders.value = folders
            _entries.value = entries
            persist(folders, entries)
        }
    }

    /** 取某条目的当前分组 id（null=未分组/未收藏）。 */
    fun folderOf(comicId: String): String? = _entries.value.firstOrNull { it.comic.id == comicId }?.folderId

    /** 分组 id → 该分组下条目数。 */
    fun folderCounts(): Map<String?, Int> {
        val m = mutableMapOf<String?, Int>()
        _entries.value.forEach { e ->
            val k = e.folderId
            m[k] = (m[k] ?: 0) + 1
        }
        return m
    }

    /**
     * 同步判断是否已收藏。**仅当 [ensureLoaded] 完成后调用才有意义**——
     * 初值为空列表，加载完成前永远返回 false。
     * 推荐用 [isFavoriteAwait] 等待加载完成后再判断。
     */
    fun isFavorite(id: String): Boolean = _entries.value.any { it.comic.id == id }

    /** 挂起版 [isFavorite]：等待首次加载完成后判断，避免冷启动误判为未收藏。 */
    suspend fun isFavoriteAwait(id: String): Boolean {
        ensureLoaded()
        return _entries.value.any { it.comic.id == id }
    }

    /** v2 格式落盘：临时文件 + renameTo 原子替换，避免 load 读到写一半的 JSON。 */
    private fun persist(folders: List<FavoriteFolder>, entries: List<FavoriteEntry>) {
        runCatching {
            val data = FavoriteStoreData(version = 2, folders = folders, entries = entries)
            val text = dataAdapter.toJson(data)
            val tmp = File(file.parentFile, "favorites.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                // renameTo 失败（如跨挂载点）退化为直接写
                file.writeText(text)
                tmp.delete()
            }
        }
    }
}
