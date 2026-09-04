@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jmreader.R
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 分类定义：slug 对应禁漫 /categories/filter 的 c 参数。 */
data class Category(val slug: String, val label: String)
val CATEGORIES = listOf(
    Category("", "全部"),
    Category("doujin", "同人"),
    Category("single", "单本"),
    Category("short", "短篇"),
    Category("hanman", "韩漫"),
    Category("meiman", "美漫"),
    Category("doujin_cosplay", "Cosplay"),
    Category("3D", "3D"),
    Category("another", "其它"),
    Category("english_site", "英文站"),
)

/** 时间范围：对应禁漫 time 参数（a/t/w/m）。 */
data class TimeRange(val key: String, val label: String)
val TIMES = listOf(
    TimeRange("t", "今日"),
    TimeRange("w", "本周"),
    TimeRange("m", "本月"),
    TimeRange("a", "全部"),
)

/**
 * 通用分类列表 VM：支持 分类 + 时间 + 排序 任意组合。
 * 参数变化时自动 refresh。直连模式调 categoriesFilter。
 */
class CategoryListViewModel(container: AppContainer) : BaseListViewModel(container) {
    var time by mutableStateOf("a")
        private set
    var category by mutableStateOf("")
        private set
    var order by mutableStateOf("mr")
        private set

    fun updateTime(t: String) { if (time != t) { time = t; refresh() } }
    fun updateCategory(c: String) { if (category != c) { category = c; refresh() } }
    fun updateOrder(o: String) { if (order != o) { order = o; refresh() } }

    /**
     * 关键修复（Bug 46）：一次性设置 order + time 再 refresh，避免连续 updateOrder + updateTime
     * 触发两次 refresh。第二次 refresh 虽 cancel了 refreshJob，但 OkHttp call 已在 IO 调度器
     * 上发出，协程 cancel 不会自动 cancel OkHttp call，导致首次启动多发一次请求，
     * 结果被丢弃；禁漫有限流时可能首次进排行 tab 就被 429。
     */
    fun setDefaults(o: String, t: String) {
        if (order != o) order = o
        if (time != t) time = t
        refresh()
    }

    override suspend fun loadPage(page: Int): Resource<Pair<List<ComicBriefDto>, Int?>> =
        when (val r = container.repository.categoriesFilter(page, time, category, order)) {
            is Resource.Success -> Resource.Success(r.data.items to r.data.total)
            is Resource.Error -> r
            Resource.Loading -> Resource.Loading
        }
}

/**
 * 随机推荐 VM：调 [JMRepository.randomComics] 获取随机本子。
 *
 * 行为：
 * - refresh()：换一批，清空原列表重新拉取
 * - loadMore()：追加一批新的随机本子（按 id 去重，避免与已有重复）
 *
 * 首次返回空时自动重试一次（与 SearchViewModel 同款 Bug 48 修复逻辑）。
 */
class RandomListViewModel(container: AppContainer) : BaseListViewModel(container) {
    override suspend fun loadPage(page: Int): Resource<Pair<List<ComicBriefDto>, Int?>> {
        val r = container.repository.randomComics()
        return when (r) {
            is Resource.Success -> {
                // 关键修复：随机 page+分类偶尔会命中空页（深 page 或冷门分类），
                // 首页为空时自动重试一次，避免用户看到"没有数据"误以为功能坏了。
                if (page == 1 && r.data.items.isEmpty()) {
                    kotlinx.coroutines.delay(500)
                    when (val r2 = container.repository.randomComics()) {
                        is Resource.Success -> Resource.Success(r2.data.items to null)
                        is Resource.Error -> r2
                        Resource.Loading -> Resource.Loading
                    }
                } else {
                    // total=null：随机模式下"已到底"无意义，让 loadMore 始终可触发，
                    // 用户可以无限"换一批"。endReached 由 items.isEmpty() 控制。
                    Resource.Success(r.data.items to null)
                }
            }
            is Resource.Error -> r
            Resource.Loading -> Resource.Loading
        }
    }
}

class HomeVMFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CategoryListViewModel(container) as T
}

/** 随机 VM 工厂：单独工厂，create 时返回 RandomListViewModel。 */
class RandomVMFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RandomListViewModel(container) as T
}

