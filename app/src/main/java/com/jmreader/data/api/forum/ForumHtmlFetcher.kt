package com.jmreader.data.api.forum

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jmreader.core.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 用隐藏的 WebView 抓取禁漫论坛页面 HTML。
 *
 * **为什么用 WebView 而不是 OkHttp**：
 * 禁漫网页端论坛受 Cloudflare 保护，纯 OkHttp 请求（即使带桌面 UA + 剥离
 * X-Requested-With）也会被 CF 的 JS 挑战拦截，返回 401/403 或挑战页。
 * WebView 能自动执行 Cloudflare 的 JS 挑战并维护 `cf_clearance` cookie，
 * 所以用 WebView 加载页面，等页面加载完成后通过 JS 提取
 * `document.documentElement.outerHTML`，再交给 [ForumRepository] 用 Jsoup 解析。
 *
 * **Cloudflare 挑战页处理（核心）**：
 * CF Turnstile 通过后通常用 JS 修改 DOM 显示真实内容，**不会**重新触发
 * [WebViewClient.onPageFinished]。所以不能只靠 onPageFinished 一次性提取，
 * 否则会拿到挑战页 HTML 给 Jsoup，自然解析不到帖子。
 *
 * 这里采用 **JS 内轮询策略**：onPageFinished 触发后注入一段轮询脚本，
 * 每 1.5 秒检查一次 `document.title` 和 body 文本，发现不再是挑战页
 * （或达到最大轮询次数）就回传 HTML 给 Kotlin。这样无论 CF 是重新加载
 * 页面还是原地修改 DOM，都能拿到真实内容。
 */
class ForumHtmlFetcher(private val context: Context) {

