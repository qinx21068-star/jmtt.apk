@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jmreader.ui.screen.comment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.outlined.LastPage
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.JmCommentDto
import com.jmreader.data.repository.Resource
import com.jmreader.ui.screen.forum.JmLinkedText
import com.jmreader.ui.screen.forum.LoadingShield
import kotlinx.coroutines.CancellationException

/**
 * 本子评论区：原生 Compose 渲染评论列表。
 *
 * v27.9：从 HTML 抓取（JmWebFetcher + jm365.work 重定向获取"无 CF 域名"，不稳定，
 * 经常"根本加载不出来"）改为 /forum JSON API（同 reqApi 通道：token 鉴权 + AES 解密
 * + 域名轮换，与搜索/详情同款，已验证稳定）。与 jasmine 客户端的 forum 方法一致。
 *
 * 数据来源：[com.jmreader.data.repository.JMRepository.forum]，
 * mode="manhua", aid=本子ID, page=页码。返回结构化评论列表（含嵌套回复）。
 *
 * JM 编号可点跳转详情页（复用 [JmLinkedText]）。
 */
@Composable
fun CommentsScreen(
    albumId: String,
    onBack: () -> Unit,
    onOpenComic: (String) -> Unit,
    container: AppContainer,
) {
    var comments by remember { mutableStateOf<List<JmCommentDto>>(emptyList()) }
    // 翻页状态用 rememberSaveable，从详情页返回时恢复页码
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(1) }
    var totalPages by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(1) }
    var totalCount by remember { mutableIntStateOf(0) }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 触发重新加载的计数器（loadPage 自增，LaunchedEffect 监听）
    var loadTrigger by remember { mutableIntStateOf(0) }

    fun loadPage(target: Int) {
        page = target
        loading = true
        error = null
        loadTrigger++
    }

    // v27.9：通过 /forum JSON API 加载评论（替代 HTML 抓取）。
    // reqApi 内部已做域名轮换 + 自愈（拉取最新域名重试），这里无需额外重试/刷新域名。
    // 失败通常是网络断或会话过期，单次重试足够，避免无限退避让用户久等。
    LaunchedEffect(loadTrigger, page, albumId) {
        if (loadTrigger == 0) return@LaunchedEffect
        val target = page
        try {
            val r = container.repository.forum(mode = "manhua", aid = albumId, uid = null, page = target)
            when (r) {
                is Resource.Success -> {
                    comments = r.data.list
                    totalCount = r.data.total
                    // 总页数：仅在第 1 页时根据 total/list.size 计算（与 jasmine 一致），
                    // 避免末页 list.size 偏小导致 totalPages 被错误缩小。
                    if (target == 1) {
                        totalPages = if (r.data.total <= 0) 1
                        else {
                            val pageSize = if (r.data.list.isNotEmpty()) r.data.list.size else 10
                            (r.data.total + pageSize - 1) / pageSize
                        }
                    }
                    loading = false
                    if (r.data.list.isEmpty() && r.data.total == 0) {
                        error = null // 真的没评论，不是错误
                    }
                }
                is Resource.Error -> {
                    // 单次重试：网络抖动/域名瞬时故障
                    kotlinx.coroutines.delay(800L)
                    val r2 = container.repository.forum(mode = "manhua", aid = albumId, uid = null, page = target)
                    when (r2) {
                        is Resource.Success -> {
                            comments = r2.data.list
                            totalCount = r2.data.total
                            if (target == 1) {
                                totalPages = if (r2.data.total <= 0) 1
                                else {
                                    val pageSize = if (r2.data.list.isNotEmpty()) r2.data.list.size else 10
                                    (r2.data.total + pageSize - 1) / pageSize
                                }
                            }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loading = false
            error = e.message ?: e::class.simpleName ?: "未知错误"
        }
    }

    // 首次进入或从详情页返回时加载
    // v27.13：上次加载失败后返回也自动重载（之前 loadTrigger>0 就不重载，用户面对错误页必须手动重试）
    LaunchedEffect(Unit) {
        if (comments.isEmpty() && (loadTrigger == 0 || error != null)) loadPage(page.coerceAtLeast(1))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (totalCount > 0) "评论 ($totalCount)" else "评论",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { loadPage(page) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            // 内容区：只要 comments 非空就始终渲染列表，loading/error 用叠加层提示
            if (comments.isNotEmpty()) {
                CommentsListContent(
                    comments = comments,
                    page = page,
                    totalPages = totalPages,
                    onOpenComic = onOpenComic,
                    onPrev = { if (page > 1) loadPage(page - 1) },
                    onNext = { if (page < totalPages) loadPage(page + 1) },
                    onJumpToPage = { target -> if (target != page) loadPage(target) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (loading) {
                LoadingShield(
                    onAbort = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (error != null) {
                val err = error
                if (err != null) {
                    ErrorView(
                        message = err,
                        onRetry = { loadPage(page) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    EmptyView("暂无评论", Modifier.fillMaxSize())
                }
            } else {
                EmptyView("暂无评论", Modifier.fillMaxSize())
            }

            // 翻页时顶部进度条（保留旧评论可查看）
            if (loading && comments.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }

            // 翻页失败 banner
            val bannerErr = error
            if (bannerErr != null && comments.isNotEmpty()) {
                PageErrorBanner(
                    message = bannerErr,
                    onRetry = { loadPage(page) },
                    onDismiss = { error = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** 翻页失败时顶部叠加的 banner：保留旧评论可查看，提供"重试"和"忽略"按钮。 */
@Composable
private fun PageErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "翻页失败：$message",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRetry) { Text("重试") }
        TextButton(onClick = onDismiss) { Text("忽略") }
    }
}

// ============================ 评论列表 ============================

@Composable
private fun CommentsListContent(
    comments: List<JmCommentDto>,
    page: Int,
    totalPages: Int,
    onOpenComic: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showJumpDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(comments, key = { i, c -> c.cid.ifBlank { "idx_$i" } }) { _, c ->
            CommentCard(comment = c, onOpenComic = onOpenComic)
        }
        // 分页栏：首页 / 上一页 / 页码(可点跳页) / 下一页 / 末页
        item(key = "pagination") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onJumpToPage(1) },
                    enabled = page > 1,
                ) { Icon(Icons.Outlined.FirstPage, contentDescription = "第一页") }
                IconButton(onClick = onPrev, enabled = page > 1) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateBefore, contentDescription = "上一页")
                }
                Text(
                    "第 $page / $totalPages 页",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showJumpDialog = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                IconButton(onClick = onNext, enabled = page < totalPages) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = "下一页")
                }
                IconButton(
                    onClick = { onJumpToPage(totalPages) },
                    enabled = page < totalPages,
                ) { Icon(Icons.Outlined.LastPage, contentDescription = "最后一页") }
            }
        }
    }

    // 跳页对话框
    if (showJumpDialog) {
        PageJumpDialog(
            currentPage = page,
            totalPages = totalPages,
            onDismiss = { showJumpDialog = false },
            onJump = { target ->
                showJumpDialog = false
                onJumpToPage(target)
            },
        )
    }
}

/** 跳页对话框：输入页码跳转到指定页。 */
@Composable
private fun PageJumpDialog(
    currentPage: Int,
    totalPages: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    var input by remember { mutableStateOf(currentPage.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到页") },
        text = {
            Column {
                Text(
                    "共 $totalPages 页，当前第 $currentPage 页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("页码") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                input.toIntOrNull()?.let { n ->
                    val target = n.coerceIn(1, totalPages)
                    onJump(target)
                }
            }) { Text("跳转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CommentCard(comment: JmCommentDto, onOpenComic: (String) -> Unit) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("comment", comment.content),
                    )
                    android.widget.Toast.makeText(ctx, "已复制评论内容", android.widget.Toast.LENGTH_SHORT).show()
                },
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = com.jmreader.ui.theme.LocalCardElevation.current),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 头像：优先加载网络头像，失败兜底首字母圆圈
                com.jmreader.ui.components.UserAvatar(
                    avatar = comment.photo,
                    name = comment.nickname,
                    sizeDp = 32,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            comment.nickname.ifBlank { "匿名" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (comment.level > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Lv.${comment.level}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    // 副信息行：点赞数 + 时间
                    val metaText = buildString {
                        if (comment.likes > 0) append("♥ ${comment.likes}")
                        if (comment.addtime.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(comment.addtime)
                        }
                    }
                    if (metaText.isNotBlank()) {
                        Text(
                            metaText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (comment.content.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))
                JmLinkedText(
                    text = comment.content,
                    onOpenComic = onOpenComic,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    "(无内容)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 嵌套回复：内联展示（缩进 + 浅色背景）
            if (comment.replys.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                comment.replys.forEach { reply ->
                    ReplyItem(reply = reply, onOpenComic = onOpenComic)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** 嵌套回复项：缩进 + 浅色背景，与主评论区分。 */
@Composable
private fun ReplyItem(reply: JmCommentDto, onOpenComic: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.jmreader.ui.components.UserAvatar(
                avatar = reply.photo,
                name = reply.nickname,
                sizeDp = 24,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                reply.nickname.ifBlank { "匿名" },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (reply.addtime.isNotBlank()) {
                Text(
                    reply.addtime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (reply.content.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            JmLinkedText(
                text = reply.content,
                onOpenComic = onOpenComic,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ============================ 工具 ============================

@Composable
private fun EmptyView(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Comment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("加载失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(top = 8.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            onRetry?.let {
                androidx.compose.material3.Button(onClick = it) { Text("重试") }
            }
            androidx.compose.material3.OutlinedButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("error", message))
                android.widget.Toast.makeText(ctx, "错误已复制", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("复制错误") }
        }
    }
}
