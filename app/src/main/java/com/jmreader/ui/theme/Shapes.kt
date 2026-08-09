package com.jmreader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 全局圆角规范（v27，蓝白风格一致性）。
 *
 * 所有 UI 组件应优先使用 [androidx.compose.material3.MaterialTheme].shapes 取圆角，
 * 避免各处硬编码不同 dp 导致视觉割裂。
 *
 * - small  (8.dp)  : chip、小按钮、tag、缩略图
 * - medium (14.dp) : 卡片、列表项、输入框
 * - large  (20.dp) : 底部 sheet、对话框、大封面
 */
val JMShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)
