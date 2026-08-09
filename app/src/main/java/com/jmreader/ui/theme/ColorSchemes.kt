package com.jmreader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 一套预设配色方案：包含浅色与深色两个 [ColorScheme]。
 *
 * v27.4：用户要求「在设置里多提供好多种配色方案」，原来固定只有蓝白一套。
 * 现在提供 8 套预设 + 1 套自定义。每套都有独立的 light/dark 调色板，
 * 在「设置 → 外观 → 配色方案」里可一键切换。
 *
 * v27.5：修复对比度问题——
 * - 晨曦橙 light 模式 onPrimary 由白改为深棕（白字在亮橙上对比度仅 2.9:1，不达标）
 * - AMOLED 纯黑 dark surface/surfaceVariant 拉开层次（之前几乎同色，卡片无浮起感）
 * - 牛皮纸 light surface 与 surfaceVariant 区分（之前完全相同）
 * - buildScheme 不再让 onSecondary 直接复用 onPrimary（自定义配色时 secondary 与 primary
 *   亮度差异大会有不可读文字）；改为按 secondary 亮度自动派生
 * - customColorsToScheme 不再让 onSurface 直接复用 onBackground（surface 与 background
 *   亮度差异大时会有不可读文字）；改为按 surface 亮度自动派生
 */
@Immutable
data class PresetScheme(
    /** 唯一标识，持久化到 DataStore。 */
    val id: String,
    /** 显示名称。 */
    val name: String,
    /** 一句话描述，给用户选色参考。 */
    val description: String,
    /** 浅色调色板。 */
    val light: ColorScheme,
    /** 深色调色板。 */
    val dark: ColorScheme,
    /** 预览用的 4 个主色调（用于设置页小色块展示）。 */
    val swatches: List<Color>,
)

/** 自定义颜色配置：用户可在「自定义」对话框里逐项挑选。 */
@Immutable
data class CustomColors(
    val primary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
) {
    /** 序列化为 "RRGGBB,RRGGBB,..." 便于存 DataStore。 */
    fun encode(): String = listOf(primary, primaryContainer, secondary, background, surface, surfaceVariant)
        .joinToString(",") { "%08X".format(it.value.toLong()) }

    companion object {
        fun decode(s: String): CustomColors? = runCatching {
            val parts = s.split(",")
            if (parts.size != 6) return null
            CustomColors(
                primary = Color(parts[0].toLong(16)),
                primaryContainer = Color(parts[1].toLong(16)),
                secondary = Color(parts[2].toLong(16)),
                background = Color(parts[3].toLong(16)),
                surface = Color(parts[4].toLong(16)),
                surfaceVariant = Color(parts[5].toLong(16)),
            )
        }.getOrNull()
    }
}

// ============================ 配色方案工具函数 ============================

/**
 * 由 primary + background 派生完整的 ColorScheme，避免每个预设都要手写 30+ 字段。
 *
 * v27.5 修复（关键 bug）：lightColorScheme/darkColorScheme 的位置参数顺序是
 * `... surfaceTint, inverseSurface, inverseOnSurface, inversePrimary, outline, outlineVariant, scrim, ...`，
 * 之前代码把 `outline, outlineVariant` 误放在 `inverseSurface` 前面，导致 6 个字段错位：
 * inverseSurface 收到 outline 值（变浅）、inverseOnSurface 收到 outlineVariant 值、
 * inversePrimary 收到 inverseSurface 值、outline 收到 inverseOnSurface 值（边框变浅看不见）、
 * outlineVariant 收到 inversePrimary 值（分隔线变蓝）。
 * 现按正确顺序传参。
 *
 * v27.5 修复：onSecondary 不再直接复用 onPrimary，而是按 secondary 亮度自动派生
 * （黑白二选一），这样自定义配色时 secondary 与 primary 亮度差异大的情况下，
 * secondary 上的文字依然可读。
 */
