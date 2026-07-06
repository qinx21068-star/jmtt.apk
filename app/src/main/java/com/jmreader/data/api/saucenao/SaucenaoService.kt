package com.jmreader.data.api.saucenao

import com.jmreader.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SauceNAO 以图搜图服务。
 *
 * API 文档：https://saucenao.com/user.php?page=search-api
 * 端点：https://saucenao.com/search.php
 *
 * 支持两种搜图方式：
 * 1. 上传本地图片文件（multipart/form-data，字段名 "file"）
 * 2. 提供图片 URL（参数 url）
 *
 * 限流：
 * - 无 api_key：4 次/30秒
 * - 有 api_key：6 次/30秒，200 次/天
 *
 * 返回 JSON，关键字段：
 * - header.status: 0=成功，>0=错误
 * - header.short_remaining / long_remaining: 剩余配额
 * - results[].header.similarity: 相似度
 * - results[].header.thumbnail: 缩略图 URL
 * - results[].data.title: 标题
 * - results[].data.ext_urls: 来源 URL 列表
 * - results[].data.author_name / member_name: 作者
 * - results[].data.source: 来源（如 Pixiv、Danbooru、Twitter 等）
 */
class SaucenaoService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    /** 用户可配置的 API key（无 key 也能用，但限流更严）。 */
    @Volatile
    var apiKey: String? = null

    /**
     * 上传本地图片文件搜图。
     *
     * @param imageFile 本地图片文件
     * @return 搜索结果列表，按相似度降序排列
     */
    suspend fun searchByFile(imageFile: File): Result<List<SaucenaoResult>> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("output_type", "2")  // JSON
                .addFormDataPart("db", "999")         // 全部索引
                .addFormDataPart("numres", "8")       // 返回 8 条
                .addFormDataPart("minsim", "30")      // 最小相似度 30%
            apiKey?.takeIf { it.isNotBlank() }?.let { multipartBuilder.addFormDataPart("api_key", it) }
            multipartBuilder.addFormDataPart(
                "file", imageFile.name,
                imageFile.asRequestBody(),
            )
            val req = Request.Builder()
                .url("https://saucenao.com/search.php")
                .post(multipartBuilder.build())
                .build()
            // .use {} 确保 Response 被关闭，避免连接泄漏
            client.newCall(req).execute().use { resp ->
                parseResponse(resp.body?.string().orEmpty())
            }
        }.onFailure { Logger.e("Saucenao", "searchByFile 失败", it) }
    }

    /**
     * 用图片 URL 搜图。
     */
    suspend fun searchByUrl(imageUrl: String): Result<List<SaucenaoResult>> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val urlBuilder = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("saucenao.com")
                .addPathSegment("search.php")
                .addQueryParameter("output_type", "2")
                .addQueryParameter("db", "999")
                .addQueryParameter("numres", "8")
                .addQueryParameter("minsim", "30")
                .addQueryParameter("url", imageUrl)
            apiKey?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("api_key", it) }
            val req = Request.Builder().url(urlBuilder.build())
                .post(ByteArray(0).toRequestBody())
                .build()
            client.newCall(req).execute().use { resp ->
                parseResponse(resp.body?.string().orEmpty())
            }
        }.onFailure { Logger.e("Saucenao", "searchByUrl 失败", it) }
    }

    /**
     * 解析 SauceNAO JSON 响应。
     * status=0 表示成功；非 0 通常是限流或错误，抛出异常。
     */
    private fun parseResponse(json: String): List<SaucenaoResult> {
        if (json.isBlank()) throw RuntimeException("空响应")
        val root = JSONObject(json)
        val header = root.optJSONObject("header")
        val status = header?.optInt("status", -1) ?: -1
        // status 0=成功，-1=错误。剩余配额 <0 表示超限
        val shortRemaining = header?.optInt("short_remaining", 0) ?: 0
        val longRemaining = header?.optInt("long_remaining", 0) ?: 0
        if (status != 0) {
            val msg = header?.optString("message") ?: "未知错误"
            throw RuntimeException("SauceNAO 错误 (status=$status): $msg")
        }
        if (shortRemaining < 0) throw RuntimeException("30秒内请求过多，请稍后再试")
        if (longRemaining < 0) throw RuntimeException("今日请求次数已用完")

        val results = root.optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<SaucenaoResult>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val itemHeader = item.optJSONObject("header") ?: continue
            val data = item.optJSONObject("data") ?: continue
            val similarity = itemHeader.optString("similarity").toDoubleOrNull() ?: 0.0
            // 只保留相似度 >= 30 的结果，过滤噪音
            if (similarity < 30.0) continue
            list.add(
                SaucenaoResult(
                    similarity = similarity,
                    thumbnail = itemHeader.optString("thumbnail"),
                    indexName = itemHeader.optString("index_name"),
                    title = data.optString("title").ifBlank { data.optString("source") },
                    author = data.optString("author_name")
                        .ifBlank { data.optString("member_name") }
                        .ifBlank { data.optString("creator") },
                    source = data.optString("source"),
                    extUrls = data.optJSONArray("ext_urls")?.let { arr ->
                        List(arr.length()) { arr.optString(it) }
                    } ?: emptyList(),
                    pixivId = data.optInt("pixiv_id", 0),
                ),
            )
        }
        // 按相似度降序
        return list.sortedByDescending { it.similarity }
    }
}

/**
 * SauceNAO 单条搜索结果。
 */
data class SaucenaoResult(
    /** 相似度（0-100，越高越匹配） */
    val similarity: Double,
    /** 缩略图 URL（SauceNAO 临时图床，有时效） */
    val thumbnail: String,
    /** 索引名称（如 "Pixiv Images", "Danbooru", "Twitter"） */
    val indexName: String,
    /** 标题 */
    val title: String,
    /** 作者 */
    val author: String,
    /** 来源标识（如 "Pixiv", "Danbooru"） */
    val source: String,
    /** 来源 URL 列表（可能有多个） */
    val extUrls: List<String>,
    /** Pixiv 作品 ID（如果是 Pixiv 来源） */
    val pixivId: Int,
)

/**
 * runCatching 的协程安全版本：不吞 CancellationException，保证协程取消正常传播。
 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
