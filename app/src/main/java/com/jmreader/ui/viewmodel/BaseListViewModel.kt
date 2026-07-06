package com.jmreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmreader.core.Logger
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.local.BlockedTagsStore
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

/**
 * 列表页通用状态。
 * 自动应用屏蔽 tag 过滤：含被屏蔽 tag 的条目不会出现在最终列表里。
 */
data class ListUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val items: List<ComicBriefDto> = emptyList(),
    val page: Int = 1,
    val total: Int? = null,
    val error: String? = null,
    val endReached: Boolean = false,
)

abstract class BaseListViewModel(protected val container: AppContainer) : ViewModel() {

    protected val _state = MutableStateFlow(ListUiState())
    val state: StateFlow<ListUiState> = _state

    /** 未经屏蔽过滤的原始条目（含所有已加载页）。 */
    private var rawItems: List<ComicBriefDto> = emptyList()

    /** 已补全 tags 的 albumId 集合，避免重复请求 /album。 */
    private val enrichedIds = mutableSetOf<String>()

    /** 当前补全协程，新的补全启动前取消旧的，避免并发竞态。 */
    private var enrichJob: Job? = null

    /** 屏蔽 tag 的实时集合（仅用于 UI 展示，过滤逻辑不依赖此缓存）。 */
    val blockedTags: StateFlow<Set<String>> = container.blockedTagsStore.tags
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 屏蔽名称关键词的实时集合（仅用于 UI 展示，过滤逻辑不依赖此缓存）。 */
    val blockedNames: StateFlow<Set<String>> = container.blockedTagsStore.names
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        // 屏蔽规则变化时，对已加载的原始数据重新过滤，立即刷新可见列表。
        // 关键修复：直接用 collect 到的 (tags, names, authors) 过滤 + 触发补全，
        // 不读 stateIn 缓存（派发顺序不确定，可能读到旧空集 → 过滤不生效）。
        // 关键性能：combine(tags, names, authors) 对同一 DataStore 的一次 emit 最多触发 3 次，
        // 用 lastRules 去重，避免 3 次 _state.value 写入 → HomeScreen 3 次重组 → ComicCard 级联掉帧。
        viewModelScope.launch {
            var lastRules: BlockedTagsStore.NormalizedRules? = null
            container.blockedTagsStore.allRules.collect { (tags, names, authors) ->
                if (rawItems.isNotEmpty()) {
                    val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
                    // 去重：归一化后的规则集合内容相同则跳过（combine 多次 emit 同一结果）
                    if (rules == lastRules) return@collect
                    lastRules = rules
                    _state.value = _state.value.copy(items = applyBlock(rawItems, rules))
                    if (tags.isNotEmpty()) enrichTagsInBackground()
                }
            }
        }
    }

    /** 子类提供分页加载逻辑。返回 (items, total)。 */
    protected abstract suspend fun loadPage(page: Int): Resource<Pair<List<ComicBriefDto>, Int?>>

    /** 当前 refresh 的 Job，新 refresh 启动前取消旧的，避免并发竞态覆盖结果。 */
    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true, error = null, page = 1, endReached = false)
            try {
                when (val r = loadPage(1)) {
                    is Resource.Success -> {
                        val (items, total) = r.data
                        Logger.i("List", "refresh ok: ${items.size} items, total=$total")
                        rawItems = items
                        enrichedIds.clear()  // 新刷新，重置补全缓存
                        val reached = items.isEmpty() || (total != null && rawItems.size >= total)
                        // 关键修复：直接从 DataStore 读最新规则，不用 stateIn 缓存。
                        // stateIn(Eagerly) 的初值是空集，DataStore 异步读盘期间缓存仍为空，
                        // 若 refresh 在 DataStore 首次 emit 前执行，会读到空集 → tag 屏蔽不生效。
                        val (tags, names, authors) = currentRules()
                        val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
                        _state.value = _state.value.copy(
                            items = applyBlock(rawItems, rules),
                            page = 1,
                            total = total,
                            refreshing = false,
                            endReached = reached,
                        )
                        // 若启用了 tag 屏蔽，异步补全真实 tags 后重过滤
                        if (tags.isNotEmpty()) enrichTagsInBackground()
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
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true, error = null)
            val next = s.page + 1
            try {
                when (val r = loadPage(next)) {
                    is Resource.Success -> {
                        val (newItems, total) = r.data
                        rawItems = rawItems + newItems
                        val reached = newItems.isEmpty() || (total != null && rawItems.size >= total)
                        // 关键修复：loadMore 同样从 DataStore 读最新规则，且新页也要补全 tags。
                        // 之前 loadMore 只用 stateIn 缓存过滤、不触发补全，导致翻页后的条目
                        // 即使含被屏蔽 tag 也照常显示（tag 屏蔽对第 2 页之后无效）。
                        val (tags, names, authors) = currentRules()
                        val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
                        _state.value = _state.value.copy(
                            items = applyBlock(rawItems, rules),
                            page = next,
                            total = total ?: _state.value.total,
                            loadingMore = false,
                            endReached = reached,
                        )
                        if (tags.isNotEmpty()) enrichTagsInBackground()
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
     * 用预归一化的规则集合过滤列表。规则为空则原样返回（零开销）。
     *
     * 性能优化：规则端归一化由 [BlockedTagsStore.normalizeRules] 预计算一次，
     * 此处仅对每条漫画的 tags/name/author 做归一化，不再对规则集合重复归一化。
     * 列表越大、屏蔽规则越多，相比旧实现的节省越明显（O(M*N) → O(M + N)）。
     */
    private fun applyBlock(
        items: List<ComicBriefDto>,
        rules: BlockedTagsStore.NormalizedRules,
    ): List<ComicBriefDto> {
        if (rules.isEmpty()) return items
        return items.filterNot { comic ->
            container.blockedTagsStore.isBlocked(comic.tags, comic.name, comic.author, rules)
        }
    }

    /**
     * 后台异步补全列表项的真实 tags（列表接口不返回 tags，只有 category 粗分类）。
     * 对每个未补全的条目请求 /album 取真实 tags 回填，再触发重过滤。
     * 仅在用户配置了 tag 屏蔽时才有必要，避免无谓流量。
     *
     * 关键设计：
     * 1. 并发补全（每批 8 个），而非串行——原来 40 条串行要 1-2 分钟，用户等不到。
     * 2. 不限数量，补全所有未补全条目——原来第 41 条之后的条目永不补全、不过滤。
     * 3. **全部补全完成后才刷新 UI 一次**（旧实现每批刷新一次 → N 次重组 → 滚动卡顿）。
     *    补全是后台 IO，用户不急；减少 N 次刷新为 1 次，列表滚动期间不被打断。
     * 4. 用 enrichJob 管理协程，新补全启动前取消旧的，避免并发竞态。
     * 5. 正确处理 CancellationException（不吞），保证协程取消机制正常。
     * 6. rawItems 更新用 mutableMap 索引而非整表 map，O(N) → O(1) 单点替换。
     */
    private fun enrichTagsInBackground() {
        enrichJob?.cancel()
        enrichJob = viewModelScope.launch {
            val toEnrich = rawItems.filter { it.id.isNotEmpty() && it.id !in enrichedIds }
            if (toEnrich.isEmpty()) return@launch
            Logger.i("List", "开始补全 tags：${toEnrich.size} 条待补全")

            // rawItems 的可变索引：id → 下标。补全时单点替换，避免每次 rawItems.map { } 全表 O(N)。
            val indexMap = rawItems.withIndex().associate { (i, c) -> c.id to i }.toMutableMap()
            val rawMutable = rawItems.toMutableList()

            // 分批并发，每批 8 个，避免请求风暴触发限流
            val batchSize = 8
            toEnrich.chunked(batchSize).forEach { batch ->
                ensureActive()
                coroutineScope {
                    batch.forEach { comic ->
                        launch {
                            if (enrichedIds.contains(comic.id)) return@launch
                            try {
                                val detail = container.repository.comicDetail(comic.id)
                                    .let { (it as? Resource.Success)?.data }
                                enrichedIds.add(comic.id)
                                if (detail != null && detail.tags.isNotEmpty()) {
                                    val idx = indexMap[comic.id]
                                    if (idx != null) {
                                        rawMutable[idx] = comic.copy(tags = detail.tags)
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e  // 不吞 CancellationException，保证协程取消正常
                            } catch (e: Throwable) {
                                enrichedIds.add(comic.id)  // 失败也标记，避免反复重试
                            }
                        }
                    }
                }
            }
            // 全部补全完成后，统一刷新一次 UI（旧实现每批刷新一次 → 滚动期间反复重组）
            rawItems = rawMutable
            val (tags, names, authors) = currentRules()
            val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
            val filtered = applyBlock(rawItems, rules)
            // 只有过滤结果数量变化时才更新（避免无谓的 List 引用变更触发 LazyGrid diff）
            if (filtered.size != _state.value.items.size) {
                _state.value = _state.value.copy(items = filtered)
            }
            Logger.i("List", "补全 tags 完成")
        }
    }
}
