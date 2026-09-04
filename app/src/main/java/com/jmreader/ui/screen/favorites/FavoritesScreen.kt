@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.FavoriteEntry
import com.jmreader.data.local.FavoritesStore
import com.jmreader.ui.components.ComicList
import com.jmreader.ui.components.EmptyBox
import com.jmreader.ui.components.FavoriteFolderManageDialog
import com.jmreader.ui.components.LoadingBox
import com.jmreader.ui.components.rememberBlockAction
import com.jmreader.ui.nav.Routes
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(container: AppContainer, navController: NavController) {
    // v27：用 rememberSaveable 保存 tab，从详情页返回时恢复（本地收藏/站点收藏/浏览历史）
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val localEntriesRaw by container.favoritesStore.entries.collectAsState()
    val folders by container.favoritesStore.folders.collectAsState()
    val browseHistoryRaw by container.browseHistoryStore.items.collectAsState()
    // 屏蔽规则变化时实时过滤本地收藏/浏览历史，与首页/搜索行为一致
    val rulesTriple by container.blockedTagsStore.allRules.collectAsState(
        Triple(emptySet(), emptySet(), emptySet()),
    )
    val rules = remember(rulesTriple) {
        container.blockedTagsStore.normalizeRules(rulesTriple.first, rulesTriple.second, rulesTriple.third)
    }
    // v27.6：异步补全本地收藏/历史中 tags 为空的条目
    // 缓存的 tags 可能是列表 API 的粗分类版本（收藏时未经过详情页），补全后屏蔽过滤更准确
    val enrichedLocalTags = remember { mutableStateMapOf<String, List<String>>() }
    var enrichVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(localEntriesRaw, browseHistoryRaw) {
        val allEntries = (localEntriesRaw.map { it.comic } + browseHistoryRaw.map { it.comic })
            .distinctBy { it.id }
            .filter { it.tags.isEmpty() && it.id !in enrichedLocalTags }
            .take(30)
        allEntries.forEach { comic ->
            try {
                when (val r = container.repository.comicDetail(comic.id)) {
                    is com.jmreader.data.repository.Resource.Success -> {
                        r.data.tags.takeIf { it.isNotEmpty() }?.let {
                            enrichedLocalTags[comic.id] = it
                            enrichVersion++
                        }
                    }
                    else -> {}
                }
            } catch (_: Throwable) {}
        }
    }
    // v27.5 #15：本地收藏分组过滤。filterFolder=null 表示「全部」；"__none__" 表示「未分组」
    // v27.15：FavoritesStore.READ_LATER_FOLDER_ID 表示「稍后再看」内置文件夹
    var filterFolder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val localEntries = remember(localEntriesRaw, rules, enrichVersion) {
        if (rules.isEmpty()) localEntriesRaw
        else localEntriesRaw.filterNot { e ->
            // v27.6：优先用补全后的 tags，补全失败则用缓存的原 tags
            val tags = enrichedLocalTags[e.comic.id] ?: e.comic.tags
            container.blockedTagsStore.isBlocked(tags, e.comic.name, e.comic.author, rules)
        }
    }
    val folderCounts = remember(localEntriesRaw) { container.favoritesStore.folderCounts() }
    val localFavorites = remember(localEntries, filterFolder) {
        // v27.15.2：渲染前去重（保留第一个），防御性兜底。
        // 数据层已做加载/写入去重，但保险起见 UI 层也去一次，
        // 避免任何残留重复条目导致 ComicList 的 LazyColumn key=c.id 冲突崩溃或显示数量不一致。
        val raw = when (filterFolder) {
            null -> localEntries.map { it.comic }
            "__none__" -> localEntries.filter { it.folderId == null }.map { it.comic }
            FavoritesStore.READ_LATER_FOLDER_ID ->
                localEntries.filter { it.folderId == FavoritesStore.READ_LATER_FOLDER_ID }.map { it.comic }
            else -> localEntries.filter { it.folderId == filterFolder }.map { it.comic }
        }
        val seen = HashSet<String>(raw.size)
        val out = ArrayList<com.jmreader.data.dto.ComicBriefDto>(raw.size)
        for (c in raw) {
            val key = c.id.ifBlank { "idx_${out.size}" }
            if (seen.add(key)) out.add(c)
        }
        out
    }
    val browseHistory = remember(browseHistoryRaw, rules, enrichVersion) {
        if (rules.isEmpty()) browseHistoryRaw
        else browseHistoryRaw.filterNot { e ->
            // v27.6：优先用补全后的 tags
            val tags = enrichedLocalTags[e.comic.id] ?: e.comic.tags
            container.blockedTagsStore.isBlocked(tags, e.comic.name, e.comic.author, rules)
        }
    }
    // v27.5 性能优化：用 cachedSnapshot 作为初始值，避免 null → 默认 → 真实 两轮重组
    val settings by container.settingsStore.settings.collectAsState(initial = container.settingsStore.cachedSnapshot)
    val listStyle = settings.listStyle
    // 顶层共享 SnackbarHost：三个 tab 的长按操作反馈都通过它显示
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // v27.5 性能修复：onResult 用 remember 缓存稳定 lambda，避免每重组新建并向下传递给 3 个 Tab
    val onResult: (String) -> Unit = remember(scope, snackbar) {
        { msg -> scope.launch { snackbar.showSnackbar(msg) }; Unit }
    }
    // v27.5 性能修复：onClick 用 remember 缓存，避免 ComicList 因 lambda 不稳定无法跳过重组
    val onItemClick = remember(navController) {
        { c: com.jmreader.data.dto.ComicBriefDto -> navController.navigate(Routes.detail(c.id)) }
    }
    val onNavigateToDetail = remember(navController) {
        { id: String -> navController.navigate(Routes.detail(id)) }
    }
    val onNavigateLogs = remember(navController) { { navController.navigate(Routes.LOGS) } }

    // v27.5 #15：「管理分组」对话框开关
    var showFolderManager by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("本地收藏 (${localEntries.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("站点收藏") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("浏览历史 (${browseHistory.size})") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("阅读历史") })
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> {
                        if (localEntries.isEmpty()) {
                            EmptyBox("还没有本地收藏")
                        } else {
                            // v27.6：FolderFilterRow 作为 ComicList 的 header，随列表滚动移出视野
                            val onLongClick = rememberBlockAction(
                                container = container,
                                onResult = onResult,
                                onNavigateToDetail = onNavigateToDetail,
                            )
                            // v27.5 #15：本地收藏分组过滤 chip 行
                            val folderHeader: @androidx.compose.runtime.Composable () -> Unit = {
                                FolderFilterRow(
                                    folders = folders,
                                    folderCounts = folderCounts,
                                    totalCount = localEntries.size,
                                    readLaterCount = folderCounts[FavoritesStore.READ_LATER_FOLDER_ID] ?: 0,
                                    selected = filterFolder,
                                    onSelect = { filterFolder = it },
                                    onManage = { showFolderManager = true },
                                )
                            }
                            if (localFavorites.isEmpty()) {
                                Column(Modifier.fillMaxSize()) {
                                    folderHeader()
                                    EmptyBox("当前分组下没有收藏")
                                }
                            } else {
                                ComicList(
                                    items = localFavorites,
                                    onClick = onItemClick,
                                    onLongClick = onLongClick,
                                    listStyle = listStyle,
                                    header = folderHeader,
                                )
                            }
                        }
                    }
                    1 -> ServerFavoritesTab(container, navController, onResult, listStyle)
                    2 -> BrowseHistoryTab(container, navController, browseHistory, onResult, listStyle)
                    // v27.5 #9 阅读历史：记录章节/页码进度，区别于「浏览历史」
                    3 -> com.jmreader.ui.screen.history.ReadingHistoryTab(
                        container = container,
                        navController = navController,
                        snackbar = snackbar,
                    )
                }
            }
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // v27.5 #15：「管理分组」对话框
    if (showFolderManager) {
        FavoriteFolderManageDialog(
            folders = folders,
            folderCounts = folderCounts,
            onDismiss = { showFolderManager = false },
            onCreate = { name ->
                scope.launch {
                    // v27.5 稳定性加固：MainScope 无 CEH，IO/Moshi 异常会让进程崩溃。
                    // 加 try-catch 兜底，失败时给用户提示而不是闪退。
                    try {
                        val f = container.favoritesStore.createFolder(name)
                        onResult("已创建分组：${f.name}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        com.jmreader.core.Logger.w("Favorites", "创建分组失败: ${com.jmreader.core.Logger.brief(e)}")
                        onResult("创建失败：${e.message ?: "未知错误"}")
                    }
                }
            },
            onRename = { id, newName ->
                scope.launch {
                    try {
                        container.favoritesStore.renameFolder(id, newName)
                        onResult("已重命名")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        com.jmreader.core.Logger.w("Favorites", "重命名分组失败: ${com.jmreader.core.Logger.brief(e)}")
                        onResult("重命名失败：${e.message ?: "未知错误"}")
                    }
                }
            },
            onDelete = { id ->
                scope.launch {
                    try {
                        // 删除时组内条目移到「未分组」（默认行为）
                        container.favoritesStore.deleteFolder(id, moveToFolderId = null)
                        // 若当前过滤的就是被删分组，重置为「全部」
                        if (filterFolder == id) filterFolder = null
                        onResult("已删除分组")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        com.jmreader.core.Logger.w("Favorites", "删除分组失败: ${com.jmreader.core.Logger.brief(e)}")
                        onResult("删除失败：${e.message ?: "未知错误"}")
                    }
                }
            },
        )
    }
}

