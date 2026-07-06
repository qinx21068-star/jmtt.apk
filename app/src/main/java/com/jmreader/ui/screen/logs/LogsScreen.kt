@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.logs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmreader.core.Logger
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志级别筛选：选 ALL 看全部，选 E 只看错误（便于排查闪退）。 */
private enum class LogFilter(val label: String, val match: (Logger.Level) -> Boolean) {
    ALL("全部", { true }),
    W("警告+", { it == Logger.Level.W || it == Logger.Level.E }),
    E("仅错误", { it == Logger.Level.E }),
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val entries by Logger.recent.collectAsState()
    val state = rememberLazyListState()
    val ctx = LocalContext.current
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val shown = remember(entries, filter) {
        if (filter == LogFilter.ALL) entries.reversed()
        else entries.reversed().filter { filter.match(it.level) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日志 (${shown.size}/${entries.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = Logger.export()
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "JMReader 日志")
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                        }
                        ctx.startActivity(android.content.Intent.createChooser(send, "分享日志"))
                    }) { Icon(Icons.Outlined.Share, contentDescription = "分享") }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "清空")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).background(Color(0xFF1E1E1E))) {
            // 级别筛选 + 日志文件路径（便于定位问题）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) },
                    )
                }
            }
            Logger.filePath()?.let { path ->
                Text(
                    text = "文件：$path",
                    color = Color(0xFF757575),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(shown, key = { it.seq }) { e ->
                    LogRow(e, onCopy = { msg ->
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("log", msg))
                        scope.launch { snackbar.showSnackbar("已复制") }
                    })
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空日志") },
            text = { Text("将清除全部 ${entries.size} 条日志记录，并清空日志文件。\n\n此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    Logger.clear()
                    scope.launch { snackbar.showSnackbar("已清空") }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(e: Logger.Entry, onCopy: (String) -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val color = when (e.level) {
        Logger.Level.V, Logger.Level.D -> Color(0xFF9E9E9E)
        Logger.Level.I -> Color(0xFF8BC34A)
        Logger.Level.W -> Color(0xFFFFC107)
        Logger.Level.E -> Color(0xFFFF5252)
    }
    // 长按复制整条日志（含时间/级别/tag/消息），便于用户排查时贴到别处
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    val text = "${fmt.format(Date(e.time))} ${e.level.label} ${e.tag} ${e.message}"
                    onCopy(text)
                },
            ),
    ) {
        Row {
            Text(
                text = fmt.format(Date(e.time)),
                color = Color(0xFF757575),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = " ${e.level.label} ",
                color = color,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = e.tag,
                color = Color(0xFF82B1FF),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = e.message,
            color = Color(0xFFE0E0E0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
