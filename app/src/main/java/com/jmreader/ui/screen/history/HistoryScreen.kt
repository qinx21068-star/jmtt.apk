@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jmreader.data.AppContainer
import com.jmreader.data.local.HistoryEntry
import com.jmreader.ui.components.EmptyBox
import com.jmreader.ui.nav.Routes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v27.5 #9 阅读历史页：展示最近阅读的本子（含章节/页码进度）。
 *
 * - 数据来源：[com.jmreader.data.local.HistoryStore.items]（按 updatedAt 倒序）
 * - 每条显示：封面 / 标题 / 上次章节 / 页码 / 时间
 * - 点击 → 进入详情页（详情页"继续阅读"按钮会自动跳到上次章节）
 * - 长按或右侧按钮 → 删除单条
 * - 顶部右上角 → 清空全部
 *
 * 与 FavoritesScreen 第 3 个 tab「浏览历史」不同：
 * - 浏览历史：只记录打开过详情页（BrowseHistoryStore）
 * - 阅读历史：记录阅读到哪章哪页（HistoryStore）
 */
@Composable
fun ReadingHistoryTab(
    container: AppContainer,
    navController: NavController,
    snackbar: SnackbarHostState,
) {
    val history by container.historyStore.items.collectAsState()
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
    // 屏蔽规则过滤（与 FavoritesScreen 浏览历史一致）
    val rulesTriple by container.blockedTagsStore.allRules.collectAsState(
        Triple(emptySet(), emptySet(), emptySet()),
    )
    val rules = remember(rulesTriple) {
        container.blockedTagsStore.normalizeRules(rulesTriple.first, rulesTriple.second, rulesTriple.third)
    }
    // 同一本子可能有多条章节进度，只显示最近的一条
    val visible = remember(history, rules) {
        val filtered = if (rules.isEmpty()) history else history.filterNot { e ->
            container.blockedTagsStore.isBlocked(e.comic.tags, e.comic.name, e.comic.author, rules)
        }
        filtered.groupBy { it.comic.id }.mapValues { (_, v) -> v.first() }.values
            .sortedByDescending { it.updatedAt }
    }

    Box(Modifier.fillMaxSize()) {
        if (history.isEmpty()) {
            EmptyBox("还没有阅读历史")
        } else if (visible.isEmpty()) {
            EmptyBox("所有阅读历史都被屏蔽规则过滤")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // v27.5 稳定性加固：用 itemsIndexed + 复合 key，避免 comic.id 为空或重复时
                // LazyColumn 抛 IllegalArgumentException: Key "xxx" was already used 崩溃
                itemsIndexed(
                    visible,
                    key = { i, e -> e.comic.id.ifBlank { "idx_$i" } },
                    contentType = { _, _ -> "history_row" },
                ) { _, entry ->
                    // v27.5 性能修复：onClick/onRemove 用 remember 缓存，避免 HistoryRow 因 lambda 不稳定无法跳过重组
                    val onClick = remember(entry, navController) {
                        { navController.navigate(Routes.detail(entry.comic.id)) }
                    }
                    val onRemove = remember(entry, scope, container, snackbar) {
                        {
                            // v27.5 稳定性加固：DataStore 写盘可能抛 IOException，未捕获会冒泡到
                            // Thread.uncaughtExceptionHandler → CrashHandler → 杀进程。包 try-catch。
                            scope.launch {
                                try {
                                    container.historyStore.remove(entry.comic.id)
                                    snackbar.showSnackbar("已删除")
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    com.jmreader.core.Logger.w("History", "删除历史失败: ${com.jmreader.core.Logger.brief(e)}")
                                    snackbar.showSnackbar("删除失败")
                                }
                            }
                            Unit
                        }
                    }
                    HistoryRow(
                        entry = entry,
                        onClick = onClick,
                        onRemove = onRemove,
                    )
                }
                // 底部清空按钮 + 条数提示
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "共 ${visible.size} 条阅读历史",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清空全部")
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空阅读历史") },
            text = { Text("将清除全部 ${history.size} 条阅读进度记录。\n\n此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    // v27.5 稳定性加固：DataStore clear 可能抛 IOException，包 try-catch。
                    scope.launch {
                        try {
                            container.historyStore.clear()
                            snackbar.showSnackbar("已清空")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            com.jmreader.core.Logger.w("History", "清空历史失败: ${com.jmreader.core.Logger.brief(e)}")
                            snackbar.showSnackbar("清空失败")
                        }
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
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = remember(entry.comic.cover, ctx) {
                // v27.5 性能修复：移除 crossfade(true)，避免滑动时每个封面跑 300ms alpha 动画抢主线程
                ImageRequest.Builder(ctx)
                    .data(entry.comic.cover)
                    .build()
            },
            contentDescription = entry.comic.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(56.dp)
                .height(80.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.comic.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "上次：${entry.chapterTitle} · 第 ${entry.page + 1} 页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                fmt.format(Date(entry.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