    /** 论坛域名池，某个失败时切下一个。 */
    private val domains = listOf(
        "https://18comic.vip",
        "https://18comic.org",
        "https://jmcomic.me",
    )

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 抓取指定路径的 HTML。
     * @param path 论坛路径，如 "/forum/forums/latest.2/" 或 "/forum/threads/123/"
     * @return 页面 HTML 字符串
     */
    suspend fun fetch(path: String): String = withTimeout(40_000) {
        // 遍历域名池，第一个成功就返回
        var lastError: Throwable? = null
        for (domain in domains) {
            val url = domain + path
            try {
                return@withTimeout fetchUrl(url)
            } catch (e: Exception) {
                Logger.w("Forum", "WebView 抓取失败 $url: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("所有域名均失败")
    }

    /**
     * 用临时 WebView 加载单个 URL，等加载完成后注入轮询脚本提取 HTML。
     * WebView 必须在主线程操作，这里用 suspendCancellableCoroutine 桥接。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchUrl(url: String): String = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            if (cont.isCompleted) return@post

            val webView = WebView(context)
            var finished = false
            var pollCount = 0
            val maxPolls = 20  // 20 * 1.5s = 30s 上限

            // JS 接口：JS 轮询脚本调用 AndroidBridge.onHtml(html, isChallenge, pollIndex) 回传
            class HtmlInterface {
                @android.webkit.JavascriptInterface
                fun onHtml(html: String, isChallenge: Boolean, pollIndex: Int) {
                    if (finished || cont.isCompleted) return
                    pollCount = pollIndex
                    if (isChallenge) {
                        Logger.d("Forum", "JS 轮询 #$pollIndex 仍是 CF 挑战页，继续等待")
                        // 脚本自己会 setTimeout 继续轮询；这里什么都不做
                        return
                    }
                    Logger.i("Forum", "JS 轮询 #$pollIndex 拿到真实页面（长度=${html.length}）")
                    finishWithHtml(html)
                }

                @android.webkit.JavascriptInterface
                fun onTimeout(html: String) {
                    if (finished || cont.isCompleted) return
                    Logger.w("Forum", "JS 轮询达到上限，返回最后 HTML（长度=${html.length}）")
                    finishWithHtml(html)
                }

                private fun finishWithHtml(html: String) {
                    if (finished) return
                    finished = true
                    mainHandler.post {
                        try { webView.destroy() } catch (_: Exception) {}
                    }
                    if (cont.isActive) cont.resume(html)
                }
            }

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.allowFileAccess = true
            webView.settings.allowContentAccess = true
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webView.settings.userAgentString = ua
            // 注入 JS 接口
            webView.addJavascriptInterface(HtmlInterface(), "AndroidBridge")

            // Cookie：复用单例 CookieManager，cf_clearance 跨请求保留
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            CookieManager.getInstance().flush()

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?,
                ): Boolean = false

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    // 注入 cookie 持久化（保存 cf_clearance）
                    CookieManager.getInstance().flush()
                    // 注入 JS 轮询脚本：CF Turnstile 通过后原地修改 DOM 不会触发
                    // 新的 onPageFinished，所以让 JS 自己轮询检测真实页面出现。
                    // 首次延迟 1.2s 让 CF JS 启动；之后每 1.5s 检查一次，最多 20 次。
                    view?.evaluateJavascript(POLL_SCRIPT, null)
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && !finished && !cont.isCompleted) {
                        finished = true
                        mainHandler.post {
                            try { webView.destroy() } catch (_: Exception) {}
                        }
                        if (cont.isActive) {
                            cont.resumeWithException(
                                RuntimeException("WebView 加载失败: ${error?.description}")
                            )
                        }
                    }
                }
            }

            // 取消时销毁 WebView，避免泄漏
            cont.invokeOnCancellation {
                mainHandler.post {
                    try { webView.destroy() } catch (_: Exception) {}
                }
            }

            // 加载 URL
            webView.loadUrl(url)
        }
    }

    companion object {
        /**
         * JS 轮询脚本：onPageFinished 后注入，作用是检测 CF 挑战页 → 等真实页面出现 → 回传 HTML。
         *
         * 流程：
         * 1. 首次延迟 1.2s（给 CF Turnstile JS 启动时间）
         * 2. 每 1.5s 检查一次：读 document.title / body.innerText / outerHTML
         * 3. 若仍含挑战页特征 → 调 onHtml(html, true, n) 通知 Kotlin 进度，继续轮询
         * 4. 若不再含挑战页特征 → 调 onHtml(html, false, n) 回传真实 HTML，Kotlin resume
         * 5. 达到 maxPolls（20 次，约 30s）仍挑战页 → 调 onTimeout(html) 回传当前 HTML 供诊断
         *
         * 挑战页检测特征（尽量全）：
         * - title 含 "just a moment" / "moment" / "请稍候"
         * - HTML 含 challenge-form / cf-browser-verification / cf_chl_opt / cf-turnstile / turnstile-wrapper / cf-mitigated
         * - body 文本含 "Verifying you are human" / "Checking your browser" / "验证您是真人" / "正在验证"
         */
        private val POLL_SCRIPT = """
(function(){
    var MAX_POLLS = 20;
    var INTERVAL = 1500;
    var n = 0;
    function isChallengePage() {
        try {
            var title = (document.title || '').toLowerCase();
            var html = document.documentElement.outerHTML;
            var bodyText = (document.body && document.body.innerText) ? document.body.innerText.substring(0, 1500) : '';
            if (title.indexOf('just a moment') >= 0) return true;
            if (title.indexOf('moment') >= 0 && bodyText.indexOf('cloudflare') >= 0) return true;
            if (html.indexOf('challenge-form') >= 0) return true;
            if (html.indexOf('cf-browser-verification') >= 0) return true;
            if (html.indexOf('cf_chl_opt') >= 0) return true;
            if (html.indexOf('cf-turnstile') >= 0) return true;
            if (html.indexOf('turnstile-wrapper') >= 0) return true;
            if (html.indexOf('cf-mitigated') >= 0) return true;
            if (bodyText.indexOf('Verifying you are human') >= 0) return true;
            if (bodyText.indexOf('Checking your browser') >= 0) return true;
            if (bodyText.indexOf('验证您是真人') >= 0) return true;
            if (bodyText.indexOf('正在验证') >= 0) return true;
            if (bodyText.indexOf('请稍候') >= 0 && bodyText.indexOf('cloudflare') >= 0) return true;
            // 额外：禁漫论坛真实页一定有 structItem 或 message 容器，否则也算未加载完
            if (html.indexOf('structItem') < 0 && html.indexOf('message-') < 0
                && html.indexOf('p-title-value') < 0 && html.indexOf('bbWrapper') < 0) {
                // 页面无任何 XenForo 标志，可能是中间页/错误页，仍判为"未就绪"
                return true;
            }
            return false;
        } catch(e) { return false; }
    }
    function extract() {
        try { return document.documentElement.outerHTML; }
        catch(e) { return ''; }
    }
    function poll() {
        n++;
        var ch = isChallengePage();
        if (!ch) {
            try { AndroidBridge.onHtml(extract(), false, n); } catch(e) {}
            return;
        }
        // 还是挑战页
        try { AndroidBridge.onHtml('', true, n); } catch(e) {}
        if (n >= MAX_POLLS) {
            try { AndroidBridge.onTimeout(extract()); } catch(e) {}
            return;
        }
        setTimeout(poll, INTERVAL);
    }
    setTimeout(poll, 1200);
})();
        """.trimIndent()
    }
}
