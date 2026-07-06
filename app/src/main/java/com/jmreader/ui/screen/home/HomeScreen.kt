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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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

    override suspend fun loadPage(page: Int): Resource<Pair<List<ComicBriefDto>, Int?>> =
        when (val r = container.repository.categoriesFilter(page, time, category, order)) {
            is Resource.Success -> Resource.Success(r.data.items to r.data.total)
            is Resource.Error -> r
            Resource.Loading -> Resource.Loading
        }
}

class HomeVMFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CategoryListViewModel(container) as T
}

@Composable
fun HomeScreen(container: AppContainer, navController: NavController) {
    var tab by remember { mutableStateOf(0) }
    // 最新 tab 和排行 tab 各用独立 VM，切回时保留各自的分类/时间选择
    val latestVm: CategoryListViewModel = viewModel(key = "latest", factory = HomeVMFactory(container))
    val rankVm: CategoryListViewModel = viewModel(key = "rank", factory = HomeVMFactory(container))
    // 首次进入：触发「最新」tab 初始加载（BaseListViewModel 不会自动 refresh），
    // 并把「排行」tab 默认设为按观看+本周（周榜）
    LaunchedEffect(Unit) {
        if (latestVm.state.value.items.isEmpty() && !latestVm.state.value.refreshing) {
            latestVm.refresh()
        }
        if (rankVm.order == "mr") rankVm.updateOrder("mv")
        if (rankVm.time == "a") rankVm.updateTime("w")
    }

    val current = if (tab == 0) latestVm else rankVm
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

    // 用 Box 包裹以承载 SnackbarHost：长按屏蔽/收藏操作需要反馈
    Box(Modifier.fillMaxSize()) {
     Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.home_latest)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.home_ranking)) })
        }
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
        // 分类选择（横向滚动）：两个 tab 共用
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(CATEGORIES) { cat ->
                FilterChip(
                    selected = current.category == cat.slug,
                    onClick = { current.updateCategory(cat.slug) },
                    label = { Text(cat.label) },
                )
            }
        }
        // 最新 tab 显示排序选择
        if (tab == 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
        // 列表
        val listState = remember(tab) { LazyListState() }
        val reachEnd by remember {
            derivedStateOf {
                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= state.items.size - 4
            }
        }
        LaunchedEffect(reachEnd) { if (reachEnd) current.loadMore() }
        Box(Modifier.fillMaxSize()) {
            when {
                state.refreshing && state.items.isEmpty() -> LoadingBox()
                state.error != null && state.items.isEmpty() ->
                    ErrorBox(
                        state.error!!,
                        onRetry = { current.refresh() },
                        onViewLogs = { navController.navigate(Routes.LOGS) },
                    )
                state.items.isEmpty() -> EmptyBox(
                    if (tab == 1) "该分类在此时间段内暂无排行数据" else "没有数据"
                )
                else -> ComicList(
                    items = state.items,
                    state = listState,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    contentPadding = PaddingValues(bottom = 12.dp),
                    loadingMore = state.loadingMore,
                    endReached = state.endReached,
                    loadError = if (state.items.isNotEmpty()) state.error else null,
                    onRetry = { current.loadMore() },
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
