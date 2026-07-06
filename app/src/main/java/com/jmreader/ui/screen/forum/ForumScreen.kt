@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jmreader.ui.screen.forum

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jmreader.core.Logger
import com.jmreader.data.api.forum.ForumPost
import com.jmreader.data.api.forum.ForumRepository
import com.jmreader.data.api.forum.ForumThread
import com.jmreader.data.api.forum.ThreadPage

/**
 * 讨论区：原生 Compose UI 渲染帖子/回复，**不直接显示 JM 网页**。
 *
 * **绕过 Cloudflare 的关键**：
 * v12 用"隐藏 WebView"（没加到视图树）抓 HTML，CF 判定为机器人一直不放行。
 * 这里把 WebView **通过 AndroidView 加到 Compose 视图树**（CF 检测时
 * WebView 是 visible 的），但用一个不透明蒙层盖住，用户看不到 JM 网页。
 * onPageFinished 后用 JS 轮询检测真实页面（非 CF 挑战页），拿到 HTML 后
 * 用 Jsoup 解析，**立即销毁 WebView 切换到原生 UI**。
 *
 * 如果 CF 卡住（需要点 checkbox），用户可点"手动验证"按钮露出 WebView
 * 完成交互，交互后自动提取 HTML 切回原生 UI。
 *
 * cf_clearance cookie 拿到后保存在 CookieManager，后续 WebView 请求会
 * 自动带上，CF 不会再挑战（30 分钟内）。
 *
 * **JM 编号跳转**：回复正文里的 JM 编号用 AnnotatedString 高亮 + clickable，
 * 点击调 onOpenComic 跳 App 内详情页。
 */
sealed class ForumMode {
    data object List : ForumMode()
    data class Detail(val thread: ForumThread) : ForumMode()
}

