package com.jmreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmreader.core.Logger
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.local.BlockedTagsStore
import com.jmreader.data.local.BlockMode
import com.jmreader.data.repository.Resource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * 列表页通用状态。
 * 自动应用屏蔽规则过滤（tag/名称/作者）：含被屏蔽内容的条目不会出现在最终列表里。
 *
 * v27.11 屏蔽重构：持久化缓存 + 持回(fail-closed)策略，彻底解决 tag 屏蔽泄漏。
 * 根因：禁漫列表接口不返回 tags 字段，tag 屏蔽无法即时生效。
 * 方案：
 * - 持久化 tags 缓存（ComicTagsCache）：enrich 成功后写入磁盘，下次加载直接命中，零网络请求。
 * - 有 tag 规则时：缓存命中→即时屏蔽；缓存未命中→持回隐藏+enrich，enrich 失败 fail-closed 保持隐藏。
 * - 无 tag 规则时：全部立即显示，enrich 仅补全 tags/likes/views/publishTime 用于卡片显示。
 * - name/author 屏蔽：列表接口有返回，始终即时生效。
 * 效果：第二次加载用缓存即时屏蔽（快）；有 tag 规则时绝不泄漏（准）；无 tag 规则时立即显示（快）。
 */
@androidx.compose.runtime.Immutable
data class ListUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val items: List<ComicBriefDto> = emptyList(),
    val page: Int = 1,
    val total: Int? = null,
    val error: String? = null,
    val endReached: Boolean = false,
    /** 有 tag 规则且部分条目未补全 tags（持回中）。UI 可显示"正在过滤…"。 */
    val filtering: Boolean = false,
    /**
     * 屏蔽模式为 COVER_ONLY 时，被屏蔽但仍保留卡片的本子 ID 集合。
     * UI 应将这些条目的封面替换为打叉灰白占位图，保留标题/作者/标签等。
     * 屏蔽模式为 HIDE 时此集合为空（被屏蔽条目直接从 items 中移除）。
     */
    val coverHiddenIds: Set<String> = emptySet(),
)

abstract class BaseListViewModel(protected val container: AppContainer) : ViewModel() {

    protected val _state = MutableStateFlow(ListUiState())
    val state: StateFlow<ListUiState> = _state

    /** 未经屏蔽过滤的原始条目（含所有已加载页）。enrich 会就地更新此列表的 tags 等字段。
     *  v27.12：@Volatile。enrich 在 Dispatchers.Default 上写，setScrolling/refresh 等在 Main 上读，
     *  字段引用必须可见一致。List 自身的不可变性保证读到的快照可用。 */
    @Volatile private var rawItems: List<ComicBriefDto> = emptyList()

    /**
     * v27.13 竞态根因修复：enrich 不再持有 rawMutable 快照。
     *
     * 之前 enrich 拍快照 rawMutable，enrich 结果写入 rawMutable，完成时 rawItems = rawMutable。
     * 但 loadMore 期间 rawItems 已被改写 → enrich 放弃写回 → enrichedIds 已标记"已补全"
     * 但 rawItems 里这些条目没 tags → 下次 enrich 跳过 → 永远无 tags。
     *
     * 现在 enrich 只写 enrichedDetailCache（全局，不依赖快照），
     * 刷新 UI 时用 [mergeEnriched] 从 enrichedDetailCache 合并到最新 rawItems。
     * loadMore/refresh 改 rawItems 不影响 enrich，enrich 结果永不丢失。
     */
    private fun mergeEnriched(source: List<ComicBriefDto>): List<ComicBriefDto> {
        if (enrichedDetailCache.isEmpty()) return source
        var changed = false
        val result = source.map { c ->
            val enriched = enrichedDetailCache[c.id]
            if (enriched != null && enriched != c) {
                changed = true
                enriched
            } else {
                c
            }
        }
        return if (changed) result else source
    }

