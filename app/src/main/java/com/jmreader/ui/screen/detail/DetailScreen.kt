@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.jmreader.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ChapterDto
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.repository.Resource
import com.jmreader.ui.components.ErrorBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class DetailUiState(
    val loading: Boolean = true,
    val detail: ComicDetailDto? = null,
    val error: String? = null,
    val isFavorite: Boolean = false,
    /** 上次阅读的章节 ID。null=没读过；用于"开始阅读"按钮跳转到上次章节而非第一章。 */
    val lastReadChapterId: String? = null,
)

class DetailViewModel(
    private val container: AppContainer,
    private val comicId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** 当前已屏蔽的 tag 集合（实时同步 DataStore，用于标签 chip 显示已屏蔽状态）。 */
    val blockedTags: StateFlow<Set<String>> = container.blockedTagsStore.tags
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 当前已屏蔽的作者集合（实时同步 DataStore，用于作者名旁显示屏蔽状态）。 */
    val blockedAuthors: StateFlow<Set<String>> = container.blockedTagsStore.authors
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                when (val r = container.repository.comicDetail(comicId)) {
                    is Resource.Success -> {
                        // 查最近阅读章节：若有历史则按钮显示"继续阅读"并跳该章节，否则"开始阅读"跳第一章
                        val lastChapter = container.historyStore.items.first()
                            .firstOrNull { it.comic.id == comicId }?.chapterId
                        _state.value = DetailUiState(
                            loading = false,
                            detail = r.data,
                            isFavorite = container.favoritesStore.isFavorite(comicId),
                            lastReadChapterId = lastChapter,
                        )
                        // 记录浏览历史：打开详情页即记录，不需阅读
                        val brief = ComicBriefDto(
                            r.data.id, r.data.name, r.data.author,
                            r.data.tags, r.data.cover,
                        )
                        container.browseHistoryStore.upsert(brief)
                    }
                    is Resource.Error -> {
                        com.jmreader.core.Logger.e("Detail", "加载详情失败: ${r.message}")
                        _state.value = _state.value.copy(
                            loading = false,
                            error = com.jmreader.core.Logger.friendlyError(r.message),
                        )
                    }
                    Resource.Loading -> {}
                }
            } catch (e: Throwable) {
                com.jmreader.core.Logger.e("Detail", "load 异常", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = com.jmreader.core.Logger.friendlyError(com.jmreader.core.Logger.brief(e)),
                )
            }
        }
    }

    fun toggleFavorite(onResult: (String) -> Unit = {}) {
        val detail = _state.value.detail ?: return
        val brief = ComicBriefDto(detail.id, detail.name, detail.author, detail.tags, detail.cover)
        viewModelScope.launch {
            try {
                val now = container.favoritesStore.toggle(brief)
                _state.value = _state.value.copy(isFavorite = now)
                onResult(if (now) "已加入收藏" else "已取消收藏")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.jmreader.core.Logger.e("Detail", "收藏操作失败", e)
                onResult("收藏操作失败")
            }
        }
    }

    /** 屏蔽指定 tag（长按标签触发）。 */
    fun blockTag(tag: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                container.blockedTagsStore.addTag(tag)
                onResult("已屏蔽标签：$tag")
            } catch (e: Throwable) {
                onResult("屏蔽失败：${com.jmreader.core.Logger.brief(e)}")
            }
        }
    }

    /** 取消屏蔽指定 tag（长按已屏蔽标签触发）。 */
    fun unblockTag(tag: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                container.blockedTagsStore.removeTag(tag)
                onResult("已取消屏蔽：$tag")
            } catch (e: Throwable) {
                onResult("取消屏蔽失败：${com.jmreader.core.Logger.brief(e)}")
            }
        }
    }

    /** 屏蔽指定作者（详情页作者名旁按钮触发）。 */
    fun blockAuthor(author: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                container.blockedTagsStore.addAuthor(author)
                onResult("已屏蔽作者：$author")
            } catch (e: Throwable) {
                onResult("屏蔽失败：${com.jmreader.core.Logger.brief(e)}")
            }
        }
    }

    /** 取消屏蔽指定作者。 */
    fun unblockAuthor(author: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                container.blockedTagsStore.removeAuthor(author)
                onResult("已取消屏蔽作者：$author")
            } catch (e: Throwable) {
                onResult("取消屏蔽失败：${com.jmreader.core.Logger.brief(e)}")
            }
        }
    }

    fun download(onResult: (String) -> Unit) {
        val detail = _state.value.detail ?: return
        val brief = ComicBriefDto(detail.id, detail.name, detail.author, detail.tags, detail.cover)
        try {
            container.downloadManager.enqueue(brief, detail)
            onResult("已加入下载队列")
        } catch (e: Throwable) {
            com.jmreader.core.Logger.e("Detail", "加入下载失败", e)
            onResult("下载失败：${com.jmreader.core.Logger.brief(e)}")
        }
    }
}

class DetailVMFactory(
    private val container: AppContainer,
    private val comicId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DetailViewModel(container, comicId) as T
}

