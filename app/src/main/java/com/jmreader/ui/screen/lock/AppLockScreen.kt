package com.jmreader.ui.screen.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch

/**
 * v27.5 #28：应用锁界面。
 *
 * 两种解锁方式：
 * - PIN 模式（settings.appLockPin != null）：显示 4-8 位数字 PIN 输入面板
 * - 生物识别模式（settings.appLockPin == null）：自动调起 BiometricPrompt；
 *   用户取消/失败后可点"使用 PIN"按钮 fallback（但若无 PIN 设置，仅可重试生物识别）
 *
 * 解锁成功调用 [onUnlocked]；界面始终覆盖在内容之上（全屏）。
 *
 * v27.5 性能修复（指纹延迟）：
 * - MainActivity 冷启动时已通过 cachedSnapshot 同步设置 locked=true，
 *   Compose 第一帧 showLock=true 立即显示本界面。
 * - 本界面 LaunchedEffect 在第一帧 composition 完成后立即调起 BiometricPrompt，
 *   不再等待 settings flow emit（cachedSnapshot 已是初始值）。
 *
 * v27.5 UI 排版优化：
 * - 指纹模式：指纹图标 + 标题 + 副标题垂直堆叠居中，间距统一（16dp）
 * - PIN 模式：圆点指示器 + 数字键盘对齐，键盘按钮 64dp（之前 56dp 在大屏太小）
 * - 数字键盘空位补齐为正方形占位（与按钮同尺寸），保证最后一行三列对齐
 *
 * @param pin 已设置的 PIN（null 表示用生物识别）
 * @param onUnlocked 验证成功回调
 */
