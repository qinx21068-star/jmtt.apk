package com.jmreader.data.api.direct

import com.jmreader.core.Logger
import com.jmreader.data.dto.ChapterDto
import com.jmreader.data.dto.ChapterImagesDto
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.dto.JmCommentDto
import com.jmreader.data.dto.JmCommentPageDto
import com.jmreader.data.dto.PageResultDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 禁漫移动端 API 直连客户端。
 *
 * 移植自 jmcomic python 库的 JmApiClient，关键点：
 * - 每个 GET 请求带 header: token = md5(ts+secret), tokenparam = "ts,ver"
 * - 响应 json["data"] 是 base64+AES-ECB 密文，用 ts 解密得到真实 JSON
 * - /chapter_view_template 用 secret_2，且返回 HTML（解析 var scramble_id）
 * - 域名经常被墙，做轮换重试
 * - 移动端要求带 cookies（不校验内容），用内存 CookieJar 自动维护
 *
 * 不再依赖任何后端，App 单机即可工作。
 *
 * @param appContext 用于 cookie 持久化（登录态跨重启保留）
 */

/** 会话过期（401/403 或业务码 401/403）：所有域名返回相同，不轮换，上层应清登录态。 */
class SessionExpiredException(message: String) : RuntimeException(message)

/** 业务错误（如内容不存在）：所有域名返回相同，不轮换，直接抛给上层。 */
class BusinessException(message: String) : RuntimeException(message)

