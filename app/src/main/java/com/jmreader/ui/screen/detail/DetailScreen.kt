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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.jmreader.data.dto.JmCommentDto
import com.jmreader.data.repository.Resource
import com.jmreader.ui.components.ErrorBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    /** 当前已屏蔽的作者集合（实时同步 DataStore，用于作者名旁显示屏蔽状态）。 */
    val blockedAuthors: StateFlow<Set<String>> = container.blockedTagsStore.authors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    // v27.5 #12 相似推荐：用首个 tag 作为种子搜索，结果过滤掉当前本子
    private val _relatedComics = MutableStateFlow<List<ComicBriefDto>>(emptyList())
    val relatedComics: StateFlow<List<ComicBriefDto>> = _relatedComics.asStateFlow()

    // v27.5 #13 作者其它作品：用作者名搜索，结果过滤掉当前本子
    private val _authorWorks = MutableStateFlow<List<ComicBriefDto>>(emptyList())
    val authorWorks: StateFlow<List<ComicBriefDto>> = _authorWorks.asStateFlow()

    init {
        load()
        // 关键修复：监听 HistoryStore 变化，用户从阅读器返回详情页时
        // lastReadChapterId 实时更新，按钮从"开始阅读"变为"继续阅读"。
        // v27.5 稳定性加固：collect 内任何异常（DataStore IOException 等）若不捕获会冒泡到
        // Thread.uncaughtExceptionHandler → CrashHandler → 杀进程。整个 collect 包 try-catch，
        // 单次 emit 失败不影响后续 emit 处理。
        viewModelScope.launch {
            try {
                container.historyStore.items.collect { list ->
                    try {
                        val last = list.firstOrNull { it.comic.id == comicId }?.chapterId
                        if (last != _state.value.lastReadChapterId) {
                            _state.value = _state.value.copy(lastReadChapterId = last)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        com.jmreader.core.Logger.w("Detail", "historyStore collect 处理失败: ${com.jmreader.core.Logger.brief(e)}")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.jmreader.core.Logger.w("Detail", "historyStore collect 失败: ${com.jmreader.core.Logger.brief(e)}")
            }
        }
    }

    // v27.13：跟踪 load Job，重试时取消旧请求，避免叠加多批 comicDetail + relatedComics + authorWorks
    private var loadJob: kotlinx.coroutines.Job? = null

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                // v27.13：详情请求与本地 store 加载并行，减少串行等待。
                // 之前 /album 完成后才串行 ensureLoaded + isFavoriteAwait，
                // 冷启动时本地 JSON 解析几十~几百 ms 叠加在后面。
                val detailDeferred = async { container.repository.comicDetail(comicId) }
                val historyDeferred = async {
                    container.historyStore.ensureLoaded()
                    container.historyStore.items.value
                }
                val favDeferred = async { container.favoritesStore.isFavoriteAwait(comicId) }
                val settingsDeferred = async {
                    runCatching { container.settingsStore.settings.first() }.getOrNull()
                }

                when (val r = detailDeferred.await()) {
                    is Resource.Success -> {
                        val historyList = historyDeferred.await()
                        val lastChapter = historyList
                            .firstOrNull { it.comic.id == comicId }?.chapterId
                        _state.value = DetailUiState(
                            loading = false,
                            detail = r.data,
                            isFavorite = favDeferred.await(),
                            lastReadChapterId = lastChapter,
                        )
                        // 记录浏览历史：打开详情页即记录，不需阅读
                        val brief = ComicBriefDto(
                            r.data.id, r.data.name, r.data.author,
                            r.data.tags, r.data.cover,
                        )
                        // v27.5 #30：隐身模式下不记录浏览历史（与 UI 副标题承诺一致）
                        val incognito = settingsDeferred.await()?.incognito ?: false
                        if (!incognito) {
                            container.browseHistoryStore.upsert(brief)
                        }
                        // v27.5 #12 #13：加载相似推荐 + 作者其它作品（失败不阻塞详情页）
                        loadRelatedComics(r.data)
                        loadAuthorWorks(r.data)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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

    /**
     * v27.5 #12 相似推荐：用首个非空 tag 作为种子调 search 接口。
     * 取前 12 条，过滤掉当前本子。失败静默（不阻塞详情页）。
     *
     * v27.7：列表 API 返回粗 tags，有 tag 屏蔽规则时拉取详情页真实 tags 再过滤，
     * 确保被屏蔽的具体 tag 不泄漏（如"NTR"被屏蔽但列表只返回"同人"）。
     */
    private fun loadRelatedComics(detail: ComicDetailDto) {
        val seed = detail.tags.firstOrNull { it.isNotBlank() } ?: return
        viewModelScope.launch {
            try {
                when (val r = container.repository.search(seed, page = 1, order = "latest")) {
                    is Resource.Success -> {
                        val (tags, names, authors) = container.blockedTagsStore.allRules.first()
                        val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
                        _relatedComics.value = filterWithRealTags(
                            r.data.items.filter { it.id != detail.id },
                            rules,
                        ).take(12)
                    }
                    else -> {}
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Throwable) { /* 静默：相似推荐失败不影响详情页 */ }
        }
    }

    /**
     * v27.5 #13 作者其它作品：用作者名调 search 接口。
     * 取前 12 条，过滤掉当前本子。失败静默。
     *
     * v27.7：同 [loadRelatedComics]，有 tag 规则时拉取真实 tags 再过滤。
     */
    private fun loadAuthorWorks(detail: ComicDetailDto) {
        val author = detail.author?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            try {
                when (val r = container.repository.search(author, page = 1, order = "latest")) {
                    is Resource.Success -> {
                        val (tags, names, authors) = container.blockedTagsStore.allRules.first()
                        val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
                        _authorWorks.value = filterWithRealTags(
                            r.data.items.filter { it.id != detail.id },
                            rules,
                        ).take(12)
                    }
                    else -> {}
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Throwable) { /* 静默：作者其它作品失败不影响详情页 */ }
        }
    }

    /**
     * v27.7：用详情页真实 tags 过滤列表条目。
     * - 无 tag 规则：name/author 立即判定，无需补全
     * - 有 tag 规则：并发拉取详情页真实 tags 再判定，补全失败的条目 fail-closed 隐藏
     */
    private suspend fun filterWithRealTags(
        items: List<ComicBriefDto>,
        rules: com.jmreader.data.local.BlockedTagsStore.NormalizedRules,
    ): List<ComicBriefDto> {
        if (rules.tags.isEmpty()) {
            // 无 tag 规则：name/author 可立即准确判定
            return items.filterNot {
                container.blockedTagsStore.isBlocked(it.tags, it.name, it.author, rules)
            }
        }
        // 有 tag 规则：并发拉取详情页真实 tags（最多 20 条候选）
        val candidates = items.take(20)
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
                        // fail-closed：补全失败则隐藏
                        comic to false
                    }
                }
            }.awaitAll()
        }
        return enriched.filter { it.second }.map { it.first }
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
    onOpenComments: (String) -> Unit = {},
    onOpenComic: (String) -> Unit = {},
) {
    val vm: DetailViewModel = viewModel(factory = DetailVMFactory(container, comicId))
    val state by vm.state.collectAsState()
    val blockedTags by vm.blockedTags.collectAsState()
    val blockedAuthors by vm.blockedAuthors.collectAsState()
    val relatedComics by vm.relatedComics.collectAsState()
    val authorWorks by vm.authorWorks.collectAsState()
    // v27.5 性能优化：用 cachedSnapshot 作为初始值，避免 null → 默认 → 真实 两轮重组
    val settings by container.settingsStore.settings.collectAsState(initial = container.settingsStore.cachedSnapshot)
    val detailParallax = settings.detailParallax
    // v27.6：图片质量档位（之前硬编码 Size.ORIGINAL，设置页改了无效）
    val imageQuality = settings.imageQuality
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
            // v27.5 稳定性加固：捕获 state 到本地 val，避免 state.error!! race condition NPE
            val s = state
            val detail = s.detail
            when {
                s.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                s.error != null -> ErrorBox(
                    message = s.error,
                    onRetry = { vm.load() },
                    onViewLogs = onOpenLogs,
                )
                detail != null -> {
                    // v27.5 性能修复：5 个嵌套 lambda 用 remember 缓存，避免 DetailContent 因 lambda 不稳定无法跳过重组
                    // 注意：scope.launch 返回 Job，需在 lambda 末尾加 Unit 显式返回 Unit，避免推断为 (String) -> Job
                    val showMsg = remember(scope, snackbar) {
                        { msg: String ->
                            scope.launch { snackbar.showSnackbar(msg) }
                            Unit
                        }
                    }
                    val onToggleFavorite = remember(vm, showMsg) {
                        { vm.toggleFavorite(showMsg) }
                    }
                    val onDownload = remember(vm, showMsg) {
                        { vm.download(showMsg) }
                    }
                    val onBlockTag = remember(vm, blockedTags, showMsg) { { tag: String ->
                        if (tag in blockedTags) {
                            vm.unblockTag(tag, showMsg)
                        } else {
                            vm.blockTag(tag, showMsg)
                        }
                    } }
                    val onBlockAuthor = remember(vm, blockedAuthors, showMsg) { { author: String ->
                        if (author in blockedAuthors) {
                            vm.unblockAuthor(author, showMsg)
                        } else {
                            vm.blockAuthor(author, showMsg)
                        }
                    } }
                    val onReadChapter = remember(detail, onRead) {
                        { ch: ChapterDto -> onRead(detail.id, ch.id) }
                    }
                    DetailContent(
                        detail = detail,
                        container = container,
                        isFavorite = s.isFavorite,
                        blockedTags = blockedTags,
                        blockedAuthors = blockedAuthors,
                        lastReadChapterId = s.lastReadChapterId,
                        parallax = detailParallax,
                        relatedComics = relatedComics,
                        authorWorks = authorWorks,
                        imageQuality = imageQuality,
                        onToggleFavorite = onToggleFavorite,
                        onDownload = onDownload,
                        onBlockTag = onBlockTag,
                        onBlockAuthor = onBlockAuthor,
                        onSearchByTag = onSearchByTag,
                        onSearchByAuthor = onSearchByAuthor,
                        onOpenComments = onOpenComments,
                        onOpenComic = onOpenComic,
                        onRead = onReadChapter,
                    )
                }
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
    container: AppContainer,
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
    onOpenComments: (String) -> Unit,
    onOpenComic: (String) -> Unit,
    onRead: (ChapterDto) -> Unit,
    // v27.5 #27：封面视差滚动开关
    parallax: Boolean = true,
    // v27.5 #12 相似推荐列表
    relatedComics: List<ComicBriefDto> = emptyList(),
    // v27.5 #13 作者其它作品列表
    authorWorks: List<ComicBriefDto> = emptyList(),
    // v27.6：图片质量档位，控制封面解码尺寸
    imageQuality: com.jmreader.data.local.ImageQuality = com.jmreader.data.local.ImageQuality.HIGH,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val ctx = LocalContext.current
                // v27.5 #27：视差滚动——封面以 0.5x 速率跟随列表滚动，产生"内容下移快、封面下移慢"的视差。
                // 仅在第一个 item（firstVisibleItemIndex==0）时生效；滚出首屏后视差无意义。
                // 关闭时 parallaxFactor=1f，等价于无 modifier。
                //
                // v27.5 性能修复：之前 scrollOffset 在 composition 阶段读取 firstVisibleItemScrollOffset，
                // 每帧滚动都触发整个 item 重组（含 AsyncImage 重新求值）。
                // 现在把 scrollOffset 读取移到 graphicsLayer 的 lambda 内（draw 阶段），
                // 只触发 draw 不触发 recomposition，滚动流畅度大幅提升。
                val parallaxFactor = if (parallax) 0.5f else 1f
                AsyncImage(
                    model = remember(detail.cover, ctx, imageQuality) {
                        val builder = ImageRequest.Builder(ctx).data(detail.cover)
                        // v27.6：根据 imageQuality 设置解码尺寸（之前硬编码 Size.ORIGINAL）
                        when (imageQuality) {
                            com.jmreader.data.local.ImageQuality.ORIGINAL -> builder.size(Size.ORIGINAL)
                            com.jmreader.data.local.ImageQuality.HIGH -> builder.size(300, 420)
                            com.jmreader.data.local.ImageQuality.MEDIUM -> builder.size(240, 336)
                            com.jmreader.data.local.ImageQuality.LOW -> builder.size(180, 252)
                        }
                        // v27.5 性能修复：移除 crossfade(true)，单图加载完无需淡入动画
                        builder.build()
                    },
                    contentDescription = detail.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(0.7f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .graphicsLayer {
                            // 在 draw 阶段读取滚动偏移，避免每帧重组
                            val scrollOffset = if (listState.firstVisibleItemIndex == 0) {
                                listState.firstVisibleItemScrollOffset.toFloat()
                            } else 0f
                            // translationY 为负：滚动向下时封面"上移"少一点，产生视差
                            translationY = -scrollOffset * (1f - parallaxFactor)
                        },
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
                    // JM 号 + 发布时间：详情页必备元信息。
                    // JM 号即 album_id 加 "JM" 前缀（禁漫本子号惯例），点击复制方便分享/检索。
                    // 发布时间来自 /album 接口（JmDirectClient.parsePublishTime 解析）。
                    Spacer(Modifier.height(8.dp))
                    val ctxInfo = LocalContext.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "JM号：${detail.id}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable {
                                runCatching {
                                    val cm = ctxInfo.getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.setPrimaryClip(
                                        android.content.ClipData.newPlainText("JM号", detail.id),
                                    )
                                    android.widget.Toast.makeText(ctxInfo, "已复制 JM号", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }.padding(vertical = 2.dp),
                        )
                        detail.publishTime?.let { pt ->
                            Text(
                                text = "发布：$pt",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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

        // 登场角色（actors）+ 登场作品（works）：像官方客户端那样显示。
        // 来自 /album 接口的 actors / works 字段。两者均可能为空。
        // 点击角色/作品 chip → 用该词搜索（复用 onSearchByTag，禁漫支持按角色名搜索）。
        if (detail.actors.isNotEmpty() || detail.works.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (detail.actors.isNotEmpty()) {
                        Text(
                            "登场角色",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            detail.actors.forEach { actor ->
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.clickable { onSearchByTag(actor) },
                                ) {
                                    Text(
                                        text = actor,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (detail.works.isNotEmpty()) {
                        if (detail.actors.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        Text(
                            "登场作品",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            detail.works.forEach { work ->
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.clickable { onSearchByTag(work) },
                                ) {
                                    Text(
                                        text = work,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
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
        itemsIndexed(detail.chapters, key = { i, ch -> ch.id.ifBlank { "idx_$i" } }) { _, ch ->
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
        // ===== v27.5 #12 相似推荐：横向卡片列表（用首个 tag 作种子）=====
        if (relatedComics.isNotEmpty()) {
            item {
                RelatedComicsRow(
                    title = "相似推荐",
                    comics = relatedComics,
                    onOpenComic = onOpenComic,
                    onSearchMore = {
                        detail.tags.firstOrNull { it.isNotBlank() }?.let(onSearchByTag)
                    },
                )
            }
        }
        // ===== v27.5 #13 作者其它作品：横向卡片列表 =====
        if (authorWorks.isNotEmpty()) {
            item {
                val authorName = detail.author
                RelatedComicsRow(
                    title = "${authorName ?: "作者"} 的其它作品",
                    comics = authorWorks,
                    onOpenComic = onOpenComic,
                    onSearchMore = authorName?.let { name -> { onSearchByAuthor(name) } },
                )
            }
        }
        // ===== 内嵌评论区（v26 新增）=====
        // 用户反馈："讨论区应该直接出现在本子详情页的下面不用再另外设置一个跳转"
        // 原来是"查看评论"卡片点击跳转到 CommentsScreen，现在直接在详情页底部内嵌
        // 评论列表第一页 + "查看全部评论"入口，用户无需跳转即可看到讨论。
        // 完整分页浏览仍可点底部"查看全部评论"跳到 CommentsScreen。
        item {
            InlineCommentsSection(
                albumId = detail.id,
                onOpenComic = onOpenComic,
                onOpenAllComments = { onOpenComments(detail.id) },
                container = container,
            )
        }
    }
}

/**
 * v27.5 #12 #13 详情页底部"相似推荐"/"作者其它作品"横向卡片列表。
 *
 * - comics 为空时不显示（由调用方判断）
 * - 点击卡片 → onOpenComic(id) 跳详情页
 * - 点击"查看更多" → onSearchMore() 用种子词打开搜索页
 */
@Composable
private fun RelatedComicsRow(
    title: String,
    comics: List<ComicBriefDto>,
    onOpenComic: (String) -> Unit,
    onSearchMore: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onSearchMore != null) {
                TextButton(onClick = onSearchMore) {
                    Text("查看更多")
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(comics, key = { i, c -> c.id.ifBlank { "idx_$i" } }) { _, c ->
                val click = remember(c, onOpenComic) { { onOpenComic(c.id) } }
                RelatedComicCard(c, onClick = click)
            }
        }
    }
}

/** 横向列表中的单卡片：封面 + 标题，固定宽度，纵向布局。 */
@Composable
private fun RelatedComicCard(comic: ComicBriefDto, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .width(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = remember(comic.cover, ctx) {
                // v27.5 性能修复：移除 crossfade(true)，避免横向滑动时每个封面跑 300ms alpha 动画抢主线程
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
        Spacer(Modifier.height(4.dp))
        Text(
            comic.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 详情页内嵌评论区：自动加载第一页评论，原生渲染在本子详情底部。
 *
 * v27.9：从 HTML 抓取（JmWebFetcher + jm365.work 重定向，CF 拦截频繁失效，
 * 导致"根本加载不出来"）改为 /forum JSON API（同 reqApi 通道：token 鉴权 +
 * AES 解密 + 域名轮换，与搜索/详情同款，已验证稳定）。与 CommentsScreen 同源。
 *
 * - 数据来源：[com.jmreader.data.repository.JMRepository.forum]，mode="manhua", aid=本子ID, page=1。
 * - 失败不阻塞详情页：仅在评论区内部显示错误，提供"重试"。
 * - "查看全部评论"按钮跳转到 CommentsScreen（完整分页）。
 * - 内嵌只显示第一页前 5 条，更多内容点"查看全部评论"。
 */
@Composable
private fun InlineCommentsSection(
    albumId: String,
    onOpenComic: (String) -> Unit,
    onOpenAllComments: () -> Unit,
    container: AppContainer,
) {
    var comments by remember(albumId) { mutableStateOf<List<JmCommentDto>>(emptyList()) }
    var loading by remember(albumId) { mutableStateOf(true) }
    var error by remember(albumId) { mutableStateOf<String?>(null) }
    var totalCount by remember(albumId) { mutableIntStateOf(-1) }
    var trigger by remember(albumId) { mutableIntStateOf(0) }

    // v27.9：通过 /forum JSON API 加载第一页评论（替代 HTML 抓取）。
    // reqApi 内部已做域名轮换 + 自愈，单次重试足够覆盖瞬时网络抖动。
    LaunchedEffect(albumId, trigger) {
        if (trigger < 0) return@LaunchedEffect
        loading = true
        error = null
        try {
            val r = container.repository.forum(mode = "manhua", aid = albumId, uid = null, page = 1)
            when (r) {
                is Resource.Success -> {
                    comments = r.data.list
                    totalCount = r.data.total
                    loading = false
                }
                is Resource.Error -> {
                    // 单次重试：网络抖动/域名瞬时故障
                    kotlinx.coroutines.delay(800L)
                    val r2 = container.repository.forum(mode = "manhua", aid = albumId, uid = null, page = 1)
                    when (r2) {
                        is Resource.Success -> {
                            comments = r2.data.list
                            totalCount = r2.data.total
                            loading = false
                        }
                        is Resource.Error -> {
                            loading = false
                            error = r2.message
                        }
                        Resource.Loading -> {}
                    }
                }
                Resource.Loading -> {}
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            loading = false
            error = e.message ?: e::class.simpleName ?: "未知错误"
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // 标题行："评论 (N)" + 右侧"查看全部"按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Comment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (totalCount >= 0) "评论 ($totalCount)" else "评论",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenAllComments) {
                Text("查看全部")
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            loading -> {
                // 加载中：细条进度 + "正在加载评论…"
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "正在加载评论…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error != null -> {
                // 加载失败：紧凑错误提示 + 重试按钮（不阻塞详情页其它部分）
                // v27.5 稳定性加固：error 是 mutableStateOf<String?>，分支条件判定为非 null 后
                // 到渲染 Text(error!!) 之间可能被另一 effect/重组置 null（如重试触发 error=null 后再请求），
                // 直接 !! 会抛 KotlinNullPointerException 崩溃。用本地 val 捕获，避免竞态。
                val err = error
                if (err != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "评论加载失败",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { trigger++ }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("重试")
                            }
                            TextButton(onClick = onOpenAllComments) { Text("在评论区查看") }
                        }
                    }
                }
                }
            }
            comments.isEmpty() -> {
                Text(
                    "暂无评论",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            else -> {
                // 渲染第一页评论（最多显示前 5 条，避免详情页过长）
                val preview = comments.take(5)
                preview.forEach { c ->
                    InlineCommentItem(comment = c, onOpenComic = onOpenComic)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (comments.size > 5 || totalCount > 5) {
                    TextButton(
                        onClick = onOpenAllComments,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看全部 ${if (totalCount > 0) totalCount else comments.size} 条评论")
                    }
                }
            }
        }
    }
}

/** 内嵌评论的单条卡片：简化版 CommentCard，无楼层跳页/复制等长按功能（详情页内嵌只读浏览）。 */
@Composable
private fun InlineCommentItem(
    comment: JmCommentDto,
    onOpenComic: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.jmreader.ui.components.UserAvatar(
                avatar = comment.photo,
                name = comment.nickname,
                sizeDp = 28,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    comment.nickname.ifBlank { "匿名" },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    if (comment.likes > 0) append("♥ ${comment.likes}")
                    if (comment.addtime.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(comment.addtime)
                    }
                }
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (comment.content.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            com.jmreader.ui.screen.forum.JmLinkedText(
                text = comment.content,
                onOpenComic = onOpenComic,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