    /** 已补全 tags 的 albumId 集合，避免重复请求 /album。
     *  v27.12：改为线程安全集合，enrich（Default）与 setScrolling（Main）并发读写。 */
    private val enrichedIds: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** 已补全的完整字段缓存（id → ComicBriefDto），refresh 后回填 tags/likes/views/publishTime。
     *  v27.12：改为线程安全 Map，enrich（Default）与 refresh（Main）并发访问。 */
    private val enrichedDetailCache: MutableMap<String, ComicBriefDto> =
        java.util.Collections.synchronizedMap(HashMap())

    /** 每条目补全失败次数（仅用于日志统计）。 */
    private val enrichAttempts: MutableMap<String, Int> =
        java.util.Collections.synchronizedMap(HashMap())

    /** 当前补全协程，新的补全启动前取消旧的，避免并发竞态。 */
    private var enrichJob: Job? = null

    /** enrich 去抖 Job。所有触发先经 200ms 去抖，避免 combine 多次 emit 导致频繁重启。 */
    private var enrichDebounceJob: Job? = null

    /** 当前是否有 tag 屏蔽规则。有则采用持回策略；无则立即显示。 */
    @Volatile private var hasTagRules: Boolean = false

    /** 当前屏蔽模式。HIDE=直接移除；COVER_ONLY=保留卡片仅隐藏封面。
     *  v27.14：通过 settings flow 实时同步，模式切换后列表立即重算。
     *  初始值取自 cachedSnapshot，避免 refresh 早于 settings 首次 emit 时仍为默认 HIDE。 */
    @Volatile private var blockMode: BlockMode = container.settingsStore.cachedSnapshot.blockMode

    /** 列表是否正在滚动。滚动时暂停 enrich 的 UI 刷新，避免列表变化导致 LazyColumn 重新布局卡顿。
     *  enrich 后台继续跑（写 enrichedDetailCache），停下后一次性刷新。 */
    @Volatile private var scrolling = false
    @Volatile private var enrichDirty = false

    /** 屏蔽 tag 的实时集合（仅用于 UI 展示，过滤逻辑不依赖此缓存）。 */
    val blockedTags: StateFlow<Set<String>> = container.blockedTagsStore.tags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    /** 屏蔽名称关键词的实时集合（仅用于 UI 展示，过滤逻辑不依赖此缓存）。 */
    val blockedNames: StateFlow<Set<String>> = container.blockedTagsStore.names
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    /**
     * UI 层通知列表滚动状态变化。滚动时彻底暂停 enrich 协程的计算与 UI 刷新，
     * 避免后台计算争抢 CPU、避免列表变化导致 LazyColumn 重新布局卡顿。
     * 调用方：ComicList 内 LaunchedEffect(isScrollInProgress) → onScrollStateChange?.invoke(it)。
     *
     * v27.12：滚动停止后的刷新整段切到 Dispatchers.Default，
     * 避免 applyBlock 中的繁简转换在主线程执行（卡顿主因之一）。
     */
    fun setScrolling(s: Boolean) {
        scrolling = s
        // 滚动停止后，如果 enrich 期间有积压的刷新，立即执行一次（在 Default 上跑）
        if (!s && enrichDirty) {
            enrichDirty = false
            viewModelScope.launch(Dispatchers.Default) {
                refreshUiStateFromRaw()
            }
        }
    }

    /**
     * 获取当前应使用的"原始数据源"：rawItems + enrichedDetailCache 合并。
     *
     * v27.13：不再用 enrichingList 快照。直接从 enrichedDetailCache 合并到最新 rawItems，
     * 确保任何时刻刷新 UI 都能看到已 enrich 的 tags，即使 loadMore 改了 rawItems 也不丢。
     */
    private fun currentDataSource(): List<ComicBriefDto> = mergeEnriched(rawItems)

    /**
     * 用当前数据源 + 当前规则重新计算可见列表并写入 _state。
     * 必须在 [Dispatchers.Default] 上调用（applyBlock 内有大量繁简转换）。
     */
    private suspend fun refreshUiStateFromRaw() {
        val (tags, names, authors) = currentRules()
        val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
        val snapshot = currentDataSource()
        val (filtered, coverHidden) = applyBlock(snapshot, rules, blockMode)
        val stillPending = hasTagRules && snapshot.any { it.id !in enrichedIds }
        // _state.value 是线程安全的，可在任意线程写
        if (filtered != _state.value.items || stillPending != _state.value.filtering ||
            coverHidden != _state.value.coverHiddenIds
        ) {
            _state.value = _state.value.copy(
                items = filtered,
                filtering = stillPending,
                coverHiddenIds = coverHidden,
            )
        }
    }

