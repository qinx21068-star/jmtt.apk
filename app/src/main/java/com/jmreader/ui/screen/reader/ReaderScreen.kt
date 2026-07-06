@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import me.saket.telephoto.zoomable.zoomable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.local.ReaderDirection
import com.jmreader.data.repository.Resource
import com.jmreader.data.repository.proxiedImageUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class ReaderUiState(
    val loading: Boolean = true,
    val imageFiles: List<String> = emptyList(),
    val fromLocal: Boolean = false,
    val title: String = "",
    val prevChapterId: String? = null,
    val nextChapterId: String? = null,
    /** 进入章节时应恢复到的页码索引（来自上次阅读历史）。null=从头开始。 */
    val initialPage: Int = 0,
    val error: String? = null,
)

class ReaderViewModel(
    private val container: AppContainer,
    private val comicId: String,
    initialChapterId: String,
) : ViewModel() {

    private var chapterId = initialChapterId
    private var cachedDetail: ComicDetailDto? = null
    /** 节流：相同页码不重复保存 */
    private var lastSavedPage = -1
    /** 进度保存协程：debounce 期间新页码来时取消旧的，避免高频写盘 */
    private var saveJob: Job? = null
    /** 当前加载协程：连点下一章/重试时取消旧的，避免竞态覆盖章节状态 */
    private var loadJob: Job? = null
    /** 切章时短暂禁用 saveProgress，避免旧章节的 snapshotFlow 末次发射写入新章进度 */
    private var saveEnabled: Boolean = true

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        // 关键修复：取消上一次未完成的 load，避免连点下一章时旧请求覆盖新章节 state
        loadJob?.cancel()
        // 同步置 loading=true 并禁用保存：让 UI 立即卸载旧 Pager（取消其 LaunchedEffect
        // 和 snapshotFlow collect），避免切章瞬间旧章页码串写到新章进度。
        saveEnabled = false
        _state.value = _state.value.copy(loading = true, error = null, imageFiles = emptyList())
        loadJob = viewModelScope.launch {
            com.jmreader.core.Logger.d("Reader", "load comic=$comicId chapter=$chapterId")
            try {
                // 查历史，恢复上次阅读页码
                val histPage = container.historyStore.items.first()
                    .firstOrNull { it.comic.id == comicId && it.chapterId == chapterId }
                    ?.page ?: 0
                val safeInitial = if (histPage > 0) histPage else 0
                com.jmreader.core.Logger.i("Reader", "恢复进度: $comicId/$chapterId → page=$safeInitial")

                // 优先读本地离线图片
                val local = container.downloadManager.listLocalFiles(comicId, chapterId)
                if (local.isNotEmpty()) {
                    val (prev, next) = computePrevNext()
                    val chTitle = cachedDetail?.chapters?.firstOrNull { it.id == chapterId }?.title
                    _state.value = ReaderUiState(
                        loading = false,
                        imageFiles = local.map { it.absolutePath },
                        fromLocal = true,
                        title = chTitle ?: chapterId,
                        prevChapterId = prev,
                        nextChapterId = next,
                        initialPage = safeInitial,
                    )
                    com.jmreader.core.Logger.i("Reader", "本地图片 ${local.size} 张")
                    saveEnabled = true
                    return@launch
                }
                val serverUrl = container.settingsStore.settings.first().serverUrl
                val useBackend = serverUrl.isNotBlank()
                when (val r = container.repository.chapterImages(chapterId)) {
                    is Resource.Success -> {
                        val (prev, next) = computePrevNext()
                        // 直连模式：图片 URL 已带 jm_sid 标记，JmImageFetcher 会自动解密
                        // 后端模式：套后端代理 URL 解密
                        val imgs = if (useBackend) {
                            r.data.images.map { proxiedImageUrl(serverUrl, it) }
                        } else {
                            r.data.images
                        }
                        _state.value = ReaderUiState(
                            loading = false,
                            imageFiles = imgs,
                            fromLocal = false,
                            title = r.data.title ?: chapterId,
                            prevChapterId = prev,
                            nextChapterId = next,
                            initialPage = safeInitial,
                        )
                        com.jmreader.core.Logger.i("Reader", "远程图片 ${r.data.images.size} 张, 模式=${if (useBackend) "后端" else "直连"}, 恢复页=$safeInitial")
                        saveEnabled = true
                    }
                    is Resource.Error -> {
                        com.jmreader.core.Logger.e("Reader", "加载章节失败: ${r.message}")
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
                com.jmreader.core.Logger.e("Reader", "load 异常", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = com.jmreader.core.Logger.friendlyError(com.jmreader.core.Logger.brief(e)),
                )
            }
        }
    }

    fun gotoChapter(id: String) {
        if (id == chapterId) return
        chapterId = id
        lastSavedPage = -1
        load()
    }

    private suspend fun computePrevNext(): Pair<String?, String?> {
        val detail = cachedDetail ?: when (val r = container.repository.comicDetail(comicId)) {
            is Resource.Success -> r.data
            else -> return null to null
        }
        cachedDetail = detail
        val idx = detail.chapters.indexOfFirst { it.id == chapterId }
        if (idx < 0) return null to null
        return detail.chapters.getOrNull(idx - 1)?.id to detail.chapters.getOrNull(idx + 1)?.id
    }

    /**
     * 保存阅读进度。
     *
     * 关键修复：
     * 1. 不再重新请求详情（避免每次滚动触发网络请求 → 卡顿/崩溃）。
     * 2. 切章期间 [saveEnabled]=false，避免旧章节 snapshotFlow 末次发射污染新章进度。
     * 3. debounce 800ms：用户快速 fling 经过几十页时不会触发几十次写盘。
     *    新页码来时取消旧 saveJob，仅最后一次落盘。
     */
    fun saveProgress(page: Int) {
        if (!saveEnabled) return
        if (page == lastSavedPage) return
        lastSavedPage = page
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            try {
                val detail = cachedDetail
                if (detail != null) {
                    val brief = ComicBriefDto(detail.id, detail.name, detail.author, detail.tags, detail.cover)
                    container.historyStore.upsert(brief, chapterId, state.value.title, page)
                }
                container.settingsStore.setReadingPosition(comicId, chapterId, page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.jmreader.core.Logger.w("Reader", "保存进度失败: ${com.jmreader.core.Logger.brief(e)}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // VM 销毁时（离开阅读器）立即落盘最后一次进度，不等待 delay
        saveJob?.cancel()
        // 用一个新的同步协程立即写入，避免 debounce 期间 VM 销毁丢失最后一次进度
        val page = lastSavedPage
        val detail = cachedDetail
        val ch = chapterId
        val title = state.value.title
        if (page >= 0 && detail != null) {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val brief = ComicBriefDto(detail.id, detail.name, detail.author, detail.tags, detail.cover)
                    container.historyStore.upsert(brief, ch, title, page)
                    container.settingsStore.setReadingPosition(detail.id, ch, page)
                } catch (_: Throwable) {}
            }
        }
    }
}

class ReaderVMFactory(
    private val container: AppContainer,
    private val comicId: String,
    private val chapterId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ReaderViewModel(container, comicId, chapterId) as T
}

@Composable
fun ReaderScreen(
    container: AppContainer,
    comicId: String,
    chapterId: String,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit = {},
) {
    val vm: ReaderViewModel = viewModel(factory = ReaderVMFactory(container, comicId, chapterId))
    val state by vm.state.collectAsState()
    val settings by container.settingsStore.settings.collectAsState(initial = null)
    val direction = settings?.readerDirection ?: ReaderDirection.VERTICAL

    var uiVisible by remember { mutableStateOf(true) }
    // 阅读进度恢复提示：进入章节自动滚到上次位置后，弹 snackbar 告知用户
    val jumpSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val jumpScope = androidx.compose.runtime.rememberCoroutineScope()

    // 当前页码（0-based），用于底栏页码指示器和跳页滑块。
    // 注意：用 state.imageFiles 作为 key，切章后 imageFiles 变化，currentPage 重置为 0，
    // 避免旧章遗留页码污染新章（导致页码错乱、Slider value 越界）。
    var currentPage by remember(state.imageFiles) { mutableStateOf(0) }
    // Slider 拖动期间的中间值：拖动时只更新此值，释放后才写入 currentPage 并真正滚动，
    // 避免拖动期间 currentPage 被改写导致 UI 显示与实际页面不同步。
    var sliderDragging by remember { mutableStateOf(0f) }
    var isSliderDragging by remember { mutableStateOf(false) }
    // 竖滑和横滑各自的滚动状态，提升到 ReaderScreen 以便底栏 Slider 操控
    val verticalListState = rememberLazyListState()
    val horizontalPagerState = rememberPagerState(pageCount = { state.imageFiles.size })

    // 切章时把页码、Slider 中间值重置到首页。
    // 滚动状态（ListState/PagerState）的重置由 ReaderPager 内部的 LaunchedEffect 统一处理
    // （滚到 initialPage 或 0），避免两个 LaunchedEffect 冲突导致闪烁。
    LaunchedEffect(state.imageFiles) {
        currentPage = 0
        sliderDragging = 0f
    }

    // 阅读器沉浸态：工具栏隐藏时，第一次系统返回键先唤出工具栏，第二次才退出。
    // 避免用户误触返回直接退出阅读器，丢失上下文。
    BackHandler(enabled = !uiVisible) {
        uiVisible = true
    }

    val totalPages = state.imageFiles.size

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(jumpSnackbar) },
        topBar = {
            if (uiVisible) {
                TopAppBar(
                    title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                    modifier = Modifier.statusBarsPadding(),
                )
            }
        },
        bottomBar = {
            if (uiVisible && totalPages > 0) {
                BottomAppBar(
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    IconButton(
                        onClick = { state.prevChapterId?.let { vm.gotoChapter(it) } },
                        enabled = state.prevChapterId != null,
                    ) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, contentDescription = "上一章") }
                    // 页码指示器：拖动 Slider 时显示拖动中间值，平时显示实际页码
                    val displayPage = if (isSliderDragging) sliderDragging.toInt() else currentPage
                    Text(
                        "${(displayPage + 1).coerceIn(1, totalPages)}/$totalPages",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Slider(
                        value = if (isSliderDragging) sliderDragging else currentPage.toFloat(),
                        onValueChange = {
                            isSliderDragging = true
                            sliderDragging = it
                        },
                        onValueChangeFinished = {
                            val target = sliderDragging.toInt().coerceIn(0, totalPages - 1)
                            isSliderDragging = false
                            currentPage = target
                            jumpScope.launch {
                                if (direction == ReaderDirection.VERTICAL) {
                                    verticalListState.scrollToItem(target)
                                } else {
                                    horizontalPagerState.scrollToPage(target)
                                }
                            }
                        },
                        valueRange = 0f..(totalPages - 1).toFloat().coerceAtLeast(0f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    IconButton(
                        onClick = { state.nextChapterId?.let { vm.gotoChapter(it) } },
                        enabled = state.nextChapterId != null,
                    ) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = "下一章") }
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).background(Color.Black)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                state.error != null -> com.jmreader.ui.components.ErrorBox(
                    message = state.error!!,
                    onRetry = { vm.load() },
                    onViewLogs = onOpenLogs,
                )
                state.imageFiles.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("无图片", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "该章节可能未发布或被删除",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    androidx.compose.material3.Button(
                        onClick = { vm.load() },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("重试") }
                }
                else -> ReaderPager(
                    images = state.imageFiles,
                    direction = direction,
                    initialPage = state.initialPage,
                    verticalListState = verticalListState,
                    horizontalPagerState = horizontalPagerState,
                    onPageChanged = {
                        currentPage = it
                        vm.saveProgress(it)
                    },
                    onTap = { uiVisible = !uiVisible },
                    onJumpNotice = { page ->
                        // 页码从 0 开始，用户视角从 1 开始
                        currentPage = page
                        jumpScope.launch {
                            jumpSnackbar.showSnackbar("已自动跳转至第 ${page + 1} 页")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReaderPager(
    images: List<String>,
    direction: ReaderDirection,
    initialPage: Int,
    verticalListState: LazyListState,
    horizontalPagerState: PagerState,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onJumpNotice: (Int) -> Unit = {},
) {
    when (direction) {
        ReaderDirection.VERTICAL -> VerticalReader(images, initialPage, verticalListState, onPageChanged, onTap, onJumpNotice)
        ReaderDirection.HORIZONTAL -> HorizontalReader(images, initialPage, horizontalPagerState, onPageChanged, onTap, onJumpNotice)
    }
}

@Composable
private fun VerticalReader(
    images: List<String>,
    initialPage: Int,
    listState: LazyListState,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onJumpNotice: (Int) -> Unit = {},
) {
    // 进入章节时恢复到上次阅读位置（仅首次，initialPage/images 变化时触发一次）
    // 关键修复：之前条件是 initialPage > 0，导致切到无历史章节（initialPage=0）时不滚动，
    // ListState 保留旧章位置，用户从错误页码开始阅读。现在总是滚动到 initialPage（包括 0）。
    LaunchedEffect(initialPage, images) {
        if (images.isEmpty()) return@LaunchedEffect
        val target = initialPage.coerceIn(0, images.lastIndex)
        listState.scrollToItem(target)
        if (target > 0) onJumpNotice(target)
    }
    // 用 snapshotFlow + distinctUntilChanged 节流，避免滚动时频繁触发
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onPageChanged(it) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(images, key = { it }) { path ->
            // 竖向滚动用普通 AsyncImage：不带手势检测，滚动更流畅
            ReaderImage(path = path, zoomable = false, onTap = onTap, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HorizontalReader(
    images: List<String>,
    initialPage: Int,
    pagerState: PagerState,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onJumpNotice: (Int) -> Unit = {},
) {
    // 恢复上次阅读页，恢复后弹提示
    // 关键修复：同 VerticalReader，总是滚动到 initialPage（包括 0），避免切章遗留旧位置
    LaunchedEffect(initialPage, images) {
        if (images.isEmpty()) return@LaunchedEffect
        val target = initialPage.coerceIn(0, images.lastIndex)
        pagerState.scrollToPage(target)
        if (target > 0) onJumpNotice(target)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { onPageChanged(it) }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().background(Color.Black),
        key = { it },
    ) { page ->
        // 越界保护：page 理论上不会越界，但加保护避免极端情况闪退
        val path = images.getOrNull(page) ?: return@HorizontalPager
        ReaderImage(path = path, zoomable = true, onTap = onTap, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 单张阅读图片：远程 URL 或本地文件。双击缩放 1x↔2x，单击切换 UI 显隐。
 *
 * - zoomable=true（横滑模式）：用 telephoto ZoomableAsyncImage，maxZoomFactor=2f。
 *   telephoto 默认双击循环在 1x 和 maxZoom 间切换，配置后即 1x↔2x；
 *   同时支持双指缩放、放大后拖拽平移。单击透传给 [onTap] 切换顶/底栏。
 * - zoomable=false（竖滑模式）：用 AsyncImage + graphicsLayer + detectTapGestures。
 *   双击在 1x/2x 间切换（以中心放大），单击切顶/底栏。
 *   放大后不处理平移（避免与 LazyColumn 滚动冲突），想平移看细节请切横滑模式。
 */
@Composable
private fun ReaderImage(
    path: String,
    zoomable: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val model = remember(path) {
        when {
            path.startsWith("http") -> coil.request.ImageRequest.Builder(ctx)
                .data(path)
                .crossfade(true)
                .build()
            else -> File(path)
        }
    }
    Box(modifier = modifier.background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
        if (zoomable) {
            // 横滑：telephoto 完整缩放（双击 1x↔2x、双指、平移）
            val zoomState = me.saket.telephoto.zoomable.rememberZoomableState(
                zoomSpec = me.saket.telephoto.zoomable.ZoomSpec(maxZoomFactor = 2f)
            )
            me.saket.telephoto.zoomable.coil.ZoomableAsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .zoomable(zoomState)
                    // 单击切换 UI 显隐（telephoto 只消费双击/双指，单击会冒泡到这里）
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap() })
                    },
            )
        } else {
            // 竖滑：AsyncImage + 双击缩放 1x↔2x（中心放大），单击切 UI
            var scale by remember(path) { mutableFloatStateOf(1f) }
            val animScale by animateFloatAsState(targetValue = scale, label = "imgScale")
            var imgState by remember(path) {
                mutableStateOf<coil.compose.AsyncImagePainter.State>(coil.compose.AsyncImagePainter.State.Empty)
            }
            coil.compose.AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                onState = { imgState = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(scaleX = animScale, scaleY = animScale)
                    .pointerInput(path) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = {
                                scale = if (scale > 1.5f) 1f else 2f
                            }
                        )
                    },
            )
            when (val s = imgState) {
                is coil.compose.AsyncImagePainter.State.Loading -> {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                }
                is coil.compose.AsyncImagePainter.State.Error -> {
                    com.jmreader.core.Logger.w("Reader", "图片加载失败: $path, ${com.jmreader.core.Logger.brief(s.result.throwable)}")
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = com.jmreader.core.Logger.friendlyError(com.jmreader.core.Logger.brief(s.result.throwable)),
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                else -> {}
            }
        }
    }
}
