package com.jmreader.ui.theme

import android.app.Activity
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * v27.5：卡片阴影（elevation）通过 CompositionLocal 全局提供，
 * 让设置页"卡片阴影"滑块真正生效到所有 Card 组件。
 * 之前所有 Card 硬编码 `CardDefaults.cardElevation(defaultElevation = 1.dp)`，
 * 设置项写入 DataStore 但无人读取，用户调节无效果。
 *
 * 用法：在 Card 调用处用 `LocalCardElevation.current` 替代硬编码 1.dp。
 * JMTheme 中根据 settings.cardElevation provide。
 */
val LocalCardElevation = androidx.compose.runtime.compositionLocalOf { 1.dp }

/**
 * v27.6：列表标题/正文字号 + 封面宽高比通过 CompositionLocal 全局提供，
 * 让设置页对应滑块/选项真正生效到所有卡片组件。
 * 之前设置项写入 DataStore 但卡片用固定 typography / 硬编码 0.7f，用户调节无效果。
 */
val LocalListTitleFontSize = androidx.compose.runtime.compositionLocalOf { 13f }
val LocalListBodyFontSize = androidx.compose.runtime.compositionLocalOf { 12f }
/** 封面宽高比（宽/高），如 2:3 → 0.6667f */
val LocalCoverAspectRatio = androidx.compose.runtime.compositionLocalOf { 0.7f }

private val LightColors = lightColorScheme(
    primary = md_primary,
    onPrimary = md_on_primary,
    primaryContainer = md_primary_container,
    onPrimaryContainer = md_on_primary_container,
    secondary = md_secondary,
    onSecondary = md_on_secondary,
    secondaryContainer = md_secondary_container,
    onSecondaryContainer = md_on_secondary_container,
    tertiary = md_tertiary,
    onTertiary = md_on_tertiary,
    tertiaryContainer = md_tertiary_container,
    onTertiaryContainer = md_on_tertiary_container,
    background = md_background,
    onBackground = md_on_background,
    surface = md_surface,
    onSurface = md_on_surface,
    surfaceVariant = md_surface_variant,
    onSurfaceVariant = md_on_surface_variant,
    surfaceTint = md_surface_tint,
    outline = md_outline,
    outlineVariant = md_outline_variant,
    inverseSurface = md_inverse_surface,
    inverseOnSurface = md_inverse_on_surface,
    inversePrimary = md_inverse_primary,
    scrim = md_scrim,
    error = md_error,
    onError = md_on_error,
    errorContainer = md_error_container,
    onErrorContainer = md_on_error_container,
)

private val DarkColors = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_on_primary,
    primaryContainer = md_dark_primary_container,
    onPrimaryContainer = md_dark_on_primary_container,
    secondary = md_dark_secondary,
    onSecondary = md_dark_on_secondary,
    secondaryContainer = md_dark_secondary_container,
    onSecondaryContainer = md_dark_on_secondary_container,
    tertiary = md_dark_tertiary,
    onTertiary = md_dark_on_tertiary,
    tertiaryContainer = md_dark_tertiary_container,
    onTertiaryContainer = md_dark_on_tertiary_container,
    background = md_dark_background,
    onBackground = md_dark_on_background,
    surface = md_dark_surface,
    onSurface = md_dark_on_surface,
    surfaceVariant = md_dark_surface_variant,
    onSurfaceVariant = md_dark_on_surface_variant,
    surfaceTint = md_dark_surface_tint,
    outline = md_dark_outline,
    outlineVariant = md_dark_outline_variant,
    inverseSurface = md_dark_inverse_surface,
    inverseOnSurface = md_dark_inverse_on_surface,
    inversePrimary = md_dark_inverse_primary,
    scrim = md_dark_scrim,
    error = md_dark_error,
    onError = md_dark_on_error,
    errorContainer = md_dark_error_container,
    onErrorContainer = md_dark_on_error_container,
)

/**
 * v27.4 主题入口：支持 8 套预设配色 + 1 套自定义 + 背景图片（可调不透明度/模糊）。
 *
 * v27.5：新增 [cardCornerRadius] 参数，让设置页的"卡片圆角"滑块真正生效——
 * 之前 JMShapes 是全局固定 val，cardCornerRadius 设置项写入 DataStore 但无人读取，
 * 用户调节无效果。现在 JMTheme 根据 cardCornerRadius 动态生成 Shapes，
 * ComicCard 用 MaterialTheme.shapes.medium 取圆角，调节即时可见。
 *
 * @param themeMode 浅色/深色/跟随系统
 * @param dynamicColor Android 12+ 动态取色（Material You），优先级最高
 * @param colorSchemeId 预设 id（如蓝白预设的 "kazumi_blue"，仅为持久化键名，无品牌含义）或 CUSTOM_SCHEME_ID
 * @param customColors 自定义颜色（仅 colorSchemeId == CUSTOM_SCHEME_ID 时使用）
 * @param backgroundImageUri 背景图 URI，null=不显示
 * @param backgroundImageOpacity 背景图不透明度 0..1
 * @param backgroundImageBlur 背景图模糊半径 0..25.dp（暂未渲染模糊，仅占位字段；Coil 不直接支持，
 *        如需模糊用 RenderEffect.createBlurEffect 在 Android 12+，这里为简化先不实现）
 * @param backgroundImageLightOnly true=仅浅色模式显示背景图，深色模式隐藏（默认）
 * @param cardCornerRadius 卡片圆角半径 dp，影响 MaterialTheme.shapes.medium
 * @param cardElevation 卡片阴影 dp，通过 LocalCardElevation 提供给所有 Card
 */
