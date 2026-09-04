@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jmreader.ui.screen.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jmreader.core.Logger
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.repository.Resource
import com.jmreader.ui.components.ComicList
import com.jmreader.ui.components.EmptyBox
import com.jmreader.ui.components.ErrorBox
import com.jmreader.ui.components.LoadingBox
import com.jmreader.ui.components.rememberBlockAction
import com.jmreader.ui.nav.Routes
import com.jmreader.ui.viewmodel.BaseListViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class Order(val key: String, val label: String)
private val ORDERS = listOf(
    Order("latest", "最新"),
    Order("views", "观看"),
    Order("likes", "评论"),
    Order("picture", "图片数"),
)

class SearchViewModel(container: AppContainer) : BaseListViewModel(container) {
    var query by mutableStateOf("")
        private set
    var order by mutableStateOf("latest")
        private set

    private var debounce: Job? = null
    /** v27.14：当前是否处于批量搜索模式（用于阻止 loadMore 触发分页）。 */
    private var batchMode: Boolean = false
    /**
     * v27.14：批量搜索的当前 Job。新搜索启动前取消旧 Job，
     * 避免旧 searchBatch 完成后调用 setBatchResult 覆盖新结果（竞态）。
     */
    private var searchBatchJob: Job? = null

    fun onQueryChange(q: String) {
        query = q
        debounce?.cancel()
        // 空关键词不发起请求，直接清空列表回到初始态，避免无意义转圈
        if (q.isBlank()) {
            batchMode = false
            // 取消尚未完成的批量搜索，避免清空后又写回旧批量结果
            searchBatchJob?.cancel()
            _state.value = _state.value.copy(
                items = emptyList(),
                refreshing = false,
                loadingMore = false,
                error = null,
                page = 1,
                endReached = false,
                coverHiddenIds = emptySet(),
            )
            return
        }
        debounce = viewModelScope.launch {
            delay(400)
            // 关键修复：debounce 自动搜索不写入历史，避免输入中途停顿污染历史
            // （输入 "abc" 中途停顿会产生 "a"、"ab"、"abc" 三个历史项）
            // 只有用户明确触发搜索（searchDirect：点历史词 / IME 搜索键）才记录历史
            val batchIds = parseBatchIds(q)
            if (batchIds != null) {
                searchBatch(batchIds, saveHistory = false)
            } else {
                // v27.14：从批量模式切回普通搜索时取消尚未完成的批量搜索，
                // 避免其 setBatchResult 在 refresh 之后覆盖普通搜索结果。
                if (batchMode) {
                    batchMode = false
                    searchBatchJob?.cancel()
                }
                refresh()
            }
        }
    }

