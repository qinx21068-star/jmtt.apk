@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jmreader.ui.screen.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import kotlinx.coroutines.delay
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

    fun onQueryChange(q: String) {
        query = q
        debounce?.cancel()
        // 空关键词不发起请求，直接清空列表回到初始态，避免无意义转圈
        if (q.isBlank()) {
            _state.value = _state.value.copy(
                items = emptyList(),
                refreshing = false,
                loadingMore = false,
                error = null,
                page = 1,
                endReached = false,
            )
            return
        }
        debounce = viewModelScope.launch {
            delay(400)
            // 关键修复：debounce 自动搜索不写入历史，避免输入中途停顿污染历史
            // （输入 "abc" 中途停顿会产生 "a"、"ab"、"abc" 三个历史项）
            // 只有用户明确触发搜索（searchDirect：点历史词 / IME 搜索键）才记录历史
            refresh()
        }
    }

    /** 直接用历史词搜索（点击历史词条触发，立即搜索并存历史）。 */
    fun searchDirect(q: String) {
        query = q
        debounce?.cancel()
        viewModelScope.launch {
            container.searchHistoryStore.add(q.trim())
            refresh()
        }
    }

    fun onOrderChange(o: String) {
        order = o
        debounce?.cancel()   // 取消尚未触发的输入 debounce，避免与本次 refresh 并发
        refresh()
    }

    override suspend fun loadPage(page: Int): Resource<Pair<List<ComicBriefDto>, Int?>> =
        when (val r = container.repository.search(query, page, order)) {
            is Resource.Success -> Resource.Success(r.data.items to r.data.total)
            is Resource.Error -> r
            Resource.Loading -> Resource.Loading
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
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val history by container.searchHistoryStore.allTerms.collectAsState(initial = emptyList())
    val onLongClick = rememberBlockAction(
        container = container,
        onResult = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
        onNavigateToDetail = { id -> navController.navigate(Routes.detail(id)) },
    )

    // 从详情页点标签进入时，initialQuery 非空 → 自动用该词搜索。
    // 用 key=initialQuery 保证只在该值变化时触发一次，避免 recomposition 重复搜索。
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank() && vm.query.isBlank()) {
            vm.searchDirect(initialQuery)
        }
    }

    // 搜索开始时收起键盘：避免软键盘遮挡底部导航栏，导致用户搜索后无法切回首页/其他 tab。
    // 覆盖所有触发 refresh 的场景：debounce 自动搜索、点历史词条、IME 搜索键、换排序。
    // loadMore 不触发 refreshing，不会误收键盘。
    LaunchedEffect(state.refreshing) {
        if (state.refreshing) keyboardController?.hide()
    }

    // 用 Box 包裹以承载 SnackbarHost：长按屏蔽/收藏操作需要反馈
    Box(Modifier.fillMaxSize()) {
     Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                    IconButton(onClick = { navController.navigate(Routes.IMAGE_SEARCH) }) {
                        Icon(
                            Icons.Outlined.ImageSearch,
                            contentDescription = "以图搜图",
                        )
                    }
                }
            },
            singleLine = true,
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
        // 搜索历史：输入框为空时显示，点击直接搜索，每条带叉号可逐条删除
        if (vm.query.isBlank() && history.isNotEmpty()) {
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
                    onClick = { scope.launch { container.searchHistoryStore.clear() } },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) { Text("清空", style = MaterialTheme.typography.labelSmall) }
            }
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                history.take(20).forEach { term ->
                    // InputChip 带删除图标：点词条搜索，点叉号删除单条
                    // 用 IconButton 包裹叉号：提供合适点击区域 + 拦截事件避免冒泡到 chip onClick
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
        Box(Modifier.fillMaxSize()) {
            when {
                state.refreshing && state.items.isEmpty() -> LoadingBox()
                state.error != null && state.items.isEmpty() ->
                    ErrorBox(
                        state.error!!,
                        onRetry = { vm.refresh() },
                        onViewLogs = { navController.navigate(Routes.LOGS) },
                    )
                state.items.isEmpty() -> {
                    // 区分"未搜索"（query 空）与"搜索后无结果"（query 非空但 items 空）
                    val msg = if (vm.query.isBlank()) "输入关键词开始搜索"
                    else "未找到「${vm.query}」相关结果"
                    EmptyBox(msg)
                }
                else -> {
                    val reachEnd by remember {
                        derivedStateOf {
                            (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= state.items.size - 4
                        }
                    }
                    LaunchedEffect(reachEnd) { if (reachEnd) vm.loadMore() }
                    // 新搜索/换排序后结果刷新，滚回顶部，避免停在旧滚动位置看不到第一条
                    LaunchedEffect(state.items.firstOrNull()?.id, vm.order) {
                        if (!state.refreshing) listState.scrollToItem(0)
                    }
                    ComicList(
                        items = state.items,
                        state = listState,
                        onClick = { navController.navigate(Routes.detail(it.id)) },
                        onLongClick = onLongClick,
                        contentPadding = PaddingValues(12.dp),
                        loadingMore = state.loadingMore,
                        endReached = state.endReached,
                        loadError = if (state.items.isNotEmpty()) state.error else null,
                        onRetry = { vm.loadMore() },
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
