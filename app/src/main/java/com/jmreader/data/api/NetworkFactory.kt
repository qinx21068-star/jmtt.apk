package com.jmreader.data.api

import com.jmreader.core.Logger
import com.jmreader.core.LoggingInterceptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 网络工厂：根据用户配置的后端地址动态创建 Retrofit 实例。
 * 切换后端地址时会重建。
 *
 * 调参说明（针对「一直转圈」问题）：
 * - 连接超时 6s：连不上后端快速失败
 * - 读取超时 20s：图片走 /api/img 代理，留足时间但不会无限等
 * - 整体 callTimeout 25s：硬上限，超时即报错，UI 不再无限转圈
 * - 失败立即在 UI 显示错误（含原因 + 重试 + 查看日志）
 */
object NetworkFactory {

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    /**
     * 构建后端 API。
     * @param baseUrl 后端地址
     * @param proxyStr 代理设置（"host:port" 或 "socks5://host:port"，null=不走代理）。v27.5 #37 新增。
     * @return [BuiltApi] 含 JMApi 与底层 OkHttpClient；切换后端时调用方应 [OkHttpClient.shutdown]
     *   旧的 client，避免连接池/Dispatcher 线程泄漏（每次重建都泄漏一个 client 实例）。
     */
    fun build(baseUrl: String, proxyStr: String? = null): BuiltApi {
        val rawUrl = baseUrl.trim()
        if (rawUrl.isEmpty()) {
            Logger.w("Net", "后端地址为空，无法构建 API")
        }
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            Logger.w("Net", "后端地址缺少 http(s):// 前缀: $rawUrl")
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)   // 整个请求上限，超时即报错，避免无限转圈
            .retryOnConnectionFailure(true)
            .addInterceptor(LoggingInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.NONE // 已有自定义日志，关闭重复
            })

        // v27.5 #37：应用代理设置
        parseProxy(proxyStr)?.let { (proxy, type) ->
            builder.proxy(proxy)
            Logger.i("Net", "已应用代理: $type")
        }

        val client = builder.build()

        // Retrofit 要求 baseUrl 以 '/' 结尾
        val url = (rawUrl.ifEmpty { "http://localhost:8000" }).trimEnd('/') + "/"

        val api = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(JMApi::class.java)
        return BuiltApi(api, client)
    }

    /** [build] 的返回值，把 client 暴露出来供调用方在重建时 shutdown 旧实例。 */
    data class BuiltApi(val api: JMApi, val client: OkHttpClient)

    /**
     * v27.5 #37：解析代理字符串。
     * 支持：
     * - "host:port" → HTTP 代理
     * - "http://host:port" → HTTP 代理
     * - "https://host:port" → HTTP 代理（HTTPS 上游）
     * - "socks5://host:port" → SOCKS 代理
     * 返回 (Proxy, 类型描述)；无效格式返回 null。
     */
    fun parseProxy(proxyStr: String?): Pair<Proxy, String>? {
        if (proxyStr.isNullOrBlank()) return null
        val s = proxyStr.trim()
        return runCatching {
            when {
                s.startsWith("socks5://", ignoreCase = true) -> {
                    val hp = s.substringAfter("socks5://").removeSuffix("/")
                    val (h, p) = splitHostPort(hp) ?: return@runCatching null
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(h, p)) to "socks5://$h:$p"
                }
                s.startsWith("http://", ignoreCase = true) -> {
                    val hp = s.substringAfter("http://").removeSuffix("/")
                    val (h, p) = splitHostPort(hp) ?: return@runCatching null
                    Proxy(Proxy.Type.HTTP, InetSocketAddress(h, p)) to "http://$h:$p"
                }
                s.startsWith("https://", ignoreCase = true) -> {
                    val hp = s.substringAfter("https://").removeSuffix("/")
                    val (h, p) = splitHostPort(hp) ?: return@runCatching null
                    Proxy(Proxy.Type.HTTP, InetSocketAddress(h, p)) to "https://$h:$p"
                }
                else -> {
                    // 朴素 host:port → HTTP 代理
                    val (h, p) = splitHostPort(s) ?: return@runCatching null
                    Proxy(Proxy.Type.HTTP, InetSocketAddress(h, p)) to "http://$h:$p"
                }
            }
        }.getOrNull()
    }

    private fun splitHostPort(s: String): Pair<String, Int>? {
        val idx = s.lastIndexOf(':')
        if (idx <= 0 || idx == s.length - 1) return null
        val host = s.substring(0, idx)
        val port = s.substring(idx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }
}