/**
 * v27.5 #15：本地收藏上方的分组过滤 chip 行。
 *
 * Chips：[稍后再看] [全部] [未分组] [folder1] [folder2] ... [+ 管理]
 * - 选中态用 FilterChip，未选中用 AssistChip 样式
 * - 「+ 管理」打开 [FavoriteFolderManageDialog]
 *
 * v27.15：新增内置「稍后再看」chip，固定在最前，带书签图标，选中时与 [FavoritesStore.READ_LATER_FOLDER_ID] 对应。
 * 当 [readLaterCount] > 0 时 chip 显示数量。当 [readLaterCount] == 0 时不隐藏（让用户知道这个功能存在）。
 */
@Composable
private fun FolderFilterRow(
    folders: List<com.jmreader.data.dto.FavoriteFolder>,
    folderCounts: Map<String?, Int>,
    totalCount: Int,
    readLaterCount: Int,
    selected: String?,
    onSelect: (String?) -> Unit,
    onManage: () -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v27.15：内置「稍后再看」chip，固定在最前
        item(key = "read_later") {
            FilterChip(
                selected = selected == FavoritesStore.READ_LATER_FOLDER_ID,
                onClick = { onSelect(FavoritesStore.READ_LATER_FOLDER_ID) },
                label = { Text("稍后再看 ($readLaterCount)") },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Bookmark,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        item(key = "all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("全部 ($totalCount)") },
            )
        }
        item(key = "none") {
            val c = folderCounts[null] ?: 0
            FilterChip(
                selected = selected == "__none__",
                onClick = { onSelect("__none__") },
                label = { Text("未分组 ($c)") },
            )
        }
        itemsIndexed(folders, key = { i, f -> f.id.ifBlank { "idx_$i" } }) { _, f ->
            val c = folderCounts[f.id] ?: 0
            FilterChip(
                selected = selected == f.id,
                onClick = { onSelect(f.id) },
                label = { Text("${f.name} ($c)") },
            )
        }
        item(key = "manage") {
            AssistChip(
                onClick = onManage,
                label = { Text("管理") },
                leadingIcon = {
                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.padding(0.dp))
                },
            )
        }
    }
}

