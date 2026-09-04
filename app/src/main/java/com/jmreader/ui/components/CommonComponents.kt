@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jmreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.local.ListStyle
import kotlinx.coroutines.launch

/** v27.12 性能优化：文件级 Shape 常量，避免每个卡片每次重组都 new RoundedCornerShape。 */
private val CoverClipShape = RoundedCornerShape(8.dp)
private val ChipShape = RoundedCornerShape(4.dp)

/**
 * 通用本子列表组件，根据 [listStyle] 在「列表 / 网格 / 紧凑网格」之间切换。
 *
 * - [ListStyle.LIST]：单列横向卡片（左缩略图 + 右信息），信息密度高。使用调用方传入的 [state]。
 * - [ListStyle.GRID]：双列网格（封面 + 标题），浏览效率高。使用内部 LazyGridState。
 * - [ListStyle.COMPACT_GRID]：三列紧凑网格，一屏看更多。
 *
 * 触底加载更多：传入 [onLoadMore] 时由组件内部根据滚动位置自动触发，
 * 调用方无需再自行用 derivedStateOf 监听 listState（这样列表/网格两种样式都能自动 loadMore）。
 *
 * 性能要点：
 * 1. LazyColumn/LazyVerticalGrid + items (key + contentType) 让滚动时复用 slot
 * 2. ComicBriefDto 标了 @Immutable，ComicCard 在滚动时可跳过重组
 * 3. 用 rememberAsyncImagePainter + Image 而非 AsyncImage，避免 Loading→Success 触发重组
 * 4. onClick/onLongClick 由调用方 remember 包好稳定 lambda，避免新实例导致级联重组
 */
@Composable
fun ComicList(
    items: List<ComicBriefDto>,
    onClick: (ComicBriefDto) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    gridState: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(12.dp),
    onLongClick: (ComicBriefDto) -> Unit = {},
    loadingMore: Boolean = false,
    endReached: Boolean = false,
    loadError: String? = null,
    onRetry: (() -> Unit)? = null,
    listStyle: ListStyle = ListStyle.LIST,
    onLoadMore: (() -> Unit)? = null,
    // v27.6：可选头部内容（筛选条/搜索历史等），作为列表第一个 item 随滚动移出视野
    header: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    // v27.11：滚动状态回调，滚动时暂停 enrich 的 UI 刷新避免卡顿
    onScrollStateChange: ((Boolean) -> Unit)? = null,
    // v27.14：COVER_ONLY 模式下被屏蔽封面的本子 ID 集合，封面替换为打叉灰白占位图。
    coverHiddenIds: Set<String> = emptySet(),
) {
    // 监听滚动状态，通知 ViewModel 暂停/恢复 enrich 的 UI 刷新。
    // 用 rememberUpdatedState 确保 callback 总是最新的，但 LaunchedEffect 只依赖 state/gridState，
    // 避免方法引用每次重组创建新实例导致 LaunchedEffect 频繁重启。
    val scrollCallback = androidx.compose.runtime.rememberUpdatedState(onScrollStateChange)
    LaunchedEffect(state, gridState) {
        snapshotFlow {
            state.isScrollInProgress || gridState.isScrollInProgress
        }.collect { scrolling ->
            scrollCallback.value?.invoke(scrolling)
        }
    }
    when (listStyle) {
        ListStyle.LIST, ListStyle.CARD -> ComicListColumn(
            items = items,
            onClick = onClick,
            modifier = modifier,
            state = state,
            contentPadding = contentPadding,
            onLongClick = onLongClick,
            loadingMore = loadingMore,
            endReached = endReached,
            loadError = loadError,
            onRetry = onRetry,
            onLoadMore = onLoadMore,
            useCardStyle = listStyle == ListStyle.CARD,
            header = header,
            coverHiddenIds = coverHiddenIds,
        )
        ListStyle.GRID, ListStyle.COMPACT_GRID, ListStyle.MAGAZINE -> ComicListGrid(
            items = items,
            onClick = onClick,
            modifier = modifier,
            state = gridState,
            contentPadding = contentPadding,
            onLongClick = onLongClick,
            loadingMore = loadingMore,
            endReached = endReached,
            loadError = loadError,
            onRetry = onRetry,
            columns = when (listStyle) {
                ListStyle.GRID -> 2
                ListStyle.COMPACT_GRID -> 3
                ListStyle.MAGAZINE -> 2
                else -> 2
            },
            useMagazineStyle = listStyle == ListStyle.MAGAZINE,
            onLoadMore = onLoadMore,
            header = header,
            coverHiddenIds = coverHiddenIds,
        )
    }
}

/**
 * 单列横向卡片列表。当 [onLoadMore] 非空时，内部自动监听触底并回调（调用方无需再写 reachEnd）。
 *
 * @param useCardStyle true=使用 [ComicCardStyle]（带 tags chips 的卡片样式），
 *                     false=使用经典 [ComicCard]（纯文本 tags）
 */
