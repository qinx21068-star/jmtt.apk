package com.jmreader.data

import android.content.Context
import com.jmreader.data.api.JMApi
import com.jmreader.data.api.NetworkFactory
import com.jmreader.data.api.direct.JmDirectClient
import com.jmreader.data.local.BlockedTagsStore
import com.jmreader.data.local.BrowseHistoryStore
import com.jmreader.data.local.FavoritesStore
import com.jmreader.data.local.HistoryStore
import com.jmreader.data.local.SearchHistoryStore
import com.jmreader.data.local.SettingsStore
import com.jmreader.data.repository.JMRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 手动依赖容器：App 启动时创建一次，避免引入 Hilt/KSP，降低构建复杂度。
 *
 * 两种工作模式：
 * - 直连模式（默认）：不依赖任何后端，直接调用禁漫移动端 API + 本地解密分割图。
 *   只需一部手机即可使用，等价于官方/其他第三方客户端的体验。
 * - 后端模式（可选）：用户在设置里填了后端地址则走 Python backend（jmcomic 库）。
 *   适合需要服务端整本下载、Cookie 共享等高级场景。
 *
 * 注意：构造时取 [Context.getApplicationContext] 持有 Application 上下文，
 * 避免持有 Activity 上下文造成 Activity 泄漏（容器生命周期比任何 Activity 都长）。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val blockedTagsStore = BlockedTagsStore(appContext)
    val favoritesStore = FavoritesStore(appContext, NetworkFactory.moshi)
    val historyStore = HistoryStore(appContext, NetworkFactory.moshi)
    val browseHistoryStore = BrowseHistoryStore(appContext, NetworkFactory.moshi)
    val searchHistoryStore = SearchHistoryStore(appContext)
    /** SauceNAO 以图搜图服务（无 key 也能用，限流更严） */
    val saucenaoService = com.jmreader.data.api.saucenao.SaucenaoService()
    val downloadManager = com.jmreader.data.download.DownloadManager(appContext, this)

    /** 直连客户端（单例，App 生命周期内复用，内部带 cookie/scramble 缓存）。 */
    val directClient: JmDirectClient by lazy { JmDirectClient() }

    // 后端模式相关（可选）
    private val _api = MutableStateFlow<JMApi?>(null)
    val api: StateFlow<JMApi?> = _api.asStateFlow()

    /** 是否走后端模式：仅当用户配置了非空后端地址时为 true。 */
    suspend fun useBackend(): Boolean = settingsStore.settings.first().serverUrl.isNotBlank()

    /** 取后端 API（仅后端模式用）。直连模式抛错避免误用。 */
    suspend fun ensureApi(): JMApi {
        _api.value?.let { return it }
        val url = settingsStore.settings.first().serverUrl
        if (url.isBlank()) {
            throw IllegalStateException("后端地址未配置：当前为直连模式，无需配置后端；如需切换到后端模式，请到「设置 → 后端地址」填写地址。")
        }
        val built = NetworkFactory.build(url)
        _api.value = built
        return built
    }

    /** 当用户在后端切换地址后调用，强制重建 Retrofit。 */
    suspend fun rebuildApi() {
        val url = settingsStore.settings.first().serverUrl
        // 清空地址时直接置空缓存，回到直连模式
        _api.value = if (url.isBlank()) null else NetworkFactory.build(url)
    }

    val repository = JMRepository(this)

    /** 论坛 Repository：解析禁漫网页端论坛 HTML（Jsoup），HTML 由 forumHtmlFetcher 通过 WebView 抓取（绕 Cloudflare）。 */
    val forumRepository = com.jmreader.data.api.forum.ForumRepository

    /** 论坛 HTML 抓取器：用隐藏 WebView 加载论坛页面拿 HTML（绕 Cloudflare JS 挑战）。 */
    val forumHtmlFetcher = com.jmreader.data.api.forum.ForumHtmlFetcher(appContext)

    /**
     * 连通性检查：
     * - 直连模式：探测禁漫 API 域名是否可达（发一个轻量搜索请求）
     * - 后端模式：探测后端 /api/health
     */
    suspend fun healthCheck(): Pair<Boolean, String> {
        val backend = useBackend()
        return if (backend) {
            try {
                val r = ensureApi().health()
                if (r.isSuccessful) {
                    val body = r.body()
                    val ok = body?.get("ok") == true
                    val jmcomic = body?.get("jmcomic") == true
                    if (ok && jmcomic) true to "后端正常，jmcomic 已就绪"
                    else if (ok) true to "后端可达，但 jmcomic 库未安装"
                    else false to "后端返回异常"
                } else {
                    false to "后端返回 HTTP ${r.code()}"
                }
            } catch (e: Throwable) {
                com.jmreader.core.Logger.e("App", "健康检查失败", e)
                false to com.jmreader.core.Logger.brief(e)
            }
        } else {
            // 直连模式：发一个最小搜索请求验证 API 可达 + 加解密正确
            try {
                val r = directClient.search("", 1, "latest", "all")
                true to "直连禁漫成功（域名: ${directClient.currentDomain()}），共 ${r.items.size} 条"
            } catch (e: Throwable) {
                com.jmreader.core.Logger.e("App", "直连健康检查失败", e)
                false to "直连禁漫失败：${com.jmreader.core.Logger.brief(e)}"
            }
        }
    }
}
