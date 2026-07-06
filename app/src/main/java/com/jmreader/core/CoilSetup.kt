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
        return ImageLoader.Builder(ctx)
            .okHttpClient(client)
            // 注册禁漫图片解密 Fetcher（仅处理带 jm_sid 的 URL，其他交给默认 fetcher）
            .components {
                if (dc != null) add(JmImageFetcher.Factory(client, dc))
            }
            .memoryCache {
                // 阅读器长图列表划回去重新加载的根因是内存缓存太小（原 25%）导致 bitmap 被挤出，
                // LazyColumn 回收 item 后再进入视口需重新解码。提到 50% 显著减少重新解码。
                MemoryCache.Builder(ctx).maxSizePercent(0.50).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(ctx.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
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
