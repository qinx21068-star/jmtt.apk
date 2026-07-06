package com.jmreader.core

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp 拦截器：记录每个请求的方法/URL/耗时/状态码/失败原因。
 * 用于排查「加载不出来」「一直转圈」类问题。
 */
class LoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val t0 = System.nanoTime()
        val url = redact(req.url.toString())

        Logger.d("Net", "→ ${req.method} $url")

        return try {
            val resp = chain.proceed(req)
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            if (resp.isSuccessful) {
                Logger.d("Net", "← ${resp.code} (${ms}ms) $url")
            } else {
                Logger.w("Net", "← ${resp.code} ${resp.message} (${ms}ms) $url")
            }
            resp
        } catch (e: IOException) {
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            Logger.e("Net", "✗ ${e.javaClass.simpleName}: ${e.message} (${ms}ms) $url", e)
            throw e
        } catch (e: Throwable) {
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            Logger.e("Net", "✗ ${e.javaClass.simpleName}: ${e.message} (${ms}ms) $url", e)
            throw IOException("Network failure: ${e.message}", e)
        }
    }

    /** 截断过长的 URL，避免日志爆炸。 */
    private fun redact(url: String): String {
        return if (url.length > 200) url.substring(0, 200) + "...(truncated)" else url
    }
}
