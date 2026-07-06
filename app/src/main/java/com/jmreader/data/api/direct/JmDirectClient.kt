package com.jmreader.data.api.direct

import com.jmreader.core.Logger
import com.jmreader.data.dto.ChapterDto
import com.jmreader.data.dto.ChapterImagesDto
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.dto.PageResultDto
import kotlinx.coroutines.CancellationException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 */
class JmDirectClient {

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
    private val imageDomains = listOf(
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp.jmapiproxy2.cc",
        "cdn-msp2.jmapiproxy2.cc",
        "cdn-msp3.jmapiproxy2.cc",
        "cdn-msp.jmapinodeudzn.net",
        "cdn-msp3.jmapinodeudzn.net",
    )
    @Volatile private var imageDomainIndex = 0
    private val imageDomainLock = Any()

    private val cookieJar = MemoryCookieJar()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cookieJar(cookieJar)
        .build()

    // scramble_id 缓存（按 photoId），避免每张图都请求
    private val scrambleCache = ConcurrentHashMap<String, Long>()

    init {
        // 启动时后台异步拉取最新 API 域名（不阻塞；失败也无妨，请求全失败时还会再触发）。
        // 禁漫会定期换 API 域名，旧域名会 404，必须动态更新才能长期可用。
        Thread { runCatching { refreshApiDomains(forced = false) } }.start()
    }