@Composable
fun DetailScreen(
    container: AppContainer,
    comicId: String,
    onBack: () -> Unit,
    onRead: (comicId: String, chapterId: String) -> Unit,
    onOpenLogs: () -> Unit = {},
    onSearchByTag: (String) -> Unit = {},
    onSearchByAuthor: (String) -> Unit = {},
) {
    val vm: DetailViewModel = viewModel(factory = DetailVMFactory(container, comicId))
    val state by vm.state.collectAsState()
    val blockedTags by vm.blockedTags.collectAsState()
    val blockedAuthors by vm.blockedAuthors.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.name ?: "加载中…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            val detail = state.detail
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> ErrorBox(
                    message = state.error!!,
                    onRetry = { vm.load() },
                    onViewLogs = onOpenLogs,
                )
                detail != null -> DetailContent(
                    detail = detail,
                    isFavorite = state.isFavorite,
                    blockedTags = blockedTags,
                    blockedAuthors = blockedAuthors,
                    lastReadChapterId = state.lastReadChapterId,
                    onToggleFavorite = { vm.toggleFavorite { msg ->
                        scope.launch { snackbar.showSnackbar(msg) }
                    } },
                    onDownload = { vm.download { msg ->
                        scope.launch { snackbar.showSnackbar(msg) }
                    } },
                    onBlockTag = { tag ->
                        if (tag in blockedTags) {
                            vm.unblockTag(tag) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                        } else {
                            vm.blockTag(tag) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                        }
                    },
                    onBlockAuthor = { author ->
                        if (author in blockedAuthors) {
                            vm.unblockAuthor(author) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                        } else {
                            vm.blockAuthor(author) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                        }
                    },
                    onSearchByTag = onSearchByTag,
                    onSearchByAuthor = onSearchByAuthor,
                    onRead = { ch -> onRead(detail.id, ch.id) },
                )
                // 兜底：loading=false + error=null + detail=null 的极端窗口
                else -> ErrorBox(
                    message = "加载中…",
                    onRetry = { vm.load() },
                    onViewLogs = onOpenLogs,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    detail: ComicDetailDto,
    isFavorite: Boolean,
    blockedTags: Set<String>,
    blockedAuthors: Set<String>,
    lastReadChapterId: String?,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onBlockTag: (String) -> Unit,
    onBlockAuthor: (String) -> Unit,
    onSearchByTag: (String) -> Unit,
    onSearchByAuthor: (String) -> Unit,
    onRead: (ChapterDto) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val ctx = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(detail.cover)
                        // 详情页用原图解码（Size.ORIGINAL），清晰度最高。
                        // 列表项用 size(300,420) 缩略图；此处用原图形成质量分级。
                        .size(Size.ORIGINAL)
                        .crossfade(true)
                        .build(),
                    contentDescription = detail.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(0.7f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(Modifier.weight(1f)) {
                    Text(detail.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    detail.author?.let { author ->
                        Spacer(Modifier.height(4.dp))
                        // 作者名 + 屏蔽按钮：
                        // - 点击作者名 → 用作者名搜索该作者其它作品
                        // - 点击右侧 Block 图标 → 屏蔽/取消屏蔽该作者
                        val isAuthorBlocked = author in blockedAuthors
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = author,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onSearchByAuthor(author) }
                                    .padding(vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = { onBlockAuthor(author) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                // 已屏蔽用 error 色实心 Block，未屏蔽用淡色轮廓 Block
                                // 图标相同但 tint 区分状态，避免引入额外 icon 依赖
                                Icon(
                                    imageVector = Icons.Outlined.Block,
                                    contentDescription = if (isAuthorBlocked) "取消屏蔽作者" else "屏蔽作者",
                                    tint = if (isAuthorBlocked) MaterialTheme.colorScheme.error
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        detail.tags.forEach { tag ->
                            val isBlocked = tag in blockedTags
                            // 用 Surface + combinedClickable 替代 FilterChip：
                            // FilterChip 自身消费指针事件，外层 combinedClickable 的 onLongClick
                            // 永远不触发，导致长按屏蔽失效。Surface 不拦截手势，onClick/onLongClick
                            // 都能正常工作。
                            // - 单击标签 → 用该标签词搜索（onSearchByTag）
                            // - 长按标签 → 屏蔽/取消屏蔽（onBlockTag）
                            // 已屏蔽标签用 primary 色高亮，未屏蔽用 secondaryContainer。
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(50),
                                color = if (isBlocked) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (isBlocked) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.combinedClickable(
                                    onClick = { onSearchByTag(tag) },
                                    onLongClick = { onBlockTag(tag) },
                                ),
                            ) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        detail.description?.takeIf { it.isNotBlank() }?.let {
            item {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item {
            // 主按钮逻辑：有阅读历史 → "继续阅读"跳上次章节；否则 → "开始阅读"跳第一章。
            // 修复"无视阅读进度每次都从头开始"的不合理体验。
            val continueChapter = lastReadChapterId?.let { id ->
                detail.chapters.firstOrNull { it.id == id }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        val target = continueChapter ?: detail.chapters.firstOrNull() ?: return@Button
                        onRead(target)
                    },
                    modifier = Modifier.weight(1f),
                    // 章节为空时禁用按钮，避免点击无反馈
                    enabled = detail.chapters.isNotEmpty(),
                ) { Text(if (continueChapter != null) "继续阅读" else "开始阅读") }
                OutlinedButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("下载")
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "收藏",
                    )
                }
            }
        }

        item {
            Text(
                "章节 (${detail.chapters.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (detail.chapters.isEmpty()) {
            item {
                Text(
                    "暂无章节",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
        }
        items(detail.chapters, key = { it.id }) { ch ->
            // 上次阅读的章节高亮，让用户一眼看到该从哪继续
            val isLastRead = ch.id == lastReadChapterId
            // 整行可点击进入阅读（之前只有内嵌 Button 可点，点空白处无反应）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isLastRead) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onRead(ch) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ch.title,
                    modifier = Modifier.weight(1f),
                    fontWeight = if (isLastRead) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    if (isLastRead) "继续" else "阅读",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