/** 浏览历史 tab：展示看过的作品，支持清空。 */
@Composable
private fun BrowseHistoryTab(
    container: AppContainer,
    navController: NavController,
    entries: List<com.jmreader.data.local.BrowseEntry>,
    onResult: (String) -> Unit,
    listStyle: com.jmreader.data.local.ListStyle,
) {
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
    // v27.5 性能修复：onClick / onNavigateToDetail 用 remember 缓存稳定 lambda
    val onItemClick = remember(navController) {
        { c: com.jmreader.data.dto.ComicBriefDto -> navController.navigate(Routes.detail(c.id)) }
    }
    val onNavigateToDetail = remember(navController) {
        { id: String -> navController.navigate(Routes.detail(id)) }
    }
    // v27.7：「清空」按钮作为 ComicList 的 header 随滚动移出视野（举一反三）
    if (entries.isEmpty()) {
        EmptyBox("还没有浏览记录")
    } else {
        val listState = rememberLazyListState()
        val onLongClick = rememberBlockAction(
            container = container,
            onResult = onResult,
            onNavigateToDetail = onNavigateToDetail,
        )
        // 关键修复（Bug 47）：之前 entries.map { it.comic } 未用 remember 包裹，
        // 列表滚动等任何重组都会重新 map 出新 List 实例，增加 GC 压力，低端机掉帧。
        val historyComics = androidx.compose.runtime.remember(entries) { entries.map { it.comic } }
        val clearHeader: @androidx.compose.runtime.Composable () -> Unit = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { showClearConfirm = true }) {
                    Text("清空", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        ComicList(
            items = historyComics,
            state = listState,
            onClick = onItemClick,
            onLongClick = onLongClick,
            listStyle = listStyle,
            header = clearHeader,
        )
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
    listStyle: com.jmreader.data.local.ListStyle,
) {
    val vm: com.jmreader.ui.screen.favorites.ServerFavoritesViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(factory = com.jmreader.ui.screen.favorites.ServerFavoritesVMFactory(container))
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    // v27.5 性能修复：onClick/onViewLogs/onRetry/onLoadMore 用 remember 缓存稳定 lambda
    val onItemClick = remember(navController) {
        { c: com.jmreader.data.dto.ComicBriefDto -> navController.navigate(Routes.detail(c.id)) }
    }
    val onNavigateToDetail = remember(navController) {
        { id: String -> navController.navigate(Routes.detail(id)) }
    }
    val onNavigateLogs = remember(navController) { { navController.navigate(Routes.LOGS) } }
    val onLongClick = rememberBlockAction(
        container = container,
        onResult = onResult,
        onNavigateToDetail = onNavigateToDetail,
    )
    val onRetry = remember(vm) { { vm.refresh() } }
    val onLoadMore = remember(vm) { { vm.loadMore() } }
    // 触底加载更多已移入 ComicList 内部（基于 onLoadMore 回调），列表/网格通用。
    Box(Modifier.fillMaxSize()) {
        // v27.5 稳定性加固：把 state 捕获到本地 val，避免 `state.error != null` 通过后
        // 在另一线程写 StateFlow 时 state.error 变 null，下一行 `state.error!!` 抛 NPE。
        // collectAsState 的 by 委托每次访问都读最新 snapshot，单次 composition 内不保证一致。
        val s = state
        when {
            s.refreshing && s.items.isEmpty() -> LoadingBox()
            s.filtering && s.items.isEmpty() -> LoadingBox(message = "正在按屏蔽规则过滤…")
            s.error != null && s.items.isEmpty() ->
                com.jmreader.ui.components.ErrorBox(
                    s.error,
                    onRetry = onRetry,
                    onViewLogs = onNavigateLogs,
                )
            s.items.isEmpty() -> EmptyBox("在站点登录后可查看云端收藏")
            else -> ComicList(
                items = s.items,
                state = listState,
                onClick = onItemClick,
                onLongClick = onLongClick,
                loadingMore = s.loadingMore,
                endReached = s.endReached,
                loadError = if (s.items.isNotEmpty()) s.error else null,
                onRetry = onLoadMore,
                listStyle = listStyle,
                onLoadMore = onLoadMore,
                onScrollStateChange = vm::setScrolling,
                coverHiddenIds = s.coverHiddenIds,
            )
        }
    }
}
