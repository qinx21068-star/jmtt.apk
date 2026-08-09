package com.jmreader.core

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.size.Precision
import com.jmreader.data.api.direct.JmDirectClient
import com.jmreader.data.api.direct.JmImageFetcher
import okhttp3.OkHttpClient

/**
 * 全局 Coil ImageLoader 配置：
 * - 内存缓存 25%
 * - 磁盘缓存 100MB（阅读器图片可重复利用）
 * - 自动降采样（按 ImageView 大小解码，不全尺寸加载 → 解决滚动卡顿）
 * - 启用硬件位图
 * - 注册 JmImageFetcher：禁漫分割图自动解密
 *
 * 在 AndroidManifest 的 application name 仍指向 JMApp；
 * JMApp 实现 ImageLoaderFactory 即可被 Coil 自动使用。
 */
object CoilSetup : ImageLoaderFactory {

    private var okClient: OkHttpClient? = null
    private var directClient: JmDirectClient? = null

    fun bindOkHttp(client: OkHttpClient) {
        okClient = client
    }

    /** 绑定直连客户端，供 JmImageFetcher 做图片域名轮换。 */
    fun bindDirectClient(client: JmDirectClient) {
        directClient = client
    }

    override fun newImageLoader(): ImageLoader {
        val ctx = app ?: error("CoilSetup.app 未初始化")
        val client = okClient ?: OkHttpClient.Builder().build()
        val dc = directClient
        // 禁漫图片解密临时文件目录：用 cacheDir 下的子目录，fetch 时自动清理旧文件
        val imgTempDir = java.io.File(ctx.cacheDir, "jm_img_temp").apply { if (!exists()) mkdirs() }
        return ImageLoader.Builder(ctx)
            .okHttpClient(client)
            // 注册禁漫图片解密 Fetcher（仅处理带 jm_sid 的 URL，其他交给默认 fetcher）
            .components {
                if (dc != null) add(JmImageFetcher.Factory(client, dc, imgTempDir))
            }
            .memoryCache {
                // v27.6：从 25% 增到 35%，阅读器翻页来回切换时更多图片命中内存缓存，
                // 避免重复从磁盘临时文件解码（JmImageFetcher 每次都解码+写临时文件很慢）
                MemoryCache.Builder(ctx).maxSizePercent(0.35).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(ctx.cacheDir.resolve("image_cache"))
                    // 500MB：阅读器图片可重复利用，划回去不用重新下载。
                    // 之前 100MB 太小，一本本子 30-50 张图约 10-25MB，只能缓存 4-10 本，
                    // 导致"当前话已加载的内容划回去要重新加载"。
                    .maxSizeBytes(500L * 1024 * 1024)
                    .build()
            }
            // 关键性能优化：不开全局 crossfade。
            // 列表快速滚动时几十张图同时跑 300ms 淡入 → GPU 抖动 → 掉帧。
            // 详情页/阅读器如需淡入，单点在 ImageRequest 上显式 .crossfade(true)。
            .precision(Precision.AUTOMATIC)        // 自动降采样
            .allowHardware(true)                    // 硬件位图省内存
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    private var app: Application? = null
    fun init(app: Application) { this.app = app }
}
