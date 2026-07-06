package com.jmreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.jmreader.ui.components.DisclaimerDialog
import com.jmreader.ui.nav.JMApp
import com.jmreader.ui.theme.JMTheme
import com.jmreader.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 是否强制最高刷新率，由设置页写入；启动时为 false，Compose 起来后由 settings flow 更新。 */
    @Volatile private var preferMaxRefreshRate = false

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

        // 后台收集设置，刷新率开关变化时实时应用到 window
        val container = (application as JMApp).container
        lifecycleScope.launch {
            container.settingsStore.settings.collect { s ->
                preferMaxRefreshRate = s.preferMaxRefreshRate
                applyMaxRefreshRate()
            }
        }

        setContent {
            val settings = container.settingsStore.settings.collectAsState(initial = null)
            val themeMode = settings.value?.themeMode ?: ThemeMode.SYSTEM
            val dynamic = settings.value?.dynamicColor ?: false

            // 首次启动免责声明弹窗：未同意时显示，5 秒倒计时强制阅读。
            // settings 还在加载（null）时不显示，避免在状态未明时弹错。
            // 一旦用户同意（disclaimerAccepted=true），后续启动不再显示。
            var showDisclaimer by remember { mutableStateOf(false) }
            LaunchedEffect(settings.value) {
                val s = settings.value
                if (s != null && !s.disclaimerAccepted) showDisclaimer = true
            }

            JMTheme(themeMode = themeMode, dynamicColor = dynamic) {
                JMApp(container = container)
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

    /**
     * 强制最高刷新率：从 Display.supportedModes 里挑 refreshRate 最大的，
     * 写到 Window.attributes.preferredDisplayModeId。
     * 仅 Android 11+（API 30）有效；旧版本无操作。
     * 很多 ROM 默认会降刷新率省电，导致列表滚动卡顿，开启后可显著改善流畅度。
     */
    private fun applyMaxRefreshRate() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        if (!preferMaxRefreshRate) return
        val display = display ?: return
        val modes = display.supportedModes
        if (modes.isEmpty()) return
        // 选刷新率最高的；若多个相同，优先分辨率匹配当前的（避免改分辨率）。
        val currentMode = display.mode
        val best = modes.filter { it.physicalWidth == currentMode.physicalWidth
                && it.physicalHeight == currentMode.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: modes.maxByOrNull { it.refreshRate }
            ?: return
        // 关键修复：不能只改 attributes 对象，要重新 setAttributes 才生效。
        // 部分机型还需要 decorView.requestLayout() 触发窗口重绘。
        val params = window.attributes
        params.preferredDisplayModeId = best.modeId
        window.attributes = params
    }

    companion object {
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
