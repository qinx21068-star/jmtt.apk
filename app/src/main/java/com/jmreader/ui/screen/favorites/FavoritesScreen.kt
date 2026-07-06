@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jmreader.data.AppContainer
import com.jmreader.ui.components.ComicList
import com.jmreader.ui.components.EmptyBox
import com.jmreader.ui.components.LoadingBox
import com.jmreader.ui.components.rememberBlockAction
import com.jmreader.ui.nav.Routes
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(container: AppContainer, navController: NavController) {
    var tab by remember { mutableIntStateOf(0) }
    val localFavorites by container.favoritesStore.items.collectAsState()
    val browseHistory by container.browseHistoryStore.items.collectAsState()
    // 顶层共享 SnackbarHost：三个 tab 的长按操作反馈都通过它显示
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onResult: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("本地收藏 (${localFavorites.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("站点收藏") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("浏览历史 (${browseHistory.size})") })
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> {
                        if (localFavorites.isEmpty()) {
                            EmptyBox("还没有本地收藏")
                        } else {
                            // 本地收藏也支持长按操作（取消收藏/屏蔽/详情）
                            val onLongClick = rememberBlockAction(
                                container = container,
                                onResult = onResult,
                                onNavigateToDetail = { id -> navController.navigate(Routes.detail(id)) },
                            )
                            ComicList(
                                items = localFavorites,
                                onClick = { navController.navigate(Routes.detail(it.id)) },
                                onLongClick = onLongClick,
                            )
                        }
                    }
                    1 -> ServerFavoritesTab(container, navController, onResult)
                    2 -> BrowseHistoryTab(container, navController, browseHistory, onResult)
                }
            }
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 浏览历史 tab：展示看过的作品，支持清空。 */
@Composable
private fun BrowseHistoryTab(
    container: AppContainer,
    navController: NavController,
    entries: List<com.jmreader.data.local.BrowseEntry>,
    onResult: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (entries.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { showClearConfirm = true }) {
                    Text("清空", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (entries.isEmpty()) {
            EmptyBox("还没有浏览记录")
        } else {
            val listState = rememberLazyListState()
            val onLongClick = rememberBlockAction(
                container = container,
                onResult = onResult,
                onNavigateToDetail = { id -> navController.navigate(Routes.detail(id)) },
            )
            ComicList(
                items = entries.map { it.comic },
                state = listState,
                onClick = { navController.navigate(Routes.detail(it.id)) },
                onLongClick = onLongClick,
            )
        }
    }

    // 清空浏览历史二次确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空浏览历史") },
            text = { Text("将清除全部 ${entries.size} 条浏览记录。\n\n此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        container.browseHistoryStore.clear()
                        onResult("已清空浏览历史")
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ServerFavoritesTab(
    container: AppContainer,
    navController: NavController,
    onResult: (String) -> Unit,
) {
    val vm: com.jmreader.ui.screen.favorites.ServerFavoritesViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(factory = com.jmreader.ui.screen.favorites.ServerFavoritesVMFactory(container))
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    val onLongClick = rememberBlockAction(
        container = container,
        onResult = onResult,
        onNavigateToDetail = { id -> navController.navigate(Routes.detail(id)) },
    )
    // 自动加载更多：滚到接近底部时触发
    val reachEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= state.items.size - 4
        }
    }
    LaunchedEffect(reachEnd) { if (reachEnd) vm.loadMore() }
    Box(Modifier.fillMaxSize()) {
        when {
            state.refreshing && state.items.isEmpty() -> LoadingBox()
            state.error != null && state.items.isEmpty() ->
                com.jmreader.ui.components.ErrorBox(
                    state.error!!,
                    onRetry = { vm.refresh() },
                    onViewLogs = { navController.navigate(Routes.LOGS) },
                )
            state.items.isEmpty() -> EmptyBox("在站点登录后可查看云端收藏")
            else -> ComicList(
                items = state.items,
                state = listState,
                onClick = { navController.navigate(Routes.detail(it.id)) },
                onLongClick = onLongClick,
                loadingMore = state.loadingMore,
                endReached = state.endReached,
                loadError = if (state.items.isNotEmpty()) state.error else null,
                onRetry = { vm.loadMore() },
            )
        }
    }
}
