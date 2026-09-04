@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.author

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
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

private data class Order(val key: String, val label: String)
private val ORDERS = listOf(
    Order("latest", "最新"),
    Order("views", "观看"),
    Order("likes", "评论"),
    Order("picture", "图片数"),
)

/**
 * v27.5 #13：作者主页 ViewModel。
 *
 * 禁漫无独立"按作者"接口，复用通用 search API，把作者名作为关键词搜索。
 * 因此结果可能含非该作者作品（API 模糊匹配），UI 已提示用户。
 *
 * 继承 [BaseListViewModel] 自动获得：分页加载、屏蔽 tag 过滤、tags 补全、错误处理。
 */
class AuthorViewModel(
    container: AppContainer,
    val author: String,
) : BaseListViewModel(container) {
    var order by mutableStateOf("latest")
        private set

    init {
        // 构造完成即开始加载首页
        refresh()
    }

    fun onOrderChange(o: String) {
        order = o
        refresh()
    }

    override suspend fun loadPage(page: Int): Resource<Pair<List<ComicBriefDto>, Int?>> {
        val r = container.repository.search(author, page, order)
        return when (r) {
            is Resource.Success -> Resource.Success(r.data.items to r.data.total)
            is Resource.Error -> r
            Resource.Loading -> Resource.Loading
        }
    }
}

class AuthorVMFactory(
    private val container: AppContainer,
    private val author: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AuthorViewModel(container, author) as T
}

/**
 * 作者主页：展示某作者的所有作品列表（基于搜索 API）。
 *
 * - TopAppBar 显示作者名
 * - 排序 chip 行（最新/观看/评论/图片数）
 * - 标签筛选 chip 行（受 [com.jmreader.data.local.AppSettings.searchTagFilter] 控制）
 * - 列表/网格/紧凑网格样式（复用全局 listStyle 设置）
 * - 支持触底加载更多
 */
@Composable
fun AuthorScreen(
    container: AppContainer,
    navController: NavController,
    author: String,
    onBack: () -> Unit,
) {
    val vm: AuthorViewModel = viewModel(
        factory = AuthorVMFactory(container, author),
        key = "author_$author", // 不同作者用不同 VM 实例，避免复用同一 VM 导致状态错乱
    )
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // v27.5 性能优化：用 cachedSnapshot 作为初始值，避免 null → 默认 → 真实 两轮重组
    val settings by container.settingsStore.settings.collectAsState(initial = container.settingsStore.cachedSnapshot)
    val listStyle = settings.listStyle
    val tagFilterEnabled = settings.searchTagFilter
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onLongClick = rememberBlockAction(
        container = container,
        onResult = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
        onNavigateToDetail = { id -> navController.navigate(Routes.detail(id)) },
    )

    // v27.5 #11 标签筛选：从当前结果提取热门 tag，点击 chip 二次过滤
    var selectedTag by remember(vm.author) { mutableStateOf<String?>(null) }
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

    // 换排序后滚回顶部
    var lastScrolledOrder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.items.firstOrNull()?.id) {
        if (!state.refreshing && state.items.isNotEmpty() && lastScrolledOrder != vm.order) {
            lastScrolledOrder = vm.order
            if (listStyle == com.jmreader.data.local.ListStyle.LIST) {
                listState.scrollToItem(0)
            } else {
                gridState.scrollToItem(0)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = vm.author,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "作者作品集",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // v27.7：排序 chip + 标签筛选作为 ComicList 的 header，随列表滚动移出视野（举一反三）
            val authorHeader: @androidx.compose.runtime.Composable () -> Unit = {
                // 排序 chip 行
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
                // 标签筛选 chip 行
                if (tagFilterEnabled && state.items.isNotEmpty() && availableTags.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
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
                        authorHeader()
                        LoadingBox()
                    }
                    s.filtering && s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                        authorHeader()
                        LoadingBox(message = "正在按屏蔽规则过滤…")
                    }
                    s.error != null && s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                        authorHeader()
                        ErrorBox(
                            s.error,
                            onRetry = { vm.refresh() },
                            onViewLogs = { navController.navigate(Routes.LOGS) },
                        )
                    }
                    s.items.isEmpty() -> Column(Modifier.fillMaxSize()) {
                        authorHeader()
                        EmptyBox("未找到「${vm.author}」的作品\n（禁漫搜索为模糊匹配，可能该作者无作品或名字不完整）")
                    }
                    visibleItems.isEmpty() -> Column(Modifier.fillMaxSize()) {
                        authorHeader()
                        EmptyBox("「$selectedTag」标签下无匹配结果")
                    }
                    else -> ComicList(
                        items = visibleItems,
                        state = listState,
                        gridState = gridState,
                        onClick = { navController.navigate(Routes.detail(it.id)) },
                        onLongClick = onLongClick,
                        contentPadding = PaddingValues(12.dp),
                        loadingMore = s.loadingMore,
                        endReached = s.endReached,
                        loadError = if (s.items.isNotEmpty()) s.error else null,
                        onRetry = { vm.loadMore() },
                        listStyle = listStyle,
                        onLoadMore = { vm.loadMore() },
                        header = authorHeader,
                        onScrollStateChange = vm::setScrolling,
                        coverHiddenIds = s.coverHiddenIds,
                    )
                }
            }
        }
    }
}
