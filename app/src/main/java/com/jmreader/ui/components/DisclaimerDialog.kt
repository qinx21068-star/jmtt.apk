package com.jmreader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 免责声明全文（首次启动弹窗与设置-关于页共用，保证两处文案一致）。
 */
val DisclaimerContent: String = """
本项目仅供个人学习、技术交流和研究使用。

1. 本项目为第三方开源客户端，不提供任何漫画内容资源，仅作为访问接口的工具。
2. 项目所有使用者必须遵守所在国家/地区的法律法规。
3. 本项目不鼓励、不支持任何侵犯版权的行为。请尊重原作者和版权方的合法权益，支持正版内容。
4. 项目作者不承担任何因使用本软件产生的法律责任、账号封禁、数据丢失或其他任何形式的后果。
5. 未成年人禁止使用本软件
6. 使用本软件即表示您已阅读并同意本声明。如不同意，请立即停止使用并删除本软件。

珍爱生命，爱护身体，理性使用，远离风险。
""".trim()

/**
 * 免责声明对话框。
 *
 * @param forceCountdown true=首次启动场景，确认按钮 5 秒倒计时后才可点击，强制用户至少阅读 5 秒；
 *                       false=关于页查看场景，按钮立即可点击（用户已同意过，仅查看）。
 * @param onAccept       用户点击"我已阅读并同意"。
 * @param onDismiss      用户点击"关闭"或外部返回（关于页场景使用）。
 */
@Composable
fun DisclaimerDialog(
    forceCountdown: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 倒计时剩余秒数。forceCountdown=false 时直接为 0，按钮立即可点。
    var remaining by remember { mutableIntStateOf(if (forceCountdown) 5 else 0) }
    if (remaining > 0) {
        LaunchedEffect(Unit) {
            while (remaining > 0) {
                delay(1000)
                remaining -= 1
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            // 首次启动倒计时未结束不允许点外部关闭，避免用户误触跳过阅读
            if (remaining == 0) onDismiss()
        },
        title = { Text("免责声明", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = DisclaimerContent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                enabled = remaining == 0,
            ) {
                Text(
                    if (remaining > 0) "请阅读 ${remaining}s"
                    else if (forceCountdown) "我已阅读并同意"
                    else "关闭",
                )
            }
        },
        dismissButton = {
            // 首次启动场景提供"不同意"出口（退出 App）；关于页查看不显示，只留"关闭"在 confirm
            if (forceCountdown) {
                TextButton(
                    onClick = onDismiss,
                    enabled = remaining == 0,
                ) { Text("不同意，退出") }
            }
        },
    )
}
