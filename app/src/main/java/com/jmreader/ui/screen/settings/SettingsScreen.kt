@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jmreader.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmreader.R
import com.jmreader.data.AppContainer
import com.jmreader.data.repository.Resource
import com.jmreader.data.local.ReaderDirection
import com.jmreader.ui.components.DisclaimerDialog
import com.jmreader.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings = container.settingsStore.settings
    val blockedTags = container.blockedTagsStore.tags
    val blockedNames = container.blockedTagsStore.names
    val blockedAuthors = container.blockedTagsStore.authors

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun setServerUrl(url: String) = viewModelScope.launch {
        val trimmed = url.trim()
        // URL 格式校验：非空时必须以 http:// 或 https:// 开头，避免非法 URL 写盘后所有请求失败
        if (trimmed.isNotEmpty()) {
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)) {
                _events.emit("URL 必须以 http:// 或 https:// 开头")
                return@launch
            }
            // 统一去掉末尾斜杠
            container.settingsStore.setServerUrl(trimmed.trimEnd('/'))
        } else {
            // 空 URL：切回直连模式
            container.settingsStore.setServerUrl("")
        }
        container.rebuildApi()
        _events.emit(if (trimmed.isEmpty()) "已切换为直连模式" else "后端地址已更新")
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { container.settingsStore.setThemeMode(mode) }
    fun setReaderDirection(dir: ReaderDirection) = viewModelScope.launch { container.settingsStore.setReaderDirection(dir) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setDynamicColor(enabled) }
    fun setMaxRefreshRate(enabled: Boolean) = viewModelScope.launch {
        container.settingsStore.setPreferMaxRefreshRate(enabled)
        _events.emit(if (enabled) "已开启最高刷新率" else "已关闭最高刷新率")
    }

    /** 屏蔽词最大长度，避免超长关键词拖慢过滤性能 */
    private val maxBlockItemLength = 50

    fun addBlockedTag(tag: String) = viewModelScope.launch {
        val t = tag.trim()
        if (t.isEmpty()) return@launch
        if (t.length > maxBlockItemLength) {
            _events.emit("屏蔽词过长（最多 $maxBlockItemLength 字符）")
            return@launch
        }
        val existing = container.blockedTagsStore.tags.first()
        if (existing.any { it.equals(t, ignoreCase = true) }) {
            _events.emit("该屏蔽 Tag 已存在：$t")
            return@launch
        }
        container.blockedTagsStore.addTag(t)
        _events.emit("已添加屏蔽 Tag：$t")
    }

    fun removeBlockedTag(tag: String) = viewModelScope.launch {
        container.blockedTagsStore.removeTag(tag)
        _events.emit("已移除屏蔽 Tag：$tag")
    }

    fun addBlockedName(name: String) = viewModelScope.launch {
        val n = name.trim()
        if (n.isEmpty()) return@launch
        if (n.length > maxBlockItemLength) {
            _events.emit("屏蔽词过长（最多 $maxBlockItemLength 字符）")
            return@launch
        }
        val existing = container.blockedTagsStore.names.first()
        if (existing.any { it.equals(n, ignoreCase = true) }) {
            _events.emit("该屏蔽名称已存在：$n")
            return@launch
        }
        container.blockedTagsStore.addName(n)
        _events.emit("已添加屏蔽名称：$n")
    }

    fun removeBlockedName(name: String) = viewModelScope.launch {
        container.blockedTagsStore.removeName(name)
        _events.emit("已移除屏蔽名称：$name")
    }

    fun addBlockedAuthor(author: String) = viewModelScope.launch {
        val a = author.trim()
        if (a.isEmpty()) return@launch
        if (a.length > maxBlockItemLength) {
            _events.emit("屏蔽词过长（最多 $maxBlockItemLength 字符）")
            return@launch
        }
        val existing = container.blockedTagsStore.authors.first()
        if (existing.any { it.equals(a, ignoreCase = true) }) {
            _events.emit("该屏蔽作者已存在：$a")
            return@launch
        }
        container.blockedTagsStore.addAuthor(a)
        _events.emit("已添加屏蔽作者：$a")
    }

    fun removeBlockedAuthor(author: String) = viewModelScope.launch {
        container.blockedTagsStore.removeAuthor(author)
        _events.emit("已移除屏蔽作者：$author")
    }

    /** 登录中状态：UI 据此禁用登录按钮，防止连点发多次请求 */
    private val _loggingIn = MutableStateFlow(false)
    val loggingIn: StateFlow<Boolean> = _loggingIn.asStateFlow()

    fun login(user: String, pass: String) = viewModelScope.launch {
        // 防重复点击：正在登录时直接返回
        if (_loggingIn.value) return@launch
        _loggingIn.value = true
        try {
            when (val r = container.repository.login(user, pass)) {
                is Resource.Success -> _events.emit(if (r.data) "登录成功" else "登录失败：账号或密码错误")
                is Resource.Error -> _events.emit(r.message)
                Resource.Loading -> {}
            }
        } finally {
            _loggingIn.value = false
        }
    }

    fun logout() = viewModelScope.launch {
        container.repository.logout()
        _events.emit("已退出登录")
    }

    /** 健康检查：探测后端可达性 + jmcomic 就绪状态。 */
    fun healthCheck() = viewModelScope.launch {
        _events.emit("正在检查后端…")
        val (ok, msg) = container.healthCheck()
        _events.emit((if (ok) "✓ " else "✗ ") + msg)
    }

    /**
     * 保存后端 URL 并立即测试连接。
     * 关键修复：之前"测试连接"按钮调 healthCheck()，但 healthCheck 用的是已保存的 URL，
     * 用户在输入框输入新 URL 未点保存时，测试的是旧 URL，行为反直觉。
     * 现在合并为"保存并测试"，确保测试的是用户当前输入的 URL。
     */
    fun saveAndHealthCheck(url: String) = viewModelScope.launch {
        val trimmed = url.trim()
        // 基础校验：空 URL 表示切回直连模式，允许；非空 URL 做简单格式检查
        if (trimmed.isNotEmpty()) {
            // 必须以 http:// 或 https:// 开头
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)) {
                _events.emit("URL 必须以 http:// 或 https:// 开头")
                return@launch
            }
            // 去掉末尾斜杠，统一格式
            val normalized = trimmed.trimEnd('/')
            container.settingsStore.setServerUrl(normalized)
            container.rebuildApi()
        } else {
            // 空 URL：切回直连模式
            container.settingsStore.setServerUrl("")
            container.rebuildApi()
        }
        _events.emit("正在检查后端…")
        val (ok, msg) = container.healthCheck()
        _events.emit((if (ok) "✓ " else "✗ ") + msg)
    }
}

class SettingsVMFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(container) as T
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(container: AppContainer, onOpenLogs: () -> Unit = {}, onOpenDomains: () -> Unit = {}) {
    val vm: SettingsViewModel = viewModel(factory = SettingsVMFactory(container))
    val settings by vm.settings.collectAsState(initial = null)
    val blockedTags by vm.blockedTags.collectAsState(initial = emptySet())
    val blockedNames by vm.blockedNames.collectAsState(initial = emptySet())
    val blockedAuthors by vm.blockedAuthors.collectAsState(initial = emptySet())
    val loggingIn by vm.loggingIn.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 关于页查看免责声明：点击按钮显示，无倒计时强制（用户已同意过）
    var showDisclaimer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { snackbar.showSnackbar(it) }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ===================== 外观 =====================
            GroupedSection(
                title = stringResource(R.string.settings_group_appearance),
                icon = Icons.Outlined.Palette,
            ) {
                // 主题模式
                Text(
                    stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                val mode = settings?.themeMode ?: ThemeMode.SYSTEM
                val labels = listOf(
                    stringResource(R.string.settings_theme_system),
                    stringResource(R.string.settings_theme_light),
                    stringResource(R.string.settings_theme_dark),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    ThemeMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { vm.setTheme(m) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        ) { Text(labels[index]) }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // 动态取色
                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = stringResource(R.string.settings_dynamic_color_desc),
                    checked = settings?.dynamicColor ?: false,
                    onCheckedChange = { vm.setDynamicColor(it) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // 强制最高刷新率
                SwitchRow(
                    title = stringResource(R.string.settings_max_refresh_rate),
                    subtitle = stringResource(R.string.settings_max_refresh_rate_desc),
                    checked = settings?.preferMaxRefreshRate ?: false,
                    onCheckedChange = { vm.setMaxRefreshRate(it) },
                )
                // 设备刷新率诊断：告诉用户系统实际支持的最高刷新率。
                // 若显示 60Hz，说明系统设置里没开高刷，本应用开关无效——需引导用户去系统设置开启。
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val maxHz = remember {
                    runCatching {
                        com.jmreader.MainActivity.maxRefreshRate(ctx as android.app.Activity)
                    }.getOrDefault(60f).toInt()
                }
                Text(
                    text = when {
                        maxHz >= 90 -> "设备支持最高 ${maxHz}Hz（已开启高刷，本开关可生效）"
                        else -> "设备当前最高 ${maxHz}Hz（系统设置可能未开启高刷新率，本开关无法提升）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ===================== 阅读 =====================
            GroupedSection(
                title = stringResource(R.string.settings_group_reading),
                icon = Icons.Outlined.MenuBook,
            ) {
                Text(
                    stringResource(R.string.settings_reader_direction),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                val dir = settings?.readerDirection ?: ReaderDirection.VERTICAL
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    SegmentedButton(
                        selected = dir == ReaderDirection.VERTICAL,
                        onClick = { vm.setReaderDirection(ReaderDirection.VERTICAL) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.settings_reader_vertical)) }
                    SegmentedButton(
                        selected = dir == ReaderDirection.HORIZONTAL,
                        onClick = { vm.setReaderDirection(ReaderDirection.HORIZONTAL) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.settings_reader_horizontal)) }
                }
            }

            // ===================== 网络 =====================
            GroupedSection(
                title = stringResource(R.string.settings_group_network),
                icon = Icons.Outlined.Cloud,
            ) {
                Text(
                    stringResource(R.string.settings_server),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                var url by remember(settings?.serverUrl) { mutableStateOf(settings?.serverUrl ?: "") }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    placeholder = { Text(stringResource(R.string.settings_server_hint)) },
                    singleLine = true,
                )
                Text(
                    text = stringResource(R.string.settings_server_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { vm.setServerUrl(url.trim()) }) { Text("保存并应用") }
                    // 测试连接：先保存当前输入的 URL 再测试，确保测试的是用户输入而非旧值
                    OutlinedButton(onClick = { vm.saveAndHealthCheck(url.trim()) }) { Text("保存并测试") }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Text(
                    "API 域名管理",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "查看/测速/增删禁漫 API 域名。若「最新」加载失败多半是域名过期，可在此手动切换或添加新域名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "当前域名：${container.directClient.currentDomain().ifBlank { "无" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Button(
                    onClick = onOpenDomains,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("进入域名管理") }
            }

            // ===================== 内容过滤 =====================
            GroupedSection(
                title = stringResource(R.string.settings_group_filter),
                icon = Icons.Outlined.FilterAlt,
            ) {
                BlockListSection(
                    title = stringResource(R.string.settings_blocked_tags),
                    subtitle = stringResource(R.string.settings_blocked_tags_desc),
                    items = blockedTags,
                    addButtonText = stringResource(R.string.settings_add_tag),
                    inputHint = "输入 Tag 名称",
                    emptyText = "暂无屏蔽 Tag",
                    onAdd = { vm.addBlockedTag(it) },
                    onRemove = { vm.removeBlockedTag(it) },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                BlockListSection(
                    title = stringResource(R.string.settings_blocked_names),
                    subtitle = stringResource(R.string.settings_blocked_names_desc),
                    items = blockedNames,
                    addButtonText = stringResource(R.string.settings_add_name),
                    inputHint = "输入标题关键词，标题含此词的本子将被屏蔽",
                    emptyText = "暂无屏蔽名称",
                    onAdd = { vm.addBlockedName(it) },
                    onRemove = { vm.removeBlockedName(it) },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                BlockListSection(
                    title = "屏蔽作者",
                    subtitle = "屏蔽指定作者的所有作品。作者名精确匹配（不区分大小写/繁简），列表即时过滤无需补全。",
                    items = blockedAuthors,
                    addButtonText = "添加作者",
                    inputHint = "输入作者名",
                    emptyText = "暂无屏蔽作者",
                    onAdd = { vm.addBlockedAuthor(it) },
                    onRemove = { vm.removeBlockedAuthor(it) },
                )
            }

            // ===================== 账号 =====================
            GroupedSection(
                title = stringResource(R.string.settings_group_account),
                icon = Icons.Outlined.Person,
            ) {
                val user = settings?.loggedInUser
                if (user != null) {
                    Text("当前账号：$user", style = MaterialTheme.typography.bodyMedium)
                    var showLogoutConfirm by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { showLogoutConfirm = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.settings_logout))
                    }
                    // 退出登录二次确认：避免误触导致站点收藏/同步不可用
                    if (showLogoutConfirm) {
                        AlertDialog(
                            onDismissRequest = { showLogoutConfirm = false },
                            title = { Text("退出登录") },
                            text = { Text("退出后将无法访问站点收藏和同步功能。\n\n确认退出？") },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    showLogoutConfirm = false
                                    vm.logout()
                                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { showLogoutConfirm = false }) {
                                    Text("取消")
                                }
                            },
                        )
                    }
                } else {
                    var u by remember { mutableStateOf("") }
                    var p by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = u, onValueChange = { u = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("账号") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = p, onValueChange = { p = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("密码") }, singleLine = true,
                        // 密码键盘：禁用自动大写，使用密码键盘类型
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                        ),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    Button(
                        onClick = { vm.login(u, p) },
                        modifier = Modifier.padding(top = 8.dp),
                        // 空输入禁用 + 登录中禁用，避免发空请求和重复点击
                        enabled = u.isNotBlank() && p.isNotBlank() && !loggingIn,
                    ) { Text(if (loggingIn) "登录中…" else stringResource(R.string.settings_login)) }
                }
            }

            // ===================== 关于 =====================
            GroupedSection(
                title = stringResource(R.string.settings_group_about),
                icon = Icons.Outlined.Info,
            ) {
                Text("JMReader v${com.jmreader.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    "干净无广告的第三方客户端。请合规使用，仅限个人学习。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.padding(top = 8.dp)) {
                    Text("查看日志 / 诊断")
                }
                OutlinedButton(
                    onClick = { showDisclaimer = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("查看免责声明")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // 关于页查看免责声明：无倒计时，仅查看（用户首次启动时已同意过）
    if (showDisclaimer) {
        DisclaimerDialog(
            forceCountdown = false,
            onAccept = { showDisclaimer = false },
            onDismiss = { showDisclaimer = false },
        )
    }
}

/**
 * 分组卡片：顶部图标 + 标题，内容区域由 content 提供。
 * 替代旧的 Section，把零散的设置项按 外观/阅读/网络/过滤/账号/关于 归类。
 */
@Composable
private fun GroupedSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 带标题 + 副标题的开关行，外观/性能选项统一用这个样式。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 屏蔽规则列表 Section（Tag / 名称关键词共用）。
 * 已有规则以可点击删除的 Chip 展示，点「添加」弹出输入框。
 */
@Composable
private fun BlockListSection(
    title: String,
    subtitle: String,
    items: Set<String>,
    addButtonText: String,
    inputHint: String,
    emptyText: String,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        items.forEach { item ->
            // AssistChip：点 chip 主体无操作（避免误删），点 trailingIcon 的叉号才删除
            AssistChip(
                onClick = {},
                label = { Text(item, maxLines = 1) },
                trailingIcon = {
                    androidx.compose.material3.IconButton(
                        onClick = { onRemove(item) },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "移除 $item",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
        if (items.isEmpty()) Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    var newItem by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showAdd = true },
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null)
        Text("  $addButtonText")
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false; newItem = "" },
            title = { Text(addButtonText) },
            text = {
                OutlinedTextField(
                    value = newItem,
                    onValueChange = { newItem = it },
                    singleLine = true,
                    placeholder = { Text(inputHint) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newItem.isNotBlank()) {
                        onAdd(newItem.trim())
                        newItem = ""
                    }
                    showAdd = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; newItem = "" }) { Text("取消") }
            },
        )
    }
}