    init {
        // 屏蔽规则变化时，对已加载的原始数据重新过滤，立即刷新可见列表。
        // 直接用 collect 到的 (tags, names, authors) 过滤，不读 stateIn 缓存
        // （派发顺序不确定，可能读到旧空集 → 过滤不生效）。
        // 关键性能：combine(tags, names, authors) 对同一 DataStore 的一次 emit 最多触发 3 次，
        // 用 lastRules 去重，避免 3 次 _state.value 写入 → HomeScreen 3 次重组 → ComicCard 级联掉帧。
        //
        // v27.12：applyBlock（含繁简转换）切到 Dispatchers.Default，
        // 避免规则变化时主线程卡顿。
        viewModelScope.launch {
            var lastRules: BlockedTagsStore.NormalizedRules? = null
            container.blockedTagsStore.allRules.collect { (tags, names, authors) ->
                try {
                    val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
                    // 去重：归一化后的规则集合内容相同则跳过（combine 多次 emit 同一结果）
                    if (rules == lastRules) return@collect
                    lastRules = rules
                    hasTagRules = rules.tags.isNotEmpty()
                    if (rawItems.isNotEmpty()) {
                        withContext(Dispatchers.Default) {
                            val src = currentDataSource()
                            val (filtered, coverHidden) = applyBlock(src, rules, blockMode)
                            val stillPending = hasTagRules && src.any { it.id !in enrichedIds }
                            if (filtered != _state.value.items || stillPending != _state.value.filtering ||
                                coverHidden != _state.value.coverHiddenIds
                            ) {
                                _state.value = _state.value.copy(
                                    items = filtered,
                                    filtering = stillPending,
                                    coverHiddenIds = coverHidden,
                                )
                            }
                        }
                        // 规则变化可能需要重新 enrich（新增 tag 规则时之前未持回的条目现在需要补全）
                        enrichTagsInBackground()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.w("List", "屏蔽规则 collect 处理失败: ${Logger.brief(e)}")
                }
            }
        }

        // v27.14：监听屏蔽模式变化，切换 HIDE/COVER_ONLY 时立即重算列表。
        viewModelScope.launch {
            container.settingsStore.settings.collect { s ->
                if (s.blockMode != blockMode) {
                    blockMode = s.blockMode
                    if (rawItems.isNotEmpty()) {
                        withContext(Dispatchers.Default) { refreshUiStateFromRaw() }
                    }
                }
            }
        }
    }

    /** 子类提供分页加载逻辑。返回 (items, total)。 */
    protected abstract suspend fun loadPage(page: Int): Resource<Pair<List<ComicBriefDto>, Int?>>

    /** 当前 loadMore 的 Job，新 loadMore 启动前取消旧的，避免并发竞态重复加载同一页。 */
    private var loadMoreJob: Job? = null

    /** 当前 refresh 的 Job，新 refresh 启动前取消旧的，避免并发竞态覆盖结果。 */
    private var refreshJob: Job? = null

    /**
     * v27.14：取消所有正在进行的加载/补全任务。
     * 子类批量操作（如批量 ID 搜索）前调用，避免并发写 rawItems / _state 竞态。
     */
    protected fun cancelAllLoadingJobs() {
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        enrichJob?.cancel()
        enrichDebounceJob?.cancel()
    }

    /**
     * v27.14：批量设置列表数据（用于批量 ID 搜索等场景）。
     *
     * 取消所有正在进行的加载/补全任务，直接用 [items] 作为完整列表（无分页）。
     * 所有条目视为已补全（详情接口的 tags/likes/views 即真实数据），写入持久化缓存。
     * 应用屏蔽规则后更新 _state（HIDE 模式过滤被屏蔽条目；COVER_ONLY 模式保留并标记封面隐藏）。
     *
     * @param items 完整列表数据（已按目标顺序排好，例如按用户输入的 ID 顺序）
     * @param totalForState 写入 state.total 的值，默认为 items.size
     */
    protected suspend fun setBatchResult(
        items: List<ComicBriefDto>,
        totalForState: Int? = items.size,
    ) {
        cancelAllLoadingJobs()
        // 确保持久化 tags 缓存已加载
        container.comicTagsCache.ensureLoaded()
        // 详情接口返回的就是真实 tags，全部标记为已补全 + 写缓存
        val newIds = items.mapTo(HashSet(items.size)) { it.id }
        enrichedIds.retainAll(newIds)
        enrichedDetailCache.keys.retainAll(newIds)
        enrichAttempts.keys.retainAll(newIds)
        items.forEach { c ->
            if (c.id.isNotEmpty() && c.tags.isNotEmpty()) {
                enrichedIds.add(c.id)
                enrichedDetailCache[c.id] = c
                container.comicTagsCache.put(c.id, c.tags)
            }
        }
        rawItems = items
        val (tags, names, authors) = currentRules()
        val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
        hasTagRules = rules.tags.isNotEmpty()
        val srcForFilter = mergeEnriched(rawItems)
        val (filtered, coverHidden) = withContext(Dispatchers.Default) {
            applyBlock(srcForFilter, rules, blockMode)
        }
        _state.value = _state.value.copy(
            items = filtered,
            page = 1,
            total = totalForState,
            refreshing = false,
            loadingMore = false,
            endReached = true, // 批量结果无分页
            error = null,
            filtering = false,
            coverHiddenIds = coverHidden,
        )
    }

    fun refresh() {
        refreshJob?.cancel()
        // v27.13 修复：refresh 取消 loadMoreJob 和 enrichJob，避免并发写 rawItems 竞态。
        // 之前 refresh 不取消 loadMore，loadMore 的 rawItems = rawItems + newItems
        // 可能在 refresh 的 rawItems = items.map{...} 之后执行，覆盖 refresh 的新数据。
        // enrichJob 也需取消：refresh 会 retainAll 清缓存，in-flight enrich 会把被清的 key 写回造成脏数据。
        loadMoreJob?.cancel()
        enrichJob?.cancel()
        enrichDebounceJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true, error = null, page = 1, endReached = false)
            try {
                when (val r = loadPage(1)) {
                    is Resource.Success -> {
                        val (items, total) = r.data
                        Logger.i("List", "refresh ok: ${items.size} items, total=$total")
                        // 确保持久化 tags 缓存已加载（首次启动时懒加载）
                        container.comicTagsCache.ensureLoaded()
                        // 用持久化缓存 + 已 enrich 缓存回填 tags，让缓存命中的条目即时屏蔽
                        rawItems = items.map { c ->
                            val enriched = enrichedDetailCache[c.id]
                            if (enriched != null) {
                                enriched
                            } else {
                                val cachedTags = container.comicTagsCache.get(c.id)
                                if (cachedTags != null && c.tags.isEmpty()) {
                                    c.copy(tags = cachedTags)
                                } else {
                                    c
                                }
                            }
                        }
                        // 缓存命中的条目标记为已补全（无需再 enrich）
                        rawItems.forEach { c ->
                            if (c.tags.isNotEmpty() && c.id !in enrichedIds) {
                                enrichedIds.add(c.id)
                                enrichedDetailCache[c.id] = c
                            }
                        }
                        // v27.13 诊断：缓存命中数 vs 待 enrich 数
                        val cachedCount = rawItems.count { it.tags.isNotEmpty() }
                        Logger.i("List", "refresh tags: $cachedCount/${rawItems.size} 已有tags, ${rawItems.size - cachedCount} 待enrich")
                        val newIds = items.mapTo(HashSet(items.size)) { it.id }
                        enrichedIds.retainAll(newIds)
                        enrichedDetailCache.keys.retainAll(newIds)
                        enrichAttempts.keys.retainAll(newIds)
                        val reached = items.isEmpty() || (total != null && rawItems.size >= total)
                        val (tags, names, authors) = currentRules()
                        val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
                        hasTagRules = rules.tags.isNotEmpty()
                        // v27.13 关键修复：同 loadMore，用 mergeEnriched(rawItems) 保留已 enrich 的 tags
                        val srcForFilter = mergeEnriched(rawItems)
                        val (filtered, coverHidden) = withContext(Dispatchers.Default) {
                            applyBlock(srcForFilter, rules, blockMode)
                        }
                        _state.value = _state.value.copy(
                            items = filtered,
                            page = 1,
                            total = total,
                            refreshing = false,
                            endReached = reached,
                            filtering = hasTagRules && rawItems.any { it.id !in enrichedIds },
                            coverHiddenIds = coverHidden,
                        )
                        enrichTagsInBackground()
                    }
                    is Resource.Error -> {
                        Logger.e("List", "refresh 失败: ${r.message}")
                        _state.value = _state.value.copy(refreshing = false, error = friendlyError(r.message))
                    }
                    Resource.Loading -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e("List", "refresh 异常", e)
                _state.value = _state.value.copy(refreshing = false, error = friendlyError(Logger.brief(e)))
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        // 正在加载/刷新/已到底时不重复触发
        if (s.loadingMore || s.refreshing || s.endReached) return
        // 注意：error != null 时不再直接 return，否则翻页失败后重试按钮永远失效
        // （重试入口调用此方法，需清 error 并重发请求）
        // 用 Job 跟踪：两个 loadMore 几乎同时调用时（derivedStateOf 触发 + 手动触发），
        // 取消旧 Job 避免重复加载同一页 + 重复数据。
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true, error = null)
            val next = s.page + 1
            try {
                when (val r = loadPage(next)) {
                    is Resource.Success -> {
                        val (newItems, total) = r.data
                        // 关键修复：按 id 去重，避免 API 分页边界返回重复 id 导致
                        // LazyGrid key={it.id} 抛 IllegalArgumentException 崩溃。
                        val existingIds = rawItems.mapTo(HashSet(rawItems.size + newItems.size)) { it.id }
                        val deduped = newItems.filter { it.id !in existingIds }
                        // 用持久化缓存 + 已 enrich 缓存回填新页条目的 tags
                        container.comicTagsCache.ensureLoaded()
                        val enrichedNew = deduped.map { c ->
                            val enriched = enrichedDetailCache[c.id]
                            if (enriched != null) {
                                enriched
                            } else {
                                val cachedTags = container.comicTagsCache.get(c.id)
                                if (cachedTags != null && c.tags.isEmpty()) c.copy(tags = cachedTags) else c
                            }
                        }
                        // 缓存命中的新条目标记为已补全
                        enrichedNew.forEach { c ->
                            if (c.tags.isNotEmpty() && c.id !in enrichedIds) {
                                enrichedIds.add(c.id)
                                enrichedDetailCache[c.id] = c
                            }
                        }
                        rawItems = rawItems + enrichedNew
                        val reached = enrichedNew.isEmpty() || (total != null && rawItems.size >= total)
                        val (tags, names, authors) = currentRules()
                        val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
                        hasTagRules = rules.tags.isNotEmpty()
                        // v27.13 关键修复：applyBlock 必须用 mergeEnriched(rawItems) 而非 rawItems。
                        // rawItems 是 refresh/loadMore 时设置的快照，enrich 后来补全的 tags
                        // 只在 enrichedDetailCache 里。用 rawItems 会丢失已 enrich 的 tags。
                        val srcForFilter = mergeEnriched(rawItems)
                        val (filtered, coverHidden) = withContext(Dispatchers.Default) {
                            applyBlock(srcForFilter, rules, blockMode)
                        }
                        _state.value = _state.value.copy(
                            items = filtered,
                            page = next,
                            total = total ?: _state.value.total,
                            loadingMore = false,
                            endReached = reached,
                            filtering = hasTagRules && rawItems.any { it.id !in enrichedIds },
                            coverHiddenIds = coverHidden,
                        )
                        enrichTagsInBackground()
                    }
                    is Resource.Error -> {
                        Logger.w("List", "loadMore 失败: ${r.message}")
                        _state.value = _state.value.copy(loadingMore = false, error = friendlyError(r.message))
                    }
                    Resource.Loading -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e("List", "loadMore 异常", e)
                _state.value = _state.value.copy(loadingMore = false, error = friendlyError(Logger.brief(e)))
            }
        }
    }

    /** 从 DataStore 同步读取当前屏蔽规则（挂起直到 DataStore 首次 emit）。 */
    private suspend fun currentRules(): Triple<Set<String>, Set<String>, Set<String>> =
        container.blockedTagsStore.allRules.first()

    /**
     * 把底层错误转成对用户友好的提示（复用 [Logger.friendlyError]）。
     */
    private fun friendlyError(raw: String): String = Logger.friendlyError(raw)

    /**
     * 用预归一化的规则集合过滤列表。
     *
     * v27.11 持回策略：
     * - 无规则：原样返回（零开销）。
     * - 无 tag 规则（仅 name/author）：全部立即可见，即时过滤。
     * - 有 tag 规则：
     *   - name/author 屏蔽：立即判定。
     *   - 有 tags 的条目（列表接口返回了 或 缓存命中 或 enrich 完成）：即时判定。
     *   - 无 tags 且未 enrich 的条目：**持回**（不显示），等 enrich 完成再判定。
     *   - enrich 失败的条目：保持持回（fail-closed），绝不泄漏被屏蔽内容。
     *
     * v27.14 屏蔽模式：
     * - [BlockMode.HIDE]：被屏蔽条目从列表移除（行为同 v27.11）。
     * - [BlockMode.COVER_ONLY]：被屏蔽条目保留在列表中，但其 id 加入返回的 coverHiddenIds 集合，
     *   UI 据此将封面替换为打叉灰白占位图，保留卡片其他信息（标题/作者/标签）。
     *   持回（未补全 tags）的条目仍按原逻辑隐藏（不显示），不能加入 coverHiddenIds
     *   （因为还没确定是否真被屏蔽）。
     *
     * @return (可见列表, 仅隐藏封面的 id 集合)。HIDE 模式下集合为空。
     */
    private fun applyBlock(
        items: List<ComicBriefDto>,
        rules: BlockedTagsStore.NormalizedRules,
        mode: BlockMode,
    ): Pair<List<ComicBriefDto>, Set<String>> {
        if (rules.isEmpty()) return items to emptySet()
        val coverOnly = mode == BlockMode.COVER_ONLY
        val coverHidden = if (coverOnly) mutableSetOf<String>() else null

        /** 判定条目是否被屏蔽（不含持回逻辑）。 */
        fun isBlocked(comic: ComicBriefDto): Boolean =
            if (container.blockedTagsStore.isBlocked(emptyList(), comic.name, comic.author, rules)) {
                true
            } else if (comic.tags.isNotEmpty()) {
                container.blockedTagsStore.isBlocked(comic.tags, "", null, rules)
            } else {
                false
            }

        /** 屏蔽命中后的处理：HIDE 移除，COVER_ONLY 保留并记入 coverHidden。返回 true 表示从列表移除。 */
        fun handleBlocked(comic: ComicBriefDto): Boolean =
            if (coverOnly) {
                if (comic.id.isNotEmpty()) coverHidden!!.add(comic.id)
                false // 保留条目
            } else {
                true // 移除条目
            }

        val tagRulesActive = rules.tags.isNotEmpty()
        if (!tagRulesActive) {
            // 无 tag 规则：仅按 name/author 即时判定
            val filtered = items.filterNot { comic ->
                if (isBlocked(comic)) handleBlocked(comic) else false
            }
            return filtered to (coverHidden ?: emptySet())
        }
        // v27.12：一次性获取 enrichedIds 快照，避免 filterNot 内每条 item 都 synchronized。
        val enrichedSnapshot = enrichedIds.toSet()
        // 有 tag 规则：有 tags 的即时判定，无 tags 的持回（隐藏）
        val filtered = items.filterNot { comic ->
            // id="" 的条目无法 enrich，有 tags 就判定，无 tags 直接放行（无法精确屏蔽）。
            if (comic.id.isBlank()) {
                return@filterNot if (isBlocked(comic)) handleBlocked(comic) else false
            }
            // 未补全 tags → 持回（暂不显示，等 enrich 完成或缓存命中）
            // COVER_ONLY 模式下也持回：尚未确定是否真被屏蔽，不能贸然加入 coverHiddenIds。
            if (comic.id !in enrichedSnapshot) {
                return@filterNot true
            }
            // 已补全 → 用真实 tags 判定
            if (isBlocked(comic)) handleBlocked(comic) else false
        }
        return filtered to (coverHidden ?: emptySet())
    }

    /**
     * 后台异步补全列表项的真实 tags/likes/views/publishTime（列表接口不返回这些字段）。
     *
     * v27.11 关键设计：
     * - 持回模式：有 tag 规则时未补全的条目隐藏，enrich 完成后渐进出现。
     * - enrich 成功后写入持久化缓存（ComicTagsCache），下次加载零网络请求。
     * - enrich 失败 fail-closed：保持持回（隐藏），绝不泄漏。
     * - 无 tag 规则时也 enrich（补全 tags/likes/views/publishTime 用于卡片显示），但不持回。
     *
     * v27.12 性能优化：
     * - 整个协程切到 [Dispatchers.Default]，所有计算（map/normalize/filter/applyBlock）都不在 Main 上。
     * - 滚动时暂停 UI 刷新（enrich 后台继续，写 enrichedDetailCache），停下后一次性刷新。
     * - comicDetail 内部已切到 IO，Dispatchers.Default 不影响其网络执行。
     *
     * v27.13 竞态修复：
     * - 不再拍 rawMutable 快照，enrich 只写 enrichedDetailCache，刷新时用 mergeEnriched 合并。
     * - loadMore/refresh 改 rawItems 不影响 enrich，enrich 结果永不丢失。
     */
    private fun enrichTagsInBackground() {
        enrichDebounceJob?.cancel()
        enrichDebounceJob = viewModelScope.launch(Dispatchers.Default) {
            // v27.13：无 tag 规则时延迟从 1500ms 降到 400ms。
            // 之前 1500ms 太长，用户看到列表后要等 1.5s 才开始补全 tags，
            // 加上 enrich 慢，很多条目长时间无 tags。
            // enrich 走 API 域名，与封面图 CDN 不竞争，可以更早启动。
            val delayMs = if (hasTagRules) 200L else 400L
            delay(delayMs)
            runEnrich()
        }
    }

    private fun runEnrich() {
        enrichJob?.cancel()
        enrichJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val toEnrich = rawItems.filter { it.id.isNotEmpty() && it.id !in enrichedIds }
                if (toEnrich.isEmpty()) {
                    if (_state.value.filtering) _state.value = _state.value.copy(filtering = false)
                    return@launch
                }
                Logger.i("List", "开始补全字段：${toEnrich.size} 条待补全")

                val (tags, names, authors) = currentRules()
                val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)

                // v27.12：无 tag 规则时降低并发度（仅补全 tags/likes/views 用于卡片显示，不急），
                // 让封面图加载优先获得连接池资源。有 tag 规则时保持 8（屏蔽需尽快生效）。
                val concurrency = if (hasTagRules) MAX_CONCURRENT_ENRICH else LOW_PRIORITY_ENRICH_CONCURRENCY
                val semaphore = Semaphore(concurrency)
                var lastRefreshTime = 0L

                coroutineScope {
                    toEnrich.forEach { comic ->
                        launch {
                            semaphore.withPermit {
                                ensureActive()
                                if (enrichedIds.contains(comic.id)) return@withPermit
                                // v27.13：不再阻塞等待滚动停止，也不拍快照。
                                // enrich 只写 enrichedDetailCache，刷新时用 mergeEnriched 合并到最新 rawItems。

                                var success = false
                                repeat(MAX_ENRICH_ATTEMPTS) {
                                    if (success) return@repeat
                                    try {
                                        val detail = kotlinx.coroutines.withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
                                            container.repository.comicDetail(comic.id)
                                                .let { (it as? Resource.Success)?.data }
                                        }
                                        if (detail != null) {
                                            success = true
                                            enrichedIds.add(comic.id)
                                            enrichAttempts.remove(comic.id)
                                            val enriched = comic.copy(
                                                tags = detail.tags.ifEmpty { comic.tags },
                                                likes = detail.likes ?: comic.likes,
                                                views = detail.views ?: comic.views,
                                                publishTime = detail.publishTime ?: comic.publishTime,
                                            )
                                            enrichedDetailCache[comic.id] = enriched
                                            // 写入持久化缓存，下次加载直接命中
                                            if (enriched.tags.isNotEmpty()) {
                                                container.comicTagsCache.put(comic.id, enriched.tags)
                                            }
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (_: Throwable) {
                                        // 失败，继续重试
                                    }
                                }
                                if (!success) {
                                    // fail-closed：保持持回（隐藏），绝不泄漏被屏蔽内容
                                    val n = (enrichAttempts[comic.id] ?: 0) + 1
                                    enrichAttempts[comic.id] = n
                                    Logger.w("List", "补全失败 ${comic.id} 共 $n 次（fail-closed 保持隐藏）")
                                }
                                // throttle 刷新 UI：从 enrichedDetailCache 合并到最新 rawItems
                                val now = System.currentTimeMillis()
                                if (now - lastRefreshTime >= REFRESH_THROTTLE_MS) {
                                    lastRefreshTime = now
                                    // 滚动中只标记 dirty，不刷新 UI（避免 LazyColumn 重新布局卡顿）
                                    if (scrolling) {
                                        enrichDirty = true
                                    } else {
                                        val src = currentDataSource()
                                        val (filtered, coverHidden) = applyBlock(src, rules, blockMode)
                                        val stillPending = hasTagRules && src.any { it.id !in enrichedIds }
                                        if (filtered != _state.value.items ||
                                            stillPending != _state.value.filtering ||
                                            coverHidden != _state.value.coverHiddenIds
                                        ) {
                                            _state.value = _state.value.copy(
                                                items = filtered,
                                                filtering = stillPending,
                                                coverHiddenIds = coverHidden,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                ensureActive()
                // v27.13：不再写回 rawItems（没有快照）。直接从 enrichedDetailCache 合并刷新 UI。
                if (scrolling) {
                    enrichDirty = true
                } else {
                    val src = currentDataSource()
                    val (filtered, coverHidden) = applyBlock(src, rules, blockMode)
                    val stillPending = hasTagRules && src.any { it.id !in enrichedIds }
                    if (filtered != _state.value.items ||
                        stillPending != _state.value.filtering ||
                        coverHidden != _state.value.coverHiddenIds
                    ) {
                        _state.value = _state.value.copy(
                            items = filtered,
                            filtering = stillPending,
                            coverHiddenIds = coverHidden,
                        )
                    }
                }
                Logger.i("List", "补全字段完成")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e("List", "补全字段顶层异常", e)
                _state.value = _state.value.copy(filtering = false)
            }
        }
    }

    companion object {
        /** 单条补全最大尝试次数。 */
        private const val MAX_ENRICH_ATTEMPTS = 2

        /** 滑动窗口最大并发。8 并发，给图片加载留出 fastHttp 连接配额。 */
        private const val MAX_CONCURRENT_ENRICH = 8
        /** v27.13：无 tag 规则时的 enrich 并发度。
         *  从 3 提升到 6：enrich 走 API 域名，与封面图 CDN 域名不共享 maxRequestsPerHost，
         *  不会互相争抢。6 并发让一页 20 条 ~3.5s 内补完（原来 ~7s）。 */
        private const val LOW_PRIORITY_ENRICH_CONCURRENCY = 6

        /** 单次补全请求超时。快速失败不拖慢整体。 */
        private const val ENRICH_TIMEOUT_MS = 3000L

        /** UI 刷新节流间隔。攒到此间隔才刷一次，让条目成批更新。
         *  v27.12：从 200ms 提高到 500ms，减少列表重建次数。
         *  200ms 时每秒 5 次列表重建 → LazyColumn 频繁 diff → tags 闪烁 + 掉帧。
         *  500ms 让更多条目在单次刷新中累积，每秒仅 2 次重建。 */
        private const val REFRESH_THROTTLE_MS = 500L
    }
}
