package com.jmreader.data.api.direct

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.jmreader.core.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Coil 自定义 Fetcher：处理禁漫图片的下载与解密。
 *
 * 触发条件：图片 URL 的 query 中带 jm_sid=<scramble_id>（由 JmDirectClient.chapterImages 注入）。
 *
 * 流程：
 * 1. 用共享 OkHttp 下载图片字节（图片 CDN 域名失败时自动轮换重试）
 * 2. 解析 jm_sid、aid（从 URL /media/photos/{aid}/）、filename
 * 3. 调用 JmImageDecoder 解密分割图（GIF 不解密，服务端未切图）
 * 4. 返回解密后的字节作为 SourceResult 给 Coil
 *
 * 用 SourceResult 而非 DrawableResult：
 * - SourceResult 会被 Coil 写入磁盘缓存（DrawableResult 不会），避免重复下载+解密
 * - telephoto 的子采样分块能直接复用解密后的字节，不会绕过 Fetcher 加载未解密原图
 * - Fetcher 内不自行 BitmapFactory.decodeByteArray，避免大图 OOM；由 Coil 按 target 尺寸降采样解码
 *
 * 若 jm_sid 不存在（如封面、普通图片），交给 Coil 默认 HttpFetcher 处理。
 */
class JmImageFetcher(
    private val client: OkHttpClient,
    private val directClient: JmDirectClient,
    private val data: String,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // 仅处理带 jm_sid 的禁漫图片 URL
        val sidMarker = "jm_sid="
        val sidIdx = data.indexOf(sidMarker)
        if (sidIdx < 0) return null  // 交给默认 fetcher

        val scrambleId = data.substring(sidIdx + sidMarker.length)
            .substringBefore('&').substringBefore('#').toLongOrNull() ?: 0L

        // 去掉 jm_sid 参数，得到真实图片 URL
        val cleanUrl = removeQuery(data, "jm_sid")
        val aid = JmImageDecoder.parseAidFromUrl(cleanUrl)
        val filename = JmImageDecoder.parseFileNameFromUrl(cleanUrl)

        // GIF 服务端不做切图，跳过解密（否则会把未切图的 GIF 重排损坏）
        val pathPart = cleanUrl.substringBefore('?')
        val isGif = pathPart.endsWith(".gif", ignoreCase = true)

        // 诊断日志：确认分割参数（用于排查"分割太密集"问题）
        val num = JmImageDecoder.getScrambleNum(scrambleId, aid, filename)
        Logger.d("JmImg", "aid=$aid fn=$filename sid=$scrambleId num=$num gif=$isGif")

        // 下载（带禁漫图片专用 header）。图片 CDN 域名失败时轮换重试，避免单域名被墙导致图片全挂。
        val bytes = downloadWithRotate(cleanUrl)

        // 解密（GIF / 无需分割跳过）。
        // decodeToBytes 在 BitmapFactory 解码失败时会原样返回 bytes（未解密），
        // 此时不能标记 mimeType=image/jpeg（会让 Coil 用错 decoder），需探测是否真的解密了。
        val decodeFailed: Boolean
        val decoded = if (isGif || num == 0) {
            decodeFailed = false
            bytes
        } else {
            val before = bytes.size
            val out = JmImageDecoder.decodeToBytes(bytes, scrambleId, aid, filename)
            // 简单探测：解密成功时 out 是重编码的 JPEG，size 通常与原 webp 不同；
            // 解密失败时 out === bytes（原样返回），size 完全一致。
            decodeFailed = (out.size == before) && out === bytes
            if (decodeFailed) {
                Logger.w("JmImg", "解密失败，原样返回未解密字节: aid=$aid fn=$filename num=$num")
            }
            out
        }

        // mimeType：解密后压成 jpeg；未解密按 URL 扩展名推断，让 Coil 选对的 decoder
        val mimeType = when {
            isGif -> "image/gif"
            num == 0 || decodeFailed -> {
                val ext = pathPart.substringAfterLast('.', "jpeg").lowercase()
                when (ext) {
                    "webp" -> "image/webp"
                    "png" -> "image/png"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }
            }
            else -> "image/jpeg"
        }

        // 返回 SourceResult：Coil 自动写磁盘缓存 + telephoto 子采样可用，且不在此持有 Bitmap（省内存）
        // coil 2.7.0 的 ImageSource 工厂是包级顶层函数 ImageSource(file: okio.Path)，
        // 需要把 java.io.File 转成 okio.Path（.toOkioPath()）。从内存字节先落临时文件再交给 Coil。
        // deleteOnExit 让进程退出时清理，避免累积。
        val ext = if (mimeType == "image/gif") ".gif" else if (mimeType == "image/webp") ".webp" else ".jpg"
        val tempFile = File.createTempFile("jm_img_", ext).apply { writeBytes(decoded); deleteOnExit() }
        return SourceResult(
            source = ImageSource(tempFile.toOkioPath()),
            mimeType = mimeType,
            dataSource = DataSource.NETWORK,
        )
    }

    /**
     * 下载图片字节。当前图片域名失败时，遍历其他图片域名重试，提高可用性。
     * 下载 URL 的 host 会被替换为候选域名；path/query 保持不变。
     */
    private fun downloadWithRotate(originalUrl: String): ByteArray {
        val domains = directClient.imageDomainList()
        // 第一个候选是当前域名，后续按列表轮换
        val currentHost = directClient.currentImageDomain()
        val ordered = (listOf(currentHost) + domains).distinct()
        var lastErr: Throwable? = null
        for (host in ordered) {
            val tryUrl = replaceHost(originalUrl, host)
            val req = Request.Builder()
                .url(tryUrl)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("X-Requested-With", "com.JMComic3.app")
                .header("Referer", "https://www.cdnaspa.club/")
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val b = resp.body?.bytes() ?: throw RuntimeException("图片响应为空")
                    // 成功：若不是当前域名，把全局当前域名切到这个可用的
                    if (host != currentHost) directClient.rotateImageDomain()
                    return b
                }
            } catch (e: Throwable) {
                Logger.w("JmImg", "下载失败 $tryUrl: ${Logger.brief(e)}")
                lastErr = e
            }
        }
        throw lastErr ?: RuntimeException("图片下载失败：无可用域名 $originalUrl")
    }

    /** 替换 URL 的 host 部分，用于图片域名轮换。 */
    private fun replaceHost(url: String, newHost: String): String {
        // https://oldhost/path?query → https://newhost/path?query
        val m = Regex("^(https?://)([^/]+)(/.*)?$").matchEntire(url) ?: return url
        val scheme = m.groupValues[1]
        val rest = m.groupValues[3]
        return "$scheme$newHost$rest"
    }

    /** 从 URL 中移除指定 query 参数。 */
    private fun removeQuery(url: String, key: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        val base = url.substring(0, qIdx)
        val query = url.substring(qIdx + 1)
        val kept = query.split('&')
            .filterNot { it.startsWith("$key=") || it == key }
            .joinToString("&")
        return if (kept.isBlank()) base else "$base?$kept"
    }

    class Factory(
        private val client: OkHttpClient,
        private val directClient: JmDirectClient,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            // 只接管带 jm_sid 的 http(s) URL。
            // 关键修复：必须用 Fetcher.Factory<Uri> 而非 <String>。
            // Coil 2.x 的 StringMapper 会先把 String URL 映射成 android.net.Uri，
            // 再用 mapped data 选 Fetcher。Factory<String> 永远不会被调用，
            // 导致禁漫图片走默认 HttpUriFetcher（未解密）→ 图片错位。
            val url = data.toString()
            if (url.startsWith("http") && url.contains("jm_sid=")) {
                return JmImageFetcher(client, directClient, url)
            }
            return null
        }
    }
}
