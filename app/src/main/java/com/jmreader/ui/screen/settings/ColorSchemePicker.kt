package com.jmreader.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jmreader.ui.theme.CUSTOM_SCHEME_ID
import com.jmreader.ui.theme.CustomColors
import com.jmreader.ui.theme.PresetScheme
import com.jmreader.ui.theme.PresetSchemes

/**
 * v27.4 配色方案选择器：横向滚动展示 8 套预设 + 1 个「自定义」入口。
 *
 * 每个预设卡片显示 4 色色板预览 + 名称；点击切换。
 * 「自定义」入口打开 [CustomColorDialog] 让用户挑 6 个颜色（primary / primaryContainer /
 * secondary / background / surface / surfaceVariant）。
 */
@Composable
fun ColorSchemePicker(
    currentId: String,
    customColors: CustomColors?,
    enabled: Boolean,
    onPickPreset: (String) -> Unit,
    onPickCustom: (CustomColors) -> Unit,
) {
    var showCustomDialog by remember { mutableStateOf(false) }

    Text(
        "配色方案",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
    )
    Text(
        "8 套预设 + 自定义颜色，一键切换全局视觉风格",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(PresetSchemes, key = { it.id }) { scheme ->
            SchemeCard(
                scheme = scheme,
                selected = currentId == scheme.id,
                enabled = enabled,
                onClick = { onPickPreset(scheme.id) },
            )
        }
        item(key = CUSTOM_SCHEME_ID) {
            CustomSchemeCard(
                selected = currentId == CUSTOM_SCHEME_ID,
                enabled = enabled,
                customColors = customColors,
                onClick = {
                    // 直接打开对话框；首次进入用当前主题色作为初始值
                    showCustomDialog = true
                },
            )
        }
    }

    if (showCustomDialog) {
        CustomColorDialog(
            initial = customColors ?: CustomColors(
                primary = MaterialTheme.colorScheme.primary,
                primaryContainer = MaterialTheme.colorScheme.primaryContainer,
                secondary = MaterialTheme.colorScheme.secondary,
                background = MaterialTheme.colorScheme.background,
                surface = MaterialTheme.colorScheme.surface,
                surfaceVariant = MaterialTheme.colorScheme.surfaceVariant,
            ),
            onConfirm = {
                onPickCustom(it)
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false },
        )
    }
}