@Composable
private fun ComicListColumn(
    items: List<ComicBriefDto>,
    onClick: (ComicBriefDto) -> Unit,
    modifier: Modifier,
    state: LazyListState,
    contentPadding: PaddingValues,
    onLongClick: (ComicBriefDto) -> Unit,
    loadingMore: Boolean,
    endReached: Boolean,
    loadError: String?,
    onRetry: (() -> Unit)?,
    onLoadMore: (() -> Unit)?,
    useCardStyle: Boolean = false,
    header: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    coverHiddenIds: Set<String> = emptySet(),
) {
    // v27.6：header 占 1 个 index，触底阈值需 +1 偏移
    val headerOffset = if (header != null) 1 else 0
    // 触底加载：列表 + footer，footer 占 1 个 index，阈值用 items.size - 3。
    // v27.5 稳定性修复：remember 必须把 items 也作为 key，否则 items 变化后 derivedStateOf
    // 闭包仍捕获旧的 items.size → 阈值错误 → 触底加载失效或重复触发。
    if (onLoadMore != null) {
        val reachEnd by remember(state, items) {
            derivedStateOf {
                (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= items.size + headerOffset - 3
            }
        }
        LaunchedEffect(reachEnd) { if (reachEnd) onLoadMore() }
    }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // v27.6：可选 header（筛选条等），随列表滚动移出视野
        if (header != null) {
            item(key = "list_header", contentType = "list_header") { header() }
        }
        // v27.5 稳定性加固（critical 修复）：原 `items(items, key = { it.id })` 在 id="" 重复时
        // 抛 IllegalArgumentException: Key "" was already used → 闪退。
        // 触发场景：老版本迁移的收藏数据 / API 返回的 id 缺失 / Moshi 反序列化失败回退默认值
        // 都会产生 id="" 的条目。收藏列表里只要有多条 id="" 即崩溃，且 100% 复现。
        // 改用 itemsIndexed + 复合 key（id 为空时回退到 index），保证唯一性。
        itemsIndexed(items, key = { i, c -> c.id.ifBlank { "idx_$i" } }, contentType = { _, _ -> "comic_card" }) { _, item ->
            // v27.5 性能修复（卡顿主因）：
            // 之前每次重组都创建新 lambda `{ onClick(item) }` 传给 ComicCard，
            // lambda 是不稳定类型 → ComicCard 无法跳过重组 → 滚动时 listState 变化
            // 触发 HomeScreen 重组 → 所有可见 ComicCard 都强制重组 → 卡顿。
            // 用 remember(item, onClick, onLongClick) 缓存 lambda，item/回调不变时 lambda 引用稳定，
            // ComicCard（item 是 @Immutable）可跳过重组。
            val click = remember(item, onClick) { { onClick(item) } }
            val longClick = remember(item, onLongClick) { { onLongClick(item) } }
            // v27.14：COVER_ONLY 模式下被屏蔽的本子保留卡片但替换封面
            val coverHidden = item.id.isNotEmpty() && item.id in coverHiddenIds
            if (useCardStyle) {
                ComicCardStyle(
                    item = item,
                    onClick = click,
                    onLongClick = longClick,
                    coverHidden = coverHidden,
                )
            } else {
                ComicCard(
                    item = item,
                    onClick = click,
                    onLongClick = longClick,
                    coverHidden = coverHidden,
                )
            }
        }
        // 列表底部 footer：加载中转圈 / 加载失败重试 / 已到底提示
        if (items.isNotEmpty()) {
            item(key = "list_footer", contentType = "list_footer") {
                ListFooter(
                    loadingMore = loadingMore,
                    endReached = endReached,
                    error = loadError,
                    onRetry = onRetry,
                )
            }
        }
    }
}

/**
 * 网格列表：[columns] 列，每格一张封面 + 标题。
 * 触底加载通过 [onLoadMore] 回调。
 *
 * 关键修复（Bug 40）：之前内部 `val gridState = rememberLazyGridState()` 自建 state，
 * 忽略外部传入的 state，导致调用方（如 SearchScreen）的 `gridState.scrollToItem(0)`
 * "新搜索滚回顶部"在 GRID/COMPACT_GRID 模式下完全失效——用户搜新词后列表停在原滚动位置。
 * 现在接收外部 state，与 ComicListColumn 行为一致。
 */
@Composable
private fun ComicListGrid(
    items: List<ComicBriefDto>,
    onClick: (ComicBriefDto) -> Unit,
    modifier: Modifier,
    state: LazyGridState,
    contentPadding: PaddingValues,
    onLongClick: (ComicBriefDto) -> Unit,
    loadingMore: Boolean,
    endReached: Boolean,
    loadError: String?,
    onRetry: (() -> Unit)?,
    columns: Int,
    onLoadMore: (() -> Unit)?,
    useMagazineStyle: Boolean = false,
    header: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    coverHiddenIds: Set<String> = emptySet(),
) {
    // v27.6：header 占 1 个 index，触底阈值需 +1 偏移
    val headerOffset = if (header != null) 1 else 0
    // 触底加载：footer 跨整行占 1 个 index，阈值用 items.size - columns*2（约两行提前量）
    // v27.5 稳定性修复：remember 必须把 items 也作为 key，否则 items 变化后 derivedStateOf
    // 闭包仍捕获旧的 items.size → 阈值错误 → 触底加载失效或重复触发。
    if (onLoadMore != null) {
        val reachEnd by remember(state, items) {
            derivedStateOf {
                (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= items.size + headerOffset - columns * 2
            }
        }
        LaunchedEffect(reachEnd) { if (reachEnd) onLoadMore() }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // v27.6：可选 header，跨整行显示
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "grid_header", contentType = "grid_header") {
                header()
            }
        }
        // v27.5 稳定性加固（critical 修复）：同 ComicListColumn，id="" 重复时 key 撞车崩溃。
        // 改用 itemsIndexed + 复合 key（id 为空时回退到 index）。
        itemsIndexed(items, key = { i, c -> c.id.ifBlank { "idx_$i" } }, contentType = { _, _ -> "comic_grid" }) { _, item ->
            // v27.5 性能修复：同 ComicListColumn，用 remember 缓存 lambda 避免每次重组创建新 lambda
            val click = remember(item, onClick) { { onClick(item) } }
            val longClick = remember(item, onLongClick) { { onLongClick(item) } }
            // v27.14：COVER_ONLY 模式下被屏蔽的本子保留卡片但替换封面
            val coverHidden = item.id.isNotEmpty() && item.id in coverHiddenIds
            if (useMagazineStyle) {
                ComicMagazineCard(
                    item = item,
                    onClick = click,
                    onLongClick = longClick,
                    coverHidden = coverHidden,
                )
            } else {
                ComicGridCard(
                    item = item,
                    onClick = click,
                    onLongClick = longClick,
                    coverHidden = coverHidden,
                )
            }
        }
        if (items.isNotEmpty()) {
            // footer 跨整行
            item(span = {
                GridItemSpan(columns)
            }, key = "grid_footer") {
                ListFooter(
                    loadingMore = loadingMore,
                    endReached = endReached,
                    error = loadError,
                    onRetry = onRetry,
                )
            }
        }
    }
}