    /** 直接用历史词搜索（点击历史词条触发，立即搜索并存历史）。 */
    fun searchDirect(q: String) {
        query = q
        debounce?.cancel()
        // v27.14：批量 ID 搜索（输入多个 JM 号，空格/逗号/句号分隔）
        val batchIds = parseBatchIds(q)
        if (batchIds != null) {
            searchBatch(batchIds, saveHistory = true)
            return
        }
        // v27.14：从批量模式切回普通搜索时取消尚未完成的批量搜索
        if (batchMode) {
            batchMode = false
            searchBatchJob?.cancel()
        }
        viewModelScope.launch {
            // v27.5 稳定性加固：DataStore 读盘/写盘可能抛 IOException，未捕获会冒泡到
            // Thread.uncaughtExceptionHandler → CrashHandler → 杀进程。整段包 try-catch，
            // 失败仍触发 refresh，让用户至少能看到搜索结果（只是没存进历史）。
            try {
                // v27.5 #10 #30：仅在用户开启"记录搜索历史"且未启用隐身模式时才写历史
                val s = container.settingsStore.settings.first()
                if (s.saveSearchHistory && !s.incognito) {
                    container.searchHistoryStore.add(q.trim())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w("Search", "记录搜索历史失败: ${Logger.brief(e)}")
            }
            refresh()
        }
    }

    fun onOrderChange(o: String) {
        // v27.14：批量模式下排序无意义（结果固定无分页），直接忽略
        if (batchMode) return
        order = o
        debounce?.cancel()   // 取消尚未触发的输入 debounce，避免与本次 refresh 并发
        refresh()
    }

    /**
     * v27.14：通用重试入口（错误页"重试"按钮调用）。
     * - 批量模式下：解析当前 query 重新触发批量搜索（refresh 在 batchMode 下返回空，无效）。
     * - 普通模式下：等价于 refresh()。
     */
    fun retrySearch() {
        if (batchMode) {
            val ids = parseBatchIds(query) ?: run {
                // 异常状态：batchMode=true 但 query 不是批量格式，回退到普通搜索
                batchMode = false
                refresh()
                return
            }
            searchBatch(ids, saveHistory = false)
        } else {
            refresh()
        }
    }

    /**
     * v27.14：解析输入是否为批量 JM 号搜索。
     *
     * 触发条件：输入含分隔符（空格/逗号/句号/分号，中英文皆可），且拆分后≥2 个 token，
     * 且每个 token 均为纯字母数字（JM ID 特征），长度 1..30。
     *
     * @return 按输入顺序去重后的 ID 列表，或 null 表示不是批量搜索（按普通关键词走 search API）。
     */
    fun parseBatchIds(input: String): List<String>? {
        val tokens = input.split(Regex("[\\s,，。;；]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        val idPattern = Regex("^[A-Za-z0-9]+$")
        if (!tokens.all { idPattern.matches(it) && it.length <= 30 }) return null
        // 按输入顺序去重（用户可能重复输入同一个 ID）
        val seen = HashSet<String>(tokens.size)
        val ordered = ArrayList<String>(tokens.size)
        for (t in tokens) {
            if (seen.add(t)) ordered.add(t)
        }
        return ordered
    }

    /**
     * v27.14：批量按 JM 号搜索。
     *
     * 并发拉取每个 ID 的详情（comicDetail），按用户输入顺序排列结果。
     * 详情接口返回的 tags/likes/views 即真实数据，无需 enrich。
     * 批量结果无分页（endReached=true），且忽略排序变化。
     *
     * @param ids 按用户输入顺序的 ID 列表（已去重）
     * @param saveHistory true=记录搜索历史（用户主动触发时）
     */
    private fun searchBatch(ids: List<String>, saveHistory: Boolean) {
        query = ids.joinToString(" ")
        batchMode = true
        debounce?.cancel()
        // v27.14：取消上一次未完成的批量搜索，避免旧结果覆盖新结果
        searchBatchJob?.cancel()
        // 可选：记录搜索历史（批量搜索词即原始输入）
        if (saveHistory) {
            viewModelScope.launch {
                try {
                    val s = container.settingsStore.settings.first()
                    if (s.saveSearchHistory && !s.incognito) {
                        container.searchHistoryStore.add(ids.joinToString(" "))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.w("Search", "记录批量搜索历史失败: ${Logger.brief(e)}")
                }
            }
        }
        searchBatchJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                refreshing = true,
                error = null,
                page = 1,
                endReached = false,
                items = emptyList(),
                coverHiddenIds = emptySet(),
            )
            try {
                // 并发拉取每个 ID 的详情，8s 超时
                val results: Map<String, com.jmreader.data.dto.ComicDetailDto?> = coroutineScope {
                    ids.map { id ->
                        async {
                            try {
                                val detail = kotlinx.coroutines.withTimeoutOrNull(8000L) {
                                    container.repository.comicDetail(id)
                                        .let { (it as? Resource.Success)?.data }
                                }
                                id to detail
                            } catch (_: Throwable) {
                                id to null
                            }
                        }
                    }.awaitAll().toMap()
                }
                // 按输入顺序排列，跳过失败的（detail==null）
                val ordered = ids.mapNotNull { id ->
                    results[id]?.let { d ->
                        com.jmreader.data.dto.ComicBriefDto(
                            id = d.id.ifBlank { id },
                            name = d.name.ifBlank { "JM$id" },
                            author = d.author,
                            tags = d.tags,
                            cover = d.cover,
                            likes = d.likes,
                            views = d.views,
                            publishTime = d.publishTime,
                        )
                    }
                }
                if (ordered.isEmpty()) {
                    _state.value = _state.value.copy(
                        refreshing = false,
                        endReached = true,
                        error = "全部 ${ids.size} 个本子拉取失败，请检查 ID 或网络后重试",
                    )
                    return@launch
                }
                setBatchResult(ordered, ordered.size)
                val missing = ids.size - ordered.size
                if (missing > 0) {
                    Logger.w("Search", "批量搜索：$missing/${ids.size} 个 ID 拉取失败")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e("Search", "批量搜索异常", e)
                _state.value = _state.value.copy(
                    refreshing = false,
                    error = Logger.friendlyError(Logger.brief(e)),
                )
            }
        }
    }

    override suspend fun loadPage(page: Int): Resource<Pair<List<ComicBriefDto>, Int?>> {
        // v27.14：批量模式下不分页（setBatchResult 已设 endReached=true，loadMore 不会触发，
        // 但为防御性编程，这里直接返回空成功结果）
        if (batchMode) {
            return Resource.Success(emptyList<ComicBriefDto>() to _state.value.total)
        }
        val r = container.repository.search(query, page, order)
        return when (r) {
            is Resource.Success -> {
                // 关键修复（Bug 48）：偶尔禁漫 API 会因域名抖动/限流返回空列表（code=200 但 content=[]），
                // 用户表现为"搜索不出结果"。首次为空且 query 非空时自动重试一次（短暂延迟后换域名再请求）。
                // 仅首页重试：翻页返回空可能是真的没数据，不应误重试。
                if (page == 1 && r.data.items.isEmpty() && query.isNotBlank() && r.data.total != 0) {
                    Logger.w("Search", "首页搜索返回空，800ms 后重试一次")
                    kotlinx.coroutines.delay(800)
                    // reqApi 内部已会自动轮换域名重试，这里直接发第二次请求即可。
                    when (val r2 = container.repository.search(query, page, order)) {
                        is Resource.Success -> Resource.Success(r2.data.items to r2.data.total)
                        is Resource.Error -> r2
                        Resource.Loading -> Resource.Loading
                    }
                } else {
                    Resource.Success(r.data.items to r.data.total)
                }
            }
            is Resource.Error -> r
            Resource.Loading -> Resource.Loading
        }
    }
}

class SearchVMFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SearchViewModel(container) as T
}

@Composable
fun SearchScreen(
    container: AppContainer,
    navController: NavController,
    initialQuery: String? = null,
) {
    val vm: SearchViewModel = viewModel(factory = SearchVMFactory(container))
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    // 关键修复（Bug 40）：网格模式也要外部传入 state，新搜索/换排序后才能 scrollToItem(0) 滚回顶部。
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // v27.5 性能优化：用 cachedSnapshot 作为初始值，避免 null → 默认 → 真实 两轮重组
    val settings by container.settingsStore.settings.collectAsState(initial = container.settingsStore.cachedSnapshot)
    val listStyle = settings.listStyle
    // v27.5 #11 搜索结果按 tag 筛选开关；#10 搜索历史记录开关
    val tagFilterEnabled = settings.searchTagFilter
    val saveHistoryEnabled = settings.saveSearchHistory
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val history by container.searchHistoryStore.allTerms.collectAsState(initial = emptyList())
    val onLongClick = rememberBlockAction(
        container = container,
        onResult = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
        onNavigateToDetail = { id -> navController.navigate(Routes.detail(id)) },
    )
    // v27.5 性能修复：onClick 用 remember 缓存稳定 lambda，避免 ComicList 因 lambda 不稳定无法跳过重组
    val onItemClick = remember(navController) {
        { c: ComicBriefDto -> navController.navigate(Routes.detail(c.id)) }
    }
    val onImageSearch = remember(navController) { { navController.navigate(Routes.IMAGE_SEARCH) } }
    val onViewLogs = remember(navController) { { navController.navigate(Routes.LOGS) } }
    val onClearHistory = remember(scope, container) {
        { scope.launch { container.searchHistoryStore.clear() }; Unit }
    }
    // v27.14：用 retrySearch 而非 refresh，批量搜索失败时也能正确重试（refresh 在 batchMode 下返回空）
    val onRetry = remember(vm) { { vm.retrySearch() } }
    val onLoadMore = remember(vm) { { vm.loadMore() } }

    // v27.5 #11 标签筛选：从当前搜索结果提取热门 tag，点击 chip 二次过滤
    var selectedTag by remember(vm.query) { mutableStateOf<String?>(null) }
    val availableTags = remember(state.items) {
        state.items.flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .take(15)
            .map { it.key }
    }
    val visibleItems = remember(state.items, selectedTag) {
        if (selectedTag == null) state.items
        else state.items.filter { selectedTag in it.tags }
    }

    // v27.5 #10 热门推荐：query 为空时显示周榜 top 12
    var hotComics by remember { mutableStateOf<List<ComicBriefDto>>(emptyList()) }
    var hotLoading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 首次进入搜索页加载一次热门推荐（不在 query 变化时重载，避免干扰搜索）
        if (hotComics.isEmpty()) {
            hotLoading = true
            try {
                // 等设置加载完，按用户设置的排行榜周期作为"热门"维度
                val s = container.settingsStore.settings.first()
                val t = when (s.rankingPeriod) {
                    com.jmreader.data.local.RankingPeriod.DAILY -> "t"
                    com.jmreader.data.local.RankingPeriod.MONTHLY -> "m"
                    com.jmreader.data.local.RankingPeriod.ALL -> "a"
                    else -> "w"
                }
                when (val r = container.repository.ranking(time = t, category = "", page = 1)) {
                    is Resource.Success -> {
                        // v27.6 修复：热门推荐必须经过屏蔽 tag 过滤，否则被屏蔽的本子 100% 泄漏
                        val (tags, names, authors) = container.blockedTagsStore.allRules.first()
                        val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
                        // v27.7 修复：列表 API 返回的 tags 是粗分类（混入 category），
                        // 用粗 tags 过滤会漏判被屏蔽的具体 tag（如"NTR"被屏蔽但列表只返回"同人"）。
                        // 有 tag 规则时拉取详情页真实 tags 再过滤，确保零泄漏。
                        if (rules.tags.isEmpty()) {
                            // 无 tag 规则：name/author 可立即准确判定，无需补全
                            hotComics = r.data.items
                                .filterNot { container.blockedTagsStore.isBlocked(it.tags, it.name, it.author, rules) }
                                .take(12)
                        } else {
                            // 有 tag 规则：并发拉取详情页真实 tags（最多 20 条候选，取过滤后前 12 条）
                            val candidates = r.data.items.take(20)
                            val enriched = coroutineScope {
                                candidates.map { comic ->
                                    async {
                                        try {
                                            val detail = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                                                container.repository.comicDetail(comic.id)
                                                    .let { (it as? Resource.Success)?.data }
                                            }
                                            val realTags = detail?.tags?.takeIf { it.isNotEmpty() } ?: comic.tags
                                            comic.copy(tags = realTags) to
                                                !container.blockedTagsStore.isBlocked(realTags, comic.name, comic.author, rules)
                                        } catch (_: Throwable) {
                                            // fail-closed：补全失败则隐藏（宁可少显示也不泄漏被屏蔽内容）
                                            comic to false
                                        }
                                    }
                                }.awaitAll()
                            }
                            hotComics = enriched.filter { it.second }.map { it.first }.take(12)
                        }
                    }
                    else -> {}
                }
            } catch (_: Throwable) {}
            finally { hotLoading = false }
        }
    }

