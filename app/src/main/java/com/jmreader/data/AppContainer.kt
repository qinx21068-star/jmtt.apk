package com.jmreader.data

import android.content.Context
import com.jmreader.data.api.JMApi
import com.jmreader.data.api.NetworkFactory
import com.jmreader.data.api.direct.JmDirectClient
import com.jmreader.data.local.BlockedTagsStore
import com.jmreader.data.local.BrowseHistoryStore
import com.jmreader.data.local.ComicTagsCache
import com.jmreader.data.local.FavoritesStore
import com.jmreader.data.local.HistoryStore
import com.jmreader.data.local.SearchHistoryStore
import com.jmreader.data.local.SettingsStore
import com.jmreader.data.repository.JMRepository
import com.jmreader.core.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus

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

    /** v27.13：暴露 appContext 供 ReaderViewModel 预取图片用 */
    val applicationContext: Context get() = appContext

    /**
     * App 级协程 scope，生命周期与 App 进程一致。
     * 用于 VM 销毁后仍需完成的短任务（如阅读进度落盘），
     * 替代反模式 GlobalScope。比 viewModelScope 存活更久，进程退出前有机会完成。
     *
     * v27.5 稳定性加固：附加 CrashHandler.coroutineHandler 兜底未捕获异常。
     * 之前 viewModelScope.onCleared 内 launch 的进度保存任务，若 IO 失败会冒泡
     * 到 Thread.UncaughtExceptionHandler → 进程崩溃。加 CEH 后仅记日志不崩。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashHandler.coroutineHandler)

    val settingsStore = SettingsStore(appContext, appScope)
    val blockedTagsStore = BlockedTagsStore(appContext)
    /** 漫画 tags 持久化缓存：列表接口不返回 tags，用此缓存补全用于即时屏蔽 + 卡片显示。 */
    val comicTagsCache = ComicTagsCache(appContext, appScope)
    val favoritesStore = FavoritesStore(appContext, NetworkFactory.moshi, appScope)
    val historyStore = HistoryStore(appContext, NetworkFactory.moshi, appScope)
    val browseHistoryStore = BrowseHistoryStore(appContext, NetworkFactory.moshi, appScope)
    val searchHistoryStore = SearchHistoryStore(appContext)
    /** SauceNAO 以图搜图服务（无 key 也能用，限流更严） */
    val saucenaoService = com.jmreader.data.api.saucenao.SaucenaoService()
    val downloadManager = com.jmreader.data.download.DownloadManager(appContext, this)

    /** 直连客户端（单例，App 生命周期内复用，内部带 cookie/scramble 缓存）。 */
    val directClient: JmDirectClient by lazy { JmDirectClient(appContext, appScope) }

    // 后端模式相关（可选）
    private val _api = MutableStateFlow<JMApi?>(null)
    val api: StateFlow<JMApi?> = _api.asStateFlow()
    /** 当前后端 OkHttpClient，rebuildApi 时 shutdown 旧的避免线程/连接池泄漏。 */
    private var backendClient: okhttp3.OkHttpClient? = null

    /** 是否走后端模式：仅当用户配置了非空后端地址时为 true。 */
    suspend fun useBackend(): Boolean = settingsStore.settings.first().serverUrl.isNotBlank()

    /** 取后端 API（仅后端模式用）。直连模式抛错避免误用。 */
    suspend fun ensureApi(): JMApi {
        _api.value?.let { return it }
        val s = settingsStore.settings.first()
        val url = s.serverUrl
        if (url.isBlank()) {
            throw IllegalStateException("后端地址未配置：当前为直连模式，无需配置后端；如需切换到后端模式，请到「设置 → 后端地址」填写地址。")
        }
        // v27.15.2 自检修复：与 rebuildApi() 对齐，把代理配置一并传给 Retrofit client。
        // 之前只传 url 不传 proxy，用户配好代理后首次进入后端模式不走代理，
        // 弱网/被墙环境下表现为"明明配了代理，后端还是连不上"，需手动切换一次后端才生效。
        val built = NetworkFactory.build(url, s.proxy)
        backendClient = built.client
        _api.value = built.api
        return built.api
    }

    /** 当用户在后端切换地址后调用，强制重建 Retrofit。 */
    suspend fun rebuildApi() {
        val s = settingsStore.settings.first()
        val url = s.serverUrl
        // 关键修复：shutdown 旧 OkHttpClient 的连接池与 Dispatcher 线程池，
        // 避免每次切换后端地址都泄漏一个 client（含线程池/连接池），频繁切换后线程数飙升导致 OOM/ANR。
        val oldClient = backendClient
        backendClient = null
        if (url.isBlank()) {
            _api.value = null
        } else {
            // v27.5 #37：把代理设置一并传给 Retrofit client
            val built = NetworkFactory.build(url, s.proxy)
            backendClient = built.client
            _api.value = built.api
        }
        oldClient?.let { c ->
            appScope.launch {
                runCatching {
                    c.dispatcher.executorService.shutdown()
                    c.connectionPool.evictAll()
                }
            }
        }
        // v27.5 #37：同步把代理应用到直连客户端（默认模式）
        runCatching { directClient.applyProxy(s.proxy) }
    }

    val repository = JMRepository(this)

    /**
     * v27.5：清除 Coil 图片缓存（内存 + 磁盘）。
     * 在 IO 线程执行避免阻塞 UI。
     */
    fun clearImageCache() {
        appScope.launch {
            runCatching {
                val loader = coil.Coil.imageLoader(appContext)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
        }
    }

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
