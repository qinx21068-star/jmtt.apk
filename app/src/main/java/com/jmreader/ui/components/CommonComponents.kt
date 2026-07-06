@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jmreader.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import kotlinx.coroutines.launch

/**
 * 横向列表：每个本子一张横向卡片（左缩略图 + 右侧纵向信息）。
 *
 * 替代了原 LazyVerticalGrid 三列网格——用户更习惯列表式浏览，且竖屏下单列信息密度更高。
 *
 * 性能要点：
 * 1. [LazyColumn] + [items] (key + contentType) 让滚动时复用 slot
 * 2. [ComicBriefDto] 标了 @Immutable，[ComicCard] 在滚动时可跳过重组
 * 3. 用 [rememberAsyncImagePainter] + Image 而非 AsyncImage，避免 Loading→Success 触发重组
 * 4. onClick/onLongClick 由调用方 remember 包好稳定 lambda，避免新实例导致级联重组
 */
@Composable
fun ComicList(
    items: List<ComicBriefDto>,
    onClick: (ComicBriefDto) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(12.dp),
    onLongClick: (ComicBriefDto) -> Unit = {},
    loadingMore: Boolean = false,
    endReached: Boolean = false,
    loadError: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.id }, contentType = { "comic_card" }) { item ->
            ComicCard(
                item = item,
                onClick = { onClick(item) },
                onLongClick = { onLongClick(item) },
            )
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
) {
    // 颜色从 MaterialTheme 取，黑夜模式自动适配。读 colorScheme 是 O(1) CompositionLocal 查表，
    // release 下 JIT 优化后开销可忽略；之前为追求极致性能硬编码颜色导致深色模式仍显示白底。
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val placeholderColor = MaterialTheme.colorScheme.surface
    val authorColor = MaterialTheme.colorScheme.onSurfaceVariant
    val statsColor = MaterialTheme.colorScheme.onSurfaceVariant

    val cardShape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
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
        // 左侧：封面缩略图（固定宽度 76dp，按 0.7 宽高比 → 高约 108dp）
        val ctx = LocalContext.current
        val cover = item.cover
        val coverModifier = Modifier
            .width(76.dp)
            .aspectRatio(0.7f)
            .clip(RoundedCornerShape(6.dp))
        if (cover.isNullOrBlank()) {
            Box(modifier = coverModifier.background(placeholderColor))
        } else {
            // rememberAsyncImagePainter：图片加载完成只触发 Image 重绘，不触发 ComicCard 重组
            val painter = rememberAsyncImagePainter(
                model = remember(cover) {
                    ImageRequest.Builder(ctx)
                        .data(cover)
                        .size(220, 314)
                        .build()
                },
                placeholder = androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor),
                error = androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor),
                fallback = androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor),
            )
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = item.name,
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
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // 作者
            item.author?.takeIf { it.isNotBlank() }?.let { author ->
                Text(
                    text = "作者：$author",
                    style = MaterialTheme.typography.bodySmall,
                    color = authorColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 分类/标签
            if (item.tags.isNotEmpty()) {
                Text(
                    text = "分类：" + item.tags.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = authorColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 观看数 / 喜欢数（列表 API 不返回更新时间，用观看/喜欢数代替）
            val views = item.views
            val likes = item.likes
            if (!views.isNullOrBlank() || !likes.isNullOrBlank()) {
                val parts = buildList {
                    views?.takeIf { it.isNotBlank() }?.let { add("观看 $it") }
                    likes?.takeIf { it.isNotBlank() }?.let { add("喜欢 $it") }
                }
                if (parts.isNotEmpty()) {
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = statsColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp))
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
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("JMReader error", message)
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val favorites by container.favoritesStore.items.collectAsState()
    val isFav = target != null && favorites.any { it.id == target!!.id }

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
                    androidx.compose.material3.TextButton(
                        onClick = {
                            scope.launch {
                                container.blockedTagsStore.addName(comic.name)
                                target = null
                                onResult("已屏蔽：${comic.name}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("屏蔽此本子（按名称）") }
                    // 屏蔽作者
                    comic.author?.takeIf { it.isNotBlank() }?.let { author ->
                        androidx.compose.material3.TextButton(
                            onClick = {
                                scope.launch {
                                    container.blockedTagsStore.addAuthor(author)
                                    target = null
                                    onResult("已屏蔽作者：$author")
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
                                    container.blockedTagsStore.addTag(firstTag)
                                    target = null
                                    onResult("已屏蔽标签：$firstTag")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("屏蔽标签：$firstTag") }
                    }
                    // 收藏 / 取消收藏
                    androidx.compose.material3.TextButton(
                        onClick = {
                            scope.launch {
                                val now = container.favoritesStore.toggle(comic)
                                target = null
                                onResult(if (now) "已加入本地收藏" else "已取消本地收藏")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (isFav) "取消本地收藏" else "加入本地收藏") }
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

    // 关键性能：返回的 lambda 必须 remember，否则 HomeScreen 每次重组都产生新 lambda 实例
    return remember { { comic: ComicBriefDto -> target = comic } }
}