@Composable
fun HomeScreen(container: AppContainer, navController: NavController) {
    // v27：用 rememberSaveable 保存 tab 索引，从详情页返回时恢复用户之前选的 tab
    // （之前用 remember，DetailScreen 离开 composition 后 tab 重置为 0=最新，随机/排行 tab 丢失）
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    // 最新 tab / 排行 tab / 随机 tab 各用独立 VM，切回时保留各自的分类/时间选择
    val latestVm: CategoryListViewModel = viewModel(key = "latest", factory = HomeVMFactory(container))
    val rankVm: CategoryListViewModel = viewModel(key = "rank", factory = HomeVMFactory(container))
    val randomVm: RandomListViewModel = viewModel(key = "random", factory = RandomVMFactory(container))
    // v27.5 性能优化：用 cachedSnapshot 作为 collectAsState 初始值，避免 null → 默认 → 真实 两轮重组
    val settings by container.settingsStore.settings.collectAsState(initial = container.settingsStore.cachedSnapshot)
    val listStyle = settings.listStyle
    // 首次进入：触发「最新」tab 初始加载（BaseListViewModel 不会自动 refresh），
    // 并把「排行」tab 默认按观看 + 用户设置的周期加载
    // 关键修复（Bug 46）：用 setDefaults 一次性改 order+time，避免连续两次 refresh。
    // v27.5 #14：time 从 settings.rankingPeriod 映射而来（日/周/月/总）
    LaunchedEffect(Unit) {
        if (latestVm.state.value.items.isEmpty() && !latestVm.state.value.refreshing) {
            latestVm.refresh()
        }
        if (rankVm.order == "mr" && rankVm.time == "a") {
            // 等 settings 加载完成（DataStore 首次读取可能延迟几十 ms）
            val s = container.settingsStore.settings.first()
            val t = when (s.rankingPeriod) {
                com.jmreader.data.local.RankingPeriod.DAILY -> "t"
                com.jmreader.data.local.RankingPeriod.MONTHLY -> "m"
                com.jmreader.data.local.RankingPeriod.ALL -> "a"
                else -> "w"
            }
            rankVm.setDefaults("mv", t)
        }
    }
    // 首次切到随机 tab 时自动加载一批
    LaunchedEffect(tab) {
        if (tab == 2 && randomVm.state.value.items.isEmpty() && !randomVm.state.value.refreshing) {
            randomVm.refresh()
        }
    }

    // 三个 tab 的状态选择：tab=0 最新 / tab=1 排行 / tab=2 随机
    // 随机 tab 不需要分类/时间/排序选择，只展示结果 + 换一批按钮
    val current: BaseListViewModel = when (tab) {
        0 -> latestVm
        1 -> rankVm
        else -> randomVm
    }
    val state by current.state.collectAsState()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val onLongClick = rememberBlockAction(
        container = container,
        onResult = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
        onNavigateToDetail = { id -> navController.navigate(Routes.detail(id)) },
    )
    // 关键性能：onClick 必须 remember，否则 HomeScreen 每次重组（loadMore/state 变化）都产生新 lambda，
    // 传给 ComicCard 后导致 pointerInput(taps) key 变化 → 手势协程重启 + ComicCard 无法跳过重组。
    val onClick: (ComicBriefDto) -> Unit = remember(navController) {
        { item -> navController.navigate(Routes.detail(item.id)) }
    }
    // v27.5 性能修复：onRetry/onLoadMore/onViewLogs 用 remember 缓存稳定 lambda
    val onRefresh = remember(current) { { current.refresh() } }
    val onLoadMore = remember(current) { { current.loadMore() } }
    val onViewLogs = remember(navController) { { navController.navigate(Routes.LOGS) } }
    // v27.12：onScrollStateChange 必须 remember，否则 current::setScrolling 方法引用
    // 每次重组产生新实例 → ComicList 参数不稳定 → 无法跳过重组。
    val onScrollStateChange = remember(current) { { s: Boolean -> current.setScrolling(s) } }

    // 用 Box 包裹以承载 SnackbarHost：长按屏蔽/收藏操作需要反馈
    Box(Modifier.fillMaxSize()) {
     Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.home_latest)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.home_ranking)) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("随机") })
        }
        // v27.6：筛选条作为 ComicList 的 header，随列表滚动移出视野
        // PrimaryTabRow 保留在 Column 外（tab 切换是核心导航，应保持可见）
        //
        // v27.12 性能修复：filterHeader 用 remember 稳定化。
        // 之前每次 HomeScreen 重组（loadMore/state 变化）都创建新 lambda 实例 →
        // ComicList 的 header 参数不稳定 → ComicList 无法跳过重组 → LazyColumn 重新组合。
        // remember 依赖列表只包含影响 header 内容的变量，state 不在依赖中
        // （header 内只用 state.refreshing，用 derivedStateOf 细粒度订阅避免整体重组）。
        val headerRefreshing by remember(current) {
            derivedStateOf { current.state.value.refreshing }
        }
        val filterHeader: @androidx.compose.runtime.Composable () -> Unit = remember(
            tab, rankVm, latestVm, current, headerRefreshing, onRefresh,
        ) {
            {
                // v27.5 UI 排版对齐：所有顶部筛选条统一 padding（horizontal=12dp, vertical=6dp）和 spacedBy(8dp)
                // 排行榜 tab 显示时间选择（日榜/周榜/月榜/全部）
                if (tab == 1) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(TIMES) { tr ->
                            FilterChip(
                                selected = rankVm.time == tr.key,
                                onClick = { rankVm.updateTime(tr.key) },
                                label = { Text(tr.label) },
                            )
                        }
                    }
                }
                // 分类选择（横向滚动）：仅最新/排行 tab 显示；随机 tab 不需要分类筛选
                if (tab != 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(CATEGORIES) { cat ->
                                FilterChip(
                                    selected = (current as CategoryListViewModel).category == cat.slug,
                                    onClick = { (current as CategoryListViewModel).updateCategory(cat.slug) },
                                    label = { Text(cat.label) },
                                )
                            }
                        }
                        IconButton(
                            onClick = onRefresh,
                            enabled = !headerRefreshing,
                            modifier = Modifier.size(40.dp),
                        ) {
                            if (headerRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = "刷新列表")
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "随机推荐",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(
                            onClick = onRefresh,
                            enabled = !headerRefreshing,
                            modifier = Modifier.size(40.dp),
                        ) {
                            if (headerRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Outlined.Casino, contentDescription = "换一批")
                            }
                        }
                    }
                }
                // 最新 tab 显示排序选择
                if (tab == 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "mr" to "最新", "mv" to "观看", "mp" to "图片数", "tf" to "评论",
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = latestVm.order == key,
                                onClick = { latestVm.updateOrder(key) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
        // 列表：三个 tab 各用独立 LazyListState，
        // 切回 tab / 从详情页返回时保留各自的滚动位置。
        val latestListState = rememberLazyListState()
        val rankListState = rememberLazyListState()
        val randomListState = rememberLazyListState()
        val listState = when (tab) {
            0 -> latestListState
            1 -> rankListState
            else -> randomListState
        }
        // 触底加载更多已移入 ComicList 内部（基于 onLoadMore 回调），
        // 这样列表/网格两种样式都能自动 loadMore，无需调用方按 listState 写 derivedStateOf。
        Box(Modifier.fillMaxSize()) {
            // v27.5 稳定性加固：捕获 state 到本地 val，避免 state.error!! race condition NPE
            val s = state
            when {
                s.refreshing && s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    filterHeader()
                    LoadingBox()
                }
                // 有 tag 规则且部分条目未补全 tags（持回中），显示"正在过滤…"
                s.filtering && s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    filterHeader()
                    LoadingBox(message = "正在按屏蔽规则过滤…")
                }
                s.error != null && s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    filterHeader()
                    ErrorBox(
                        s.error,
                        onRetry = onRefresh,
                        onViewLogs = onViewLogs,
                    )
                }
                s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    filterHeader()
                    EmptyBox(
                        if (tab == 1) "该分类在此时间段内暂无排行数据"
                        else if (tab == 2) "暂无随机推荐，点右上角骰子换一批"
                        else "没有数据"
                    )
                }
                else -> ComicList(
                    items = s.items,
                    state = listState,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    contentPadding = PaddingValues(bottom = 12.dp),
                    loadingMore = s.loadingMore,
                    endReached = s.endReached,
                    loadError = if (s.items.isNotEmpty()) s.error else null,
                    onRetry = onLoadMore,
                    listStyle = listStyle,
                    onLoadMore = onLoadMore,
                    header = filterHeader,
                    onScrollStateChange = onScrollStateChange,
                    coverHiddenIds = s.coverHiddenIds,
                )
            }
        }
     } // end Column
     // SnackbarHost 浮在底部，长按操作（屏蔽/收藏）的反馈在这里显示
     androidx.compose.material3.SnackbarHost(
         snackbar,
         modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
     )
    } // end Box
}
