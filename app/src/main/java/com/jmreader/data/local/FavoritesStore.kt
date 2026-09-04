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

    companion object {
        /**
         * v27.15：内置「稍后再看」文件夹的固定 id。
         *
         * 设计：不在 [FavoriteFolder] 表里真正插入一条记录（避免和用户文件夹混淆/被误删），
         * 而是用一个特殊 id 字符串作为约定，[entries] 中 folderId 等于此值即视为「稍后再看」分组。
         * UI 层（[com.jmreader.ui.screen.favorites.FavoritesScreen] 的 FolderFilterRow）单独展示这个内置 chip。
         *
         * 优点：
         * - 不污染 folders StateFlow，用户在「管理分组」对话框里看不到也无法重命名/删除它。
         * - 数据层零侵入：toggle/moveToFolder/folderOf/folderCounts 都能直接用此 id，无需特判。
         * - 持久化兼容：旧版本读到的 favorites.json 没有此 folder，不影响显示；新版本写入的 entries 含此 id 也能被旧版本忽略（仅显示为"未分组的本地收藏"）。
         */
        const val READ_LATER_FOLDER_ID = "__read_later__"
    }

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
            // v27.15.2 自检修复：v1 反序列化失败时备份原文件后返回空，**不落盘空数据**。
            // 之前 fromJson 失败 → comics 为空 → persist(空) 用空 v2 文件覆盖原 v1 文件，
            // 用户全部收藏静默丢失且无法恢复（v2 分支有备份，v1 分支漏了对称处理）。
            val parsed = runCatching { comicListAdapter.fromJson(text) }
            if (parsed.isFailure) {
                val e = parsed.exceptionOrNull() ?: RuntimeException("unknown")
                com.jmreader.core.Logger.w("Favorites", "v1 收藏反序列化失败，备份原文件: ${com.jmreader.core.Logger.brief(e)}")
                runCatching {
                    val bak = File(file.parentFile, "favorites.json.bak")
                    file.copyTo(bak, overwrite = true)
                }
                return emptyList<FavoriteFolder>() to emptyList()
            }
            val comics = parsed.getOrThrow().orEmpty()
            // v27.5 稳定性加固：过滤 id 为空的条目，避免多条 id="" 在 LazyColumn 中 key 撞车崩溃。
            // 同时去掉 tags 中的重复项，避免 TagChipsRow LazyRow key 重复崩溃。
            // v27.15.2：同 id 去重（保留第一个），避免数量不一致（见 v2 分支注释）。
            val seenIds = HashSet<String>(comics.size)
            val entries = ArrayList<FavoriteEntry>(comics.size)
            for (c in comics) {
                if (c.id.isBlank()) continue
                if (!seenIds.add(c.id)) continue
                entries.add(FavoriteEntry(comic = dedupTags(c), folderId = null))
            }
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
        // v27.15.2 关键修复：对同 id 条目去重（保留第一个）。
        // 之前只过滤空 id，但历史数据/异常写入可能产生同 id 的重复条目，
        // 导致 folderCounts 统计数 > 实际显示数（用户反馈"显示 12 个实际 9 个"），
        // 且 ComicList 的 LazyColumn key=c.id 会因重复 key 跳过渲染或崩溃。
        val rawEntries = data.entries
            .filter { it.comic.id.isNotBlank() }
            .map { if (it.comic.tags.size != it.comic.tags.distinct().size) it.copy(comic = dedupTags(it.comic)) else it }
        val seenIds = HashSet<String>(rawEntries.size)
        val entries = ArrayList<FavoriteEntry>(rawEntries.size)
        for (e in rawEntries) {
            if (seenIds.add(e.comic.id)) entries.add(e)
        }
        if (entries.size < rawEntries.size) {
            com.jmreader.core.Logger.w("Favorites", "加载时去重：${rawEntries.size} → ${entries.size}（移除 ${rawEntries.size - entries.size} 个重复条目）")
        }
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
     *
     * v27.15.2：用 filterNot 移除所有同 id 条目（防御性，避免异常数据残留导致重复）。
     */
    suspend fun toggle(item: ComicBriefDto, folderId: String? = null): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val oldList = _entries.value
            val filtered = oldList.filterNot { it.comic.id == item.id }
            val nowFavorite: Boolean
            val list = if (filtered.size != oldList.size) {
                // 已存在（移除了至少一个），取消收藏
                nowFavorite = false
                filtered.toMutableList()
            } else {
                // 不存在，新增
                // v27.5 稳定性加固：写入前对 tags 去重，避免 API 偶发返回重复 tag
                // 导致后续渲染 TagChipsRow 时 LazyRow key 重复崩溃。
                // 即使 id 为空也允许收藏（LazyColumn 已用 index 兜底 key）。
                val safeItem = dedupTags(item)
                nowFavorite = true
                oldList.toMutableList().apply { add(0, FavoriteEntry(comic = safeItem, folderId = folderId)) }
            }
            _entries.value = list
            persist(_folders.value, list)
            nowFavorite
        }
    }

    /** 移动已收藏的条目到指定分组。条目不存在则忽略。 */
    suspend fun moveToFolder(comicId: String, folderId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // v27.15.2：map 全量替换（与 toggle/addReadLater 一致），防御历史重复 id 数据
            val old = _entries.value
            if (old.none { it.comic.id == comicId }) return@withContext
            val list = old.map { e -> if (e.comic.id == comicId) e.copy(folderId = folderId) else e }
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
     * v27.15：「稍后再看」条目数（同步）。
     * 用于通知栏文本实时显示"已收藏 N 部"，避免每次都跑一遍 [folderCounts]。
     */
    fun readLaterCount(): Int = _entries.value.count { it.folderId == READ_LATER_FOLDER_ID }

    /**
     * v27.15：把指定条目加入「稍后再看」。
     *
     * 行为约定：
     * - 若该 id 已存在于任意分组：先移除**所有**原条目，再以 [READ_LATER_FOLDER_ID] 重新插入到列表头部
     *   （即"移动到稍后再看"，不会重复收藏）。
     * - 若未收藏：直接以 [READ_LATER_FOLDER_ID] 插入头部。
     *
     * v27.15.2 关键修复：用 filterNot 移除所有同 id 条目，而非 indexOfFirst+removeAt 只移除第一个。
     * 之前若数据里已有 2 个相同 id 的条目（历史遗留/异常产生），addReadLater 后仍会剩 1 个重复，
     * 累积导致 folderCounts 统计数 > 实际显示数（用户反馈"显示 12 个实际 9 个"）。
     *
     * @return true 表示新增；false 表示从其他分组移动过来。
     */
    suspend fun addReadLater(item: ComicBriefDto): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val oldList = _entries.value
            // 移除所有同 id 的旧条目（防止重复）
            val list = oldList.filterNot { it.comic.id == item.id }.toMutableList()
            val isNew = list.size == oldList.size // 没移除过 = 之前不存在 = 新增
            val safeItem = dedupTags(item)
            list.add(0, FavoriteEntry(comic = safeItem, folderId = READ_LATER_FOLDER_ID))
            _entries.value = list
            persist(_folders.value, list)
            isNew
        }
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
        // v27.15.2 自检修复：记录持久化失败日志。
        // 之前静默吞掉 IO 异常（磁盘满/权限），内存是新值、磁盘是旧值，
        // 重启后用户看到"收藏消失"却无任何线索可查。
        runCatching {
            val data = FavoriteStoreData(version = 2, folders = folders, entries = entries)
            val text = dataAdapter.toJson(data)
            val tmp = File(file.parentFile, "favorites.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                // renameTo 失败（如跨挂载点）：先备份原文件再直写，降低崩溃时数据损坏风险
                runCatching { if (file.exists()) file.copyTo(File(file.parentFile, "favorites.json.bak"), overwrite = true) }
                file.writeText(text)
                tmp.delete()
            }
        }.onFailure { e ->
            com.jmreader.core.Logger.w("Favorites", "收藏落盘失败（内存态与磁盘态不一致，重启后可能回退）: ${com.jmreader.core.Logger.brief(e)}")
        }
    }
}