class JmDirectClient(
    private val appContext: android.content.Context,
    private val appScope: kotlinx.coroutines.CoroutineScope,
) {

    // ---- 域名池（禁漫移动端 API 域名，可轮换）----
    // 内置最新已知有效域名（2026-07 实测多客户端汇总），避免首次启动时若字节 CDN 不可达就没有可用域名。
    // 禁漫会定期换域，启动时及请求全失败时会通过 [refreshApiDomains] 动态拉取最新域名覆盖此列表。
    // 用户也可在「设置 → 域名管理」手动增删（持久化于 SettingsStore，启动时合并进来）。
    private val apiDomains = mutableListOf(
        "www.cdnhjk.net",
        "www.cdngwc.cc",
        "www.cdngwc.net",
        "www.cdngwc.club",
        "www.cdnutc.me",
        "www.cdnhth.net",
        "www.cdnhth.club",
        "www.cdnbea.net",
        // v27.6 新增线路：禁漫常用镜像域名，增加可用性
        "www.cdnmhg.cc",
        "www.cdnmhg.net",
        "www.cdnfbs.net",
        "www.cdnfbs.club",
        "www.cdnds.net",
        "www.cdnds.cc",
    )
    @Volatile private var domainIndex = 0

    // 获取最新 API 域名的服务器（字节跳动 CDN，3 个镜像容灾）。
    // 响应为 base64+AES 密文，用 API_DOMAIN_SERVER_SECRET 解密得 {"Server": ["www.cdnhjk.net", ...]}。
    private val apiDomainServerUrls = listOf(
        "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
        "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
        "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt",
    )
    @Volatile private var lastDomainRefreshMs = 0L
    private val refreshLock = Any()

    // ---- 图片 CDN 域名池 ----
    // v27.6 改为 mutableList 以支持运行时增删（原来 val listOf 不可变）
    private val imageDomains = mutableListOf(
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp.jmapiproxy2.cc",
        "cdn-msp2.jmapiproxy2.cc",
        "cdn-msp3.jmapiproxy2.cc",
        "cdn-msp.jmapinodeudzn.net",
        "cdn-msp3.jmapinodeudzn.net",
        // v27.6 新增线路：更多图片 CDN，增加可用性
        "cdn-msp2.jmapiproxy1.cc",
        "cdn-msp2.jmapinodeudzn.net",
        "cdn-msp.jmapiproxy3.cc",
        "cdn-msp4.jmapiproxy2.cc",
    )
    @Volatile private var imageDomainIndex = 0
    private val imageDomainLock = Any()

    private val cookieJar = MemoryCookieJar(appContext)

    /**
     * v27.5 #37：当前代理设置（null=不走代理）。
     * 改动时通过 [applyProxy] 重建 [http]/[fastHttp]。
     */
    @Volatile private var currentProxy: String? = null

    /**
     * 主请求 client（长超时，用于业务接口）。
     *
     * v27.5 #37：改为 @Volatile var，[applyProxy] 切换代理时整体替换。
     * - 已 in-flight 的请求仍用旧 client 完成（OkHttp 内部队列）
     * - 新请求读 latest 引用，用新 client
     * - Coil 的 ImageLoader 在启动时绑定一次，运行时切代理不会立即生效；
     *   需重启进程或调用 [com.jmreader.core.CoilSetup.bindOkHttp] 重新绑定。
     */
    @Volatile var http: OkHttpClient = buildMainHttp(null)
        private set

    /** v27.3 根因 #2：轮换/自愈专用短超时 client。同 [http]，代理切换时整体替换。 */
    @Volatile private var fastHttp: OkHttpClient = buildFastHttp(null)

    private fun buildMainHttp(proxyStr: String?): OkHttpClient {
        // v27.6 性能优化：增大连接池（默认 5 连接不够禁漫 8+ API 域名 + 6 图片 CDN 共用）
        // 和 Dispatcher 每域并发（默认 5，列表 20+ 封面同时加载会排队）
        val pool = okhttp3.ConnectionPool(maxIdleConnections = 20, keepAliveDuration = 5, TimeUnit.MINUTES)
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(pool)
            .dispatcher(dispatcher)
            .cookieJar(cookieJar)
            // v27.12 关键性能修复：图片 CDN 请求自动加禁漫 header。
            // 封面 URL（/media/albums/{id}.jpg）不带 jm_sid，走 Coil 默认 HttpUriFetcher，
            // 之前没有 Referer/UA → 禁漫 CDN 返回 403/空响应 → 每张封面都失败重试
            // → OkHttp 队列被失败请求占满（maxRequestsPerHost=16）→ 列表图片加载极慢
            //   + enrich 的 /album 请求也排队 → 整体感知"卡"。
            // 加拦截器后封面能正常加载，连接池释放给 enrich，整体速度大幅提升。
            // JmImageFetcher（正文图）自己也加了相同 header，.header() 覆盖无影响。
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                if (isImageCdnHost(host)) {
                    val newReq = req.newBuilder()
                        .header("User-Agent", UA)
                        .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        .header("Referer", "https://www.cdnaspa.club/")
                        .header("X-Requested-With", "com.JMComic3.app")
                        .build()
                    chain.proceed(newReq)
                } else {
                    chain.proceed(req)
                }
            }
        com.jmreader.data.api.NetworkFactory.parseProxy(proxyStr)?.let { (p, _) -> b.proxy(p) }
        return b.build()
    }

    /** 判断 host 是否为禁漫图片 CDN 域名（用于拦截器加专用 header）。 */
    private fun isImageCdnHost(host: String): Boolean {
        // 快速路径：域名特征前缀匹配，避免对每个 API 请求都加锁查 list
        if (host.startsWith("cdn-msp", ignoreCase = true) ||
            host.contains("jmapiproxy", ignoreCase = true) ||
            host.contains("jmapinodeudzn", ignoreCase = true)) {
            return true
        }
        // 精确匹配（用户自定义的图片 CDN 可能不含上述特征）
        synchronized(imageDomainLock) {
            return imageDomains.any { it.equals(host, ignoreCase = true) }
        }
    }

    private fun buildFastHttp(proxyStr: String?): OkHttpClient {
        // v27.8：增大连接池和每域并发，配合 BaseListViewModel 滑动窗口批量补全 tags。
        // OkHttp 默认 maxRequestsPerHost=5 是 enrich 批量补全的瓶颈
        //（16 个并发 comicDetail 请求只有 5 个能同时执行，其余排队 → "一个一个加载"）。
        // 提升到 16/域与主 http 一致，连接池 10→20 匹配并发量。
        // v27.13：缩短超时（connect 4s / read 6s / call 8s）加速域名轮换。
        // 之前 connect 6s + call 10s，14 域名串行最坏 140s 才触发 forced refresh，
        // 用户看到"加载失败"要等很久。缩短后最坏 ~56s（失败域名 connect refused < 1s 实际更快）。
        val pool = okhttp3.ConnectionPool(maxIdleConnections = 20, keepAliveDuration = 5, TimeUnit.MINUTES)
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        val b = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(pool)
            .dispatcher(dispatcher)
            .cookieJar(cookieJar)
        com.jmreader.data.api.NetworkFactory.parseProxy(proxyStr)?.let { (p, _) -> b.proxy(p) }
        return b.build()
    }

    /**
     * v27.5 #37：运行时切换代理。
     * 重建 [http]/[fastHttp]，旧 client 的 dispatcher 线程池与连接池显式释放，
     * 避免反复切换代理时累积泄漏的 OkHttpClient（每个 client 持有 64 线程 + 5 连接的默认池）。
     * 同步更新 [com.jmreader.core.CoilSetup] 绑定，让后续图片加载也走新代理。
     */
    fun applyProxy(proxyStr: String?) {
        if (proxyStr == currentProxy) return
        currentProxy = proxyStr
        // v27.5 稳定性加固：保存旧 client 引用，异步 shutdown 其线程池与连接池。
        // 不在主线程同步 shutdown（避免阻塞 UI），交由 appScope 异步处理。
        val oldHttp = http
        val oldFast = fastHttp
        http = buildMainHttp(proxyStr)
        fastHttp = buildFastHttp(proxyStr)
        appScope.launch {
            runCatching {
                oldHttp.dispatcher.executorService.shutdown()
                oldHttp.connectionPool.evictAll()
                oldFast.dispatcher.executorService.shutdown()
                oldFast.connectionPool.evictAll()
            }
        }
        // 同步更新 Coil 的 OkHttp 引用（ImageLoader 在启动时已构建，不会重建；
        // 但下次 newImageLoader() 调用——如进程重启——会用新引用）。
        runCatching { com.jmreader.core.CoilSetup.bindOkHttp(http) }
        Logger.i("JmDirect", "代理已切换: ${proxyStr ?: "直连"}")
    }

    // scramble_id 缓存（按 photoId），避免每张图都请求。
    // 用 LruCache 限制容量，避免长期阅读后线性增长导致内存膨胀。
    private val scrambleCache = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 200
    }

    // v27.3 根因 #3：冷启动窗口期修复。
    // 之前 init{} 启动后台 refreshApiDomains，但首请求不等待它就执行；
    // 若内置 8 个域名全部过期（禁漫已换域），首请求 8 域名全 fail 后才触发 forced refresh，
    // 用户看到「加载失败」时其实域名才刚拉到。现在用 CompletableDeferred 让首请求最多等 3s。
    private val warmupDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        // 启动时后台异步拉取最新 API 域名（不阻塞；失败也无妨，请求全失败时还会再触发）。
        // 禁漫会定期换 API 域名，旧域名会 404，必须动态更新才能长期可用。
        // 关键修复：用 appScope 启动协程替代裸 Thread，遵循结构化并发（可取消、有命名、易调试）。
        // v27.3：完成后 complete warmupDeferred，让首请求最多等 3s 就能拿到最新域名池。
        appScope.launch {
            runCatching { refreshApiDomains(forced = false) }
            warmupDeferred.complete(Unit)
        }
    }

    /**
     * 请求域名服务器，拉取禁漫最新 API 域名列表并更新 [apiDomains]。
     * @param forced true=强制刷新（用于全失败兜底，受 30s 节流约束避免对字节CDN 重复请求）；
     *               false=受 5 分钟节流约束（启动时主动刷新）
     * @return 是否成功更新
     */
    private fun refreshApiDomains(forced: Boolean): Boolean {
        val now = System.currentTimeMillis()
        synchronized(refreshLock) {
            // 关键修复（Bug 13）：之前 forced=true 完全无节流，断网场景下每个失败请求都会
            // 触发一次字节CDN调用（3个URL串行），放大网络压力且每个失败请求都要等3个CDN超时。
            // 现在 forced 也加 30s 节流：既保留自愈能力（30s 后可重试），又避免对字节CDN轰炸。
            val throttleMs = if (forced) 30 * 1000L else 5 * 60 * 1000L
            if (now - lastDomainRefreshMs < throttleMs) return false
            // v27.3：不在这里写时间戳！之前 fetch 还没开始就打卡，
            // 导致 fetch 失败后 30s 内 forced=true 也被节流拒绝，用户看到"无法拉取最新域名"。
            // 改为成功后才打卡，失败不打卡，让下次 forced=true 可立即重试。
        }
        for (url in apiDomainServerUrls) {
            try {
                val req = Request.Builder().url(url).get().build()
                // v27.3 根因 #2：用 fastHttp（10s）拉字节 CDN，避免单 URL 卡 40s
                // 字节 CDN 健康时 < 1s 返回，10s 足够；不可达时 connectRefused < 1s 即失败
                fastHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val text = resp.body?.string().orEmpty()
                    if (text.isBlank()) return@use
                    val json = JSONObject(JMCrypto.decodeDomainServerResp(text))
                    val arr = json.optJSONArray("Server") ?: return@use
                    val list = (0 until arr.length())
                        .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                    if (list.isNotEmpty()) {
                        synchronized(apiDomains) {
                            apiDomains.clear()
                            apiDomains.addAll(list)
                            domainIndex = 0
                        }
                        // v27.13：同时尝试更新图片 CDN 域名池
                        // 字节 CDN 响应可能包含 Image 字段（图片 CDN 域名列表）
                        val imgArr = json.optJSONArray("Image")
                        if (imgArr != null && imgArr.length() > 0) {
                            val imgList = (0 until imgArr.length())
                                .mapNotNull { imgArr.optString(it).takeIf { s -> s.isNotBlank() } }
                            if (imgList.isNotEmpty() && pinnedImageCdn == null) {
                                synchronized(imageDomainLock) {
                                    imageDomains.clear()
                                    imageDomains.addAll(imgList)
                                    imageDomainIndex = 0
                                }
                                Logger.i("JmDirect", "图片CDN域名已更新: $imgList")
                            }
                        }
                        // v27.3：成功才打卡
                        synchronized(refreshLock) { lastDomainRefreshMs = System.currentTimeMillis() }
                        Logger.i("JmDirect", "API域名已更新: $list")
                        return true
                    }
                }
            } catch (e: Throwable) {
                Logger.w("JmDirect", "拉取最新域名失败 $url: ${Logger.brief(e)}")
            }
        }
        return false
    }

    // ------------------------------------------------------------------------
    // 通用请求
    // ------------------------------------------------------------------------

    /** 当前秒级时间戳字符串。 */
    private fun ts(): String = (System.currentTimeMillis() / 1000).toString()

    /**
     * 请求一个 API 接口，自动加 token 头、解密响应、域名轮换重试。
     *
     * @param path    接口路径，如 "/search"
     * @param query   查询参数
     * @param secret  token 密钥（普通接口 APP_TOKEN_SECRET，scramble 接口 APP_TOKEN_SECRET_2）
     * @param decrypt 是否解密响应 data 字段（scramble 接口返回 HTML，不解密）
     * @return 解密后的 JSON 字符串；若 decrypt=false 则返回响应原文
     */
    private suspend fun reqApi(
        path: String,
        query: Map<String, String> = emptyMap(),
        secret: String = JMCrypto.APP_TOKEN_SECRET,
        decrypt: Boolean = true,
    ): String {
        // v27.3 根因 #3：首请求最多等 warmup 2s，让启动时的 refreshApiDomains 有机会完成。
        // v27.13：从 3s 缩短到 2s，减少冷启动首次请求的等待。
        // 完成/超时都不影响后续逻辑：若 warmup 拉到新域名，第一轮就用新域名更可能成功；
        // 若 warmup 超时，照常用内置域名走第一轮，全失败后再 forced refresh。
        if (!warmupDeferred.isCompleted) {
            kotlinx.coroutines.withTimeoutOrNull(2_000L) { warmupDeferred.await() }
        }
        // 第一轮：用当前域名池（内置最新域名）
        val errs1 = mutableListOf<String>()
        reqApiOnce(path, query, secret, decrypt, errs1)?.let { return it }
        // 全失败 → 拉取最新 API 域名，更新后再试一轮（域名过期的自愈机制）
        val refreshed = refreshApiDomains(forced = true)
        if (refreshed) {
            Logger.i("JmDirect", "域名更新后重试 $path")
            val errs2 = mutableListOf<String>()
            reqApiOnce(path, query, secret, decrypt, errs2)?.let { return it }
            throw RuntimeException(
                "所有域名均请求失败: $path\n" +
                "首轮(${errs1.size}域名全失败): ${errs1.joinToString("; ")}\n" +
                "已拉取最新域名并重试，仍失败(${errs2.size}域名): ${errs2.joinToString("; ")}\n" +
                "若均为连接/超时错误，可能是当前网络无法访问禁漫服务器（IP地区限制），请尝试切换网络或配置代理。"
            )
        } else {
            throw RuntimeException(
                "所有域名均请求失败: $path\n" +
                "首轮(${errs1.size}域名全失败): ${errs1.joinToString("; ")}\n" +
                "且无法拉取最新域名（字节CDN不可达），请检查网络连通性或配置代理。"
            )
        }
    }

    /** 单轮遍历当前域名池请求；成功返回响应，全部失败返回 null。 */
    private fun reqApiOnce(
        path: String,
        query: Map<String, String>,
        secret: String,
        decrypt: Boolean,
        errs: MutableList<String>,
    ): String? {
        val snapshot = synchronized(apiDomains) { apiDomains.toList() }
        if (snapshot.isEmpty()) return null
        // 用局部索引遍历，避免域名池被刷新缩容后 snapshot[domainIndex] 越界崩溃
        var idx = synchronized(apiDomains) { domainIndex } % snapshot.size
        for (attempt in snapshot.indices) {
            val domain = snapshot[idx]
            val urlBuilder = HttpUrl.Builder().scheme("https").host(domain).addPathSegments(path.trimStart('/'))
            query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
            val url = urlBuilder.build()

            val t = ts()
            val token = JMCrypto.token(t, secret)
            val tokenparam = JMCrypto.tokenparam(t)

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("token", token)
                .header("tokenparam", tokenparam)
                .get()
                .build()

            try {
                // v27.3 根因 #2：用 fastHttp（10s callTimeout）轮换，避免单域名卡 40s。
                // 成功响应通常 < 2s，10s 足够覆盖慢速但健康的域名，又能在故障域名上快速 fail。
                fastHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // 关键修复：HTTP 401/403 是业务错误，所有域名返回相同结果，轮换无意义。
                        // 之前对 401 也轮换 8 个域名 + 刷新 + 再轮换 8 个，最坏 16 次请求几分钟才报错。
                        // 直接抛 SessionExpiredException/BusinessException 让上层处理，不进 errs 不轮换。
                        if (resp.code == 401 || resp.code == 403) {
                            throw SessionExpiredException("HTTP ${resp.code}")
                        }
                        throw RuntimeException("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) throw RuntimeException("空响应")
                    if (!decrypt) return body

                    // 响应外层: {"code":200,"data":"<密文>","errorMsg":null}
                    val outer = JSONObject(body)
                    val code = outer.optInt("code", -1)
                    if (code != 200) {
                        val msg = outer.optString("errorMsg", "code=$code")
                        // 业务码 401/403 = 未登录/无权限，所有域名返回相同，不轮换
                        if (code == 401 || code == 403) {
                            throw SessionExpiredException("禁漫返回错误: $msg")
                        }
                        // 其他业务错误（如内容不存在）：同样不轮换，直接抛
                        throw BusinessException("禁漫返回错误: $msg")
                    }
                    val data = outer.optString("data")
                    if (data.isBlank()) {
                        return data.ifBlank { "{}" }
                    }
                    return JMCrypto.decodeRespData(data, t)
                }
            } catch (e: SessionExpiredException) {
                // 会话过期：所有域名都一样，不轮换，直接向上抛
                Logger.w("JmDirect", "请求 $path 会话过期: ${Logger.brief(e)}")
                throw e
            } catch (e: BusinessException) {
                // 业务错误：不轮换，直接向上抛
                Logger.w("JmDirect", "请求 $path 业务错误: ${Logger.brief(e)}")
                throw e
            } catch (e: Throwable) {
                val brief = Logger.brief(e)
                Logger.w("JmDirect", "请求 $path @ $domain 失败: $brief")
                errs.add("$domain: $brief")
                idx = (idx + 1) % snapshot.size
            }
        }
        // 把下一轮起点写回（锁内，避免与刷新缩容竞态）
        synchronized(apiDomains) {
            if (apiDomains.isNotEmpty()) domainIndex = idx % apiDomains.size
        }
        return null
    }

    /** POST 请求（用于登录、收藏等）。与 [reqApi] 一致：全失败 → 更新域名 → 重试一轮。 */
    private suspend fun postApi(path: String, form: Map<String, String> = emptyMap(), secret: String = JMCrypto.APP_TOKEN_SECRET): JSONObject {
        // SessionExpiredException/BusinessException 在 postApiOnce 内直接抛出，不进 errs 不轮换
        val errs1 = mutableListOf<String>()
        postApiOnce(path, form, secret, errs1)?.let { return it }
        if (refreshApiDomains(forced = true)) {
            Logger.i("JmDirect", "域名更新后重试 POST $path")
            val errs2 = mutableListOf<String>()
            postApiOnce(path, form, secret, errs2)?.let { return it }
            // 关键修复：聚合两轮错误详情，与 reqApi 一致。
            // 之前只抛"所有域名均请求失败: POST $path"，登录失败时用户无法判断是密码错/网络断/域名过期。
            throw RuntimeException(
                "所有域名均请求失败: POST $path\n" +
                "首轮(${errs1.size}域名全失败): ${errs1.joinToString("; ")}\n" +
                "已拉取最新域名并重试，仍失败(${errs2.size}域名): ${errs2.joinToString("; ")}"
            )
        } else {
            throw RuntimeException(
                "所有域名均请求失败: POST $path\n" +
                "首轮(${errs1.size}域名全失败): ${errs1.joinToString("; ")}\n" +
                "且无法拉取最新域名（字节CDN不可达），请检查网络连通性或配置代理。"
            )
        }
    }

    private fun postApiOnce(path: String, form: Map<String, String>, secret: String, errs: MutableList<String>): JSONObject? {
        val snapshot = synchronized(apiDomains) { apiDomains.toList() }
        if (snapshot.isEmpty()) return null
        var idx = synchronized(apiDomains) { domainIndex } % snapshot.size
        for (attempt in snapshot.indices) {
            val domain = snapshot[idx]
            val url = HttpUrl.Builder().scheme("https").host(domain).addPathSegments(path.trimStart('/')).build()
            val t = ts()
            val token = JMCrypto.token(t, secret)
            val tokenparam = JMCrypto.tokenparam(t)

            val formBody = FormBody.Builder()
            form.forEach { (k, v) -> formBody.add(k, v) }

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("token", token)
                .header("tokenparam", tokenparam)
                .post(formBody.build())
                .build()

            try {
                // v27.3 根因 #2：POST 也用 fastHttp 短超时轮换，登录/收藏失败也能快速反馈
                fastHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code == 401 || resp.code == 403) throw SessionExpiredException("HTTP ${resp.code}")
                        throw RuntimeException("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) throw RuntimeException("空响应")
                    val outer = JSONObject(body)
                    val code = outer.optInt("code", -1)
                    if (code != 200) {
                        val msg = outer.optString("errorMsg", "code=$code")
                        if (code == 401 || code == 403) throw SessionExpiredException("禁漫返回错误: $msg")
                        throw BusinessException("禁漫返回错误: $msg")
                    }
                    val data = outer.optString("data")
                    return if (data.isBlank()) JSONObject("{}") else JSONObject(JMCrypto.decodeRespData(data, t))
                }
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: BusinessException) {
                throw e
            } catch (e: Throwable) {
                Logger.w("JmDirect", "POST $path @ $domain 失败: ${Logger.brief(e)}")
                errs.add("$domain: ${Logger.brief(e)}")
                idx = (idx + 1) % snapshot.size
            }
        }
        synchronized(apiDomains) {
            if (apiDomains.isNotEmpty()) domainIndex = idx % apiDomains.size
        }
        return null
    }

    // ------------------------------------------------------------------------
    // 业务接口
    // ------------------------------------------------------------------------

    /**
     * 搜索。order: latest/views/likes/picture；time: all/today/week/month
     *
     * 支持用本子号搜索：当 search_query 是某个 album_id 时，禁漫会返回 redirect_aid
     * 字段（而非 content 数组），此时直接取该本子详情作为唯一结果
     * （移植自 jmcomic.JmApiClient.search 的 redirect_aid 处理）。
     * 另外禁漫搜索接口不认 "JM441923" 这种带前缀的输入，这里会归一化为纯数字。
     */
    suspend fun search(q: String, page: Int, order: String, time: String): PageResultDto {
        // 归一化：JM123456 / jm123456 → 123456，让禁漫能识别为本子号并触发 redirect
        val normalized = normalizeJmId(q)
        val params = mapOf(
            "main_tag" to "0",
            "search_query" to normalized,
            "page" to page.toString(),
            "o" to mapOrder(order),
            "t" to mapTime(time),
        )
        val json = JSONObject(reqApi("/search", params))

        // 本子号直搜：禁漫返回 redirect_aid，直接取该本子详情
        val redirectAid = json.optString("redirect_aid").takeIf { it.isNotBlank() }
        if (redirectAid != null && page == 1) {
            return try {
                val detail = albumDetail(redirectAid)
                PageResultDto(
                    page = 1,
                    total = 1,
                    items = listOf(
                        ComicBriefDto(
                            id = detail.id,
                            name = detail.name,
                            author = detail.author,
                            tags = detail.tags,
                            cover = detail.cover,
                            likes = detail.likes,
                            views = detail.views,
                        )
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w("JmDirect", "redirect_aid 取详情失败: ${Logger.brief(e)}")
                PageResultDto(page = page, total = 0, items = emptyList())
            }
        }

        return parseSearchPage(json, page)
    }

    /**
     * 把用户输入归一化为禁漫搜索接口能识别的形式。
     * - "JM123456" / "jm123456" → "123456"（禁漫搜索不认 JM 前缀）
     * - 纯数字 / 普通关键词原样返回
     */
    private fun normalizeJmId(text: String): String {
        val t = text.trim()
        if (t.length >= 3) {
            val c0 = t[0]
            val c1 = t[1]
            if ((c0 == 'J' || c0 == 'j') && (c1 == 'M' || c1 == 'm') && t.substring(2).all { it.isDigit() }) {
                return t.substring(2)
            }
        }
        return t
    }

    /**
     * 分类/排行接口（移动端 /categories/filter）。
     *
     * 参数协议（移植自 jmcomic.JmApiClient.categories_filter）：
     * - page: 页码
     * - order: 固定空串
     * - c: 分类 slug（"0"=全部，doujin/single/short/hanman/meiman/doujin_cosplay/3D/another/english_site）
     * - o: 排序；time 为 "a"(全部) 时取 [order] 本身（如 "mr" 最新），否则为 "order_time"（如 "mv_w" 周观看）
     *
     * 最新 = c=0, o=mr；周/月/日排行 = c=0, o=mv_w / mv_m / mv_t。
     * 返回结构与 /search 相同（content/total），复用 parseSearchPage 解析。
     */
    suspend fun categoriesFilter(
        page: Int,
        time: String,        // a / t / w / m
        category: String,    // slug 或空（空当作 "0"）
        order: String,       // mr / mv / mp / tf
    ): PageResultDto {
        val o = if (time == "a" || time.isBlank()) order else "${order}_$time"
        val params = mapOf(
            "page" to page.toString(),
            "order" to "",
            "c" to category.ifBlank { "0" },
            "o" to o,
        )
        val json = JSONObject(reqApi("/categories/filter", params))
        return parseSearchPage(json, page)
    }

    /** 最新列表：c=0, o=mr。 */
    suspend fun latest(page: Int, category: String): PageResultDto =
        categoriesFilter(page = page, time = "a", category = category, order = "mr")

    /** 排行（按观看）：c=0, o=mv_<time>。time: all/today/week/month。 */
    suspend fun ranking(time: String, category: String, page: Int): PageResultDto =
        categoriesFilter(page = page, time = mapTime(time), category = category, order = "mv")

    /** 漫画（album）详情，含章节列表。 */
    suspend fun albumDetail(albumId: String): ComicDetailDto {
        val json = JSONObject(reqApi("/album", mapOf("id" to albumId)))
        val data = json.optJSONObject("data") ?: json
        val name = data.optString("name")
        val author = data.optJSONArray("author")?.let { arr ->
            (0 until arr.length()).joinToString(" ") { arr.optString(it) }.ifBlank { null }
        }
        val tags = data.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        // 角色（actors）：API 返回数组，如 ['神里绫华']；详情页像官方客户端那样显示角色。
        val actors = data.optJSONArray("actors")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        // 作品（works）：角色所属登场作品，如 ['原神']；与 actors 一起显示。
        val works = data.optJSONArray("works")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        // 封面不在 images 字段里（images 是第一章的图片列表），
        // 封面固定为 /media/albums/{album_id}.jpg
        val cover = buildCoverUrl(data.optString("id").ifBlank { albumId })

        // series = 章节列表
        val chapters = mutableListOf<ChapterDto>()
        val series = data.optJSONArray("series")
        if (series != null && series.length() > 0) {
            for (i in 0 until series.length()) {
                val ch = series.optJSONObject(i) ?: continue
                chapters.add(ChapterDto(
                    id = ch.optString("id"),
                    title = ch.optString("name").ifBlank { "第${ch.optString("sort")}话" },
                    sort = ch.optString("sort").toIntOrNull() ?: i,
                ))
            }
        }
        if (chapters.isEmpty()) {
            // 单章节：自身即唯一章节
            chapters.add(ChapterDto(id = albumId, title = name.ifBlank { "正文" }, sort = 0))
        }

        return ComicDetailDto(
            id = data.optString("id").ifBlank { albumId },
            name = name,
            author = author,
            description = data.optString("description").ifBlank { null },
            tags = tags,
            cover = cover,
            // v26 举一反三：与 parseSearchPage/parseFavoritePage 同步用 pickFirst 多键兜底。
            // 之前 likes 只查 "likes"、views 只查 "total_views"，禁漫不同接口/版本字段名不一致，
            // 详情页 likes/views 经常解析为 null（与列表页同款问题）。
            likes = pickFirst(data, "likes", "like_cnt", "like_count"),
            views = pickFirst(data, "total_views", "views", "clicks"),
            chapters = chapters,
            publishTime = parsePublishTime(data),
            actors = actors,
            works = works,
        )
    }

    /**
     * 评论/讨论区接口（/forum，移植自 jasmine forum 方法）。
     *
     * v27.9：替代之前的 HTML 抓取方案（JmWebFetcher + jm365.work 重定向获取"无 CF 域名"，
     * 该通道不稳定——CF 拦截/换域/超时频繁，导致评论区/讨论区"根本加载不出来"）。
     * /forum 走与 /search、/album 相同的 [reqApi] 通道（token 鉴权 + AES 解密 + 域名轮换），
     * 已验证稳定，与 jasmine 客户端同款。
     *
     * @param mode 评论分类，禁漫用 "manhua" 表示漫画评论
     * @param aid  本子 ID；非空=查该本子的评论（评论区），空=全局评论流（讨论区）
     * @param uid  用户 ID；非空=查该用户的评论（个人评论页），空=不限用户
     * @param page 页码（从 1 开始）
     *
     * 响应结构：{"list":[Comment...], "total":N}
     * Comment 字段：AID/CID/UID/nickname/likes/addtime/content(HTML)/photo/name/expinfo{level}/replys[Comment]
     */
    suspend fun forum(mode: String?, aid: String?, uid: String?, page: Int): JmCommentPageDto {
        // 与 jasmine 一致：null 参数不带（禁漫 API 对空值敏感）
        val params = mutableMapOf("page" to page.toString())
        if (mode != null) params["mode"] = mode
        if (aid != null) params["aid"] = aid
        if (uid != null) params["uid"] = uid
        val json = JSONObject(reqApi("/forum", params))
        val total = json.optInt("total", 0)
        val list = json.optJSONArray("list")?.let { arr ->
            (0 until arr.length()).mapNotNull { parseForumComment(arr.optJSONObject(it)) }
        } ?: emptyList()
        return JmCommentPageDto(list = list, total = total)
    }

    /** 解析单条 Comment JSON（递归处理 replys 嵌套回复）。 */
    private fun parseForumComment(o: JSONObject?): JmCommentDto? {
        if (o == null) return null
        // AID 在全局评论流里可能为 null（JSON null）或 0；这两种都视作"无所属本子"
        val aid = if (o.isNull("AID")) null
                  else o.optInt("AID").takeIf { it != 0 }?.toString()
        // v27.13 修复：CID/UID 为 0 时视作缺失，返回空字符串。
        // 之前 optInt("CID").toString() 在字段缺失时返回 "0"，
        // 多条 CID 缺失的评论 key 全是 "0" → LazyColumn 抛 IllegalArgumentException 崩溃。
        val cid = o.optInt("CID").takeIf { it != 0 }?.toString() ?: ""
        val uid = o.optInt("UID").takeIf { it != 0 }?.toString() ?: ""
        val nickname = o.optString("nickname").ifBlank { o.optString("username").ifBlank { "匿名" } }
        val likes = o.optInt("likes", 0)
        val addtime = o.optString("addtime").ifBlank { o.optString("update_at") }
        val rawContent = o.optString("content")
        val content = cleanForumContent(rawContent)
        val photo = o.optString("photo").takeIf { it.isNotBlank() }
        val name = o.optString("name")
        val level = o.optJSONObject("expinfo")?.optInt("level", 0) ?: 0
        val replys = o.optJSONArray("replys")?.let { arr ->
            (0 until arr.length()).mapNotNull { parseForumComment(arr.optJSONObject(it)) }
        } ?: emptyList()
        return JmCommentDto(
            aid = aid,
            cid = cid,
            uid = uid,
            nickname = nickname,
            likes = likes,
            addtime = addtime,
            content = content,
            photo = photo,
            name = name,
            level = level,
            replys = replys,
        )
    }

    /**
     * 把禁漫评论 HTML 内容清洗为纯文本（无 Jsoup 依赖）。
     *
     * 禁漫评论 content 是 HTML 片段，常见结构：
     * - emoji：<img style="width:18px;height:18px" src="..." alt="doge" /> → [doge]
     * - 换行：<br> / </div> / </p> → \n
     * - 其它标签：去标签保留文本
     * - HTML 实体：&amp; &lt; &gt; &nbsp; &quot; &#39; 解码
     *
     * 保留 JM 编号原文（如 JM123456），让 UI 层 [JmLinkedText] 做 linkify。
     */
    private fun cleanForumContent(html: String): String {
        if (html.isBlank()) return ""
        var s = html
        // emoji img → [alt]
        s = Regex("""<img[^>]*alt=["']([^"']*)["'][^>]*/?>""").replace(s) { m ->
            val alt = m.groupValues[1]
            if (alt.isNotBlank()) "[$alt]" else ""
        }
        // 去掉剩余 img（无 alt 的）
        s = Regex("""<img[^>]*/?>""").replace(s, "")
        // 块级标签闭合转换行
        s = Regex("""(?i)</(div|p|blockquote|li)>""").replace(s, "\n")
        s = Regex("""(?i)<br\s*/?>""").replace(s, "\n")
        // 去掉所有剩余标签
        s = Regex("""<[^>]+>""").replace(s, "")
        // HTML 实体解码（常见几个）
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'")
        // 合并多余空行，trim
        s = s.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (s.length > 2000) s.take(2000) + "…" else s
    }

    /**
     * 解析发布时间。禁漫 /album 接口对发布时间的字段名不固定，逐个尝试常见字段：
     * addtime / timestamp / time / pub_date / publish_time / created_at。
     * 值可能是秒级 Unix 时间戳，也可能是 "yyyy-MM-dd" 字符串。
     */
    private fun parsePublishTime(data: JSONObject): String? {
        val candidates = listOf("addtime", "timestamp", "time", "pub_date", "publish_time", "created_at")
        for (key in candidates) {
            val v = data.optString(key).orEmpty()
            if (v.isBlank()) continue
            // 纯数字 → 当作 Unix 时间戳（秒）格式化
            v.toLongOrNull()?.let { ts ->
                val sec = if (ts > 1_000_000_000_000L) ts / 1000 else ts  // 兼容毫秒
                return runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(sec * 1000))
                }.getOrNull() ?: v
            }
            // 非数字 → 原样返回（可能是已格式化的日期串）
            return v
        }
        return null
    }

    /** 章节（photo）图片列表。自动获取 scramble_id 并拼到图片 URL 上。 */
    suspend fun chapterImages(photoId: String): ChapterImagesDto {
        // v27.13：并行化 /chapter 和 /chapter_view_template 两个请求。
        // 之前串行执行，冷开章节要等两次完整 reqApi（各含 warmup 3s + 域名轮换），
        // 是阅读器打开慢的主因。两请求无数据依赖（getScrambleId 只用 photoId），
        // 并行后总耗时≈max(t1, t2) 而非 t1+t2。
        return coroutineScope {
            val chapterDeferred = async(Dispatchers.IO) { reqApi("/chapter", mapOf("id" to photoId)) }
            val scrambleDeferred = async(Dispatchers.IO) { getScrambleId(photoId) }
            val json = JSONObject(chapterDeferred.await())
            val data = json.optJSONObject("data") ?: json
            val name = data.optString("name")
            val aid = data.optString("id").ifBlank { photoId }
            val scrambleId = scrambleDeferred.await()

            val images = data.optJSONArray("images")?.let { arr ->
                (0 until arr.length()).mapNotNull { idx ->
                    val fn = arr.optString(idx)
                    if (fn.isBlank()) null else buildImageUrl(aid, fn, scrambleId)
                }
            } ?: emptyList()

            ChapterImagesDto(
                id = photoId,
                title = name.ifBlank { null },
                scramble_id = scrambleId.toString(),
                images = images,
            )
        }
    }

    /**
     * 获取 scramble_id（带缓存）。
     * 请求 /chapter_view_template 返回 HTML，解析 var scramble_id = (\d+);。
     *
     * v27.13 修复：失败时不再兜底 220980。
     * 之前兜底 220980 会导致：真实阈值变化后，用错误的 scramble_id 解密让图片块状错位，
     * 且错误结果被缓存进 scrambleCache，整章所有图都错，无任何错误提示。
     * 现在失败时抛异常，让 chapterImages 整体失败，上层显示"加载失败"而非展示错位图片。
     * 正则匹配失败（HTML 有响应但无 scramble_id）也视为异常，不缓存。
     */
    private suspend fun getScrambleId(photoId: String): Long {
        synchronized(scrambleCache) { scrambleCache[photoId]?.let { return it } }
        val html = reqApi(
            "/chapter_view_template",
            mapOf(
                "id" to photoId,
                "mode" to "vertical",
                "page" to "0",
                "app_img_shunt" to "1",
                "express" to "off",
                "v" to ts(),
            ),
            secret = JMCrypto.APP_TOKEN_SECRET_2,
            decrypt = false,
        )
        val match = Regex("""var\s+scramble_id\s*=\s*(\d+)\s*;""").find(html)
        val sid = match?.groupValues?.get(1)?.toLongOrNull()
            ?: throw RuntimeException("scramble_id 解析失败：HTML 中未找到 scramble_id")
        synchronized(scrambleCache) { scrambleCache[photoId] = sid }
        return sid
    }

    /** 收藏夹。folder_id: "0"=全部收藏夹；order: mr(最新)/mv(观看)/tf(喜欢)。 */
    suspend fun favorites(page: Int, folderId: String = "0", order: String = "mr"): PageResultDto {
        val json = JSONObject(reqApi("/favorite", mapOf(
            "page" to page.toString(),
            "folder_id" to folderId,
            "o" to order,
        )))
        return parseFavoritePage(json, page)
    }

    /** 添加收藏。 */
    suspend fun addFavorite(albumId: String): Boolean {
        return try {
            postApi("/favorite", mapOf("aid" to albumId))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w("JmDirect", "添加收藏失败: ${Logger.brief(e)}"); false
        }
    }

    /**
     * 登录。成功标志：响应中含非空会话 token 字段 "s"。
     *
     * 关键修复（站点收藏 401，根因）：
     * 1. **成功判断收紧到 s**：旧逻辑 `s.isNotBlank() || uid.isNotBlank()`，当响应只有
     *    uid 非空但 s 为空时也判定成功 → 持久化 loggedInUser 但未写入 AVS 会话 cookie →
     *    后续 /favorite 等鉴权接口 401。现改为必须有非空 s 才算成功（s 即 AVS 会话 token）。
     * 2. **总是用最新 s 覆盖 AVS cookie**：旧逻辑用 `!hasCookie("AVS")` 守卫，当 jar 中已存在
     *    （可能已失效的）旧 AVS 时不更新 → 重新登录后仍用旧会话 → /favorite 持续 401。
     *    现在登录成功总是用响应里的 s 覆盖，保证会话是最新的。
     */
    suspend fun login(username: String, password: String): Boolean {
        return try {
            val r = postApi("/login", mapOf("username" to username, "password" to password))
            val s = r.optString("s").orEmpty()
            val uid = r.optString("uid").orEmpty()
            val ok = s.isNotBlank()
            if (ok) {
                cookieJar.addCookie("AVS", s)
                Logger.i("JmDirect", "登录成功，已更新 AVS cookie (uid=$uid)")
            } else {
                Logger.w("JmDirect", "登录失败：响应无有效会话 token (s 为空, uid=$uid)")
            }
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w("JmDirect", "登录失败: ${Logger.brief(e)}"); false
        }
    }

    /**
     * 清除本地会话凭证（cookie + scramble 缓存），不触及持久化的 loggedInUser。
     * 供 Repository 在检测到 /favorite 401（会话过期）时调用，配合 setLoggedInUser(null)
     * 让 UI 回到未登录态，引导用户重新登录，而非显示"所有域名失败"的误导性错误。
     */
    fun clearSession() {
        cookieJar.clear()
        synchronized(scrambleCache) { scrambleCache.clear() }
        Logger.i("JmDirect", "已清除会话凭证（cookie 与 scramble 缓存）")
    }

    /**
     * 登出：清除内存 cookie（含 AVS 会话 token）+ scramble 缓存。
     * 禁漫移动端没有显式 logout 接口，清本地凭证即可达到"登出"效果。
     */
    fun logout() {
        cookieJar.clear()
        synchronized(scrambleCache) { scrambleCache.clear() }
        Logger.i("JmDirect", "已登出：cookie 与 scramble 缓存已清空")
    }

    // ------------------------------------------------------------------------
    // 域名管理（供「设置 → 域名管理」界面调用）
    // ------------------------------------------------------------------------

    /** 当前 API 域名池快照（含内置 + 用户自定义）。 */
    fun apiDomainList(): List<String> = synchronized(apiDomains) { apiDomains.toList() }

    /** 当前正在使用的 API 域名。 */
    fun currentDomain(): String = synchronized(apiDomains) {
        apiDomains.getOrElse(domainIndex) { apiDomains.firstOrNull() ?: "" }
    }

    /** 手动指定当前 API 域名（用户在界面点选某个域名时调用）。 */
    fun selectDomain(host: String) {
        synchronized(apiDomains) {
            val idx = apiDomains.indexOf(host)
            if (idx >= 0) domainIndex = idx
        }
    }

    /**
     * 合并用户自定义域名到内置池（去重，自定义在前）。
     * App 启动时从 SettingsStore 读出自定义域名后调用。
     */
    fun mergeCustomDomains(custom: List<String>) {
        if (custom.isEmpty()) return
        synchronized(apiDomains) {
            val merged = (custom + apiDomains).distinct()
            apiDomains.clear()
            apiDomains.addAll(merged)
        }
    }

    /**
     * 从运行时域名池移除某域名（用户在界面删除自定义域名时调用）。
     * 否则被删除的域名仍留在池里，currentDomain() 仍可能选中它。
     */
    fun removeDomain(host: String) {
        synchronized(apiDomains) {
            val idx = apiDomains.indexOf(host)
            if (idx >= 0) {
                apiDomains.removeAt(idx)
                if (apiDomains.isNotEmpty()) {
                    if (domainIndex >= apiDomains.size) domainIndex = 0
                    else if (domainIndex > idx) domainIndex--
                } else {
                    domainIndex = 0
                }
            }
        }
    }

    /** 批量测速所有域名，按延迟升序返回（失败的排最后）。 */
    suspend fun testAllDomains(): List<Triple<String, Long?, String?>> = kotlinx.coroutines.coroutineScope {
        val list = apiDomainList()
        // 关键修复：并发测速 + 用 Semaphore 限制并发到 4，避免 20+ 域名同时请求触发限流/耗尽 socket。
        // 之前 list.map { testDomain(it) } 串行，每个最坏 40s，8 域名 320s 用户以为卡死。
        // 现在用 testClient 短超时（6s）+ 并发，最坏约 (ceil(N/4) * 6)s 完成。
        val sem = Semaphore(4)
        val results = list.map { host ->
            async(kotlinx.coroutines.Dispatchers.IO) {
                sem.withPermit {
                    val (lat, err) = testDomain(host)
                    Triple(host, lat, err)
                }
            }
        }.awaitAll()
        results.sortedWith(compareBy(
            { it.second == null },           // 有延迟的在前
            { it.second ?: Long.MAX_VALUE }, // 延迟小的在前
        ))
    }

    /**
     * 测速专用 client：短超时（5s），避免单个域名卡 40s 让用户等几分钟。
     * 独立于 [http]，不影响正常请求。
     */
    private val testClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 测速单个域名：发一个轻量请求（/search 空关键词），返回延迟(ms)或失败原因。
     * @return Pair<延迟ms, 错误信息?>；成功时错误为 null
     *
     * 关键修复（Bug 28）：之前文件里同时存在两个 testDomain 定义（旧版用 http 40s 超时，
     * 新版用 testClient 6s 超时但函数体没写完缺右括号），导致：
     * 1) Kotlin 编译报"redeclaration" + 括号不匹配，整个 JmDirectClient.kt 无法编译，App 构建失败；
     * 2) 即使能编译，旧版会被优先解析，testClient 形同虚设，弱网下批量测速仍卡 320s。
     * 现在合并为单一实现，统一用 testClient 短超时。
     */
    fun testDomain(host: String): Pair<Long?, String?> {
        val t = ts()
        val token = JMCrypto.token(t)
        val tokenparam = JMCrypto.tokenparam(t)
        val url = HttpUrl.Builder().scheme("https").host(host)
            .addPathSegments("search")
            .addQueryParameter("main_tag", "0")
            .addQueryParameter("search_query", "")
            .addQueryParameter("page", "1")
            .addQueryParameter("o", "mr")
            .addQueryParameter("t", "a")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("token", token)
            .header("tokenparam", tokenparam)
            .get()
            .build()
        return try {
            val start = System.currentTimeMillis()
            // 关键修复：用 testClient 短超时，避免单域名卡 40s。
            testClient.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - start
                if (!resp.isSuccessful) return latency to "HTTP ${resp.code}"
                val body = resp.body?.string().orEmpty()
                val outer = JSONObject(body)
                if (outer.optInt("code", -1) != 200) {
                    return latency to outer.optString("errorMsg", "code=${outer.optInt("code")}")
                }
                // 尝试解密验证完整性
                val data = outer.optString("data")
                if (data.isNotBlank()) {
                    JMCrypto.decodeRespData(data, t)
                }
                latency to null
            }
        } catch (e: Throwable) {
            null to Logger.brief(e)
        }
    }

    /** 手动触发一次域名动态更新（界面"刷新域名"按钮用）。 */
    suspend fun refreshDomains(): Boolean = refreshApiDomains(forced = true)

    // ------------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------------

    /**
     * 构造图片完整 URL，附加 jm_sid query 供解码器使用。
     * v 参数是解码算法/缓存版本号，bump 此值即可让 Coil 内存/磁盘缓存自动失效。
     *
     * v=4: 修复 JmImageFetcher 不生效的根本原因——之前 Factory 实现的是
     *      Fetcher.Factory<String>，但 Coil 2.x 的 StringMapper 会先把 String
     *      映射成 Uri，导致 Factory<String> 永远不被调用，禁漫图片走默认
     *      HttpUriFetcher（未解密）→ 图片错位。现已改为 Factory<Uri>，bump v=4
     *      清除之前被 HttpUriFetcher 缓存的未解密分割原图。
     */
    private fun buildImageUrl(photoId: String, filename: String, scrambleId: Long? = null): String {
        val domain = currentImageDomain()
        val url = "https://$domain/media/photos/$photoId/$filename"
        val v = "v=4"
        return when {
            scrambleId != null -> "$url?jm_sid=$scrambleId&$v"
            else -> "$url?$v"
        }
    }

    /**
     * 构造漫画封面 URL。
     *
     * 禁漫移动端 /search、/favorite、/album 接口返回的 image 字段通常为空，
     * 不能直接用作封面。封面图实际存放在图片 CDN 的 /media/albums/{album_id}.jpg
     * （移植自 python jmcomic.JmcomicText.get_album_cover_url）。
     */
    private fun buildCoverUrl(albumId: String): String? {
        if (albumId.isBlank()) return null
        val domain = currentImageDomain()
        return "https://$domain/media/albums/$albumId.jpg"
    }

    /**
     * 从搜索/收藏列表项中解析真实标签，用于屏蔽过滤与卡片"分类"显示。
     *
     * 只取列表接口返回的 tags 字段（数组或空格分隔字符串）。
     * v27.9：列表接口的 tags 即为真实标签，直接用于屏蔽判定与卡片显示，不再二次请求 /album 补全。
     *
     * 关键：不把 category / category_sub 的 title 混进 tags。
     * category.title 是粗分类（如「同人」「汉化」），混入会导致列表卡片"分类"显示不准确。
     */
    private fun parseItemTags(item: JSONObject): List<String> {
        val tags = mutableListOf<String>()

        // tags 字段：可能为数组，也可能为空格分隔的字符串
        // 只取真实 tags，不把 category / category_sub 的 title 混进来。
        // category.title 是粗分类（如"同人""汉化"），混入 tags 会导致列表卡片"分类"显示不准确。
        when (val tagsVal = item.opt("tags")) {
            is org.json.JSONArray -> {
                for (i in 0 until tagsVal.length()) {
                    tagsVal.optString(i).trim().takeIf { it.isNotBlank() }?.let { tags.add(it) }
                }
            }
            is String -> {
                if (tagsVal.isNotBlank()) {
                    tagsVal.split(" ", "、", ",").map { it.trim() }
                        .filter { it.isNotBlank() }.let { tags.addAll(it) }
                }
            }
        }

        return tags
    }

    /**
     * v27.5 #34 用户锁定的图片 CDN 域名。非空时 [currentImageDomain] 优先返回该域名。
     * v27.13：不再完全锁死，pinned 域名失败时 [imageDomainList] 会包含全部域名供 fallback。
     * 由 [com.jmreader.data.AppContainer] 在 settings 变化时调用 [setPinnedImageCdn] 同步。
     */
    @Volatile private var pinnedImageCdn: String? = null

    fun setPinnedImageCdn(cdn: String?) {
        pinnedImageCdn = cdn?.trim()?.takeIf { it.isNotBlank() }
        com.jmreader.core.Logger.i("JmDirect", "图片 CDN 锁定: ${pinnedImageCdn ?: "自动轮换"}")
    }

    fun pinnedImageCdn(): String? = pinnedImageCdn

    /** 当前图片域名（供 JmImageFetcher 拿到当前域名后自行拼装重试）。 */
    fun currentImageDomain(): String = synchronized(imageDomainLock) {
        pinnedImageCdn?.let { return it }
        imageDomains.getOrElse(imageDomainIndex) { imageDomains[0] }
    }

    /**
     * 全部图片域名（供 JmImageFetcher 失败时轮换重试）。
     *
     * v27.13 修复：
     * 1. 之前直接返回原始 mutableList 引用，JmImageFetcher 遍历时若 refreshApiDomains
     *    并发 clear+addAll 会抛 ConcurrentModificationException。现在返回拷贝。
     * 2. pinned 模式下之前只返回 [pinnedImageCdn]，CDN 故障时满屏加载失败无重试。
     *    现在 pinned 域名放第一位，其余内置域名跟在后面作为 fallback。
     */
    fun imageDomainList(): List<String> = synchronized(imageDomainLock) {
        val pinned = pinnedImageCdn
        if (pinned != null) {
            (listOf(pinned) + imageDomains).distinct()
        } else {
            imageDomains.toList()
        }
    }

    /**
     * v27.6：精确切换到指定图片域名（下载成功时调用）。
     *
     * 此方法直接把 index 设为成功 host 的位置，让后续请求优先用已验证可用的域名。
     */
    fun selectImageDomain(host: String) {
        synchronized(imageDomainLock) {
            // v27.13：pinned 模式下也记录成功的 host 索引（若该 host 在内置池中），
            // 这样用户取消 pinned 后能立即用上已验证可用的域名。
            val idx = imageDomains.indexOf(host)
            if (idx >= 0) imageDomainIndex = idx
        }
    }

    private fun mapOrder(order: String): String = when (order) {
        "latest" -> "mr"
        "views" -> "mv"
        "likes" -> "tf"
        "picture" -> "mp"
        else -> order.ifBlank { "mr" }
    }

    private fun mapTime(time: String): String = when (time) {
        "all" -> "a"
        "today" -> "t"
        "week" -> "w"
        "month" -> "m"
        else -> time.ifBlank { "a" }
    }

    private fun parseSearchPage(json: JSONObject, page: Int): PageResultDto {
        val total = json.optString("total").toIntOrNull()
        val content = json.optJSONArray("content") ?: json.optJSONArray("list")
        val items = mutableListOf<ComicBriefDto>()
        if (content != null) {
            for (i in 0 until content.length()) {
                val it = content.optJSONObject(i) ?: continue
                val id = it.optString("id")
                if (id.isBlank()) continue
                // 关键修复：禁漫列表接口的 author 字段可能是字符串或数组，统一处理。
                // 之前直接 optString("author")，遇到数组形式返回空字符串 → 作者显示为 null。
                val author = parseAuthor(it.opt("author"))
                items.add(ComicBriefDto(
                    id = id,
                    name = it.optString("name"),
                    author = author,
                    tags = parseItemTags(it),
                    cover = buildCoverUrl(id),
                    // 关键修复：禁漫列表接口 likes 字段名可能是 likes/like_cnt/like_count，
                    // views 字段可能是 total_views/views/clicks，多套兜底避免字段缺失。
                    likes = pickFirst(it, "likes", "like_cnt", "like_count"),
                    views = pickFirst(it, "total_views", "views", "clicks"),
                    // 新增：发布时间，列表接口通常返回 addtime（Unix 时间戳）。
                    publishTime = parsePublishTime(it),
                ))
            }
        }
        // v27.13 诊断：列表接口通常不返回 tags，记录有多少条自带 tags（0=需 enrich 补全）
        val withTags = items.count { it.tags.isNotEmpty() }
        Logger.i("JmDirect", "parseSearchPage page=$page total=$total items=${items.size} withTags=$withTags")
        return PageResultDto(page = page, total = total, items = items)
    }

    /**
     * 从 JSON 对象中按优先级取第一个非空字符串字段。
     * 禁漫不同接口字段名不统一（likes vs like_cnt，total_views vs views），多套兜底。
     */
    private fun pickFirst(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = obj.optString(k).ifBlank { null }
            if (v != null) return v
        }
        return null
    }

    /**
     * 解析 author 字段，兼容字符串和数组两种返回形式。
     * 禁漫部分接口 author 返回 ["作者名"] 数组，部分返回 "作者名" 字符串。
     */
    private fun parseAuthor(authorVal: Any?): String? {
        return when (authorVal) {
            is JSONArray -> (0 until authorVal.length())
                .joinToString(" ") { authorVal.optString(it) }
                .ifBlank { null }
            is String -> authorVal.ifBlank { null }
            else -> null
        }
    }

    private fun parseFavoritePage(json: JSONObject, page: Int): PageResultDto {
        val total = json.optString("total").toIntOrNull()
        val list = json.optJSONArray("list")
        val items = mutableListOf<ComicBriefDto>()
        if (list != null) {
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                val id = it.optString("id")
                if (id.isBlank()) continue
                items.add(ComicBriefDto(
                    id = id,
                    name = it.optString("name"),
                    author = parseAuthor(it.opt("author")),
                    tags = parseItemTags(it),
                    cover = buildCoverUrl(id),
                    likes = pickFirst(it, "likes", "like_cnt", "like_count"),
                    views = pickFirst(it, "total_views", "views", "clicks"),
                    publishTime = parsePublishTime(it),
                ))
            }
        }
        return PageResultDto(page = page, total = total, items = items)
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36"
    }
}

