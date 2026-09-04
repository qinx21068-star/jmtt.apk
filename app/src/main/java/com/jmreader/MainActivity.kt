package com.jmreader

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jmreader.notification.ReadLaterServiceController
import com.jmreader.ui.components.DisclaimerDialog
import com.jmreader.ui.nav.JMApp
import com.jmreader.ui.screen.lock.AppLockScreen
import com.jmreader.ui.theme.JMTheme
import com.jmreader.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * v27.5 #28：MainActivity 改为继承 FragmentActivity 以支持 BiometricPrompt。
 *
 * ComponentActivity 是 FragmentActivity 的父类，所以原有 setContent / Compose 行为完全兼容。
 */
class MainActivity : FragmentActivity() {

    /** 是否强制最高刷新率，由设置页写入；启动时为 false，Compose 起来后由 settings flow 更新。 */
    @Volatile private var preferMaxRefreshRate = false

    /**
     * v27.5 #28：App 是否处于"已锁定"状态。
     * - 冷启动时若 settingsStore.appLockEnabled=true 立即置 true（同步从快照读取，不等待 flow emit）
     * - 之后每次 App 从后台回到前台（ON_STOP→ON_START），若启用应用锁则置 true
     * - 用户验证通过后置 false
     */
    @Volatile private var locked = false

    /** v27.5 #28：是否启用应用锁（缓存到字段避免每次访问都读 flow）。 */
    @Volatile private var appLockEnabled = false

    /** v27.5 #28：PIN（null=生物识别模式）。 */
    @Volatile private var appLockPin: String? = null