    /**
     * 请求域名服务器，拉取禁漫最新 API 域名列表并更新 [apiDomains]。
     * @param forced true=强制刷新（用于全失败兜底）；false=受 5 分钟节流约束
     * @return 是否成功更新
     */
    private fun refreshApiDomains(forced: Boolean): Boolean {
        val now = System.currentTimeMillis()
        synchronized(refreshLock) {
            if (!forced && now - lastDomainRefreshMs < 5 * 60 * 1000) return false
            lastDomainRefreshMs = now
        }
        for (url in apiDomainServerUrls) {
            try {
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val text = resp.body?.string().orEmpty()
                    if (text.isBlank()) return@use
                    val arr = JSONObject(JMCrypto.decodeDomainServerResp(text)).optJSONArray("Server") ?: return@use
                    val list = (0 until arr.length())
                        .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                    if (list.isNotEmpty()) {
                        synchronized(apiDomains) {
                            apiDomains.clear()
                            apiDomains.addAll(list)
                            domainIndex = 0
                        }
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
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
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
                        throw RuntimeException("禁漫返回错误: $msg")
                    }
                    val data = outer.optString("data")
                    if (data.isBlank()) {
                        return data.ifBlank { "{}" }
                    }
                    return JMCrypto.decodeRespData(data, t)
                }
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
        postApiOnce(path, form, secret)?.let { return it }
        if (refreshApiDomains(forced = true)) {
            Logger.i("JmDirect", "域名更新后重试 POST $path")
            postApiOnce(path, form, secret)?.let { return it }
        }
        throw RuntimeException("所有域名均请求失败: POST $path")
    }

    private fun postApiOnce(path: String, form: Map<String, String>, secret: String): JSONObject? {
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
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) throw RuntimeException("空响应")
                    val outer = JSONObject(body)
                    if (outer.optInt("code", -1) != 200) {
                        throw RuntimeException(outer.optString("errorMsg", "HTTP ${resp.code}"))
                    }
                    val data = outer.optString("data")
                    return if (data.isBlank()) JSONObject("{}") else JSONObject(JMCrypto.decodeRespData(data, t))
                }
            } catch (e: Throwable) {
                Logger.w("JmDirect", "POST $path @ $domain 失败: ${Logger.brief(e)}")
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
            likes = data.optString("likes").ifBlank { null },
            views = data.optString("total_views").ifBlank { null },
            publishedTime = data.optString("publishtime").ifBlank { null },
            chapters = chapters,
        )
    }

    /** 章节（photo）图片列表。自动获取 scramble_id 并拼到图片 URL 上。 */
    suspend fun chapterImages(photoId: String): ChapterImagesDto {
        val json = JSONObject(reqApi("/chapter", mapOf("id" to photoId)))
        val data = json.optJSONObject("data") ?: json
        val name = data.optString("name")
        val scrambleId = getScrambleId(photoId, data.optString("id").ifBlank { photoId })

        val images = data.optJSONArray("images")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx ->
                val fn = arr.optString(idx)
                if (fn.isBlank()) null else buildImageUrl(data.optString("id").ifBlank { photoId }, fn, scrambleId)
            }
        } ?: emptyList()

        return ChapterImagesDto(
            id = photoId,
            title = name.ifBlank { null },
            scramble_id = scrambleId.toString(),
            images = images,
        )
    }

    /**
     * 获取 scramble_id（带缓存）。
     * 请求 /chapter_view_template 返回 HTML，解析 var scramble_id = (\d+);。
     * 失败兜底 220980。
     */
    private suspend fun getScrambleId(photoId: String, aidFromResp: String): Long {
        scrambleCache[photoId]?.let { return it }
        val aid = aidFromResp.toLongOrNull() ?: photoId.toLongOrNull() ?: 0L
        return try {
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
            val sid = match?.groupValues?.get(1)?.toLongOrNull() ?: JMCrypto.SCRAMBLE_220980.toLong()
            scrambleCache[photoId] = sid
            // 同 album 的 scramble_id 相同，这里用 photoId 的前缀没法精确关联 album，仅缓存 photoId
            sid
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w("JmDirect", "获取 scramble_id 失败，用默认 220980: ${Logger.brief(e)}")
            JMCrypto.SCRAMBLE_220980.toLong()
        }
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

    /** 登录。成功标志：响应中含 AVS token 字段 "s" 或 "uid"（禁漫不返回 success/status）。 */
    suspend fun login(username: String, password: String): Boolean {
        return try {
            val r = postApi("/login", mapOf("username" to username, "password" to password))
            r.has("s") || r.has("uid")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w("JmDirect", "登录失败: ${Logger.brief(e)}"); false
        }
    }

    /**
     * 登出：清除内存 cookie（含 AVS 会话 token）+ scramble 缓存。
     * 禁漫移动端没有显式 logout 接口，清本地凭证即可达到"登出"效果。
     */
    fun logout() {
        cookieJar.clear()
        scrambleCache.clear()
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

    /**
     * 测速单个域名：发一个轻量请求（/search 空关键词），返回延迟(ms)或失败原因。
     * @return Pair<延迟ms, 错误信息?>；成功时错误为 null
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
            http.newCall(req).execute().use { resp ->
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

    /** 批量测速所有域名，按延迟升序返回（失败的排最后）。 */
    fun testAllDomains(): List<Triple<String, Long?, String?>> {
        val list = apiDomainList()
        return list.map { host ->
            val (lat, err) = testDomain(host)
            Triple(host, lat, err)
        }.sortedWith(compareBy(
            { it.second == null },           // 有延迟的在前
            { it.second ?: Long.MAX_VALUE }, // 延迟小的在前
        ))
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
     * 从搜索/收藏列表项中解析标签，用于屏蔽过滤。
     *
     * 移动端列表接口不一定返回完整 tags 数组，这里做容错：
     * 1. 优先取 tags（数组或空格分隔字符串）
     * 2. 补充 category / category_sub 的 title（如「同人」「單本」）
     * 保证至少能按分类屏蔽。
     */
    private fun parseItemTags(item: JSONObject): List<String> {
        val tags = mutableListOf<String>()

        // tags 字段：可能为数组，也可能为空格分隔的字符串
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

        // category / category_sub 的 title 作为兜底标签
        item.optJSONObject("category")?.optString("title")?.trim()
            ?.takeIf { it.isNotBlank() && it !in tags }?.let { tags.add(it) }
        item.optJSONObject("category_sub")?.optString("title")?.trim()
            ?.takeIf { it.isNotBlank() && it !in tags }?.let { tags.add(it) }

        return tags
    }

    /** 当前图片域名（供 JmImageFetcher 拿到当前域名后自行拼装重试）。 */
    fun currentImageDomain(): String = synchronized(imageDomainLock) {
        imageDomains.getOrElse(imageDomainIndex) { imageDomains[0] }
    }

    /** 全部图片域名（供 JmImageFetcher 失败时轮换重试）。 */
    fun imageDomainList(): List<String> = imageDomains

    /** 切换图片域名（某域名失败时调用）。返回切换后的域名。 */
    fun rotateImageDomain(): String = synchronized(imageDomainLock) {
        imageDomainIndex = (imageDomainIndex + 1) % imageDomains.size
        imageDomains[imageDomainIndex]
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
                items.add(ComicBriefDto(
                    id = id,
                    name = it.optString("name"),
                    author = it.optString("author").ifBlank { null },
                    tags = parseItemTags(it),
                    cover = buildCoverUrl(id),
                    likes = it.optString("likes").ifBlank { null },
                    views = it.optString("total_views").ifBlank { null },
                ))
            }
        }
        return PageResultDto(page = page, total = total, items = items)
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
                    author = it.optString("author").ifBlank { null },
                    tags = parseItemTags(it),
                    cover = buildCoverUrl(id),
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
private class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // 同名 cookie 用新值覆盖旧值（登录刷新后服务端下发的 AVS/session 才能生效）
        synchronized(store) {
            val merged = LinkedHashMap<String, Cookie>()
            store[url.host].orEmpty().forEach { merged[it.name] = it }
            cookies.forEach { merged[it.name] = it }
            store[url.host] = merged.values.toList()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host].orEmpty().filter { it.expiresAt > now }
    }

    /** 清空所有 cookie（登出时调用，移除 AVS 会话 token）。 */
    fun clear() {
        synchronized(store) { store.clear() }
    }
}