/**
 * 列表底部 footer：显示加载更多状态。
 * - loadingMore=true：转圈"加载中…"
 * - error!=null：红色"加载失败" + 重试按钮
 * - endReached=true：灰色"没有更多了"
 * - 否则不显示（列表末尾留空）
 */
@Composable
private fun ListFooter(
    loadingMore: Boolean,
    endReached: Boolean,
    error: String?,
    onRetry: (() -> Unit)?,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    "加载中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error != null -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "加载失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (onRetry != null) {
                    androidx.compose.material3.TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("重试", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            endReached -> Text(
                "没有更多了",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 单个本子卡片：横向布局。
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ ┌─────┐  名称（粗体，最多2行）                 │
 * │ │     │  作者：xxx                            │
 * │ │ 封面 │  分类：同人、汉化                     │
 * │ │     │  观看 1.2万 · 喜欢 234                │
 * │ └─────┘                                       │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * 性能要点（曾经卡顿的元凶）：
 * 1. **ComicBriefDto 加 @Immutable**（在 Dtos.kt）：让 LazyColumn 滚动时每个 card 都能跳过重组
 * 2. **用 rememberAsyncImagePainter + Image 替代 AsyncImage**：图片加载状态变化只触发重绘不触发重组
 * 3. **combinedClickable(interactionSource = null, indication = null)**：禁用涟漪减少绘制开销
 * 4. **颜色用 MaterialTheme.colorScheme**：黑夜模式自适应（之前硬编码导致深色下白底）
 * 5. **ImageRequest 用 remember 缓存**：避免每次重组都重建请求对象
 * 6. **cover 为 null 时不启动 painter**：省掉空请求 + 重组
 * 7. **不开 crossfade**：快速滚动时几十张图同时跑 300ms 淡入会导致 GPU 抖动
 */
@Composable
fun ComicCard(
    item: ComicBriefDto,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    // v27.14：COVER_ONLY 模式下被屏蔽封面，true=渲染打叉灰白占位图替代真实封面。
    coverHidden: Boolean = false,
) {
    // 颜色从 MaterialTheme 取，黑夜模式自动适配。读 colorScheme 是 O(1) CompositionLocal 查表，
    // release 下 JIT 优化后开销可忽略；之前为追求极致性能硬编码颜色导致深色模式仍显示白底。
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val placeholderColor = MaterialTheme.colorScheme.surface
    val authorColor = MaterialTheme.colorScheme.onSurfaceVariant
    val statsColor = MaterialTheme.colorScheme.onSurfaceVariant

    // v27.5：用 MaterialTheme.shapes.medium（由 JMTheme 根据 cardCornerRadius 设置动态生成），
    // 让设置页圆角滑块真正生效。之前硬编码 14.dp 导致设置项无效。
    val cardShape = MaterialTheme.shapes.medium
    // v27.5：阴影从 LocalCardElevation 取（设置页"卡片阴影"滑块全局生效）。
    // shadow 必须在 background 之前，否则阴影会被 background 覆盖。
    val cardElevation = com.jmreader.ui.theme.LocalCardElevation.current
    // v27.6：从 CompositionLocal 读取用户设置的字号和封面宽高比
    val titleFontSize = com.jmreader.ui.theme.LocalListTitleFontSize.current
    val bodyFontSize = com.jmreader.ui.theme.LocalListBodyFontSize.current
    val coverRatio = com.jmreader.ui.theme.LocalCoverAspectRatio.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            // v27.12 性能修复：列表卡片去掉 shadow。
            // shadow 会为每个卡片创建 graphicsLayer + 绘制阴影，一屏 8-10 个卡片 = 8-10 次阴影绘制，
            // 是滚动掉帧的常见原因（尤其在中端机上）。用 1dp border 替代视觉层次感，零 GPU 开销。
            .clip(cardShape)
            .background(cardBg)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 左侧：封面缩略图（固定宽度 76dp，按用户设置的宽高比）
        val ctx = LocalContext.current
        val cover = item.cover
        val coverModifier = Modifier
            .width(76.dp)
            .aspectRatio(coverRatio)
            .clip(CoverClipShape)
        if (coverHidden) {
            BlockedCover(modifier = coverModifier)
        } else if (cover.isNullOrBlank()) {
            Box(modifier = coverModifier.background(placeholderColor))
        } else {
            // v27.12：ColorPainter remember 缓存，避免每次重组分配 3 个新实例
            val placeholderPainter = remember(placeholderColor) {
                androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor)
            }
            // rememberAsyncImagePainter：图片加载完成只触发 Image 重绘，不触发 ComicCard 重组
            val painter = rememberAsyncImagePainter(
                model = remember(cover) {
                    ImageRequest.Builder(ctx)
                        .data(cover)
                        .size(220, 314)
                        .build()
                },
                placeholder = placeholderPainter,
                error = placeholderPainter,
                fallback = placeholderPainter,
            )
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = coverModifier,
            )
        }

        // 右侧：纵向信息列
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 名称（粗体，最多2行）
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = titleFontSize.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // 作者
            item.author?.takeIf { it.isNotBlank() }?.let { author ->
                Text(
                    text = "作者：$author",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = bodyFontSize.sp),
                    color = authorColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 分类/标签（chips 样式，最多显示 4 个，避免占太多空间）
            if (item.tags.isNotEmpty()) {
                // v27.12：remember 避免 tags.take(4) 每次重组创建新 List
                val displayTags = remember(item.tags) { item.tags.take(4) }
                TagChipsRow(tags = displayTags, modifier = Modifier.padding(top = 2.dp))
            }
            // 观看数 / 喜欢数 / 发布时间（v26 新增：列表卡片旁显示发布日期，方便判断本子新旧）
            // v27.6：过滤 "0"（API 返回的占位值），避免误导用户
            // v27.12：remember 缓存拼接结果，避免 buildList 每次重组分配
            val views = item.views?.takeIf { it.isNotBlank() && it != "0" }
            val likes = item.likes?.takeIf { it.isNotBlank() && it != "0" }
            val publishTime = item.publishTime
            val statsText = remember(views, likes, publishTime) {
                buildList {
                    publishTime?.takeIf { it.isNotBlank() }?.let { add(it) }
                    views?.takeIf { it.isNotBlank() }?.let { add("观看 $it") }
                    likes?.takeIf { it.isNotBlank() }?.let { add("喜欢 $it") }
                }.joinToString(" · ")
            }
            if (statsText.isNotEmpty()) {
                Text(
                    text = statsText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = bodyFontSize.sp),
                    color = statsColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 网格卡片：纵向布局，封面占满宽度 + 下方标题。
 *
 * ```
 * ┌────────────┐
 * │            │
 * │    封面     │
 * │            │
 * └────────────┘
 * 标题（最多2行）
 * ```
 *
 * 性能要点与 [ComicCard] 一致：@Immutable + rememberAsyncImagePainter + combinedClickable 无涟漪。
 */
@Composable
fun ComicGridCard(
    item: ComicBriefDto,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    // v27.14：COVER_ONLY 模式下被屏蔽封面，true=渲染打叉灰白占位图替代真实封面。
    coverHidden: Boolean = false,
) {
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val placeholderColor = MaterialTheme.colorScheme.surface
    val cardShape = MaterialTheme.shapes.medium
    val cardElevation = com.jmreader.ui.theme.LocalCardElevation.current
    val titleFontSize = com.jmreader.ui.theme.LocalListTitleFontSize.current
    val bodyFontSize = com.jmreader.ui.theme.LocalListBodyFontSize.current
    val coverRatio = com.jmreader.ui.theme.LocalCoverAspectRatio.current
    val ctx = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (cardElevation > 0.dp) Modifier.shadow(cardElevation, cardShape) else Modifier)
            .clip(cardShape)
            .background(cardBg)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(6.dp),
    ) {
        val cover = item.cover
        val coverModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(coverRatio)
            .clip(CoverClipShape)
        if (coverHidden) {
            BlockedCover(modifier = coverModifier)
        } else if (cover.isNullOrBlank()) {
            Box(modifier = coverModifier.background(placeholderColor))
        } else {
            val placeholderPainter = remember(placeholderColor) {
                androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor)
            }
            val painter = rememberAsyncImagePainter(
                model = remember(cover) {
                    ImageRequest.Builder(ctx)
                        .data(cover)
                        .size(300, 428)
                        .build()
                },
                placeholder = placeholderPainter,
                error = placeholderPainter,
                fallback = placeholderPainter,
            )
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = coverModifier,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = titleFontSize.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // 发布时间（v26 新增：网格卡片也显示发布日期，方便判断新旧）
        item.publishTime?.takeIf { it.isNotBlank() }?.let { pt ->
            Text(
                text = pt,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = bodyFontSize.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 卡片样式（ListStyle.CARD）：单列竖排，封面更突出，下方带 tags chips + 作者 + 统计。
 *
 * 视觉效果：
 * ```
 * ┌──────────────────────────────────────────┐
 * │  ┌────────────────────────────────────┐ │
 * │  │                                    │ │
 * │  │             封面图                  │ │
 * │  │                                    │ │
 * │  └────────────────────────────────────┘ │
 * │  标题（粗体，最多2行）                    │
 * │  [chip] [chip] [chip] [chip]            │
 * │  作者 xxx · 观看 1.2万 · 喜欢 234         │
 * └──────────────────────────────────────────┘
 * ```
 *
 * 与 [ComicCard]（横向布局）的区别：竖排，封面占满宽度更具视觉冲击力；
 * 与 [ComicGridCard]（紧凑网格）的区别：单列、信息全、有 tags chips。
 */
@Composable
fun ComicCardStyle(
    item: ComicBriefDto,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    // v27.14：COVER_ONLY 模式下被屏蔽封面，true=渲染打叉灰白占位图替代真实封面。
    coverHidden: Boolean = false,
) {
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val placeholderColor = MaterialTheme.colorScheme.surface
    val cardShape = MaterialTheme.shapes.medium
    val cardElevation = com.jmreader.ui.theme.LocalCardElevation.current
    val titleFontSize = com.jmreader.ui.theme.LocalListTitleFontSize.current
    val bodyFontSize = com.jmreader.ui.theme.LocalListBodyFontSize.current
    val coverRatio = com.jmreader.ui.theme.LocalCoverAspectRatio.current
    val ctx = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (cardElevation > 0.dp) Modifier.shadow(cardElevation, cardShape) else Modifier)
            .clip(cardShape)
            .background(cardBg)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(10.dp),
    ) {
        // 封面（按用户设置的宽高比占满宽度）
        val cover = item.cover
        val coverModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(coverRatio)
            .clip(CoverClipShape)
        if (coverHidden) {
            BlockedCover(modifier = coverModifier)
        } else if (cover.isNullOrBlank()) {
            Box(modifier = coverModifier.background(placeholderColor))
        } else {
            val placeholderPainter = remember(placeholderColor) {
                androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor)
            }
            val painter = rememberAsyncImagePainter(
                model = remember(cover) {
                    ImageRequest.Builder(ctx)
                        .data(cover)
                        .size(400, 572)
                        .build()
                },
                placeholder = placeholderPainter,
                error = placeholderPainter,
                fallback = placeholderPainter,
            )
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = coverModifier,
            )
        }
        Spacer(Modifier.height(8.dp))
        // 标题
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = titleFontSize.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // tags chips（最多 5 个）
        if (item.tags.isNotEmpty()) {
            val displayTags = remember(item.tags) { item.tags.take(5) }
            TagChipsRow(
                tags = displayTags,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // 作者 + 统计 + 发布时间（v26 新增：列表卡片旁显示发布日期）
        // v27.6：过滤 "0"（API 占位值），避免误导
        val author = item.author
        val views = item.views?.takeIf { it.isNotBlank() && it != "0" }
        val likes = item.likes?.takeIf { it.isNotBlank() && it != "0" }
        val publishTime = item.publishTime
        val metaText = remember(author, views, likes, publishTime) {
            buildList {
                author?.takeIf { it.isNotBlank() }?.let { add(it) }
                publishTime?.takeIf { it.isNotBlank() }?.let { add(it) }
                views?.takeIf { it.isNotBlank() }?.let { add("观看 $it") }
                likes?.takeIf { it.isNotBlank() }?.let { add("喜欢 $it") }
            }.joinToString(" · ")
        }
        if (metaText.isNotEmpty()) {
            Text(
                text = metaText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = bodyFontSize.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 杂志风卡片（ListStyle.MAGAZINE）：双列，比 [ComicGridCard] 多 tags chip + 作者，
 * 适合喜欢浏览带信息密度的网格用户。
 *
 * 视觉效果：
 * ```
 * ┌────────────┐ ┌────────────┐
 * │   封面图    │ │   封面图    │
 * │            │ │            │
 * └────────────┘ └────────────┘
 * 标题            标题
 * [chip] [chip]   [chip]
 * 作者            作者
 * ```
 */
@Composable
fun ComicMagazineCard(
    item: ComicBriefDto,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    // v27.14：COVER_ONLY 模式下被屏蔽封面，true=渲染打叉灰白占位图替代真实封面。
    coverHidden: Boolean = false,
) {
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val placeholderColor = MaterialTheme.colorScheme.surface
    val cardShape = MaterialTheme.shapes.medium
    val cardElevation = com.jmreader.ui.theme.LocalCardElevation.current
    val titleFontSize = com.jmreader.ui.theme.LocalListTitleFontSize.current
    val bodyFontSize = com.jmreader.ui.theme.LocalListBodyFontSize.current
    val coverRatio = com.jmreader.ui.theme.LocalCoverAspectRatio.current
    val ctx = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (cardElevation > 0.dp) Modifier.shadow(cardElevation, cardShape) else Modifier)
            .clip(cardShape)
            .background(cardBg)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(6.dp),
    ) {
        val cover = item.cover
        val coverModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(coverRatio)
            .clip(CoverClipShape)
        if (coverHidden) {
            BlockedCover(modifier = coverModifier)
        } else if (cover.isNullOrBlank()) {
            Box(modifier = coverModifier.background(placeholderColor))
        } else {
            val placeholderPainter = remember(placeholderColor) {
                androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor)
            }
            val painter = rememberAsyncImagePainter(
                model = remember(cover) {
                    ImageRequest.Builder(ctx)
                        .data(cover)
                        .size(300, 428)
                        .build()
                },
                placeholder = placeholderPainter,
                error = placeholderPainter,
                fallback = placeholderPainter,
            )
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = coverModifier,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = titleFontSize.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // tags chips（最多 2 个，避免在双列窄格里挤压）
        if (item.tags.isNotEmpty()) {
            val displayTags = remember(item.tags) { item.tags.take(2) }
            TagChipsRow(
                tags = displayTags,
                modifier = Modifier.padding(top = 2.dp),
                small = true,
            )
        }
        // 作者 + 发布时间（v26 新增：杂志风卡片也显示发布日期）
        val author = item.author
        val publishTime = item.publishTime
        val magText = remember(author, publishTime) {
            buildList {
                author?.takeIf { it.isNotBlank() }?.let { add(it) }
                publishTime?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" · ")
        }
        if (magText.isNotEmpty()) {
            Text(
                text = magText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = bodyFontSize.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Tag chips 行：横向排列的标签 chips。
 *
 * - 普通模式（small=false）：高度 24dp，文字 labelSmall
 * - 紧凑模式（small=true）：高度 20dp，文字 labelSmall 但 padding 更小，适合网格窄格
 *
 * v27.12 性能修复：LazyRow → Row。
 * 之前每个 ComicCard 内嵌一个 LazyRow（含 SubcomposeLayout），在 LazyColumn 滚动时
 * 每个可见卡片都要做 subcomposition + measure，是掉帧元凶之一。
 * tags 最多 5 个，普通 Row 足够；溢出部分被父级裁剪，不影响使用。
 */
@Composable
private fun TagChipsRow(
    tags: List<String>,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val chipBg = MaterialTheme.colorScheme.secondaryContainer
    val chipFg = MaterialTheme.colorScheme.onSecondaryContainer
    val hPadding = if (small) 4.dp else 6.dp
    val vPadding = if (small) 1.dp else 2.dp

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tags.forEach { tag ->
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                color = chipFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(chipBg, ChipShape)
                    .padding(horizontal = hPadding, vertical = vPadding),
            )
        }
    }
}

/**
 * v27.14：被屏蔽封面占位图（COVER_ONLY 模式）。
 * 灰白底 + 中央打叉图标，替代真实封面图，保留卡片其他信息（标题/作者/标签）。
 *
 * @param modifier 必须由调用方提供尺寸/clip（与正常封面 Box 完全一致），保证布局不变。
 */
@Composable
private fun BlockedCover(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Box(
        modifier = modifier.background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "已屏蔽",
            tint = fg,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, message: String? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp))
            if (!message.isNullOrBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
fun ErrorBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onViewLogs: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "加载失败",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(top = 8.dp)
                .verticalScroll(rememberScrollState()),
        )
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            onRetry?.let {
                androidx.compose.material3.Button(onClick = it) { Text("重试") }
            }
            androidx.compose.material3.OutlinedButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(
                    android.content.ClipData.newPlainText("一根葱 error", message)
                )
                android.widget.Toast.makeText(ctx, "错误已复制", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("复制错误") }
            if (onViewLogs != null) {
                androidx.compose.material3.TextButton(onClick = onViewLogs) { Text("查看日志") }
            }
        }
    }
}

@Composable
fun EmptyBox(text: String = "没有数据", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 列表项长按操作处理器（屏蔽 / 收藏 / 下载）。
 *
 * 返回一个 lambda，传给 [ComicList] 的 onLongClick。
 * 长按某本子时弹出操作弹窗：
 * - 屏蔽此本子（按名称）：把整本标题作为关键词加入名称屏蔽
 * - 屏蔽此作者：把作者加入作者屏蔽（列表接口通常已返回作者，即时生效）
 * - 屏蔽其 Tag：把本子的某个 Tag 加入 Tag 屏蔽（列表项无 Tag 时该项隐藏）
 * - 收藏 / 取消收藏
 * - 下载（跳转详情页下载，列表页无下载能力，故仅导航）
 *
 * 屏蔽后 [com.jmreader.ui.viewmodel.BaseListViewModel] 会监听规则变化自动重过滤，
 * 列表立即刷新，无需手动重载。
 *
 * @param onResult 操作完成后的回调（如弹 snackbar 提示），可选
 * @param onNavigateToDetail 点击"详情/下载"时的导航回调，可选
 */
@Composable
fun rememberBlockAction(
    container: AppContainer,
    onResult: (String) -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
): (ComicBriefDto) -> Unit {
    var target by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ComicBriefDto?>(null) }
    // v27.5 #15：「移动到分组」二级对话框
    var showFolderPicker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val favorites by container.favoritesStore.items.collectAsState()
    val folders by container.favoritesStore.folders.collectAsState()
    // v27.5 性能修复：isFav 用 derivedStateOf，避免每次重组都 O(n) 遍历 favorites
    // v27.5 稳定性加固：原 `target != null && favorites.any { it.id == target!!.id }` 在
    // derivedStateOf block 内两次读 target，第一次判空通过后第二次读时若另一重组把 target 置 null
    // （如点「取消」），target!! 抛 NPE。Compose snapshot 不保证多次状态读的原子性。
    // 改为捕获到本地 val t，单次读取避免 race。
    val isFav by androidx.compose.runtime.remember(target, favorites) {
        androidx.compose.runtime.derivedStateOf {
            val t = target
            t != null && favorites.any { it.id == t.id }
        }
    }

    target?.let { comic ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(comic.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    comic.author?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = "作者：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (comic.tags.isNotEmpty()) {
                        Text(
                            text = "Tag：${comic.tags.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "选择操作，屏蔽操作列表将立即刷新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 屏蔽此本子（按名称）
                    // v27.5 稳定性加固：DataStore 写盘可能抛 IOException，未捕获会冒泡到
                    // Thread.uncaughtExceptionHandler → CrashHandler → 杀进程。整段包 try-catch。
                    androidx.compose.material3.TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    container.blockedTagsStore.addName(comic.name)
                                    target = null
                                    onResult("已屏蔽：${comic.name}")
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    com.jmreader.core.Logger.w("Block", "屏蔽失败: ${com.jmreader.core.Logger.brief(e)}")
                                    onResult("屏蔽失败：${e.message ?: "未知错误"}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("屏蔽此本子（按名称）") }
                    // 屏蔽作者
                    comic.author?.takeIf { it.isNotBlank() }?.let { author ->
                        androidx.compose.material3.TextButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        container.blockedTagsStore.addAuthor(author)
                                        target = null
                                        onResult("已屏蔽作者：$author")
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        com.jmreader.core.Logger.w("Block", "屏蔽作者失败: ${com.jmreader.core.Logger.brief(e)}")
                                        onResult("屏蔽失败：${e.message ?: "未知错误"}")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("屏蔽作者：$author") }
                    }
                    // 屏蔽首个 Tag
                    comic.tags.firstOrNull()?.let { firstTag ->
                        androidx.compose.material3.TextButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        container.blockedTagsStore.addTag(firstTag)
                                        target = null
                                        onResult("已屏蔽标签：$firstTag")
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        com.jmreader.core.Logger.w("Block", "屏蔽 tag 失败: ${com.jmreader.core.Logger.brief(e)}")
                                        onResult("屏蔽失败：${e.message ?: "未知错误"}")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("屏蔽标签：$firstTag") }
                    }
                    // 收藏 / 取消收藏
                    androidx.compose.material3.TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val now = container.favoritesStore.toggle(comic)
                                    target = null
                                    onResult(if (now) "已加入本地收藏" else "已取消本地收藏")
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    com.jmreader.core.Logger.w("Favorites", "收藏操作失败: ${com.jmreader.core.Logger.brief(e)}")
                                    onResult("收藏操作失败：${e.message ?: "未知错误"}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (isFav) "取消本地收藏" else "加入本地收藏") }
                    // v27.5 #15：已收藏时提供「移动到分组」
                    if (isFav) {
                        androidx.compose.material3.TextButton(
                            onClick = { showFolderPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("移动到分组…") }
                    }
                    // 详情/下载
                    androidx.compose.material3.TextButton(
                        onClick = {
                            target = null
                            onNavigateToDetail(comic.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("详情 / 下载") }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { target = null }) { Text("取消") }
            },
        )
    }

    // v27.5 #15：移动到分组二级对话框
    // v27.5 稳定性加固：把 target 捕获到本地 val，避免 `target != null` 通过后再读 target!! 时
    // 被另一重组置 null 抛 NPE（Compose snapshot 多次读不保证原子性）。
    val pickerTarget = target
    if (showFolderPicker && pickerTarget != null) {
        val comicId = pickerTarget.id
        FavoriteFolderPickerDialog(
            folders = folders,
            currentFolderId = container.favoritesStore.folderOf(comicId),
            onDismiss = { showFolderPicker = false },
            onCreateFolder = { name ->
                scope.launch {
                    try {
                        val folder = container.favoritesStore.createFolder(name)
                        container.favoritesStore.moveToFolder(comicId, folder.id)
                        showFolderPicker = false
                        target = null
                        onResult("已移动到分组：${folder.name}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        com.jmreader.core.Logger.w("Favorites", "创建分组并移动失败: ${com.jmreader.core.Logger.brief(e)}")
                        onResult("操作失败：${e.message ?: "未知错误"}")
                    }
                }
            },
            onPick = { folderId ->
                scope.launch {
                    try {
                        container.favoritesStore.moveToFolder(comicId, folderId)
                        val name = folders.firstOrNull { it.id == folderId }?.name ?: "未分组"
                        showFolderPicker = false
                        target = null
                        onResult("已移动到分组：$name")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        com.jmreader.core.Logger.w("Favorites", "移动到分组失败: ${com.jmreader.core.Logger.brief(e)}")
                        onResult("操作失败：${e.message ?: "未知错误"}")
                    }
                }
            },
        )
    }

    // 关键性能：返回的 lambda 必须 remember，否则 HomeScreen 每次重组都产生新 lambda 实例
    return remember { { comic: ComicBriefDto -> target = comic } }
}

/**
 * 用户头像组件：评论区/讨论区通用。
 *
 * 优先加载 [avatar] URL（用 Coil rememberAsyncImagePainter，加载完只重绘不重组）；
 * 加载失败/为空时显示首字母（取 [name] 首个字符）圆形背景兜底；
 * 若 name 也为空则回退到楼层号 [floor]。
 *
 * 不同 [name] 自动取不同色相，便于多人列表中区分。
 *
 * @param avatar 头像 URL，可为空
 * @param name 用户名（兜底取首字母）
 * @param floor 楼层号（兜底兜底）
 * @param sizeDp 头像直径，默认 32dp
 */
@Composable
fun UserAvatar(
    avatar: String?,
    name: String,
    floor: Int = 0,
    modifier: Modifier = Modifier,
    sizeDp: Int = 32,
) {
    val ctx = LocalContext.current
    val shape = androidx.compose.foundation.shape.CircleShape
    val initial = remember(name, floor) {
        // 取用户名首个非空白字符（中文/英文都行）；都拿不到则用楼层号
        name.firstOrNull { !it.isWhitespace() }?.toString()?.uppercase()
            ?: floor.toString()
    }
    val bgColor = remember(name) { avatarColorFor(name) }
    val sizeModifier = Modifier.size(sizeDp.dp)

    if (avatar.isNullOrBlank()) {
        Box(
            modifier = modifier
                .then(sizeModifier)
                .clip(shape)
                .background(bgColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = if (sizeDp >= 32) MaterialTheme.typography.labelMedium
                       else MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        // 加载网络头像：失败时 fallback 到首字母圆圈（Coil error painter 兜底）
        val painter = rememberAsyncImagePainter(
            model = remember(avatar) {
                ImageRequest.Builder(ctx)
                    .data(avatar)
                    .size(sizeDp * 2, sizeDp * 2)  // ×2 适应高 DPI 屏幕
                    .crossfade(false)  // 列表里多张头像同时淡入会卡顿
                    .build()
            },
            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(bgColor),
            error = androidx.compose.ui.graphics.painter.ColorPainter(bgColor),
            fallback = androidx.compose.ui.graphics.painter.ColorPainter(bgColor),
        )
        Box(
            modifier = modifier
                .then(sizeModifier)
                .clip(shape)
                .background(bgColor),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 根据用户名稳定生成头像背景色（同一用户每次显示颜色一致）。
 * 色盘取自 Material3 主色 + 二级色，确保深浅模式下都好看。
 */
private fun avatarColorFor(name: String): Color {
    if (name.isBlank()) return Color(0xFF7C4DFF)
    val palette = listOf(
        0xFF7C4DFF, 0xFF536DFE, 0xFF42A5F5, 0xFF26A69A,
        0xFF66BB6A, 0xFFFFA726, 0xFFFF7043, 0xFFEC407A,
        0xFFAB47BC, 0xFF26C6DA, 0xFF9CCC65, 0xFFFFCA28,
    )
    val h = name.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    val color = palette[h % palette.size]
    return Color(color)
}

// ===================== v27.5 #15：收藏夹分组 UI =====================

/**
 * 「移动到分组」二级对话框。
 *
 * 列出所有分组（含「未分组」），高亮当前所属。
 * - 点击某项 → 调 [onPick] 移动并关闭
 * - 点「+ 新建分组」→ 弹出输入框 → 调 [onCreateFolder] 创建并移动
 *
 * 调用方负责在 onPick/onCreateFolder 内完成 [FavoritesStore.moveToFolder]
 * 与 UI 反馈（snackbar/关闭父对话框等）。
 *
 * @param folders 当前所有分组
 * @param currentFolderId 当前条目所属分组 id（null=未分组），用于高亮
 * @param onPick 选择分组或「未分组」时回调；参数 null=未分组
 * @param onCreateFolder 用户输入新分组名后的回调（参数=分组名）
 */
@Composable
fun FavoriteFolderPickerDialog(
    folders: List<com.jmreader.data.dto.FavoriteFolder>,
    currentFolderId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    var showCreateInput by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var newFolderName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("移动到分组") },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 「未分组」选项
                FolderPickerRow(
                    name = "未分组",
                    selected = currentFolderId == null,
                    onClick = { onPick(null) },
                )
                folders.forEach { f ->
                    FolderPickerRow(
                        name = f.name,
                        selected = currentFolderId == f.id,
                        onClick = { onPick(f.id) },
                    )
                }
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                androidx.compose.material3.TextButton(
                    onClick = { showCreateInput = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.Text("新建分组")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("取消") }
        },
    )

    // 新建分组输入框
    if (showCreateInput) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showCreateInput = false
                newFolderName = ""
            },
            title = { androidx.compose.material3.Text("新建分组") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { androidx.compose.material3.Text("分组名") },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val name = newFolderName.trim()
                    if (name.isNotBlank()) {
                        showCreateInput = false
                        newFolderName = ""
                        onCreateFolder(name)
                    }
                }) { androidx.compose.material3.Text("创建并移动") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showCreateInput = false
                    newFolderName = ""
                }) { androidx.compose.material3.Text("取消") }
            },
        )
    }
}

/** 分组选择行：名称 + 选中标记。 */
@Composable
private fun FolderPickerRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 「管理分组」对话框：列出所有分组，每项可重命名 / 删除（删除时组内条目移到「未分组」）。
 *
 * @param folders 当前所有分组
 * @param folderCounts 分组 id → 条目数（用于在每行显示数量）
 * @param onRename 重命名回调（参数: id, newName）
 * @param onDelete 删除回调（参数: id）。调用方负责把组内条目移到「未分组」。
 */
@Composable
fun FavoriteFolderManageDialog(
    folders: List<com.jmreader.data.dto.FavoriteFolder>,
    folderCounts: Map<String?, Int>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showCreate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var newName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var renameTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.jmreader.data.dto.FavoriteFolder?>(null) }
    var renameText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var deleteTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.jmreader.data.dto.FavoriteFolder?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("管理分组") },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (folders.isEmpty()) {
                    androidx.compose.material3.Text(
                        "暂无分组。点下方「新建分组」创建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                folders.forEach { f ->
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                            androidx.compose.material3.Text(
                                f.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            androidx.compose.material3.Text(
                                "${folderCounts[f.id] ?: 0} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.TextButton(onClick = {
                            renameTarget = f
                            renameText = f.name
                        }) { androidx.compose.material3.Text("重命名") }
                        androidx.compose.material3.TextButton(
                            onClick = { deleteTarget = f },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { androidx.compose.material3.Text("删除") }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { showCreate = true }) {
                androidx.compose.material3.Text("+ 新建分组")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("完成") }
        },
    )

    // 新建分组对话框
    if (showCreate) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showCreate = false
                newName = ""
            },
            title = { androidx.compose.material3.Text("新建分组") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { androidx.compose.material3.Text("分组名") },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotBlank()) {
                        showCreate = false
                        newName = ""
                        onCreate(name)
                    }
                }) { androidx.compose.material3.Text("创建") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showCreate = false
                    newName = ""
                }) { androidx.compose.material3.Text("取消") }
            },
        )
    }

    // 重命名对话框
    // v27.5 稳定性加固：用智能转换代替 renameTarget!!，避免 race condition NPE。
    renameTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                renameTarget = null
                renameText = ""
            },
            title = { androidx.compose.material3.Text("重命名分组") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { androidx.compose.material3.Text("分组名") },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val name = renameText.trim()
                    if (name.isNotBlank()) {
                        onRename(target.id, name)
                    }
                    renameTarget = null
                    renameText = ""
                }) { androidx.compose.material3.Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    renameTarget = null
                    renameText = ""
                }) { androidx.compose.material3.Text("取消") }
            },
        )
    }

    // 删除二次确认
    // v27.5 稳定性加固：用智能转换代替 deleteTarget!!，避免 race condition NPE。
    deleteTarget?.let { target ->
        val count = folderCounts[target.id] ?: 0
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { androidx.compose.material3.Text("删除分组") },
            text = {
                androidx.compose.material3.Text(
                    "删除分组「${target.name}」？\n" +
                        if (count > 0) "组内 $count 项收藏将移动到「未分组」。" else "此分组为空。",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onDelete(target.id)
                        deleteTarget = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { androidx.compose.material3.Text("删除") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteTarget = null }) {
                    androidx.compose.material3.Text("取消")
                }
            },
        )
    }
}