@Composable
fun ForumScreen(
    forumUrl: String,
    onBack: () -> Unit,
    onOpenComic: (String) -> Unit,
    container: com.jmreader.data.AppContainer,
) {
    val context = LocalContext.current

    var mode by remember { mutableStateOf<ForumMode>(ForumMode.List) }
    var threads by remember { mutableStateOf<List<ForumThread>>(emptyList()) }
    var detailPage by remember { mutableStateOf<ThreadPage?>(null) }

    // WebView 加载状态
    var webviewLoading by remember { mutableStateOf(true) }
    var webviewError by remember { mutableStateOf<String?>(null) }
    // 最近一次拿到的 HTML 原文（解析失败时让用户复制发给我排错）
    var lastHtml by remember { mutableStateOf("") }
    var manualVerify by remember { mutableStateOf(false) }
    var currentLoadUrl by remember { mutableStateOf(forumUrl) }
    // 待执行的 HTML 解析回调（拿到 HTML 后调用）
    var pendingParser by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 加载论坛 URL：设置当前 URL + 解析回调，触发 WebView 加载
    fun loadForumUrl(url: String, parser: (String) -> Unit) {
        currentLoadUrl = url
        pendingParser = parser
        webviewLoading = true
        webviewError = null
        manualVerify = false
        webViewRef?.loadUrl(url)
    }

    fun refreshList() {
        threads = emptyList()
        loadForumUrl(forumUrl) { html ->
            lastHtml = html
            threads = ForumRepository.parseThreadList(html)
            webviewLoading = false
            if (threads.isEmpty()) {
                webviewError = diagnoseHtml(html, "帖子")
            }
        }
    }

    fun openThread(thread: ForumThread) {
        mode = ForumMode.Detail(thread)
        detailPage = null
        loadForumUrl("https://jmcomic-zzz.one/forum/threads/${thread.id}/") { html ->
            lastHtml = html
            detailPage = ForumRepository.parseThreadDetail(html, thread.id, 1)
            webviewLoading = false
            if (detailPage == null || detailPage!!.posts.isEmpty()) {
                webviewError = diagnoseHtml(html, "回复")
            }
        }
    }

    fun backToList() {
        mode = ForumMode.List
        detailPage = null
        webviewLoading = false  // 列表已加载，不需要再请求
    }

    // 首次进入自动加载列表
    LaunchedEffect(Unit) {
        if (threads.isEmpty() && pendingParser == null) refreshList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (mode) {
                            is ForumMode.List -> "讨论区"
                            is ForumMode.Detail -> (mode as ForumMode.Detail).thread.title
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (mode is ForumMode.Detail) backToList() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        when (mode) {
                            is ForumMode.List -> refreshList()
                            is ForumMode.Detail -> openThread((mode as ForumMode.Detail).thread)
                        }
                    }) { Icon(Icons.Outlined.Refresh, contentDescription = "刷新") }
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentLoadUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }) { Icon(Icons.Outlined.OpenInBrowser, contentDescription = "用浏览器打开") }
                },
            )
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            // ===== 底层：WebView（加到视图树让 CF 放行，加载完后被原生 UI 覆盖）=====
            AndroidView(
                factory = { ctx ->
                    createForumWebView(
                        context = ctx,
                        initialUrl = currentLoadUrl,
                        onRealPageLoaded = { html ->
                            // 只处理一次：pendingParser 不为 null 时处理，然后清空。
                            // 这样即使 JS 轮询多次回调（onHtml + onTimeout）也只处理第一次。
                            val parser = pendingParser
                            pendingParser = null
                            parser?.invoke(html)
                        },
                        onError = { msg ->
                            webviewError = msg
                            webviewLoading = false
                        },
                    ).also { webViewRef = it }
                },
                update = { wv ->
                    // currentLoadUrl 变化时由 loadForumUrl() 直接调 wv.loadUrl，
                    // 这里不需要做任何事（update 在重组时被调用，但加载由事件驱动）。
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 离开页面时销毁 WebView
            DisposableEffect(Unit) {
                onDispose {
                    webViewRef?.let { wv ->
                        wv.stopLoading()
                        wv.webChromeClient = null
                        wv.webViewClient = WebViewClient()
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                    }
                    webViewRef = null
                }
            }

            // ===== 中层：加载蒙层（WebView 加载中时遮住 JM 网页）=====
            if (webviewLoading && !manualVerify) {
                LoadingShield(
                    onManualVerify = { manualVerify = true },
                    onAbort = {
                        if (mode is ForumMode.Detail) backToList() else onBack()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ===== 上层：原生 UI（加载完成后覆盖 WebView）=====
            if (!webviewLoading) {
                webviewError?.let { err ->
                    ErrorBox(
                        message = err,
                        onRetry = {
                            when (mode) {
                                is ForumMode.List -> refreshList()
                                is ForumMode.Detail -> openThread((mode as ForumMode.Detail).thread)
                            }
                        },
                        onCopyHtml = if (lastHtml.isNotBlank()) {
                            { copyHtml(context, lastHtml) }
                        } else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: when (mode) {
                    is ForumMode.List -> {
                        if (threads.isNotEmpty()) {
                            ForumListContent(
                                threads = threads,
                                onOpenThread = { openThread(it) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            EmptyBox("暂无帖子", Modifier.fillMaxSize())
                        }
                    }
                    is ForumMode.Detail -> {
                        val page = detailPage
                        if (page != null && page.posts.isNotEmpty()) {
                            ForumDetailContent(
                                page = page,
                                onOpenComic = onOpenComic,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            EmptyBox("暂无回复", Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

// ============================ WebView 创建 ============================

/**
 * 创建论坛 WebView，配置 JS + 桌面 UA + cookie，注入 JS 轮询脚本检测
 * CF 挑战页 → 等真实页面 → 回调 HTML。
 *
 * 关键：这个 WebView 必须通过 AndroidView 加到 Compose 视图树才会被
 * CF 认为是 visible 的（v12 的隐藏 WebView 没加到视图树，CF 一直不放行）。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createForumWebView(
    context: android.content.Context,
    initialUrl: String,
    onRealPageLoaded: (String) -> Unit,
    onError: (String) -> Unit,
): WebView {
    val webView = WebView(context)
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = true
    settings.allowContentAccess = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    settings.userAgentString =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // JS 接口：JS 轮询脚本通过 AndroidBridge.onHtml / onTimeout 回传。
    // @JavascriptInterface 的回调在 WebView 的内部线程（非主线程），
    // 所以 post 到主线程再调上层回调（上层会修改 Compose state）。
    class Bridge {
        @JavascriptInterface
        fun onHtml(html: String, isChallenge: Boolean) {
            if (isChallenge) {
                Logger.d("Forum", "JS 轮询仍是 CF 挑战页，继续等待")
                return
            }
            Logger.i("Forum", "JS 轮询拿到真实页面（长度=${html.length}）")
            mainHandler.post { onRealPageLoaded(html) }
        }

        @JavascriptInterface
        fun onTimeout(html: String) {
            Logger.w("Forum", "JS 轮询超时，HTML 长度=${html.length}")
            mainHandler.post { onRealPageLoaded(html) }
        }
    }
    webView.addJavascriptInterface(Bridge(), "AndroidBridge")

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            CookieManager.getInstance().flush()
            // 注入 JS 轮询脚本：检测 CF 挑战页 → 等真实页面 → 回传 HTML
            view?.evaluateJavascript(POLL_SCRIPT, null)
        }

        override fun onReceivedError(
            view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                val msg = "WebView 加载失败: ${error?.description}"
                mainHandler.post { onError(msg) }
            }
        }
    }

    webView.loadUrl(initialUrl)
    return webView
}

/**
 * JS 轮询脚本：onPageFinished 后注入，检测 CF 挑战页 → 等真实页面 → 回传 HTML。
 * 首次延迟 1.2s，之后每 1.5s 检查一次，最多 25 次（约 38s）。
 */
private const val POLL_SCRIPT = """
(function(){
    var MAX = 25;
    var INTERVAL = 1500;
    var n = 0;
    function isChallenge() {
        try {
            var title = (document.title || '').toLowerCase();
            var html = document.documentElement.outerHTML;
            var body = (document.body && document.body.innerText) ? document.body.innerText.substring(0, 1500) : '';
            if (title.indexOf('just a moment') >= 0) return true;
            if (html.indexOf('challenge-form') >= 0) return true;
            if (html.indexOf('cf-browser-verification') >= 0) return true;
            if (html.indexOf('cf_chl_opt') >= 0) return true;
            if (html.indexOf('cf-turnstile') >= 0) return true;
            if (html.indexOf('turnstile-wrapper') >= 0) return true;
            if (html.indexOf('cf-mitigated') >= 0) return true;
            if (body.indexOf('Verifying you are human') >= 0) return true;
            if (body.indexOf('Checking your browser') >= 0) return true;
            if (body.indexOf('验证您是真人') >= 0) return true;
            // 真实论坛页一定有 XenForo 标志
            if (html.indexOf('structItem') < 0 && html.indexOf('message-') < 0
                && html.indexOf('p-title-value') < 0 && html.indexOf('bbWrapper') < 0) return true;
            return false;
        } catch(e) { return false; }
    }
    function extract() {
        try { return document.documentElement.outerHTML; } catch(e) { return ''; }
    }
    function poll() {
        n++;
        if (!isChallenge()) {
            try { AndroidBridge.onHtml(extract(), false); } catch(e) {}
            return;
        }
        try { AndroidBridge.onHtml('', true); } catch(e) {}
        if (n >= MAX) {
            try { AndroidBridge.onTimeout(extract()); } catch(e) {}
            return;
        }
        setTimeout(poll, INTERVAL);
    }
    setTimeout(poll, 1200);
})();
"""

// ============================ 加载蒙层 ============================

/**
 * 加载蒙层：遮住底层 WebView（不让用户看到 JM 网页），显示加载状态。
 * 提供"手动验证"按钮（CF 卡住时露出 WebView 让用户完成交互）和"返回"按钮。
 */
@Composable
private fun LoadingShield(
    onManualVerify: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 不透明背景，完全遮住底层 WebView
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Text(
                "正在加载讨论区…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "首次访问需要通过 Cloudflare 验证，请稍等\n如果长时间无响应，可点击下方按钮手动验证",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                TextButton(onClick = onManualVerify) { Text("手动验证") }
                TextButton(onClick = onAbort) { Text("返回") }
            }
        }
    }
}

// ============================ 原生帖子列表 ============================

@Composable
private fun ForumListContent(
    threads: List<ForumThread>,
    onOpenThread: (ForumThread) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(threads, key = { it.id }) { thread ->
            ThreadCard(thread = thread, onClick = { onOpenThread(thread) })
        }
    }
}

@Composable
private fun ThreadCard(thread: ForumThread, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thread.forumName.isNotBlank()) {
                    Text(
                        thread.forumName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    thread.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(thread.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (thread.replyCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Chat, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("${thread.replyCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (thread.lastReplyTime.isNotBlank()) {
                    Spacer(Modifier.weight(1f))
                    Text(thread.lastReplyTime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ============================ 原生帖子详情 ============================

@Composable
private fun ForumDetailContent(
    page: ThreadPage,
    onOpenComic: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Forum, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(page.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        items(page.posts, key = { it.id }) { post ->
            PostCard(post = post, onOpenComic = onOpenComic)
        }
        item {
            Text(
                "共 ${page.totalPages} 页（当前第 ${page.currentPage} 页）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PostCard(post: ForumPost, onOpenComic: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${post.floor}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text(post.author, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.weight(1f))
                if (post.time.isNotBlank()) {
                    Text(post.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (post.content.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))
                JmLinkedText(
                    text = post.content,
                    onOpenComic = onOpenComic,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ============================ JM 编号可点链接 ============================

private fun linkifyJm(text: String): AnnotatedString {
    val jmRegex = Regex("""\b(JM|jm)(\d{4,8})\b""")
    return buildAnnotatedString {
        var lastEnd = 0
        for (m in jmRegex.findAll(text)) {
            append(text.substring(lastEnd, m.range.first))
            val id = m.groupValues[2]
            pushStringAnnotation(tag = "JM_LINK", annotation = id)
            withStyle(SpanStyle(color = Color.White, background = Color(0xFF1976D2), fontWeight = FontWeight.SemiBold)) {
                append(m.value)
            }
            pop()
            lastEnd = m.range.last + 1
        }
        if (lastEnd < text.length) append(text.substring(lastEnd))
    }
}

@Composable
private fun JmLinkedText(
    text: String,
    onOpenComic: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(text) { linkifyJm(text) }
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations("JM_LINK", offset, offset)
                .firstOrNull()?.let { onOpenComic(it.item) }
        },
    )
}

// ============================ 通用组件 ============================

@Composable
private fun EmptyBox(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    onCopyHtml: (() -> Unit)? = null,
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
                .verticalScroll(rememberScrollState()),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            onRetry?.let {
                androidx.compose.material3.Button(onClick = it) { Text("重试") }
            }
            androidx.compose.material3.OutlinedButton(onClick = {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", message))
                android.widget.Toast.makeText(ctx, "错误已复制", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("复制错误") }
            onCopyHtml?.let {
                androidx.compose.material3.TextButton(onClick = it) { Text("复制HTML") }
            }
        }
    }
}

/** 把 HTML 原文复制到剪贴板，方便用户发给我排错（HTML 可能很大，截断到 50000 字符）。 */
private fun copyHtml(context: android.content.Context, html: String) {
    val snippet = if (html.length > 50000) html.take(50000) + "\n<!-- truncated -->" else html
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("forum_html", snippet))
    android.widget.Toast.makeText(context, "HTML 已复制（${snippet.length} 字符）", android.widget.Toast.LENGTH_LONG).show()
}

/**
 * 解析为空时生成诊断信息，区分 CF 挑战页 / 登录页 / 论坛改版。
 */
private fun diagnoseHtml(html: String, what: String): String {
    if (html.isBlank()) return "未获取到页面内容（HTML 为空）"
    return try {
        val doc = org.jsoup.Jsoup.parse(html)
        val title = doc.title().trim().ifBlank { "(无标题)" }
        val bodyText = run {
            val raw = doc.body()?.text()?.take(400)?.trim()
            if (raw.isNullOrBlank()) "(无正文)" else raw
        }
        val htmlLower = html.lowercase()
        val isCfChallenge = title.lowercase().contains("just a moment")
            || htmlLower.contains("challenge-form")
            || htmlLower.contains("cf-browser-verification")
            || htmlLower.contains("cf_chl_opt")
            || htmlLower.contains("cf-turnstile")
            || htmlLower.contains("turnstile-wrapper")
            || htmlLower.contains("cf-mitigated")
            || bodyText.contains("Verifying you are human")
            || bodyText.contains("验证您是真人")

        val hint = when {
            isCfChallenge ->
                "Cloudflare 挑战页未通过。请点击右上角刷新重试，或点'手动验证'完成 CF 交互。"
            html.length < 3000 ->
                "HTML 内容过短（${html.length} 字符），可能是中间页/错误页/登录页。"
            else ->
                "未检测到 CF 挑战页，但选择器未匹配到 $what。可能是论坛改版。"
        }
        "$hint\n\nHTML 长度: ${html.length}\n标题: $title\n正文预览: $bodyText"
    } catch (e: Exception) {
        "未解析到$what，HTML 长度 ${html.length}，诊断失败: ${e.message}"
    }
}