    /**
     * v27.5 稳定性加固：ProcessLifecycleObserver 命名字段。
     * 之前用匿名对象注册到 ProcessLifecycleOwner（进程级单例），无对应 removeObserver，
     * Activity 销毁后 Observer 仍持有 MainActivity 引用 → Activity 泄漏。
     * 现在改为命名字段，onDestroy 中 removeObserver 切断引用链。
     */
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (appLockEnabled) {
                locked = true
            }
        }
    }

    /**
     * v27.15 稍后再看通知权限管理。
     *
     * 流程：
     * - 用户在设置开启 readLaterNotificationEnabled → settings collector 检测到，
     *   若 Android 13+ 且未授权 → 启动 [notificationPermissionLauncher] 申请。
     * - 用户授予 → [onNotificationPermissionResult] 启动 Service。
     * - 用户拒绝 → 自动把 settings 改回 false（让 UI 显示与实际状态一致），并提示。
     * - 已授权时开启/关闭开关 → 直接 start/stop Service。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onNotificationPermissionResult(granted)
        }

    /** v27.15 通知权限申请结果处理。granted=true 启动 Service；false 回滚开关到 false。 */
    private fun onNotificationPermissionResult(granted: Boolean) {
        // 防御：Activity 已 finish 时不要操作 Service / settings（避免 IllegalStateException）
        if (isFinishing || isDestroyed) return
        val container = (application as JMApp).container
        if (granted) {
            ReadLaterServiceController.start(this)
        } else {
            // 用户拒绝：把开关回滚到 false，保持 UI 与实际状态一致（避免"开关亮着但通知没出来"）
            lifecycleScope.launch {
                runCatching {
                    container.settingsStore.setReadLaterNotificationEnabled(false)
                    com.jmreader.core.Logger.i("Main", "用户拒绝通知权限，已回滚 readLaterNotificationEnabled=false")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash 退出动画：让 splash 屋顶向上淡出，体验更顺滑（默认是瞬切）。
        val splash = installSplashScreen()
        splash.setOnExitAnimationListener { splashViewProvider ->
            val view = splashViewProvider.view
            view.animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(220L)
                .withEndAction { splashViewProvider.remove() }
                .start()
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as JMApp).container

        // v27.5 性能优化 + 指纹锁修复：启动时立即从 settingsStore 的同步快照读取应用锁配置。
        // 这样冷启动就能马上知道是否需要锁屏，不必等待 DataStore 异步 emit（避免"过好久才弹指纹"）。
        appLockEnabled = container.settingsStore.appLockEnabled
        appLockPin = container.settingsStore.appLockPin
        preferMaxRefreshRate = container.settingsStore.cachedSnapshot.preferMaxRefreshRate
        // v27.5 稳定性加固：locked 状态从 savedInstanceState 恢复。
        // 之前无条件 `locked = appLockEnabled`，Activity 被系统回收重建后用户已解锁的状态丢失，
        // 会被重新锁屏。现在 savedInstanceState != null 时按保存的状态恢复；仅冷启动才按 appLockEnabled 初始化。
        locked = savedInstanceState?.getBoolean(KEY_LOCKED, appLockEnabled) ?: appLockEnabled
        applyMaxRefreshRate()
        applyScreenshotBlock(container.settingsStore.cachedSnapshot.blockScreenshots)

        // 后台收集设置，刷新率 / 应用锁 / 截图屏蔽 实时生效（用户在设置页改了之后）
        // v27.5 性能优化：用 distinctUntilChanged 按字段去重，避免无关字段变化也触发
        // window.attributes 重写（window.attributes = params 会触发 window 重布局，很重）。
        lifecycleScope.launch {
            var lastRefreshRate: Boolean? = null
            var lastScreenshot: Boolean? = null
            var lastReadLaterEnabled: Boolean? = null
            container.settingsStore.settings.collect { s ->
                if (s.preferMaxRefreshRate != lastRefreshRate) {
                    lastRefreshRate = s.preferMaxRefreshRate
                    preferMaxRefreshRate = s.preferMaxRefreshRate
                    applyMaxRefreshRate()
                }
                // v27.5 #28：同步应用锁设置（轻量字段赋值，无需去重）
                appLockEnabled = s.appLockEnabled
                appLockPin = s.appLockPin
                // v27.5 #28：截图屏蔽（FLAG_SECURE）。仅在值变化时 addFlags/clearFlags，避免重复调用。
                if (s.blockScreenshots != lastScreenshot) {
                    lastScreenshot = s.blockScreenshots
                    applyScreenshotBlock(s.blockScreenshots)
                }
                // v27.15：稍后再看通知开关变化 → 启停 Service
                if (s.readLaterNotificationEnabled != lastReadLaterEnabled) {
                    lastReadLaterEnabled = s.readLaterNotificationEnabled
                    applyReadLaterNotification(s.readLaterNotificationEnabled)
                }
            }
        }

        // v27.5 #28：监听 App 前后台切换，从后台回前台时若启用应用锁则触发锁定。
        // 用 ProcessLifecycleOwner 而非 Activity lifecycle：用户跳到其他 App 再回来，
        // Activity 只是 onPause→onResume（Configuration change 也可能触发），无法可靠区分。
        // ProcessLifecycleOwner 在整个 App 进程进入后台（ON_STOP）才发事件。
        // v27.5 稳定性加固：用命名字段 processObserver，onDestroy 中 removeObserver 切断引用链，
        // 避免进程级单例持有已销毁的 MainActivity 导致泄漏。
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)

        setContent {
            // v27.5 性能优化：用 cachedSnapshot 作为 collectAsState 初始值，避免 null → 默认 → 真实 两轮重组
            val settings = container.settingsStore.settings.collectAsState(initial = container.settingsStore.cachedSnapshot)
            // v27.5 卡顿根因修复（用户连续多轮反馈"所有界面上下滑动都卡"）：
            // 之前 `val s = settings.value` 在 setContent 顶层直接读 State，每次 settings 任何字段变化
            // （包括 ReaderViewModel.saveProgress 读 incognito 触发的 emit）都会让 setContent lambda 整体重跑，
            // 进而让 JMTheme 的 content lambda（包含整个 NavHost + Scaffold + 当前屏幕）无法跳过重组。
            // 现在所有字段都用 derivedStateOf 派生，且 JMApp 的渲染完全不依赖 settings 变化——
            // 只有真正需要响应 settings 变化的 Composable（JMTheme / DisclaimerDialog / AppLockScreen）
            // 才会因对应字段变化而重组，JMApp 内部的 NavHost 与屏幕保持稳定。
            val s by remember { derivedStateOf { settings.value } }
            // v27.5 性能修复：所有从 settings 读取的字段都用 derivedStateOf 派生，
            // 让只有该字段变化时才触发依赖它的 Composable 重组。
            val themeMode by remember { derivedStateOf { s.themeMode } }
            val dynamic by remember { derivedStateOf { s.dynamicColor } }
            // v27.5 卡顿根因修复：把主题/外观相关字段用 derivedStateOf 派生，
            // 让只有这些字段变化时才触发 JMTheme 重组。
            // 之前整个 settings 对象变化都触发 JMTheme 重组 → BackgroundImageLayer 重组 →
            // 背景图重新解码。现在 derivedStateOf 会记忆上次值，只有主题字段变化才让 JMTheme 重组。
            // 其余字段（incognito / appLockEnabled / splashAnim 等）变化不会牵动 JMTheme。
            val colorSchemeId by remember { derivedStateOf { s.colorSchemeId } }
            val customColors by remember { derivedStateOf { s.customColors } }
            val backgroundImageUri by remember { derivedStateOf { s.backgroundImageUri } }
            val backgroundImageOpacity by remember { derivedStateOf { s.backgroundImageOpacity } }
            val backgroundImageBlur by remember { derivedStateOf { s.backgroundImageBlur } }
            val backgroundImageLightOnly by remember { derivedStateOf { s.backgroundImageLightOnly } }
            val cardCornerRadius by remember { derivedStateOf { s.cardCornerRadius } }
            val cardElevation by remember { derivedStateOf { s.cardElevation } }
            val listTitleFontSize by remember { derivedStateOf { s.listTitleFontSize } }
            val listBodyFontSize by remember { derivedStateOf { s.listBodyFontSize } }
            val coverAspectRatio by remember { derivedStateOf { s.coverAspectRatio } }
            val disclaimerAccepted by remember { derivedStateOf { s.disclaimerAccepted } }
            val splashAnimEnabled by remember { derivedStateOf { s.splashAnim } }

            // 首次启动免责声明弹窗：未同意时显示，5 秒倒计时强制阅读。
            // 一旦用户同意（disclaimerAccepted=true），后续启动不再显示。
            var showDisclaimer by remember { mutableStateOf(false) }
            // v27.5 性能修复：key 用具体字段 disclaimerAccepted，避免任何无关 settings 字段变化都触发 effect
            LaunchedEffect(disclaimerAccepted) {
                if (!disclaimerAccepted) showDisclaimer = true
            }

            // v27.5 #28：是否当前显示锁屏（依赖同步的 locked 字段，settings 已有初始值）
            var showLock by remember { mutableStateOf(locked) }
            LaunchedEffect(locked, disclaimerAccepted, showDisclaimer) {
                // 仅在已同意免责声明后才显示锁屏（避免锁屏遮挡声明弹窗）
                showLock = locked && disclaimerAccepted && !showDisclaimer
            }

            JMTheme(
                themeMode = themeMode,
                dynamicColor = dynamic,
                colorSchemeId = colorSchemeId,
                customColors = customColors,
                backgroundImageUri = backgroundImageUri,
                backgroundImageOpacity = backgroundImageOpacity,
                backgroundImageBlur = backgroundImageBlur,
                backgroundImageLightOnly = backgroundImageLightOnly,
                cardCornerRadius = cardCornerRadius,
                cardElevation = cardElevation,
                listTitleFontSize = listTitleFontSize,
                listBodyFontSize = listBodyFontSize,
                coverAspectRatio = coverAspectRatio,
            ) {
                // 关键修复：未同意免责声明前不渲染主界面（避免在声明未同意时操作各功能，合规风险）。
                val canEnter = disclaimerAccepted && !showDisclaimer
                if (canEnter) {
                    // v27.5 #26：启动动画（splash 后列表淡入）。
                    // splashAnim=true 时主界面从 alpha=0 渐显到 1（300ms），与 splash 退出动画衔接。
                    // 关闭则直接显示。
                    val alphaAnim = remember { Animatable(if (splashAnimEnabled) 0f else 1f) }
                    LaunchedEffect(splashAnimEnabled) {
                        if (splashAnimEnabled) {
                            alphaAnim.animateTo(1f, tween(durationMillis = 300))
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // v27.5 卡顿修复：用 graphicsLayer 在 draw 阶段读 alpha，避免在
                            // composition 阶段读 Animatable.value 导致每帧重组整个 JMApp（300ms 内）。
                            .graphicsLayer { alpha = alphaAnim.value },
                    ) {
                        JMApp(container = container)
                    }
                }
                if (showDisclaimer) {
                    DisclaimerDialog(
                        forceCountdown = true,
                        onAccept = {
                            lifecycleScope.launch {
                                container.settingsStore.setDisclaimerAccepted(true)
                                showDisclaimer = false
                            }
                        },
                        onDismiss = {
                            // 用户不同意：直接 finish 退出 App
                            finishAffinity()
                        },
                    )
                }
                // v27.5 #28：应用锁屏覆盖在主界面之上
                if (showLock) {
                    AppLockScreen(
                        pin = appLockPin,
                        onUnlocked = {
                            locked = false
                            showLock = false
                        },
                    )
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyMaxRefreshRate()
    }

    override fun onResume() {
        super.onResume()
        // 关键修复：从设置页切回 MainActivity 时，window 已 attach，
        // 但之前 applyMaxRefreshRate 可能因 preferMaxRefreshRate 还是 false（settings flow 未 emit）而跳过。
        // onResume 重应用一次，保证用户开关切换后真正生效。
        applyMaxRefreshRate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // v27.5 稳定性加固：保存 locked 状态，Activity 被系统回收重建后恢复，
        // 避免用户已解锁后因 Activity 重建被重新锁屏。
        outState.putBoolean(KEY_LOCKED, locked)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // v27.5 稳定性加固：ProcessLifecycleOwner 是进程级单例，Activity 销毁后 Observer 仍挂载
        // 会持续持有 MainActivity 引用导致泄漏。onDestroy 中 removeObserver 切断引用链。
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver) }
        // v27.15.2 自检修复：tags 缓存退出前强制落盘。
        // 之前 flush() 从未被调用，节流调度在进程被杀时丢失 dirty 数据，
        // 下次启动需重新 enrich（浪费流量 + 屏蔽规则短暂失效）。
        // runBlocking 是必要的：onDestroy 后 appScope 不保证有机会跑（进程可能直接退出）。
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(1000L) {
                    JMApp.instance.container.comicTagsCache.flush()
                }
            }
        }
        super.onDestroy()
    }

    /**
     * 强制最高刷新率：从 Display.supportedModes 里挑 refreshRate 最大的，
     * 写到 Window.attributes.preferredDisplayModeId。
     * 仅 Android 11+（API 30）有效；旧版本无操作。
     * 很多 ROM 默认会降刷新率省电，导致列表滚动卡顿，开启后可显著改善流畅度。
     */
    private fun applyMaxRefreshRate() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        // v27.5 稳定性加固：window.attributes 在 lifecycleScope.launch 的 collect 回调里被调用，
        // 极端时序下（Activity finish 后 settings 又 emit 一次，coroutine 还未取消）window 可能
        // 抛 IllegalStateException 或 NPE。参照 applyScreenshotBlock 用 runCatching 包裹防御。
        if (isFinishing || isDestroyed) return
        runCatching {
            // 关键修复：关闭开关时必须显式重置 preferredDisplayModeId = 0（系统默认）。
            // 之前开过开关后 preferredDisplayModeId 被写成最高刷新率 modeId，
            // 关闭开关时直接 return 不重置，window 仍锁定高刷，耗电不下降，与设置项承诺不符。
            if (!preferMaxRefreshRate) {
                val params = window.attributes
                if (params.preferredDisplayModeId != 0) {
                    params.preferredDisplayModeId = 0
                    window.attributes = params
                }
                return@runCatching
            }
            val display = display ?: return@runCatching
            val modes = display.supportedModes
            if (modes.isEmpty()) return@runCatching
            // 选刷新率最高的；若多个相同，优先分辨率匹配当前的（避免改分辨率）。
            val currentMode = display.mode
            val best = modes.filter { it.physicalWidth == currentMode.physicalWidth
                    && it.physicalHeight == currentMode.physicalHeight }
                .maxByOrNull { it.refreshRate }
                ?: modes.maxByOrNull { it.refreshRate }
                ?: return@runCatching
            // 关键修复：不能只改 attributes 对象，要重新 setAttributes 才生效。
            // 部分机型还需要 decorView.requestLayout() 触发窗口重绘。
            val params = window.attributes
            params.preferredDisplayModeId = best.modeId
            window.attributes = params
        }
    }

    /**
     * v27.5 #28：应用 / 取消 FLAG_SECURE。
     * 开启后系统会阻止截图 + 最近任务卡片模糊。
     * 注意：FLAG_SECURE 在某些机型会导致 Surface 渲染问题（如黑屏），用户已被告知"重启生效"。
     */
    private fun applyScreenshotBlock(block: Boolean) {
        runCatching {
            if (block) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    /**
     * v27.15：根据 settings 里的开关状态启停 [com.jmreader.notification.ReadLaterForegroundService]。
     *
     * - enabled=true：Android 13+ 未授权 → 申请权限；已授权 → 直接启动 Service。
     * - enabled=false：停止 Service（通知自动消失）。
     *
     * 注意：本方法在 settings collector 里调用，lastReadLaterEnabled 去重保证只在值变化时触发一次。
     * 用户拒绝权限后会回滚 settings，从而又触发一次（这次是 false → stop），幂等无副作用。
     */
    private fun applyReadLaterNotification(enabled: Boolean) {
        if (!enabled) {
            ReadLaterServiceController.stop(this)
            return
        }
        // enabled=true：Android 13+ 先检查权限
        if (ReadLaterServiceController.hasNotificationPermission(this)) {
            ReadLaterServiceController.start(this)
        } else {
            // 申请权限，结果由 notificationPermissionLauncher 回调处理（授权→start，拒绝→rollback to false）
            // 用 launcher（而非 ActivityCompat.requestPermissions）以确保走 registerForActivityResult 回调链
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * v27.6 音量键翻页：在阅读器中拦截音量键翻页。
     *
     * Compose 的 onPreviewKeyEvent 在沉浸态（无焦点节点）下可能收不到硬件音量键事件，
     * 故在 Activity 层用 onKeyDown 兜底。ReaderVolumeKeyBridge 是否已注册回调决定是否消费：
     * - 阅读器在前台且开启了音量键翻页 → bridge.callback != null → 消费事件翻页
     * - 不在阅读器或未开启 → bridge.callback == null → 不消费，系统正常调音量
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                if (com.jmreader.ui.screen.reader.ReaderVolumeKeyBridge.handleVolumeKey(isUp = true)) {
                    return true
                }
            }
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (com.jmreader.ui.screen.reader.ReaderVolumeKeyBridge.handleVolumeKey(isUp = false)) {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        /** v27.5 稳定性加固：savedInstanceState 中保存 locked 状态的 key。 */
        private const val KEY_LOCKED = "jm_locked"

        /**
         * 返回设备支持的最高刷新率（Hz），用于设置页提示用户。
         * 若系统设置里关闭了高刷，supportedModes 可能只返回 60Hz，
         * 此时即使开启本应用开关也无效——需要提示用户去系统设置开启高刷。
         */
        fun maxRefreshRate(activity: android.app.Activity): Float {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                return activity.display?.mode?.refreshRate ?: 60f
            }
            val display = activity.display ?: return 60f
            val modes = display.supportedModes
            if (modes.isEmpty()) return display.mode.refreshRate
            return modes.maxOf { it.refreshRate }
        }
    }
}