/** 单个预设卡片：4 色块 + 名称 + 选中边框。 */
@Composable
private fun SchemeCard(
    scheme: PresetScheme,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Card(
        modifier = Modifier
            .width(108.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 0.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            // 4 色块预览：2x2 网格
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.swatches.getOrElse(0) { scheme.light.primary }),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    scheme.swatches.take(2).forEach { c ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(c),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 28.dp)) {
                    scheme.swatches.drop(2).take(2).forEach { c ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(c),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                scheme.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                scheme.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 自定义卡片：显示当前自定义色板或"未配置"提示，点击打开对话框。 */
@Composable
private fun CustomSchemeCard(
    selected: Boolean,
    enabled: Boolean,
    customColors: CustomColors?,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val swatches = if (customColors != null) {
        listOf(
            customColors.primary,
            customColors.primaryContainer,
            customColors.background,
            customColors.surface,
        )
    } else {
        // 占位色：用当前主题色提示用户"默认会以当前主题色作起点"
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface,
        )
    }
    Card(
        modifier = Modifier
            .width(108.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 0.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(swatches[0]),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    swatches.take(2).forEach { c ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(c),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 28.dp)) {
                    swatches.drop(2).take(2).forEach { c ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(c),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "自定义",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                if (customColors != null) "已配置" else "点击挑选 6 种颜色",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 自定义颜色对话框：6 个颜色槽位 + 实时预览 + 文本输入 hex。
 *
 * 颜色输入用 hex 文本框（无需第三方颜色选择器库，避免引入新依赖）。
 * 用户输入 6 位 hex（如 4A90C8）后回车应用；非法输入不应用。
 */
@Composable
private fun CustomColorDialog(
    initial: CustomColors,
    onConfirm: (CustomColors) -> Unit,
    onDismiss: () -> Unit,
) {
    var primary by remember { mutableStateOf(initial.primary) }
    var primaryContainer by remember { mutableStateOf(initial.primaryContainer) }
    var secondary by remember { mutableStateOf(initial.secondary) }
    var background by remember { mutableStateOf(initial.background) }
    var surface by remember { mutableStateOf(initial.surface) }
    var surfaceVariant by remember { mutableStateOf(initial.surfaceVariant) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义配色") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "直接输入 6 位 Hex 色值（如 4A90C8），无需 # 前缀",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                ColorRow("主色 Primary", primary) { primary = it }
                ColorRow("主色容器 PrimaryContainer", primaryContainer) { primaryContainer = it }
                ColorRow("副色 Secondary", secondary) { secondary = it }
                ColorRow("背景 Background", background) { background = it }
                ColorRow("表面 Surface", surface) { surface = it }
                ColorRow("卡片 SurfaceVariant", surfaceVariant) { surfaceVariant = it }
                Spacer(Modifier.height(12.dp))
                // 实时预览：模拟一个按钮 + 卡片
                Text("预览", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(surfaceVariant)
                        .padding(10.dp),
                ) {
                    Column {
                        Text(
                            "标题文字",
                            color = if (surfaceVariant.luminance() > 0.5f) Color.Black else Color.White,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(primary)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                "按钮",
                                color = if (primary.luminance() > 0.5f) Color.Black else Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    CustomColors(
                        primary = primary,
                        primaryContainer = primaryContainer,
                        secondary = secondary,
                        background = background,
                        surface = surface,
                        surfaceVariant = surfaceVariant,
                    ),
                )
            }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 单行颜色输入：左侧色块预览 + 标签 + hex 输入框。 */
@Composable
private fun ColorRow(
    label: String,
    color: Color,
    onChange: (Color) -> Unit,
) {
    var text by remember(color) {
        mutableStateOf("%06X".format(color.value.toInt() and 0xFFFFFF))
    }
    var parseError by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                // 只保留 hex 字符，最多 6 位
                val cleaned = v.filter { it.isLetterOrDigit() }.take(6)
                text = cleaned
                parseError = false
                if (cleaned.length == 6) {
                    val parsed = runCatching { Color(("FF" + cleaned).toLong(16)) }.getOrNull()
                    if (parsed != null) onChange(parsed) else parseError = true
                }
            },
            isError = parseError,
            singleLine = true,
            modifier = Modifier.width(110.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ============================ 背景图片设置 ============================

/**
 * 背景图片设置：选图按钮 + 不透明度/模糊滑块 + "仅浅色模式显示"开关。
 *
 * 使用 ACTION_OPEN_DOCUMENT 选取图片，takePersistableUriPermission 让 URI 跨重启仍可访问。
 */
@Composable
fun BackgroundImageSection(
    imageUri: String?,
    opacity: Float,
    blur: Float,
    lightOnly: Boolean,
    enabled: Boolean,
    onPickUri: (String) -> Unit,
    onClearUri: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onBlurChange: (Float) -> Unit,
    onLightOnlyChange: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // SAF 选图器：takePersistableUriPermission 让 URI 在进程重启后仍可用
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                // FLAG_GRANT_READ_URI_PERMISSION 必须显式 take，否则下次启动打开会 SecurityException
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onPickUri(uri.toString())
        }
    }

    Text(
        "背景图片",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
    )
    Text(
        "为整个 App 设置一张背景图，氛围感拉满（建议选竖图，深色模式默认不显示）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )

    Spacer(Modifier.height(10.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { launcher.launch(arrayOf("image/*")) },
            enabled = enabled,
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (imageUri == null) "选择图片" else "更换图片")
        }
        if (imageUri != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClearUri) {
                Icon(Icons.Outlined.Clear, contentDescription = "清除背景图")
            }
        }
    }

    if (imageUri != null) {
        Spacer(Modifier.height(12.dp))

        // 不透明度滑块
        Text("不透明度：${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = opacity,
            onValueChange = onOpacityChange,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )

        Spacer(Modifier.height(8.dp))

        // 模糊半径滑块（仅 Android 12+ 生效，目前 UI 占位）
        Text(
            "模糊半径：${blur.toInt()} dp" +
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) "（需 Android 12+）" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = blur,
            onValueChange = onBlurChange,
            valueRange = 0f..25f,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
        )

        Spacer(Modifier.height(8.dp))

        // 仅浅色模式显示
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("深色模式隐藏背景", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "深色模式下纯黑底更省电护眼",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(checked = lightOnly, onCheckedChange = onLightOnlyChange, enabled = enabled)
        }
    }
}