    // 从详情页点标签进入时，initialQuery 非空 → 自动用该词搜索。
    // 用 key=initialQuery 保证只在该值变化时触发一次，避免 recomposition 重复搜索。
    //
    // 关键修复（Bug 39）：之前用 `vm.query.isBlank()` 作为条件，但底部 tab 导航配置
    // popUpTo+saveState+restoreState 导致 SearchScreen 的 ViewModel 状态被保留——
    // 用户第一次搜索后 vm.query 非空，从详情页点另一个标签进入时被跳过，新标签不触发搜索。
    // 改为 `initialQuery != vm.query`：只要点的是不同的标签词就触发搜索。
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank() && initialQuery != vm.query) {
            vm.searchDirect(initialQuery)
        }
    }

    // Bug 8 修复：搜索结果加载完成后自动收起键盘。
    // 之前 debounce 自动搜索（输入停顿 400ms）不收键盘，键盘遮挡底部导航栏，
    // 用户点首页 tab 实际点到键盘上 →「无法跳转首页」。
    //
    // 关键修复（v26）：原条件 `!state.refreshing && state.items.isNotEmpty()` 只在
    // "搜索完成且有结果"时收键盘。若搜索无结果/出错，键盘不收，底部 tab 仍被遮挡。
    // 改为只要 refreshing 从 true 变 false 就收键盘（搜索完成即收，无论结果如何）。
    LaunchedEffect(state.refreshing) {
        if (!state.refreshing) {
            keyboardController?.hide()
        }
    }
    // 滚动列表时也收键盘：用户开始浏览结果时键盘应消失，露出底部导航栏。
    // 用 snapshotFlow 监听 firstVisibleItemIndex 变化，任何滚动即 hide。
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (idx, off) -> if (idx != 0 || off != 0) keyboardController?.hide() }
    }
    LaunchedEffect(gridState) {
        androidx.compose.runtime.snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (idx, off) -> if (idx != 0 || off != 0) keyboardController?.hide() }
    }

    // Bug 11 修复：把 lastScrolledQuery/Order 提到 when 分支外，
    // 避免 state 在"有结果"和"加载中"之间切换时 rememberSaveable 丢失值导致列表跳回顶部。
    var lastScrolledQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var lastScrolledOrder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    // 用 Box 包裹以承载 SnackbarHost：长按屏蔽/收藏操作需要反馈
    Box(Modifier.fillMaxSize()) {
     Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            singleLine = true,
            placeholder = { Text("搜索漫画 / 作者 / 本子号") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            // 搜索栏右侧：清空按钮（输入非空时显示）+ 识图按钮
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清空")
                        }
                    }
                    IconButton(onClick = onImageSearch) {
                        Icon(
                            Icons.Outlined.ImageSearch,
                            contentDescription = "以图搜图",
                        )
                    }
                }
            },
            // 键盘回车直接搜索（绕过 400ms debounce，立即触发）
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                val q = vm.query.trim()
                if (q.isNotEmpty()) {
                    vm.searchDirect(q)
                    keyboardController?.hide()
                }
            }),
        )
        // v27.6：历史/热门/排序chips/tag chips 作为 ComicList 的 header，随列表滚动移出视野
        // 搜索框保持固定（用户需要随时看到搜索框）
        val searchHeader: @androidx.compose.runtime.Composable () -> Unit = {
            // 搜索历史：输入框为空且开启"记录搜索历史"时显示
            if (vm.query.isBlank() && saveHistoryEnabled && history.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "历史",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.TextButton(
                        onClick = onClearHistory,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) { Text("清空", style = MaterialTheme.typography.labelSmall) }
                }
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    history.take(20).forEach { term ->
                        androidx.compose.material3.InputChip(
                            selected = false,
                            onClick = { vm.searchDirect(term) },
                            label = { Text(term, style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = {
                                androidx.compose.material3.IconButton(
                                    onClick = { scope.launch { container.searchHistoryStore.remove(term) } },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Outlined.Close,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            // 热门推荐：输入框为空时显示周榜 top 12
            if (vm.query.isBlank() && hotComics.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "热门推荐",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(hotComics, key = { i, c -> c.id.ifBlank { "idx_$i" } }) { _, c ->
                            val click = remember(c, navController) {
                                { navController.navigate(Routes.detail(c.id)) }
                            }
                            HotComicCard(c, onClick = click)
                        }
                    }
                }
            }
            // 排序 chips
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ORDERS.forEach { o ->
                    FilterChip(
                        selected = vm.order == o.key,
                        onClick = { vm.onOrderChange(o.key) },
                        label = { Text(o.label) },
                    )
                }
            }
            // 标签筛选：开启开关且有搜索结果时显示 tag chips 二级过滤
            if (tagFilterEnabled && state.items.isNotEmpty() && availableTags.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        FilterChip(
                            selected = selectedTag == null,
                            onClick = { selectedTag = null },
                            label = { Text("全部", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    items(availableTags) { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { selectedTag = if (selectedTag == tag) null else tag },
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            // v27.5 稳定性加固：捕获 state 到本地 val，避免 state.error!! race condition NPE
            val s = state
            when {
                s.refreshing && s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    searchHeader()
                    LoadingBox()
                }
                // 有 tag 规则且部分条目未补全 tags（持回中），显示"正在过滤…"
                s.filtering && s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    searchHeader()
                    LoadingBox(message = "正在按屏蔽规则过滤…")
                }
                s.error != null && s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    searchHeader()
                    ErrorBox(
                        s.error,
                        onRetry = onRetry,
                        onViewLogs = onViewLogs,
                    )
                }
                s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    searchHeader()
                    val msg = if (vm.query.isBlank()) "输入关键词开始搜索"
                    else "未找到「${vm.query}」相关结果"
                    EmptyBox(msg)
                }
                visibleItems.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    searchHeader()
                    EmptyBox("「$selectedTag」标签下无匹配结果")
                }
                else -> {
                    // 触底加载更多已移入 ComicList 内部（基于 onLoadMore 回调），列表/网格通用。
                    // 新搜索/换排序后结果刷新，滚回顶部。
                    // 仅在 query 或 order 变化时才滚到顶部，从详情页返回时不滚。
                    // lastScrolledQuery/Order 已提到 when 分支外（Bug 11 修复）。
                    LaunchedEffect(s.items.firstOrNull()?.id) {
                        if (!s.refreshing && s.items.isNotEmpty() &&
                            (lastScrolledQuery != vm.query || lastScrolledOrder != vm.order)) {
                            lastScrolledQuery = vm.query
                            lastScrolledOrder = vm.order
                            // 关键修复（Bug 40）：网格模式用 gridState 滚顶，列表模式用 listState。
                            if (listStyle == com.jmreader.data.local.ListStyle.LIST) {
                                listState.scrollToItem(0)
                            } else {
                                gridState.scrollToItem(0)
                            }
                        }
                    }
                    ComicList(
                        // v27.5 #11：使用经 tag 筛选后的 visibleItems
                        items = visibleItems,
                        state = listState,
                        gridState = gridState,
                        onClick = onItemClick,
                        onLongClick = onLongClick,
                        contentPadding = PaddingValues(12.dp),
                        loadingMore = s.loadingMore,
                        endReached = s.endReached,
                        loadError = if (s.items.isNotEmpty()) s.error else null,
                        onRetry = onLoadMore,
                        listStyle = listStyle,
                        onLoadMore = onLoadMore,
                        header = searchHeader,
                        onScrollStateChange = vm::setScrolling,
                        coverHiddenIds = s.coverHiddenIds,
                    )
                }
            }
        }
     } // end Column
     androidx.compose.material3.SnackbarHost(
         snackbar,
         modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
     )
    } // end Box
}

/** v27.5 #10 热门推荐卡片：固定宽度封面 + 标题，点击跳详情页。 */
@Composable
private fun HotComicCard(comic: ComicBriefDto, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = remember(comic.cover, ctx) {
                // v27.5 性能修复：移除 crossfade(true)，避免滑动时每个封面跑 300ms alpha 动画抢主线程
                ImageRequest.Builder(ctx)
                    .data(comic.cover)
                    .build()
            },
            contentDescription = comic.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            comic.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