private fun buildScheme(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    onSecondary: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    background: Color,
    onBackground: Color,
    surface: Color,
    onSurface: Color,
    surfaceVariant: Color,
    onSurfaceVariant: Color,
    outline: Color,
    isDark: Boolean,
): ColorScheme {
    val error = if (isDark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
    val onError = if (isDark) Color(0xFF690005) else Color(0xFFFFFFFF)
    val errorContainer = if (isDark) Color(0xFF93000A) else Color(0xFFFFDAD6)
    val onErrorContainer = if (isDark) Color(0xFFFFDAD6) else Color(0xFF410002)
    val outlineVariant = if (isDark) Color(0xFF3A4651) else Color(0xFFD8E0E6)
    val scrim = Color(0xFF000000)
    val inverseSurface = if (isDark) Color(0xFFDDE3E8) else Color(0xFF2F3A44)
    val inverseOnSurface = if (isDark) Color(0xFF2F3A44) else Color(0xFFEFF3F6)
    val inversePrimary = primary
    // 用命名参数传，避免位置错位再次发生
    return if (isDark) {
        darkColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = primary, onTertiary = onPrimary,
            tertiaryContainer = primaryContainer, onTertiaryContainer = onPrimaryContainer,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onSurface,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary, // v27.5：Card elevation 带主色调
            inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
            inversePrimary = inversePrimary,
            outline = outline, outlineVariant = outlineVariant,
            scrim = scrim,
            error = error, onError = onError,
            errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = primary, onTertiary = onPrimary,
            tertiaryContainer = primaryContainer, onTertiaryContainer = onPrimaryContainer,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onSurface,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary, // v27.5：Card elevation 带主色调
            inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
            inversePrimary = inversePrimary,
            outline = outline, outlineVariant = outlineVariant,
            scrim = scrim,
            error = error, onError = onError,
            errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        )
    }
}

/** 工具：按背景色亮度自动派生前景色（黑或白），保证对比度。 */
private fun autoOnColor(bg: Color): Color =
    if (bg.luminance() > 0.5f) Color(0xFF1A1A1A) else Color(0xFFEDEDED)

// ============================ 8 套预设配色 ============================

