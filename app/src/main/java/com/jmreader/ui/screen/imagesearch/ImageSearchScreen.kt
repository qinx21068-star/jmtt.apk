package com.jmreader.ui.screen.imagesearch

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.jmreader.core.Logger

/**
 * 以图搜图：用 WebView 直接打开 SauceNAO 网站。
 *
 * 之前的实现走 SauceNAO 公开 API（无 key 限 4 次/30秒），用户反馈"用不了"
 * （限流频繁 + 没法输入图床 URL 选项），改为直接 WebView 加载 saucenao.com，
 * 让用户使用网页版完整功能（本地上传、URL、数据库选择等）。
 *
 * 关键点：
 * 1. **文件上传**：网页的 <input type="file"> 由 [WebChromeClient.onShowFileChooser]
 *    拦截，转发到系统文件选择器，选完图通过 [ValueCallback] 回传给 WebView。
 * 2. **CF Turnstile 兼容**：SauceNAO 用 Cloudflare Turnstile 人机验证。
 *    早期版本用 [WebViewClient.shouldInterceptRequest] 转发子请求以剥离
 *    X-Requested-With 头，但这破坏了 Turnstile 的 JS 执行环境（资源加载顺序、
 *    cookie 时序、子资源完整性校验异常），导致上传图片后 CF 一直转圈。
 *    SauceNAO 不是禁漫，X-Requested-With 头对它影响不大，因此这里
 *    **不拦截任何请求**，让 WebView 原生加载所有资源，Turnstile 能正常通过。
 * 3. **桌面 UA**：网页版 saucenao 桌面端布局更实用（结果列表更清晰）。
 * 4. **新窗口/外链**：搜图结果通常链接到 Pixiv/Danbooru 等，点击时用
 *    [shouldOverrideUrlLoading] 跳系统浏览器（避免在 WebView 里跳走回不来）。
 * 5. **兜底**：顶栏"用浏览器打开"按钮，CF 仍过不去时让用户用系统浏览器。
 */
private const val SAUCENAO_URL = "https://saucenao.com/"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSearchScreen(
    container: com.jmreader.data.AppContainer,
    navController: androidx.navigation.NavController,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // WebView 文件选择回调（点网页 <input type=file> 时由 onShowFileChooser 注入）
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // 系统图片选择器：选完图回传给 WebView 的 ValueCallback
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        val cb = filePathCallback
        filePathCallback = null
        if (cb == null) return@rememberLauncherForActivityResult
        if (uris.isEmpty()) {
            cb.onReceiveValue(null)
        } else {
            cb.onReceiveValue(uris.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("以图搜图") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        webView?.reload()
                        loading = true
                    }) { Icon(Icons.Outlined.Refresh, contentDescription = "刷新") }
                    // 兜底：WebView 内 CF 实在过不去时，让用户用系统浏览器
                    IconButton(onClick = {
                        val url = webView?.url ?: SAUCENAO_URL
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }) { Icon(Icons.Outlined.OpenInBrowser, contentDescription = "用浏览器打开") }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = DESKTOP_UA
                        // 关键：不设置 webViewClient 拦截请求，让 WebView 原生加载，
                        // CF Turnstile 才能正常执行 JS 完成验证。
                        // 只覆盖 shouldOverrideUrlLoading 处理外链跳转。
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?, request: WebResourceRequest?,
                            ): Boolean {
                                // saucenao 自身页面继续在 WebView 内加载，
                                // 其它域名（如 pixiv/danbooru 结果链接）交给系统浏览器
                                val host = request?.url?.host ?: return false
                                if (host.contains("saucenao.com")) return false
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    request.url,
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                                return true
                            }

                            override fun onPageStarted(
                                view: WebView?, url: String?, favicon: Bitmap?,
                            ) {
                                // 关键修复：WebView 内部导航（点链接、翻页）时重新显示 loading，
                                // 否则 loading 保持 false，用户看不到新加载在进行
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                webView: WebView?,
                                callback: ValueCallback<Array<Uri>>?,
                                params: FileChooserParams?,
                            ): Boolean {
                                // 取消上一次未完成的回调，避免 WebView 卡死
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = callback
                                return try {
                                    filePicker.launch("image/*")
                                    true
                                } catch (e: Exception) {
                                    Logger.e("ImageSearch", "无法启动文件选择器", e)
                                    filePathCallback = null
                                    false
                                }
                            }

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                // CF Turnstile 转圈时进度会卡在 90+，用 100 才算完成
                                if (newProgress >= 100) loading = false
                            }
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        loadUrl(SAUCENAO_URL)
                        webView = this
                    }
                },
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    // 关键：Composable 离开组合时销毁 WebView，避免 Activity context 泄漏 +
    // 后台 JS 继续执行耗电。WebView 不销毁会持有 Activity 引用导致内存泄漏。
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
            // 取消未完成的文件选择回调，避免回调到一个已销毁的 WebView
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }
}

// 保留旧的 VM 类文件结构占位，避免外部引用断裂（实际不再使用）。
// 已删除原 ImageSearchViewModel 与 SaucenaoResultCard —— 改用 WebView 后无需业务逻辑。