@Composable
fun AppLockScreen(
    pin: String?,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var biometricAttempted by remember { mutableStateOf(false) }

    // 生物识别模式：进入界面立即调起一次 BiometricPrompt。
    // v27.5：依赖 pin 而非 activity（activity 来自 context.findFragmentActivity() 是同步的，不变化），
    // 这样 LaunchedEffect 在第一帧 composition 后立即触发，无需等待额外帧。
    //
    // v27.5 稳定性加固（协程泄漏修复）：
    // 1. BiometricPrompt.authenticate 要求 Activity 至少处于 STARTED 状态，否则抛 IllegalStateException。
    //    冷启动时 Activity 可能尚未 STARTED → runCatching 兜底吞异常但 UI 卡死（用户看不到指纹弹窗）。
    // 2. 原代码在 LaunchedEffect 内嵌套 lifecycleScope.launch 启动协程，破坏 LaunchedEffect 的取消语义：
    //    LaunchedEffect 取消时（如 pin 变化、composition 离开）lifecycleScope 内的协程不会被取消，
    //    会继续轮询等待 STARTED 并最终调起 BiometricPrompt，导致解锁后还会再弹一次指纹窗（用户困扰）。
    //    且 lifecycleScope.launch 内未捕获异常会冒泡到 Thread.uncaughtExceptionHandler。
    // 改为直接在 LaunchedEffect 协程内 awaitCondition 等待 STARTED，LaunchedEffect 取消时协程立即取消。
    LaunchedEffect(pin) {
        val act = activity ?: return@LaunchedEffect
        if (pin != null || biometricAttempted) return@LaunchedEffect
        biometricAttempted = true
        try {
            // 等 Activity 至少 STARTED 再调起 BiometricPrompt（最多等 2 秒，避免死循环）
            var waitMs = 0
            while (!act.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && waitMs < 2000) {
                kotlinx.coroutines.delay(50)
                waitMs += 50
            }
            act.showBiometricPrompt(
                onSuccess = onUnlocked,
                onError = { msg -> error = msg },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.jmreader.core.Logger.w("AppLock", "调起 BiometricPrompt 失败: ${com.jmreader.core.Logger.brief(e)}")
            error = "无法调起指纹验证"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = "应用已锁定",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (pin != null) "请输入 PIN 解锁" else "请验证指纹解锁",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(24.dp))

            if (pin != null) {
                // PIN 输入：圆点指示器 + 数字键盘
                PinDots(
                    filled = input.length,
                    total = pin.length,
                    error = error != null,
                )
                Spacer(Modifier.height(24.dp))
                NumericKeypad(
                    onDigit = { d ->
                        if (input.length < pin.length) {
                            input += d
                            error = null
                            if (input.length == pin.length) {
                                if (input == pin) {
                                    onUnlocked()
                                } else {
                                    error = "PIN 不正确"
                                    input = ""
                                }
                            }
                        }
                    },
                    onBackspace = {
                        if (input.isNotEmpty()) input = input.dropLast(1)
                        error = null
                    },
                )
            } else {
                // 生物识别模式：显示指纹图标 + 重试按钮
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = "指纹",
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                TextButton(onClick = {
                    val act = activity
                    if (act != null) {
                        // v27.5 稳定性加固：用 rememberCoroutineScope 而非 lifecycleScope，
                        // 这样 composition 离开时协程自动取消，避免解锁后残留协程再次调起 BiometricPrompt。
                        // 同时包 try-catch，避免 showBiometricPrompt 内部异常冒泡到 Thread.uncaughtExceptionHandler。
                        scope.launch {
                            try {
                                var waitMs = 0
                                while (!act.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && waitMs < 2000) {
                                    kotlinx.coroutines.delay(50)
                                    waitMs += 50
                                }
                                act.showBiometricPrompt(
                                    onSuccess = onUnlocked,
                                    onError = { msg -> error = msg },
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                com.jmreader.core.Logger.w("AppLock", "重新调起 BiometricPrompt 失败: ${com.jmreader.core.Logger.brief(e)}")
                                error = "无法调起指纹验证"
                            }
                        }
                    } else {
                        // 无 FragmentActivity 上下文（不应发生），fallback 直接放行
                        onUnlocked()
                    }
                }) { Text("重新验证指纹") }
            }
        }
    }
}

@Composable
private fun PinDots(filled: Int, total: Int, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(total) { i ->
            val isFilled = i < filled
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(
                        color = when {
                            error -> MaterialTheme.colorScheme.error
                            isFilled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * 数字键盘：3x4 布局（1-9, 空, 0, 退格）。
 *
 * v27.5 UI 排版优化：
 * - 按钮尺寸 64dp（之前 56dp 在大屏显示偏小）
 * - 空位用 Modifier.size(64.dp) 占位，保证最后一行三列严格对齐
 * - 按钮 padding 内文字垂直居中（Box contentAlignment = Center）
 * - 间距统一 16dp
 */
@Composable
private fun NumericKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        keys.chunked(3).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                row.forEach { k ->
                    if (k.isEmpty()) {
                        // 空位占位：与按钮同尺寸，保证三列严格对齐
                        Box(modifier = Modifier.size(64.dp))
                    } else if (k == "⌫") {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape,
                                )
                                .clickable { onBackspace() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Backspace,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape,
                                )
                                .clickable { onDigit(k) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = k,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 从 Compose Context 向上查找 FragmentActivity。
 * BiometricPrompt 必须绑定 FragmentActivity（v1.1.0 要求）。
 */
private tailrec fun android.content.Context.findFragmentActivity(): FragmentActivity? {
    return when (this) {
        is FragmentActivity -> this
        is android.content.ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
}

/**
 * 检查生物识别是否可用（设备支持 + 已录入指纹/人脸）。
 */
fun isBiometricAvailable(context: android.content.Context): Boolean {
    val mgr = BiometricManager.from(context)
    return mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
        BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * 在 FragmentActivity 上调起 BiometricPrompt。
 * 成功调用 onSuccess；失败/取消调用 onError。
 */
fun FragmentActivity.showBiometricPrompt(
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
    // v27.5 稳定性加固：回调内 runCatching 包裹 onSuccess/onError 调用。
    // 极端时序：用户按 Home 退到后台、Activity 进入 STOPPED/DESTROYED 状态时，
    // BiometricPrompt 的回调仍可能在 mainExecutor 上被触发，此时访问已 destroy 的
    // FragmentManager/Fragment 会抛 IllegalStateException。runCatching 避免崩溃。
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            runCatching { onSuccess() }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // 用户取消（用户主动按返回/back）→ 不当作错误，但也不解锁
            // 其他错误（如失败次数过多、未录入指纹）→ 提示
            val msg = when (errorCode) {
                BiometricPrompt.ERROR_USER_CANCELED,
                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                BiometricPrompt.ERROR_CANCELED -> "已取消"
                BiometricPrompt.ERROR_HW_NOT_PRESENT -> "设备无生物识别硬件"
                BiometricPrompt.ERROR_HW_UNAVAILABLE -> "生物识别硬件不可用"
                BiometricPrompt.ERROR_NO_BIOMETRICS -> "未录入指纹/面部"
                BiometricPrompt.ERROR_LOCKOUT -> "尝试次数过多，请稍后再试"
                BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "生物识别已被锁定，请稍后再试"
                else -> errString.toString()
            }
            runCatching { onError(msg) }
        }
    }
    val prompt = BiometricPrompt(this, executor, callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("应用锁")
        .setSubtitle("验证身份以继续使用")
        .setNegativeButtonText("取消")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    runCatching { prompt.authenticate(info) }
}