/** 简单内存 CookieJar。禁漫移动端要求带 cookie，但不校验内容，自动存取即可。 */
private class MemoryCookieJar(context: android.content.Context) : CookieJar {
    /**
     * 跨域共享 + 持久化的 CookieJar。
     *
     * 关键修复（站点收藏 401）：
     * 1. **跨域共享**：禁漫有 8+ 个 API 域名做轮换。登录通常在某个域名上下发 AVS 会话 cookie，
     *    旧的 per-host 存储导致后续 /favorite 轮到其它域名时无 cookie → 401。
     *    这里改为按 cookie 名全局存储，loadForRequest 时把所有未过期 cookie 挂到当前请求域名返回，
     *    OkHttp 会全部写入 Cookie 头，从而任意域名都能带上登录态。
     * 2. **持久化**：登录态写入 SharedPreferences，App 重启后恢复，避免「登录信息还在但会话丢失」
     *    导致重启后 /favorite 一直 401。
     */
    private val prefs = context.getSharedPreferences("jm_cookies", android.content.Context.MODE_PRIVATE)
    private val store = ConcurrentHashMap<String, CookieEntry>()

    private data class CookieEntry(val name: String, val value: String, val expiresAt: Long)

    init {
        runCatching {
            val arr = JSONArray(prefs.getString("cookies", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                store[name] = CookieEntry(name, o.optString("value"), o.optLong("expiresAt", Long.MAX_VALUE))
            }
        }
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            store.values.forEach { e ->
                arr.put(JSONObject().put("name", e.name).put("value", e.value).put("expiresAt", e.expiresAt))
            }
            prefs.edit().putString("cookies", arr.toString()).apply()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(store) {
            cookies.forEach { c -> store[c.name] = CookieEntry(c.name, c.value, c.expiresAt) }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        synchronized(store) {
            // 清理过期
            store.entries.removeIf { it.value.expiresAt <= now }
            // 把所有未过期 cookie 挂到当前请求域名返回（跨域共享登录态）
            return store.values.map { e ->
                Cookie.Builder()
                    .name(e.name)
                    .value(e.value)
                    .domain(url.host)
                    .path("/")
                    .expiresAt(e.expiresAt.coerceAtLeast(now + 1000L))
                    .build()
            }
        }
    }

    /** 清空所有 cookie（登出时调用，移除 AVS 会话 token）。 */
    fun clear() {
        synchronized(store) {
            store.clear()
            prefs.edit().remove("cookies").apply()
        }
    }

    /** 是否存在指定名称的 cookie。 */
    fun hasCookie(name: String): Boolean = synchronized(store) { store[name] != null }

    /**
     * 手动注入一个 cookie（登录后服务端未通过 Set-Cookie 下发时兜底）。
     *
     * 关键修复（Bug 25）：默认 maxAgeSec 从 365 天改为 7 天。
     * AVS 是服务端会话 token，实际有效期通常只有几小时到几天（服务端策略不公开）。
     * 之前持久化 365 天导致：登录后数天/数周 token 早已在服务端失效，但本地仍持续发送，
     * 鉴权接口（/favorite 等）一直 401，用户却以为"已登录"，看不到重登录提示。
     * 7 天是合理的上限：超过则视为过期，loadForRequest 会自动清理，触发用户重新登录。
     * 若服务端通过 Set-Cookie 下发了真实 expiresAt，saveFromResponse 会覆盖此值。
     */
    fun addCookie(name: String, value: String, maxAgeSec: Long = 7L * 24 * 3600) {
        synchronized(store) {
            store[name] = CookieEntry(name, value, System.currentTimeMillis() + maxAgeSec * 1000)
            persist()
        }
    }
}