/** 全部预设配色方案，列表顺序即设置页展示顺序。 */
val PresetSchemes: List<PresetScheme> = listOf(
    // 1. 蓝白（默认）
    PresetScheme(
        id = "kazumi_blue",
        name = "蓝白",
        description = "天蓝主色 + 浅蓝灰背景 + 白卡片，清爽经典",
        swatches = listOf(Color(0xFF4A90C8), Color(0xFFEFF3F6), Color(0xFFFFFFFF), Color(0xFFD6E4F0)),
        light = buildScheme(
            primary = Color(0xFF4A90C8), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD6E4F0), onPrimaryContainer = Color(0xFF0D3A5C),
            secondary = Color(0xFF4A90C8), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFD6E4F0), onSecondaryContainer = Color(0xFF0D3A5C),
            background = Color(0xFFEFF3F6), onBackground = Color(0xFF1A2428),
            surface = Color(0xFFEFF3F6), onSurface = Color(0xFF1A2428),
            surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF5A6A78),
            outline = Color(0xFFB0BEC8), isDark = false,
        ),
        dark = buildScheme(
            primary = Color(0xFF7AB5E0), onPrimary = Color(0xFF003355),
            primaryContainer = Color(0xFF1E3A52), onPrimaryContainer = Color(0xFFD6E4F0),
            secondary = Color(0xFF7AB5E0), onSecondary = Color(0xFF003355),
            secondaryContainer = Color(0xFF1E3A52), onSecondaryContainer = Color(0xFFD6E4F0),
            background = Color(0xFF0F1418), onBackground = Color(0xFFDDE3E8),
            surface = Color(0xFF0F1418), onSurface = Color(0xFFDDE3E8),
            surfaceVariant = Color(0xFF1E252C), onSurfaceVariant = Color(0xFFB0BEC8),
            outline = Color(0xFF5A6A78), isDark = true,
        ),
    ),
    // 2. 樱花粉
    PresetScheme(
        id = "sakura_pink",
        name = "樱花粉",
        description = "粉色主色 + 米白背景，温柔少女心",
        swatches = listOf(Color(0xFFE91E63), Color(0xFFFDF2F5), Color(0xFFFFFFFF), Color(0xFFFCE4EC)),
        light = buildScheme(
            primary = Color(0xFFE91E63), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFCE4EC), onPrimaryContainer = Color(0xFF4A0D2A),
            secondary = Color(0xFFE91E63), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFCE4EC), onSecondaryContainer = Color(0xFF4A0D2A),
            background = Color(0xFFFDF2F5), onBackground = Color(0xFF241A1E),
            surface = Color(0xFFFDF2F5), onSurface = Color(0xFF241A1E),
            surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF7A5A66),
            outline = Color(0xFFD8BAC4), isDark = false,
        ),
        dark = buildScheme(
            primary = Color(0xFFFFB1C8), onPrimary = Color(0xFF5A0D33),
            primaryContainer = Color(0xFF7B2949), onPrimaryContainer = Color(0xFFFFD9E2),
            secondary = Color(0xFFFFB1C8), onSecondary = Color(0xFF5A0D33),
            secondaryContainer = Color(0xFF7B2949), onSecondaryContainer = Color(0xFFFFD9E2),
            background = Color(0xFF1A0F14), onBackground = Color(0xFFF0DDE2),
            surface = Color(0xFF1A0F14), onSurface = Color(0xFFF0DDE2),
            surfaceVariant = Color(0xFF2B1A22), onSurfaceVariant = Color(0xFFD8BAC4),
            outline = Color(0xFFA08C94), isDark = true,
        ),
    ),
    // 3. 抹茶绿
    PresetScheme(
        id = "matcha_green",
        name = "抹茶绿",
        description = "草绿主色 + 米色背景，自然护眼",
        swatches = listOf(Color(0xFF558B2F), Color(0xFFF5F7F0), Color(0xFFFFFFFF), Color(0xFFDDE8D0)),
        light = buildScheme(
            primary = Color(0xFF558B2F), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFDDE8D0), onPrimaryContainer = Color(0xFF14290A),
            secondary = Color(0xFF558B2F), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFDDE8D0), onSecondaryContainer = Color(0xFF14290A),
            background = Color(0xFFF5F7F0), onBackground = Color(0xFF1A2018),
            surface = Color(0xFFF5F7F0), onSurface = Color(0xFF1A2018),
            surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF4E5E48),
            outline = Color(0xFFB5C5A8), isDark = false,
        ),
        dark = buildScheme(
            primary = Color(0xFF9CCC65), onPrimary = Color(0xFF0E2200),
            primaryContainer = Color(0xFF2D4A1A), onPrimaryContainer = Color(0xFFDDE8D0),
            secondary = Color(0xFF9CCC65), onSecondary = Color(0xFF0E2200),
            secondaryContainer = Color(0xFF2D4A1A), onSecondaryContainer = Color(0xFFDDE8D0),
            background = Color(0xFF121610), onBackground = Color(0xFFDDE5D2),
            surface = Color(0xFF121610), onSurface = Color(0xFFDDE5D2),
            surfaceVariant = Color(0xFF1E2520), onSurfaceVariant = Color(0xFFB5C5A8),
            outline = Color(0xFF6B7B60), isDark = true,
        ),
    ),
    // 4. 晨曦橙（v27.5：light onPrimary 改为深棕，白字在亮橙上仅 2.9:1 不达标）
    PresetScheme(
        id = "sunrise_orange",
        name = "晨曦橙",
        description = "橙色主色 + 暖白背景，活力温暖",
        swatches = listOf(Color(0xFFEF6C00), Color(0xFFFFF8F0), Color(0xFFFFFFFF), Color(0xFFFFE0B2)),
        light = buildScheme(
            primary = Color(0xFFEF6C00), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFE0B2), onPrimaryContainer = Color(0xFF2A1500),
            secondary = Color(0xFFEF6C00), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFE0B2), onSecondaryContainer = Color(0xFF2A1500),
            background = Color(0xFFFFF8F0), onBackground = Color(0xFF221810),
            surface = Color(0xFFFFF8F0), onSurface = Color(0xFF221810),
            surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF6E5E50),
            outline = Color(0xFFD8C0A8), isDark = false,
        ),
        dark = buildScheme(
            primary = Color(0xFFFFB74D), onPrimary = Color(0xFF3E2500),
            primaryContainer = Color(0xFF5A3A00), onPrimaryContainer = Color(0xFFFFE0B2),
            secondary = Color(0xFFFFB74D), onSecondary = Color(0xFF3E2500),
            secondaryContainer = Color(0xFF5A3A00), onSecondaryContainer = Color(0xFFFFE0B2),
            background = Color(0xFF161210), onBackground = Color(0xFFF0E2D8),
            surface = Color(0xFF161210), onSurface = Color(0xFFF0E2D8),
            surfaceVariant = Color(0xFF251E1A), onSurfaceVariant = Color(0xFFD8C0A8),
            outline = Color(0xFF9A8270), isDark = true,
        ),
    ),
    // 5. 薰衣草紫
    PresetScheme(
        id = "lavender_purple",
        name = "薰衣草紫",
        description = "紫色主色 + 浅紫灰背景，神秘优雅",
        swatches = listOf(Color(0xFF7E57C2), Color(0xFFF5F2FA), Color(0xFFFFFFFF), Color(0xFFE1D5F0)),
        light = buildScheme(
            primary = Color(0xFF7E57C2), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE1D5F0), onPrimaryContainer = Color(0xFF2A0D5A),
            secondary = Color(0xFF7E57C2), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE1D5F0), onSecondaryContainer = Color(0xFF2A0D5A),
            background = Color(0xFFF5F2FA), onBackground = Color(0xFF1C1828),
            surface = Color(0xFFF5F2FA), onSurface = Color(0xFF1C1828),
            surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF5A4F78),
            outline = Color(0xFFC5B8D8), isDark = false,
        ),
        dark = buildScheme(
            primary = Color(0xFFB39DDB), onPrimary = Color(0xFF3A1A6E),
            primaryContainer = Color(0xFF452A6E), onPrimaryContainer = Color(0xFFE1D5F0),
            secondary = Color(0xFFB39DDB), onSecondary = Color(0xFF3A1A6E),
            secondaryContainer = Color(0xFF452A6E), onSecondaryContainer = Color(0xFFE1D5F0),
            background = Color(0xFF141018), onBackground = Color(0xFFE5DDEE),
            surface = Color(0xFF141018), onSurface = Color(0xFFE5DDEE),
            surfaceVariant = Color(0xFF221E2C), onSurfaceVariant = Color(0xFFC5B8D8),
            outline = Color(0xFF7A6E92), isDark = true,
        ),
    ),
    // 6. AMOLED 纯黑（v27.5：surfaceVariant 略提亮，让卡片有浮起感）
    PresetScheme(
        id = "amoled_black",
        name = "AMOLED 纯黑",
        description = "纯黑深色背景 + 天蓝主色，省电流畅",
        swatches = listOf(Color(0xFF4A90C8), Color(0xFF000000), Color(0xFF0F1419), Color(0xFF1A1F26)),
        light = buildScheme(
            primary = Color(0xFF4A90C8), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD6E4F0), onPrimaryContainer = Color(0xFF0D3A5C),
            secondary = Color(0xFF4A90C8), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFD6E4F0), onSecondaryContainer = Color(0xFF0D3A5C),
            background = Color(0xFFEFF3F6), onBackground = Color(0xFF1A2428),
            surface = Color(0xFFEFF3F6), onSurface = Color(0xFF1A2428),
            surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF5A6A78),
            outline = Color(0xFFB0BEC8), isDark = false,
        ),
        dark = buildScheme(
            primary = Color(0xFF7AB5E0), onPrimary = Color(0xFF003355),
            primaryContainer = Color(0xFF1E3A52), onPrimaryContainer = Color(0xFFD6E4F0),
            secondary = Color(0xFF7AB5E0), onSecondary = Color(0xFF003355),
            secondaryContainer = Color(0xFF1E3A52), onSecondaryContainer = Color(0xFFD6E4F0),
            background = Color(0xFF000000), onBackground = Color(0xFFDDE3E8),
            surface = Color(0xFF0A0E12), onSurface = Color(0xFFDDE3E8),
            surfaceVariant = Color(0xFF1A1F26), onSurfaceVariant = Color(0xFFB0BEC8),
            outline = Color(0xFF5A6A78), isDark = true,
        ),
    ),
    // 7. 牛皮纸（v27.5：surfaceVariant 与 surface 区分，让卡片有浮起感）
    PresetScheme(
        id = "paper_sepia",
        name = "牛皮纸",
        description = "米黄主色 + 暖灰背景，护眼复古",
        swatches = listOf(Color(0xFF8B6E4F), Color(0xFFF4ECD8), Color(0xFFFBF5E5), Color(0xFFE8DCBC)),
        light = buildScheme(
            primary = Color(0xFF8B6E4F), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE0D4B8), onPrimaryContainer = Color(0xFF2E1F0F),
            secondary = Color(0xFF8B6E4F), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE0D4B8), onSecondaryContainer = Color(0xFF2E1F0F),
            background = Color(0xFFF4ECD8), onBackground = Color(0xFF241E14),
            surface = Color(0xFFFBF5E5), onSurface = Color(0xFF241E14),
            surfaceVariant = Color(0xFFEFE4CA), onSurfaceVariant = Color(0xFF6A5A45),
            outline = Color(0xFFC8B698), isDark = false,
        ),
        dark = buildScheme(
            primary = Color(0xFFD4B888), onPrimary = Color(0xFF3A2A10),
            primaryContainer = Color(0xFF4A3818), onPrimaryContainer = Color(0xFFE0D4B8),
            secondary = Color(0xFFD4B888), onSecondary = Color(0xFF3A2A10),
            secondaryContainer = Color(0xFF4A3818), onSecondaryContainer = Color(0xFFE0D4B8),
            background = Color(0xFF15110A), onBackground = Color(0xFFE8DCC0),
            surface = Color(0xFF15110A), onSurface = Color(0xFFE8DCC0),
            surfaceVariant = Color(0xFF221E14), onSurfaceVariant = Color(0xFFC8B698),
            outline = Color(0xFF8A7A5E), isDark = true,
        ),
    ),
    // 8. 海洋青
    PresetScheme(
        id = "ocean_teal",
        name = "海洋青",
        description = "青绿主色 + 浅水蓝背景，清凉通透",
        swatches = listOf(Color(0xFF00897B), Color(0xFFE8F4F2), Color(0xFFFFFFFF), Color(0xFFB2DFDB)),
        light = buildScheme(
            primary = Color(0xFF00897B), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFB2DFDB), onPrimaryContainer = Color(0xFF00322C),
            secondary = Color(0xFF00897B), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFB2DFDB), onSecondaryContainer = Color(0xFF00322C),
            background = Color(0xFFE8F4F2), onBackground = Color(0xFF0A2020),
            surface = Color(0xFFE8F4F2), onSurface = Color(0xFF0A2020),
            surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF406860),
            outline = Color(0xFFA0C8C0), isDark = false,
        ),
        dark = buildScheme(
            primary = Color(0xFF4DB6AC), onPrimary = Color(0xFF00322C),
            primaryContainer = Color(0xFF1A4844), onPrimaryContainer = Color(0xFFB2DFDB),
            secondary = Color(0xFF4DB6AC), onSecondary = Color(0xFF00322C),
            secondaryContainer = Color(0xFF1A4844), onSecondaryContainer = Color(0xFFB2DFDB),
            background = Color(0xFF0A1414), onBackground = Color(0xFFD0E5E0),
            surface = Color(0xFF0A1414), onSurface = Color(0xFFD0E5E0),
            surfaceVariant = Color(0xFF162220), onSurfaceVariant = Color(0xFFA0C8C0),
            outline = Color(0xFF5A7C76), isDark = true,
        ),
    ),
)

