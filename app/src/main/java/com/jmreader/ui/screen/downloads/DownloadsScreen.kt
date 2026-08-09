@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.downloads

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jmreader.data.AppContainer
import com.jmreader.data.download.DownloadStatus
import com.jmreader.data.download.DownloadTask
import com.jmreader.ui.components.EmptyBox
import com.jmreader.ui.nav.Routes
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(container: AppContainer, navController: NavController) {
    val tasks by container.downloadManager.tasks.collectAsState()
    // v27.5 性能优化：用 cachedSnapshot 作为初始值，避免 null → 默认 → 真实 两轮重组
    val settings by container.settingsStore.settings.collectAsState(initial = container.settingsStore.cachedSnapshot)
    val localSearchEnabled = settings.localSearchEnabled
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showClearAll by remember { mutableStateOf(false) }
    // 单条删除确认：记录待删除的任务，确认后才真正删除
    var deleteTarget by remember { mutableStateOf<DownloadTask?>(null) }
    // v27.5 #19 本地搜索关键词
    var searchQuery by remember { mutableStateOf("") }

    // v27.5 #17 SAF 路径选择器：用户选目录后持久化 URI 权限并写入 settings
    val saFLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            // 持久化 URI 读写权限，避免重启后失去访问
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                ctx.contentResolver.takePersistableUriPermission(uri, flags)
            }
            scope.launch {
                container.settingsStore.setDownloadDirUri(uri.toString())
                snackbar.showSnackbar("已设置下载路径，下载完成后将自动复制到外部存储")
            }
        }
    }

    // v27.5 #19 本地搜索：按标题/作者/JM号过滤
    val visibleTasks = remember(tasks, searchQuery, localSearchEnabled) {
        if (!localSearchEnabled || searchQuery.isBlank()) tasks
        else {
            val q = searchQuery.trim()
            tasks.filter { t ->
                t.comic.name.contains(q, ignoreCase = true) ||
                    (t.comic.author?.contains(q, ignoreCase = true) ?: false) ||
                    t.comic.id.contains(q, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载管理 (${tasks.size})") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // v27.5 #17 SAF 路径选择：选外置 SD 卡目录
                    IconButton(onClick = { saFLauncher.launch(null) }) {
                        Icon(Icons.Outlined.Folder, contentDescription = "选择下载路径")
                    }
                    // 一键清空（带确认对话框，避免误触）
                    if (tasks.isNotEmpty()) {
                        IconButton(onClick = { showClearAll = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空全部")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // v27.5 #19 本地搜索框：开启开关且有任务时显示
            if (localSearchEnabled && tasks.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    placeholder = { Text("搜索已下载的本子（标题/作者/JM号）") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清空")
                            }
                        }
                    },
                    singleLine = true,
                )
            }
            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    EmptyBox("还没有下载任务\n在漫画详情页点击「下载」")
                }
            } else if (visibleTasks.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    EmptyBox("没有匹配「$searchQuery」的下载")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // v27.5 稳定性加固：用 itemsIndexed + 复合 key，避免 comic.id 为空或重复时
                    // LazyColumn 抛 IllegalArgumentException: Key "xxx" was already used 崩溃
                    itemsIndexed(
                        visibleTasks,
                        key = { i, t -> t.comic.id.ifBlank { "idx_$i" } },
                        contentType = { _, _ -> "download_row" },
                    ) { _, task ->
                        // v27.5 性能修复：lambda 用 remember 缓存，避免每次重组新建 lambda
                        // 导致 DownloadItem 无法跳过重组
                        val onOpen = remember(task, navController) {
                            { navController.navigate(Routes.detail(task.comic.id)) }
                        }
                        val onRetry = remember(task, container, scope, snackbar) {
                            {
                                val ok = container.downloadManager.retry(task.comic)
                                scope.launch {
                                    snackbar.showSnackbar(if (ok) "已重新加入队列" else "无法重试：缺少详情数据，请到详情页重新下载")
                                }
                                Unit
                            }
                        }
                        val onDelete = remember(task) { { deleteTarget = task } }
                        DownloadItem(
                            task = task,
                            onOpen = onOpen,
                            onRetry = onRetry,
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("清空全部下载") },
            text = {
                Text(
                    buildString {
                        append("将删除全部 ${tasks.size} 个下载任务及其本地文件。\n\n")
                        val completed = tasks.count { it.status == DownloadStatus.COMPLETED }
                        if (completed > 0) append("其中 $completed 个已完成，离线阅读器将无法再读这些本子。\n")
                        append("\n此操作不可撤销。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearAll = false
                    tasks.forEach { container.downloadManager.remove(it.comic.id) }
                    scope.launch { snackbar.showSnackbar("已清空全部下载") }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text("取消") }
            },
        )
    }

    // 单条删除确认对话框
    deleteTarget?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除下载") },
            text = {
                Text(
                    buildString {
                        append("确认删除「${task.comic.name}」？\n\n")
                        if (task.status == DownloadStatus.COMPLETED) {
                            append("该本子已下载完成，删除后离线阅读器将无法再读。\n")
                        }
                        append("\n此操作不可撤销。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = task.comic.name
                    deleteTarget = null
                    container.downloadManager.remove(task.comic.id)
                    scope.launch { snackbar.showSnackbar("已删除：$name") }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DownloadItem(
    task: DownloadTask,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.comic.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val statusText = when (task.status) {
                    DownloadStatus.QUEUED -> "等待中"
                    DownloadStatus.DOWNLOADING -> "下载中 ${task.doneChapters}/${task.totalChapters}"
                    DownloadStatus.COMPLETED -> "已完成 ${task.totalChapters} 章"
                    DownloadStatus.FAILED -> buildString {
                        append("下载失败")
                        if (task.failedImages > 0) append("（${task.failedImages} 张图失败）")
                    }
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (task.status) {
                        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.QUEUED) {
                    Box(Modifier.padding(top = 6.dp)) {
                        LinearProgressIndicator(
                            progress = { task.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            // 失败任务显示重试按钮
            if (task.status == DownloadStatus.FAILED) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重试")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除")
            }
        }
    }
}
