package com.jmreader.ui.theme

import androidx.compose.ui.graphics.Color

// 一根葱品牌配色（v27，蓝白经典风格）。
//   背景 EFF3F6（浅蓝灰） · 卡片 FFFFFF（纯白） · 主色 4A90C8（天蓝）
// 同色系明度分层：背景最浅 → 卡片次之 → 主色最深，整体清爽通透不杂乱。
// 与应用图标（一根葱白绿配色的 PNG）视觉协调。
//
// v27.3：补齐 Material3 完整色板，之前缺 secondaryContainer/tertiary/errorContainer/
// outlineVariant/surfaceTint/inverseSurface/scrim 等槽位，Material3 组件（FilterChip/
// AssistChip/HorizontalDivider/Card 阴影/错误容器）会回落到默认紫色，与蓝白系冲突。
val md_primary = Color(0xFF4A90C8)         // 天蓝（主色，按钮/选中态）
val md_on_primary = Color(0xFFFFFFFF)
val md_primary_container = Color(0xFFD6E4F0)  // 浅蓝容器（选中态背景/chip 选中底）
val md_on_primary_container = Color(0xFF0D3A5C)

val md_secondary = Color(0xFF4A90C8)       // 副色同主色，保持视觉统一
val md_on_secondary = Color(0xFFFFFFFF)
val md_secondary_container = Color(0xFFD6E4F0)  // v27.3：补齐，FilterChip/AssistChip 选中态用
val md_on_secondary_container = Color(0xFF0D3A5C)

val md_tertiary = Color(0xFF5A9CB8)        // v27.3：第三色（较少用，与主色同色系微调）
val md_on_tertiary = Color(0xFFFFFFFF)
val md_tertiary_container = Color(0xFFCDE6F0)
val md_on_tertiary_container = Color(0xFF0A3548)

val md_background = Color(0xFFEFF3F6)      // 浅蓝灰背景
val md_on_background = Color(0xFF1A2428)
val md_surface = Color(0xFFEFF3F6)         // surface 与 background 一致，无割裂
val md_on_surface = Color(0xFF1A2428)
val md_surface_variant = Color(0xFFFFFFFF)  // 卡片纯白（蓝白风格核心：白卡片浮在浅蓝灰底上）
val md_on_surface_variant = Color(0xFF5A6A78)
val md_surface_tint = Color(0xFF4A90C8)     // v27.3：Card elevation 阴影色（与主色一致）
val md_outline = Color(0xFFB0BEC8)
val md_outline_variant = Color(0xFFD8E0E6)  // v27.3：HorizontalDivider 默认色（浅蓝灰，比 outline 弱）

val md_inverse_surface = Color(0xFF2F3A44)  // v27.3：反向表面（Snackbar 等）
val md_inverse_on_surface = Color(0xFFEFF3F6)
val md_inverse_primary = Color(0xFF7AB5E0)
val md_scrim = Color(0xFF000000)            // v27.3：Dialog 背景遮罩

val md_error = Color(0xFFBA1A1A)
val md_on_error = Color(0xFFFFFFFF)
val md_error_container = Color(0xFFFFDAD6)  // v27.3：错误容器（评论区翻页失败 banner 用）
val md_on_error_container = Color(0xFF410002)

// 深色模式：深蓝黑底 + 浅天蓝主色 + 深灰蓝卡片
val md_dark_primary = Color(0xFF7AB5E0)     // 浅天蓝
val md_dark_on_primary = Color(0xFF003355)
val md_dark_primary_container = Color(0xFF1E3A52)  // 深蓝容器
val md_dark_on_primary_container = Color(0xFFD6E4F0)

val md_dark_secondary = Color(0xFF7AB5E0)
val md_dark_on_secondary = Color(0xFF003355)
val md_dark_secondary_container = Color(0xFF1E3A52)  // v27.3：补齐
val md_dark_on_secondary_container = Color(0xFFD6E4F0)

val md_dark_tertiary = Color(0xFF8FC4DC)    // v27.3
val md_dark_on_tertiary = Color(0xFF0A3548)
val md_dark_tertiary_container = Color(0xFF234B5E)
val md_dark_on_tertiary_container = Color(0xFFCDE6F0)

val md_dark_background = Color(0xFF0F1418)  // 深蓝黑，比纯黑温暖
val md_dark_on_background = Color(0xFFDDE3E8)
val md_dark_surface = Color(0xFF0F1418)
val md_dark_on_surface = Color(0xFFDDE3E8)
val md_dark_surface_variant = Color(0xFF1E252C)  // 深灰蓝卡片
val md_dark_on_surface_variant = Color(0xFFB0BEC8)
val md_dark_surface_tint = Color(0xFF7AB5E0)  // v27.3
val md_dark_outline = Color(0xFF5A6A78)
val md_dark_outline_variant = Color(0xFF3A4651)  // v27.3

val md_dark_inverse_surface = Color(0xFFDDE3E8)  // v27.3
val md_dark_inverse_on_surface = Color(0xFF2F3A44)
val md_dark_inverse_primary = Color(0xFF4A90C8)
val md_dark_scrim = Color(0xFF000000)  // v27.3

val md_dark_error = Color(0xFFFFB4AB)
val md_dark_on_error = Color(0xFF690005)
val md_dark_error_container = Color(0xFF93000A)  // v27.3
val md_dark_on_error_container = Color(0xFFFFDAD6)

// 向后兼容别名（旧代码引用 error/onError）
val error: Color get() = md_error
val onError: Color get() = md_on_error