@Composable
fun JMTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    colorSchemeId: String = "kazumi_blue",
    customColors: CustomColors? = null,
    backgroundImageUri: String? = null,
    backgroundImageOpacity: Float = 1.0f,
    backgroundImageBlur: Float = 0f,
    backgroundImageLightOnly: Boolean = true,
    cardCornerRadius: Float = 14f,
    cardElevation: Float = 1f,
    listTitleFontSize: Float = 13f,
    listBodyFontSize: Float = 12f,
    coverAspectRatio: String = "2:3",
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        colorSchemeId == CUSTOM_SCHEME_ID && customColors != null ->
            customColorsToScheme(customColors, dark)
        else -> {
            // v27.5 稳定性加固：避免 `findPreset("kazumi_blue")!!` 强解，
            // 即便 PresetSchemes 被改动/资源被裁剪也不至于崩溃。
            // 回退顺序：传入 id → 默认 "kazumi_blue" → 列表首项 → 内置 Light/DarkColors
            val preset = findPreset(colorSchemeId)
                ?: findPreset("kazumi_blue")
                ?: PresetSchemes.firstOrNull()
            if (preset != null) {
                if (dark) preset.dark else preset.light
            } else {
                if (dark) DarkColors else LightColors
            }
        }
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            // 关键：状态栏底色与 background 同色，避免动态切换配色后状态栏仍是上一套的色。
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
        }
    }
    // v27.4：背景图层（可选）。
    // 仅当满足显示条件时渲染：浅色模式始终显示；深色模式仅在 backgroundImageLightOnly=false 时显示。
    val showBg = backgroundImageUri != null &&
        (!dark || !backgroundImageLightOnly)
    // 当显示背景图时，把 background/surface 改为半透明（保留 92% 不透明度），
    // 让背景图透过 Scaffold 的底色隐约可见；不破坏卡片等 surfaceVariant 元素的可读性。
    // 用 0.92 而非更低：阅读卡片文字时底色仍以配色为主，背景图作为氛围层。
    // v27.5 卡顿修复：remember(colorScheme, showBg) 避免每次重组都新建 ColorScheme 对象
    val effectiveScheme = remember(colorScheme, showBg) {
        if (showBg) colorScheme.copy(
            background = colorScheme.background.copy(alpha = 0.92f),
            surface = colorScheme.surface.copy(alpha = 0.92f),
        ) else colorScheme
    }
    // v27.5：根据 cardCornerRadius 动态生成 Shapes，让设置页圆角滑块真正生效。
    // remember(cardCornerRadius) 避免每次重组都新建 Shapes 对象。
    val shapes = remember(cardCornerRadius) {
        Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(cardCornerRadius.dp),
            large = RoundedCornerShape(20.dp),
        )
    }
    // v27.6：解析封面宽高比字符串 "2:3" → Float 0.6667f
    val coverRatio = remember(coverAspectRatio) {
        coverAspectRatio.split(":").takeIf { it.size == 2 }?.let { (w, h) ->
            w.toFloatOrNull()?.let { wv -> h.toFloatOrNull()?.let { hv -> if (hv > 0f) wv / hv else null } }
        } ?: 0.7f
    }
    // v27.5：用 CompositionLocalProviders 包裹 content，让 LocalCardElevation
    // 在所有 Card 组件中可读，实现"卡片阴影"设置全局生效。
    // v27.6：同时 provide 列表字号 + 封面宽高比，让对应设置项生效。
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCardElevation provides cardElevation.dp,
        LocalListTitleFontSize provides listTitleFontSize,
        LocalListBodyFontSize provides listBodyFontSize,
        LocalCoverAspectRatio provides coverRatio,
    ) {
        MaterialTheme(
            colorScheme = effectiveScheme,
            typography = JMTypography,
            shapes = shapes,
        ) {
            if (showBg && backgroundImageUri != null) {
                Box(Modifier.fillMaxSize()) {
                    BackgroundImageLayer(
                        uri = backgroundImageUri,
                        opacity = backgroundImageOpacity,
                        blurDp = backgroundImageBlur,
                    )
                    // 内容层覆盖在背景图上，背景色透明避免遮挡
                    content()
                }
            } else {
                content()
            }
        }
    }
}

/** 渲染背景图：填满屏幕 + 可调透明度。Coil 异步加载，支持 content:// URI。 */
@Composable
private fun BackgroundImageLayer(
    uri: String,
    opacity: Float,
    blurDp: Float,
) {
    val context = LocalContext.current
    // v27.5 卡顿根因修复：ImageRequest 必须 remember，否则每次 BackgroundImageLayer 重组都新建
    // ImageRequest 对象（equals 涉及 context 引用 + extras map，几乎必判不等），
    // 触发 rememberAsyncImagePainter 重新加载 → 重新解码整张背景图（1080x1920+，耗时 100-500ms）。
    // 当设置页滑块（cardCornerRadius 等）高频 emit settings flow → JMTheme 重组 → 这里跟着重组
    // → 每帧重新解码背景图 → 全局卡顿。
    // 用 remember(uri) 缓存 ImageRequest，相同 URI 不再重建。
    val request = remember(uri, context) {
        ImageRequest.Builder(context)
            .data(Uri.parse(uri))
            .crossfade(true)
            .build()
    }
    val painter = rememberAsyncImagePainter(request)
    val state = painter.state
    // 仅在加载成功时应用透明度，避免加载失败时整个底色被透明化露出黑底
    val effectiveAlpha = if (state is AsyncImagePainter.State.Success) opacity.coerceIn(0f, 1f) else 0f
    Image(
        painter = painter,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .alpha(effectiveAlpha),
        contentScale = ContentScale.Crop,
    )
}