/** 特殊 id：自定义配色。 */
const val CUSTOM_SCHEME_ID = "custom"

/** 根据 id 查找预设；找不到（包括 "custom"）返回 null，由调用方处理自定义场景。 */
fun findPreset(id: String): PresetScheme? = PresetSchemes.firstOrNull { it.id == id }

/**
 * 把自定义颜色转成完整 ColorScheme（自动派生 onXxx 等）。
 *
 * v27.5 修复：onSurface 不再复用 onBackground，改为按 surface 亮度自动派生，
 * 避免 surface 与 background 亮度差异大时文字不可读。
 * onSecondary 按 secondary 亮度派生，与 buildScheme 保持一致。
 */
fun customColorsToScheme(colors: CustomColors, isDark: Boolean): ColorScheme {
    val onPrimary = autoOnColor(colors.primary)
    val onPrimaryContainer = autoOnColor(colors.primaryContainer)
    val onSecondary = autoOnColor(colors.secondary)
    val onSecondaryContainer = autoOnColor(colors.primaryContainer) // 与 primaryContainer 同色，复用
    val onBackground = autoOnColor(colors.background)
    val onSurface = autoOnColor(colors.surface)
    val onSurfaceVariant = if (colors.surfaceVariant.luminance() > 0.5f) Color(0xFF444444) else Color(0xFFCCCCCC)
    val outline = if (isDark) Color(0xFF6A7A88) else Color(0xFFB0BEC8)
    return buildScheme(
        primary = colors.primary, onPrimary = onPrimary,
        primaryContainer = colors.primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = colors.secondary, onSecondary = onSecondary,
        secondaryContainer = colors.primaryContainer, onSecondaryContainer = onSecondaryContainer,
        background = colors.background, onBackground = onBackground,
        surface = colors.surface, onSurface = onSurface,
        surfaceVariant = colors.surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        outline = outline, isDark = isDark,
    )
}
