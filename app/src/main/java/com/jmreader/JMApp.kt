package com.jmreader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.jmreader.core.CoilSetup
import com.jmreader.core.CrashHandler
import com.jmreader.core.Logger
import com.jmreader.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用入口，持有全局依赖容器。
 * 干净无广告：不集成任何统计/广告 SDK，无后台保活。
 *
 * 关键初始化：
 * - Logger：日志系统（文件 + 内存 + Logcat）
 * - CrashHandler：全局未捕获异常 → 写日志，避免静默闪退
 * - Coil ImageLoader：降采样 + 缓存，提升阅读器滚动流畅度
 *
 * 注意：DataStore 读取放到后台协程，不在主线程 runBlocking，避免 ANR。
 * 自定义域名合并异步进行，首次启动若未及时合并也只是用内置域名，不影响可用性。
 */
class JMApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /** App 级别的协程作用域，用于启动阶段的异步初始化任务。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 顺序很重要：Logger 最先
        Logger.init(this)
        CoilSetup.init(this)
        CrashHandler().install()

        instance = this
        container = AppContainer(this)
        // 让 Coil 用直连客户端的 OkHttp（共享连接池、超时配置、cookie）
        CoilSetup.bindOkHttp(container.directClient.http)
        // 让 JmImageFetcher 能做图片域名轮换
        CoilSetup.bindDirectClient(container.directClient)
        // 启动时把用户自定义 API 域名合并进直连客户端的域名池（自定义在前，优先使用）。
        // 异步合并：DataStore 读取在 IO 线程，不阻塞主线程，避免 ANR。
        // 内置域名池已含最新可用域名，未及时合并也不影响首请求。
        appScope.launch {
            runCatching {
                val custom = container.settingsStore.settings.first().customApiDomains
                if (custom.isNotEmpty()) {
                    container.directClient.mergeCustomDomains(custom.toList())
                    Logger.i("App", "已合并 ${custom.size} 个自定义 API 域名")
                }
            }
        }

        Logger.i("App", "JMApp 初始化完成，版本=1.0.0，默认直连模式")
    }

    /** Coil 通过 Application 上下文拿 ImageLoader。 */
    override fun newImageLoader(): ImageLoader = CoilSetup.newImageLoader()

    companion object {
        lateinit var instance: JMApp
            private set
    }
}
