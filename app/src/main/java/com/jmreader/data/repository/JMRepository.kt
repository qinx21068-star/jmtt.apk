package com.jmreader.data.repository

import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ChapterImagesDto
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.dto.JmCommentPageDto
import com.jmreader.data.dto.LoginRequest
import com.jmreader.data.dto.PageResultDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

/** 统一返回包装：成功带数据，失败带错误信息。 */
sealed class Resource<out T> {
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String) : Resource<Nothing>()
    data object Loading : Resource<Nothing>()
}

/**
 * 数据仓库：根据当前模式（直连 / 后端）分发请求。
 *
 * - 直连模式（默认）：调用 directClient，单机即可工作，无需任何后端。
 * - 后端模式（可选）：用户在设置里填了后端地址就走 Retrofit + Python backend。
 *
 * 所有 suspend 方法都在 IO 调度器上执行，避免阻塞 UI。
 */
class JMRepository(private val container: AppContainer) {

    private val moshi: Moshi = com.jmreader.data.api.NetworkFactory.moshi
    private val errorType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val errorAdapter = moshi.adapter<Map<String, Any?>>(errorType)

    private fun <T> Response<T>.toResource(): Resource<T> {
        return if (isSuccessful) {
            body()?.let { Resource.Success(it) } ?: Resource.Error("空响应")
        } else {
            val msg = runCatching {
                errorBody()?.string()?.let { errorAdapter.fromJson(it)?.get("detail")?.toString() }
            }.getOrNull() ?: "HTTP ${code()}"
            Resource.Error(msg)
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun search(
        q: String, page: Int = 1, order: String = "latest",
        time: String = "all", category: String = "",
    ): Resource<PageResultDto> = io {
        if (container.useBackend()) {
            container.ensureApi().search(q, page, order, time, category).toResource()
        } else {
            runCatching { container.directClient.search(q, page, order, time) }
                .toResource("搜索失败")
        }
    }

    suspend fun latest(page: Int = 1, category: String = ""): Resource<PageResultDto> = io {
        if (container.useBackend()) {
            container.ensureApi().latest(page, category).toResource()
        } else {
            runCatching { container.directClient.latest(page, category) }
                .toResource("加载最新失败")
        }
    }

    suspend fun ranking(time: String = "all", category: String = "", page: Int = 1): Resource<PageResultDto> = io {
        if (container.useBackend()) {
            container.ensureApi().ranking(time, category, page).toResource()
        } else {
            runCatching { container.directClient.ranking(time, category, page) }
                .toResource("加载排行失败")
        }
    }

    /**
     * 通用分类过滤：支持任意 分类 + 时间 + 排序 组合。
     *
     * 直连模式直接调 [JmDirectClient.categoriesFilter]，后端模式暂用 latest/ranking 兜底
     * （后端 API 未暴露通用 categoriesFilter 端点，后端场景退化为最新/排行二选一）。
     *
     * @param time a(全部)/t(今日)/w(本周)/m(本月)
     * @param category 分类 slug（空=全部，doujin/single/short/hanman/meiman/doujin_cosplay/3D/another/english_site）
     * @param order mr(最新)/mv(观看)/mp(图片数)/tf(评论)
     */
    suspend fun categoriesFilter(
        page: Int = 1,
        time: String = "a",
        category: String = "",
        order: String = "mr",
    ): Resource<PageResultDto> = io {
        if (container.useBackend()) {
            // 后端模式：order=mr 走 latest，其余走 ranking
            if (order == "mr") container.ensureApi().latest(page, category).toResource()
            else container.ensureApi().ranking(
                if (time == "a") "all" else time, category, page,
            ).toResource()
        } else {
            runCatching { container.directClient.categoriesFilter(page, time, category, order) }
                .toResource("加载失败")
        }
    }

    /**
     * 随机推荐：禁漫无原生 /random 端点，这里通过随机 page + 随机分类实现"换一批"体验。
     *
     * 策略：
     * - 时间范围固定 "a"（全部）：扩大候选池
     * - 分类从热门分类里随机选（含 "全部"）：增加多样性
     * - 排序固定 "mr"（最新）：保证有内容（按观看排序时深页可能为空）
     * - page 在 1..30 内随机：禁漫每个分类大概有几十页内容，深页可能为空
     *
     * 失败时由调用方重试一次（[RandomListViewModel.loadPage] 已处理）。
     */
    suspend fun randomComics(): Resource<PageResultDto> = io {
        val categories = listOf("", "doujin", "single", "short", "hanman", "meiman", "doujin_cosplay", "3D", "another")
        val category = categories.random()
        val page = (1..30).random()
        if (container.useBackend()) {
            container.ensureApi().latest(page, category).toResource()
        } else {
            runCatching {
                container.directClient.categoriesFilter(
                    page = page, time = "a", category = category, order = "mr",
                )
            }.toResource("随机推荐加载失败")
        }
    }

    suspend fun comicDetail(id: String): Resource<ComicDetailDto> = io {
        if (container.useBackend()) {
            container.ensureApi().comicDetail(id).toResource()
        } else {
            runCatching { container.directClient.albumDetail(id) }
                .toResource("加载详情失败")
        }
    }

    suspend fun chapterImages(id: String): Resource<ChapterImagesDto> = io {
        if (container.useBackend()) {
            container.ensureApi().chapterImages(id).toResource()
        } else {
            runCatching { container.directClient.chapterImages(id) }
                .toResource("加载章节图片失败")
        }
    }

    /**
     * 评论/讨论区（/forum JSON API，v27.9）。
     *
     * - 评论区：mode="manhua", aid=本子ID, uid=null → 该本子的评论列表
     * - 讨论区：mode="manhua", aid=null, uid=null → 全局评论流（所有本子的最新评论）
     * - 个人评论：mode="manhua", aid=null, uid=用户ID → 该用户的评论
     *
     * 直连模式调 [JmDirectClient.forum]（走 reqApi 通道，稳定）。
     * 后端模式暂未实现 forum 端点，明确报错而非假成功。
     */
    suspend fun forum(
        mode: String? = "manhua",
        aid: String? = null,
        uid: String? = null,
        page: Int = 1,
    ): Resource<JmCommentPageDto> = io {
        if (container.useBackend()) {
            Resource.Error("后端模式暂不支持评论/讨论区，请切换为直连模式")
        } else {
            // v27.13：处理 SessionExpiredException，风控 401 时清本地登录态
            try {
                Resource.Success(container.directClient.forum(mode, aid, uid, page))
            } catch (e: com.jmreader.data.api.direct.SessionExpiredException) {
                container.directClient.clearSession()
                Resource.Error("登录已过期，请重新登录")
            } catch (e: Throwable) {
                Resource.Error("加载评论失败：${com.jmreader.core.Logger.friendlyError(com.jmreader.core.Logger.brief(e))}")
            }
        }
    }

    suspend fun login(username: String, password: String): Resource<Boolean> = io {
        if (container.useBackend()) {
            val r = container.ensureApi().login(LoginRequest(username, password)).toResource()
            when (r) {
                is Resource.Success -> {
                    if (r.data.ok) container.settingsStore.setLoggedInUser(username)
                    Resource.Success(r.data.ok)
                }
                is Resource.Error -> r
                Resource.Loading -> Resource.Loading
            }
        } else {
            runCatching {
                val ok = container.directClient.login(username, password)
                if (ok) container.settingsStore.setLoggedInUser(username)
                ok
            }.toResource("登录失败")
        }
    }

    suspend fun logout(): Resource<Boolean> = io {
        if (container.useBackend()) {
            val r = container.ensureApi().logout().toResource()
            // 关键修复（Bug 43）：之前无条件 setLoggedInUser(null)，即使后端 logout 接口失败也清本地。
            // 弱网下点退出 → 看到已退出 → 实际服务端 session 没清 → 下次登录可能命中旧 session 的过期 cookie。
            // 现在仅在成功时才清本地登录态，失败时返回错误让用户重试。
            when (r) {
                is Resource.Success -> {
                    container.settingsStore.setLoggedInUser(null)
                    Resource.Success(true)
                }
                is Resource.Error -> r
                Resource.Loading -> Resource.Loading
            }
        } else {
            // 直连模式：清本地登录态 + 内存 cookie（含 AVS 会话 token）+ scramble 缓存
            container.directClient.logout()
            container.settingsStore.setLoggedInUser(null)
            Resource.Success(true)
        }
    }

    suspend fun serverFavorites(page: Int = 1): Resource<PageResultDto> = io {
        if (container.useBackend()) {
            container.ensureApi().favorites(page).toResource()
        } else {
            // 关键修复：用 SessionExpiredException 类型判断，不再用 contains("401") 字符串匹配。
            // 禁漫鉴权失败有两种形式：HTTP 401（错误信息含"401"）和 HTTP 200 + code=401
            // （错误信息是"禁漫返回错误: 未登录"，不含"401"）。字符串匹配漏判后者，
            // 用户会话过期时看到误导性的"所有域名均请求失败"而非引导重新登录。
            try {
                val data = container.directClient.favorites(page)
                Resource.Success(data)
            } catch (e: com.jmreader.data.api.direct.SessionExpiredException) {
                container.directClient.clearSession()
                container.settingsStore.setLoggedInUser(null)
                Resource.Error("登录已过期，请重新登录账号后查看站点收藏")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Resource.Error("加载收藏失败：${com.jmreader.core.Logger.friendlyError(e.message ?: e::class.simpleName.orEmpty())}")
            }
        }
    }

    suspend fun addServerFavorite(id: String): Resource<Boolean> = io {
        if (container.useBackend()) {
            val r = container.ensureApi().addFavorite(id).toResource()
            when (r) {
                is Resource.Success -> Resource.Success(r.data.ok)
                is Resource.Error -> Resource.Error(r.message)
                Resource.Loading -> Resource.Loading
            }
        } else {
            // 会话过期同样清登录态引导重新登录（与 serverFavorites 一致）
            try {
                container.directClient.addFavorite(id)
                Resource.Success(true)
            } catch (e: com.jmreader.data.api.direct.SessionExpiredException) {
                container.directClient.clearSession()
                container.settingsStore.setLoggedInUser(null)
                Resource.Error("登录已过期，请重新登录后再收藏")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Resource.Error("添加收藏失败：${com.jmreader.core.Logger.friendlyError(e.message ?: e::class.simpleName.orEmpty())}")
            }
        }
    }

    suspend fun removeServerFavorite(id: String): Resource<Boolean> = io {
        // 直连模式禁漫移动端 API 未暴露取消收藏接口，明确告知用户不可用，
        // 而非假成功让用户以为已取消（实际站点收藏仍在）。
        if (container.useBackend()) {
            val r = container.ensureApi().removeFavorite(id).toResource()
            when (r) {
                is Resource.Success -> Resource.Success(r.data.ok)
                is Resource.Error -> Resource.Error(r.message)
                Resource.Loading -> Resource.Loading
            }
        } else {
            Resource.Error("直连模式暂不支持取消站点收藏，请到禁漫网页端管理")
        }
    }

    suspend fun triggerServerDownload(id: String): Resource<Boolean> = io {
        // 直连模式由本地 DownloadManager 处理整本下载，不需要触发服务端下载。
        // 这里返回 Success(true) 是合理的——DetailScreen 调用此方法时，
        // 实际下载逻辑由 DownloadManager.enqueue 接管，本方法仅在后端模式有用。
        if (container.useBackend()) {
            val r = container.ensureApi().downloadAlbum(id).toResource()
            when (r) {
                is Resource.Success -> Resource.Success(r.data.ok)
                is Resource.Error -> Resource.Error(r.message)
                Resource.Loading -> Resource.Loading
            }
        } else {
            Resource.Success(true)
        }
    }

    /**
     * 把 Result 转成 Resource，失败信息用 friendlyError 包装。
     *
     * v27.15.2 自检修复：SessionExpiredException 在这里统一处理——
     * 之前 search/latest/ranking/categoriesFilter/randomComics/comicDetail/chapterImages
     * 等 7 个方法用它包装直连调用，会话过期（401/403）时只返回模糊错误，
     * 既不清 directClient session 也不清登录态，用户持续看到"加载失败"
     * 却不知道要重新登录。与 forum/serverFavorites 的显式处理对齐。
     */
    private fun <T> Result<T>.toResource(errPrefix: String): Resource<T> {
        // 关键：CancellationException 不能被 Result 吞掉再转成 Resource.Error，
        // 否则协程取消（如 VM 切章/退出）会被误认为"加载失败"并在 UI 显示错误，
        // 同时破坏协程取消的传播语义。
        if (isFailure) {
            val e = exceptionOrNull()
            if (e is CancellationException) throw e
            if (e is com.jmreader.data.api.direct.SessionExpiredException) {
                runCatching { container.directClient.clearSession() }
                // setLoggedInUser 是 suspend，toResource 非 suspend——用 appScope 异步清理
                container.appScope.launch {
                    runCatching { container.settingsStore.setLoggedInUser(null) }
                }
                return Resource.Error("登录已过期，请重新登录")
            }
        }
        return fold(
            onSuccess = { Resource.Success(it) },
            onFailure = { Resource.Error("$errPrefix：${com.jmreader.core.Logger.friendlyError(it.message ?: it::class.simpleName.orEmpty())}") },
        )
    }
}

/**
 * 后端模式专用：把后端图片原始 URL 转成经后端代理的 URL（解密分割图）。
 * 直连模式不需要此函数（图片 URL 已带 jm_sid 标记，由 JmImageFetcher 解密）。
 */
fun proxiedImageUrl(serverUrl: String, rawUrl: String): String {
    val base = serverUrl.trimEnd('/')
    return "$base/api/img?url=${java.net.URLEncoder.encode(rawUrl, "UTF-8")}"
}
