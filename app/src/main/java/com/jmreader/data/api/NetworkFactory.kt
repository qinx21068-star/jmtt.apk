package com.jmreader.data.api

import com.jmreader.core.Logger
import com.jmreader.core.LoggingInterceptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
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

    fun build(baseUrl: String): JMApi {
        val rawUrl = baseUrl.trim()
        if (rawUrl.isEmpty()) {
            Logger.w("Net", "后端地址为空，无法构建 API")
        }
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            Logger.w("Net", "后端地址缺少 http(s):// 前缀: $rawUrl")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)   // 整个请求上限，超时即报错，避免无限转圈
            .retryOnConnectionFailure(true)
            .addInterceptor(LoggingInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.NONE // 已有自定义日志，关闭重复
            })
            .build()

        // Retrofit 要求 baseUrl 以 '/' 结尾
        val url = (rawUrl.ifEmpty { "http://localhost:8000" }).trimEnd('/') + "/"

        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(JMApi::class.java)
    }
}
