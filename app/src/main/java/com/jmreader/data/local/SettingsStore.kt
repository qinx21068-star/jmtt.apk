package com.jmreader.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.runtime.Immutable
import com.jmreader.ui.theme.CUSTOM_SCHEME_ID
import com.jmreader.ui.theme.CustomColors
import com.jmreader.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 阅读方向：VERTICAL=上下滚动，HORIZONTAL_LR=左右(左→右)，HORIZONTAL_RL=左右(右→左，日漫原生)。 */
enum class ReaderDirection { VERTICAL, HORIZONTAL_LR, HORIZONTAL_RL }

/** 横向阅读时的点击翻页区域分配。 */
enum class TapZoneMode { LEFT_RIGHT, THIRD_THIRD, DISABLED }

/** Tab 栏样式。 */
enum class TabBarStyle { DEFAULT, COMPACT, ICON_ONLY }

/** 卡片圆角模式：UNIFIED=所有圆角统一；SECTIONED=标题/内容分段不同圆角。 */
enum class CornerMode { UNIFIED, SECTIONED }

/** 图片质量档位。 */
enum class ImageQuality { ORIGINAL, HIGH, MEDIUM, LOW }

/** 排行榜时间维度。 */
enum class RankingPeriod { DAILY, WEEKLY, MONTHLY, ALL }

/** 缓存清理粒度。 */
enum class ClearCacheTarget { IMAGES, SEARCH, ALL }

/**
 * 屏蔽模式。
 * - [HIDE]：直接从列表中移除被屏蔽的本子（默认行为）。
 * - [COVER_ONLY]：保留卡片（标题/作者/标签等），仅把封面替换为打叉灰白占位图。
 */
enum class BlockMode { HIDE, COVER_ONLY }

/**
 * 列表展示样式。
 * - [LIST]：单列横向卡片（左缩略图 + 右信息），信息密度高。
 * - [GRID]：双列网格（封面 + 标题），浏览效率高。
 * - [COMPACT_GRID]：三列紧凑网格，一屏看更多。
 * - [CARD]：单列卡片样式（封面 + 标题 + tags chips + 统计），信息更丰富更美观。
 * - [MAGAZINE]：双列杂志风卡片（带 tags chip + 作者），适合美观浏览。
 */
enum class ListStyle { LIST, GRID, COMPACT_GRID, CARD, MAGAZINE }

/**
 * v27.5 性能修复：加 @Immutable 让 Compose 编译器信任此类型稳定。
 * 之前因含 Set<String> customApiDomains 字段，Compose 推断为 Unstable，
 * 导致所有 collectAsState<AppSettings> 的屏幕无法 skip 重组。
 * AppSettings 是 data class，所有字段都是 val 且类型本身不可变（Set/List 用不可变引用），
 * 实际语义就是 Immutable——加注解只是告诉编译器这一事实。
 */
@Immutable
data class AppSettings(
    val serverUrl: String,
    val themeMode: ThemeMode,
    val readerDirection: ReaderDirection,
    val dynamicColor: Boolean,
    val loggedInUser: String?,
    val lastComicId: String?,
    val lastChapterId: String?,
    val lastPageIndex: Int,
    /** 用户自定义 API 域名（合并到内置池前，优先使用）。 */
    val customApiDomains: Set<String>,
    /** 强制最高刷新率：true=锁定屏幕支持的最高刷新率（更流畅更耗电）；false=跟随系统默认。 */
    val preferMaxRefreshRate: Boolean,
    /** 是否已同意免责声明。首次启动为 false，用户阅读并同意后置 true，之后不再弹出。 */
    val disclaimerAccepted: Boolean = false,
    /** 列表展示样式（列表/双列网格/三列紧凑网格）。 */
    val listStyle: ListStyle = ListStyle.LIST,
    /** 音量键翻页：阅读器中按音量上/下键翻页（仅左右翻页模式生效，避免与竖滑滚动冲突）。 */
    val volumeKeyPaging: Boolean = false,
    /** v27.4 配色方案 id（对应 PresetScheme.id 或 CUSTOM_SCHEME_ID）。默认蓝白。 */
    val colorSchemeId: String = "kazumi_blue",
    /** v27.4 自定义颜色（仅当 colorSchemeId == CUSTOM_SCHEME_ID 时使用）。null=未配置。 */
    val customColors: CustomColors? = null,
    /** v27.4 背景图片 URI（content://... 形式，需 takePersistableUriPermission）。null=无背景图。 */
    val backgroundImageUri: String? = null,
    /** v27.4 背景图不透明度 0..1，默认 1.0（完全显示）。 */
    val backgroundImageOpacity: Float = 1.0f,
    /** v27.4 背景图模糊半径 0..25f（dp），默认 0（不模糊）。 */
    val backgroundImageBlur: Float = 0f,
    /** v27.4 背景图是否仅在浅色模式显示（深色模式下纯黑底更护眼）。 */
    val backgroundImageLightOnly: Boolean = true,

    // ============= v27.5 阅读器增强 =============
    /** 章节内点击翻页模式（横向阅读时生效）。 */
    val tapZoneMode: TapZoneMode = TapZoneMode.THIRD_THIRD,
    /** 自动滚动（竖向阅读时按速度自动滚屏）。 */
    val autoScroll: Boolean = false,
    /** 自动滚动速度（px/s），1..30，默认 5。 */
    val autoScrollSpeed: Float = 5f,
    /** 预加载下一章图片，避免翻章白屏。 */
    val preloadNextChapter: Boolean = true,
    /** 章节进度记忆到具体页（而非仅记章节）。 */
    val rememberPageLevel: Boolean = true,
    /** 阅读器字体大小 sp（竖向阅读文字模式才用到，本子主要是图片；为图片章节时的标题/页码用）。 */
    val readerFontSize: Float = 14f,
    /** 阅读器字体行距倍数 1.0..2.0。 */
    val readerLineSpacing: Float = 1.2f,
    /** 阅读器双指缩放开关（默认开）。 */
    val pinchZoom: Boolean = true,
    /** v27.5 #5：夜间阅读护眼滤镜（暖色叠加层，降低蓝光）。 */
    val nightModeFilter: Boolean = false,
    /** v27.5 #5：护眼滤镜强度 0..1，0.3 默认（轻暖色）。 */
    val nightModeFilterStrength: Float = 0.3f,

    // ============= v27.5 列表/UI 自定义 =============
    /** 卡片圆角 dp（4..24，默认 14）。 */
    val cardCornerRadius: Float = 14f,
    /** 卡片阴影 elevation dp（0..8，默认 0）。
     *  v27.5 卡顿修复：默认 0dp。Modifier.shadow 即使 1dp 也要做离屏渲染 + BlurMaskFilter，
     *  全屏 8-10 个卡片同时画 shadow 是滑动卡顿的核心原因之一。0dp 时跳过 .shadow() 完全无开销。
     *  想要阴影效果可在设置页「外观 → 卡片阴影」滑块调高。 */
    val cardElevation: Float = 0f,
    /** 卡片圆角模式：UNIFIED=统一；SECTIONED=标题/内容分段不同。 */
    val cornerMode: CornerMode = CornerMode.UNIFIED,
    /** 列表标题字体大小 sp（10..18，默认 13）。 */
    val listTitleFontSize: Float = 13f,
    /** 列表正文/副标题字体大小 sp（10..16，默认 12）。 */
    val listBodyFontSize: Float = 12f,
    /** 杂志风/网格封面的宽高比（"2:3" / "3:4" / "1:1" / "4:5"）。 */
    val coverAspectRatio: String = "2:3",
    /** 顶部 Tab 栏样式。 */
    val tabBarStyle: TabBarStyle = TabBarStyle.DEFAULT,
    /** 详情页封面视差滚动开关。 */
    val detailParallax: Boolean = true,
    /** 应用启动动画开关（splash 后列表淡入）。 */
    val splashAnim: Boolean = true,

    // ============= v27.5 隐私 =============
    /** 应用锁开关。开启后从后台返回前台需验证指纹/密码。 */
    val appLockEnabled: Boolean = false,
    /** 应用锁密码（PIN，4-8 位数字）。null=用生物识别，非 null=用 PIN。 */
    val appLockPin: String? = null,
    /** 隐身模式：不记录浏览历史、阅读进度、搜索历史。下载仍正常保存。 */
    val incognito: Boolean = false,
    /** 截图屏蔽（FLAG_SECURE）。 */
    val blockScreenshots: Boolean = false,

    // ============= v27.5 搜索 =============
    /** 记录搜索历史。关闭则不写本地。 */
    val saveSearchHistory: Boolean = true,
    /** 搜索结果按 tag 二次筛选（启用后搜索页显示 tag chips 二级过滤）。 */
    val searchTagFilter: Boolean = true,

    // ============= v27.5 下载 =============
    /** 下载路径（外置 SD 卡 SAF URI，null=默认内部存储）。 */
    val downloadDirUri: String? = null,
    /** 下载并发数 1..4，默认 2。 */
    val downloadConcurrency: Int = 2,
    /** 已下载本子本地搜索（启用后下载页提供搜索框）。 */
    val localSearchEnabled: Boolean = true,

    // ============= v27.5 网络/图片 =============
    /** 图片质量档位。 */
    val imageQuality: ImageQuality = ImageQuality.HIGH,
    /** 手动锁定图片 CDN 域名（null=自动轮换）。 */
    val pinnedImageCdn: String? = null,
    /** 代理设置（"host:port" 或 "socks5://host:port"，null=不走代理）。 */
    val proxy: String? = null,

    // ============= v27.5 排行榜 =============
    /** 排行榜时间维度（首页 ranking tab 用）。 */
    val rankingPeriod: RankingPeriod = RankingPeriod.WEEKLY,

    /** 屏蔽模式：HIDE=直接隐藏；COVER_ONLY=只隐藏封面（保留卡片，封面替换为打叉占位图）。 */
    val blockMode: BlockMode = BlockMode.HIDE,

    // ============= v27.15 稍后再看（通知栏快速收藏） =============
    /** 「通知栏稍后再看」开关。开启后启动 ForegroundService 常驻通知，
     *  用户可在通知里直接输入 JM 号加入「稍后再看」收藏夹。
     *  关闭后通知消失、Service 停止，已收藏的条目保留在「稍后再看」文件夹。 */
    val readLaterNotificationEnabled: Boolean = false,
)

class SettingsStore(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val THEME = stringPreferencesKey("theme")
        val READER_DIR = stringPreferencesKey("reader_dir")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LOGGED_USER = stringPreferencesKey("logged_user")
        val LAST_COMIC = stringPreferencesKey("last_comic")
        val LAST_CHAPTER = stringPreferencesKey("last_chapter")
        val LAST_PAGE = intPreferencesKey("last_page")
        val CUSTOM_API_DOMAINS = stringSetPreferencesKey("custom_api_domains")
        val MAX_REFRESH_RATE = booleanPreferencesKey("max_refresh_rate")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val LIST_STYLE = stringPreferencesKey("list_style")
        val VOLUME_KEY_PAGING = booleanPreferencesKey("volume_key_paging")
        // v27.4
        val COLOR_SCHEME_ID = stringPreferencesKey("color_scheme_id_v27_4")
        val CUSTOM_COLORS = stringPreferencesKey("custom_colors_v27_4")
        val BG_IMAGE_URI = stringPreferencesKey("bg_image_uri_v27_4")
        val BG_IMAGE_OPACITY = floatPreferencesKey("bg_image_opacity_v27_4")
        val BG_IMAGE_BLUR = floatPreferencesKey("bg_image_blur_v27_4")
        val BG_IMAGE_LIGHT_ONLY = booleanPreferencesKey("bg_image_light_only_v27_4")
        // v27.5 阅读器
        val TAP_ZONE_MODE = stringPreferencesKey("tap_zone_mode_v27_5")
        val AUTO_SCROLL = booleanPreferencesKey("auto_scroll_v27_5")
        val AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed_v27_5")
        val PRELOAD_NEXT_CHAPTER = booleanPreferencesKey("preload_next_ch_v27_5")
        val REMEMBER_PAGE_LEVEL = booleanPreferencesKey("remember_page_level_v27_5")
        val READER_FONT_SIZE = floatPreferencesKey("reader_font_size_v27_5")
        val READER_LINE_SPACING = floatPreferencesKey("reader_line_spacing_v27_5")
        val PINCH_ZOOM = booleanPreferencesKey("pinch_zoom_v27_5")
        val NIGHT_MODE_FILTER = booleanPreferencesKey("night_mode_filter_v27_5")
        val NIGHT_MODE_FILTER_STRENGTH = floatPreferencesKey("night_mode_filter_strength_v27_5")
        // v27.5 列表/UI
        val CARD_CORNER_RADIUS = floatPreferencesKey("card_corner_radius_v27_5")
        val CARD_ELEVATION = floatPreferencesKey("card_elevation_v27_5")
        val CORNER_MODE = stringPreferencesKey("corner_mode_v27_5")
        val LIST_TITLE_FONT_SIZE = floatPreferencesKey("list_title_font_v27_5")
        val LIST_BODY_FONT_SIZE = floatPreferencesKey("list_body_font_v27_5")
        val COVER_ASPECT_RATIO = stringPreferencesKey("cover_aspect_v27_5")
        val TAB_BAR_STYLE = stringPreferencesKey("tab_bar_style_v27_5")
        val DETAIL_PARALLAX = booleanPreferencesKey("detail_parallax_v27_5")
        val SPLASH_ANIM = booleanPreferencesKey("splash_anim_v27_5")
        // v27.5 隐私
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_v27_5")
        val APP_LOCK_PIN = stringPreferencesKey("app_lock_pin_v27_5")
        val INCOGNITO = booleanPreferencesKey("incognito_v27_5")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots_v27_5")
        // v27.5 搜索
        val SAVE_SEARCH_HISTORY = booleanPreferencesKey("save_search_history_v27_5")
        val SEARCH_TAG_FILTER = booleanPreferencesKey("search_tag_filter_v27_5")
        // v27.5 下载
        val DOWNLOAD_DIR_URI = stringPreferencesKey("download_dir_uri_v27_5")
        val DOWNLOAD_CONCURRENCY = intPreferencesKey("download_concurrency_v27_5")
        val LOCAL_SEARCH_ENABLED = booleanPreferencesKey("local_search_v27_5")
        // v27.5 网络/图片
        val IMAGE_QUALITY = stringPreferencesKey("image_quality_v27_5")
        val PINNED_IMAGE_CDN = stringPreferencesKey("pinned_image_cdn_v27_5")
        val PROXY = stringPreferencesKey("proxy_v27_5")
        // v27.5 排行榜
        val RANKING_PERIOD = stringPreferencesKey("ranking_period_v27_5")
        // 屏蔽模式
        val BLOCK_MODE = stringPreferencesKey("block_mode")
        // v27.15 稍后再看通知
        val READ_LATER_NOTIFICATION = booleanPreferencesKey("read_later_notification_v27_15")
    }

    /**
     * v27.5 性能优化：启动时同步读取一次设置快照。
     * - 作为 [settings] StateFlow 的初始值（让 collectAsState 首帧就能拿到非 null 值，避免"null → 默认 → 真实"两轮重组）
     * - 暴露 [appLockEnabled] / [appLockPin] / [disclaimerAccepted] 给 MainActivity 冷启动立即判断是否需要锁屏
     *
     * runBlocking 仅在 SettingsStore 构造时执行一次（AppContainer 构造期），DataStore 首次读取约 30-100ms，可接受。
     * 官方文档明确允许在应用启动时使用 runBlocking 读取 DataStore：
     * https://developer.android.com/topic/libraries/architecture/datastore#kotlin
     *
     * v27.5 性能优化：公开为 val（非 private），让所有屏幕的 collectAsState 都能用它作为初始值，
     * 避免"null → 默认 → 真实"两轮重组（用户感受为"全局卡顿"）。
     *
     * 注意：必须在 [settings] 之前声明，因为 settings 的 stateIn 用它作为初始值。
     *
     * v27.5 稳定性加固（用户反馈多人闪退）：DataStore 文件损坏 / 半截写入 / 磁盘满时
     * readFromDisk 会抛 IOException 一路冒泡到 Application.onCreate → App 启动失败。
     * 用 runCatching 兜底，失败时回退到 DEFAULT_SETTINGS，让用户至少能进 App 重置。
     */
    val cachedSnapshot: AppSettings = runCatching {
        runBlocking { readFromDisk() }
    }.getOrDefault(DEFAULT_SETTINGS)

    /** v27.5 稳定性加固：SettingsStore 启动兜底用的默认配置。 */
    companion object {
        /** 启动兜底默认配置：与 AppSettings 各字段默认值保持一致。 */
        val DEFAULT_SETTINGS: AppSettings = AppSettings(
            serverUrl = "",
            themeMode = ThemeMode.SYSTEM,
            readerDirection = ReaderDirection.VERTICAL,
            dynamicColor = false,
            loggedInUser = null,
            lastComicId = null,
            lastChapterId = null,
            lastPageIndex = 0,
            customApiDomains = emptySet(),
            preferMaxRefreshRate = true,
        )
    }

    /** 启动时同步可用的应用锁开关，供 MainActivity 冷启动立即判断。 */
    val appLockEnabled: Boolean get() = cachedSnapshot.appLockEnabled
    /** 启动时同步可用的应用锁 PIN（null=生物识别模式）。 */
    val appLockPin: String? get() = cachedSnapshot.appLockPin
    /** 启动时同步可用的免责声明接受状态。 */
    val disclaimerAccepted: Boolean get() = cachedSnapshot.disclaimerAccepted

    /**
     * v27.5 性能优化：把 settings 从冷 Flow 改为共享 StateFlow。
     * - 之前：每个 collectAsState 各自订阅 DataStore，每次 emit 都跑一次 map 重建 AppSettings
     * - 现在：stateIn(scope, Eagerly, snapshot) 共享单一上游订阅，所有订阅者拿到同一份 StateFlow
     * - distinctUntilChanged 避免字段未变时无谓 emit
     */
    val settings: StateFlow<AppSettings> = context.dataStore.data
        .catch { e ->
            // v27.5 稳定性加固：DataStore 文件损坏 / 反序列化异常时回退到空 preferences，
            // 让下游能继续拿到 AppSettings（用默认值），而不是抛 IOException 让订阅者崩溃。
            com.jmreader.core.Logger.w("Settings", "DataStore 读取失败，回退到默认值", e)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { mapPreferences(it) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, cachedSnapshot)

    private suspend fun readFromDisk(): AppSettings = context.dataStore.data.map { mapPreferences(it) }.first()

    /**
     * v27.5 稳定性加固：把 Preferences → AppSettings 的映射抽取为单一函数。
     * 之前 settings flow 和 readFromDisk 各写了一份字段映射，字段一多就容易漂移
     * （历史上已发生过 readFromDisk 把 saveSearchHistory/searchTagFilter 字段名写错，
     * 导致 cachedSnapshot 与运行时 settings 不一致）。统一从 [mapPreferences] 出，
     * 任何字段增改只改一处。
     */
    private fun mapPreferences(p: androidx.datastore.preferences.core.Preferences): AppSettings = AppSettings(
        // 默认空：未配置时 app 会明确提示「请先到设置填写后端地址」，
        // 而不是去连一个不存在的 192.168.1.10 让用户困惑。
        serverUrl = p[Keys.SERVER_URL] ?: "",
        themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        // 兼容老枚举值 "HORIZONTAL" → 默认改 HORIZONTAL_LR
        readerDirection = runCatching {
            when (p[Keys.READER_DIR] ?: "VERTICAL") {
                "HORIZONTAL" -> ReaderDirection.HORIZONTAL_LR
                else -> ReaderDirection.valueOf(p[Keys.READER_DIR] ?: "VERTICAL")
            }
        }.getOrDefault(ReaderDirection.VERTICAL),
        dynamicColor = p[Keys.DYNAMIC_COLOR] ?: false,
        loggedInUser = p[Keys.LOGGED_USER],
        lastComicId = p[Keys.LAST_COMIC],
        lastChapterId = p[Keys.LAST_CHAPTER],
        lastPageIndex = p[Keys.LAST_PAGE] ?: 0,
        customApiDomains = p[Keys.CUSTOM_API_DOMAINS] ?: emptySet(),
        preferMaxRefreshRate = p[Keys.MAX_REFRESH_RATE] ?: true,
        disclaimerAccepted = p[Keys.DISCLAIMER_ACCEPTED] ?: false,
        listStyle = runCatching { ListStyle.valueOf(p[Keys.LIST_STYLE] ?: "LIST") }.getOrDefault(ListStyle.LIST),
        volumeKeyPaging = p[Keys.VOLUME_KEY_PAGING] ?: false,
        colorSchemeId = p[Keys.COLOR_SCHEME_ID] ?: "kazumi_blue",
        customColors = p[Keys.CUSTOM_COLORS]?.let { raw ->
            // v27.5 稳定性加固：CustomColors.decode 可能因存储格式升级/数据损坏抛异常，
            // 未捕获会让整个 settings flow 崩溃 → 全局设置丢失 → App 无法启动。
            // 用 runCatching 兜底，失败时返回 null（用户需重新设置自定义配色）。
            runCatching { CustomColors.decode(raw) }.getOrElse {
                com.jmreader.core.Logger.w("Settings", "解析 customColors 失败: ${com.jmreader.core.Logger.brief(it)}")
                null
            }
        },
        backgroundImageUri = p[Keys.BG_IMAGE_URI],
        backgroundImageOpacity = p[Keys.BG_IMAGE_OPACITY] ?: 1.0f,
        backgroundImageBlur = p[Keys.BG_IMAGE_BLUR] ?: 0f,
        backgroundImageLightOnly = p[Keys.BG_IMAGE_LIGHT_ONLY] ?: true,
        // v27.5 阅读器
        tapZoneMode = runCatching { TapZoneMode.valueOf(p[Keys.TAP_ZONE_MODE] ?: "THIRD_THIRD") }.getOrDefault(TapZoneMode.THIRD_THIRD),
        autoScroll = p[Keys.AUTO_SCROLL] ?: false,
        autoScrollSpeed = p[Keys.AUTO_SCROLL_SPEED] ?: 5f,
        preloadNextChapter = p[Keys.PRELOAD_NEXT_CHAPTER] ?: true,
        rememberPageLevel = p[Keys.REMEMBER_PAGE_LEVEL] ?: true,
        readerFontSize = p[Keys.READER_FONT_SIZE] ?: 14f,
        readerLineSpacing = p[Keys.READER_LINE_SPACING] ?: 1.2f,
        pinchZoom = p[Keys.PINCH_ZOOM] ?: true,
        nightModeFilter = p[Keys.NIGHT_MODE_FILTER] ?: false,
        nightModeFilterStrength = (p[Keys.NIGHT_MODE_FILTER_STRENGTH] ?: 0.3f).coerceIn(0f, 1f),
        // v27.5 列表/UI
        cardCornerRadius = p[Keys.CARD_CORNER_RADIUS] ?: 14f,
        cardElevation = p[Keys.CARD_ELEVATION] ?: 0f,
        cornerMode = runCatching { CornerMode.valueOf(p[Keys.CORNER_MODE] ?: "UNIFIED") }.getOrDefault(CornerMode.UNIFIED),
        listTitleFontSize = p[Keys.LIST_TITLE_FONT_SIZE] ?: 13f,
        listBodyFontSize = p[Keys.LIST_BODY_FONT_SIZE] ?: 12f,
        coverAspectRatio = p[Keys.COVER_ASPECT_RATIO] ?: "2:3",
        tabBarStyle = runCatching { TabBarStyle.valueOf(p[Keys.TAB_BAR_STYLE] ?: "DEFAULT") }.getOrDefault(TabBarStyle.DEFAULT),
        detailParallax = p[Keys.DETAIL_PARALLAX] ?: true,
        splashAnim = p[Keys.SPLASH_ANIM] ?: true,
        // v27.5 隐私
        appLockEnabled = p[Keys.APP_LOCK_ENABLED] ?: false,
        appLockPin = p[Keys.APP_LOCK_PIN],
        incognito = p[Keys.INCOGNITO] ?: false,
        blockScreenshots = p[Keys.BLOCK_SCREENSHOTS] ?: false,
        // v27.5 搜索
        saveSearchHistory = p[Keys.SAVE_SEARCH_HISTORY] ?: true,
        searchTagFilter = p[Keys.SEARCH_TAG_FILTER] ?: true,
        // v27.5 下载
        downloadDirUri = p[Keys.DOWNLOAD_DIR_URI],
        downloadConcurrency = (p[Keys.DOWNLOAD_CONCURRENCY] ?: 2).coerceIn(1, 4),
        localSearchEnabled = p[Keys.LOCAL_SEARCH_ENABLED] ?: true,
        // v27.5 网络/图片
        imageQuality = runCatching { ImageQuality.valueOf(p[Keys.IMAGE_QUALITY] ?: "HIGH") }.getOrDefault(ImageQuality.HIGH),
        pinnedImageCdn = p[Keys.PINNED_IMAGE_CDN],
        proxy = p[Keys.PROXY],
        // v27.5 排行榜
        rankingPeriod = runCatching { RankingPeriod.valueOf(p[Keys.RANKING_PERIOD] ?: "WEEKLY") }.getOrDefault(RankingPeriod.WEEKLY),
        blockMode = runCatching { BlockMode.valueOf(p[Keys.BLOCK_MODE] ?: "HIDE") }.getOrDefault(BlockMode.HIDE),
        readLaterNotificationEnabled = p[Keys.READ_LATER_NOTIFICATION] ?: false,
    )

    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[Keys.SERVER_URL] = url }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setReaderDirection(dir: ReaderDirection) = context.dataStore.edit { it[Keys.READER_DIR] = dir.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setPreferMaxRefreshRate(enabled: Boolean) = context.dataStore.edit { it[Keys.MAX_REFRESH_RATE] = enabled }
    suspend fun setDisclaimerAccepted(accepted: Boolean) = context.dataStore.edit { it[Keys.DISCLAIMER_ACCEPTED] = accepted }
    suspend fun setListStyle(style: ListStyle) = context.dataStore.edit { it[Keys.LIST_STYLE] = style.name }
    suspend fun setVolumeKeyPaging(enabled: Boolean) = context.dataStore.edit { it[Keys.VOLUME_KEY_PAGING] = enabled }
    suspend fun setLoggedInUser(user: String?) = context.dataStore.edit {
        if (user == null) it.remove(Keys.LOGGED_USER) else it[Keys.LOGGED_USER] = user
    }
    suspend fun setReadingPosition(comicId: String?, chapterId: String?, page: Int) =
        context.dataStore.edit {
            if (comicId == null) it.remove(Keys.LAST_COMIC) else it[Keys.LAST_COMIC] = comicId
            if (chapterId == null) it.remove(Keys.LAST_CHAPTER) else it[Keys.LAST_CHAPTER] = chapterId
            it[Keys.LAST_PAGE] = page
        }

    /** 设置用户自定义 API 域名列表（覆盖）。 */
    suspend fun setCustomApiDomains(domains: Set<String>) = context.dataStore.edit {
        if (domains.isEmpty()) it.remove(Keys.CUSTOM_API_DOMAINS) else it[Keys.CUSTOM_API_DOMAINS] = domains
    }

    // v27.4：配色方案 + 背景图相关 setter
    suspend fun setColorSchemeId(id: String) = context.dataStore.edit { it[Keys.COLOR_SCHEME_ID] = id }
    suspend fun setCustomColors(colors: CustomColors?) = context.dataStore.edit {
        if (colors == null) it.remove(Keys.CUSTOM_COLORS) else it[Keys.CUSTOM_COLORS] = colors.encode()
    }
    /** 设置背景图 URI。null=清除。调用方需自行 takePersistableUriPermission。 */
    suspend fun setBackgroundImageUri(uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(Keys.BG_IMAGE_URI) else it[Keys.BG_IMAGE_URI] = uri
    }
    suspend fun setBackgroundImageOpacity(value: Float) = context.dataStore.edit {
        it[Keys.BG_IMAGE_OPACITY] = value.coerceIn(0f, 1f)
    }
    suspend fun setBackgroundImageBlur(value: Float) = context.dataStore.edit {
        it[Keys.BG_IMAGE_BLUR] = value.coerceIn(0f, 25f)
    }
    suspend fun setBackgroundImageLightOnly(value: Boolean) = context.dataStore.edit {
        it[Keys.BG_IMAGE_LIGHT_ONLY] = value
    }

    // ============= v27.5 setter =============
    // 阅读器
    suspend fun setTapZoneMode(m: TapZoneMode) = context.dataStore.edit { it[Keys.TAP_ZONE_MODE] = m.name }
    suspend fun setAutoScroll(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCROLL] = v }
    suspend fun setAutoScrollSpeed(v: Float) = context.dataStore.edit { it[Keys.AUTO_SCROLL_SPEED] = v.coerceIn(1f, 30f) }
    suspend fun setPreloadNextChapter(v: Boolean) = context.dataStore.edit { it[Keys.PRELOAD_NEXT_CHAPTER] = v }
    suspend fun setRememberPageLevel(v: Boolean) = context.dataStore.edit { it[Keys.REMEMBER_PAGE_LEVEL] = v }
    suspend fun setReaderFontSize(v: Float) = context.dataStore.edit { it[Keys.READER_FONT_SIZE] = v.coerceIn(10f, 24f) }
    suspend fun setReaderLineSpacing(v: Float) = context.dataStore.edit { it[Keys.READER_LINE_SPACING] = v.coerceIn(1f, 2f) }
    suspend fun setPinchZoom(v: Boolean) = context.dataStore.edit { it[Keys.PINCH_ZOOM] = v }
    suspend fun setNightModeFilter(v: Boolean) = context.dataStore.edit { it[Keys.NIGHT_MODE_FILTER] = v }
    suspend fun setNightModeFilterStrength(v: Float) = context.dataStore.edit {
        it[Keys.NIGHT_MODE_FILTER_STRENGTH] = v.coerceIn(0f, 1f)
    }
    // 列表/UI
    suspend fun setCardCornerRadius(v: Float) = context.dataStore.edit { it[Keys.CARD_CORNER_RADIUS] = v.coerceIn(0f, 28f) }
    suspend fun setCardElevation(v: Float) = context.dataStore.edit { it[Keys.CARD_ELEVATION] = v.coerceIn(0f, 8f) }
    suspend fun setCornerMode(m: CornerMode) = context.dataStore.edit { it[Keys.CORNER_MODE] = m.name }
    suspend fun setListTitleFontSize(v: Float) = context.dataStore.edit { it[Keys.LIST_TITLE_FONT_SIZE] = v.coerceIn(10f, 18f) }
    suspend fun setListBodyFontSize(v: Float) = context.dataStore.edit { it[Keys.LIST_BODY_FONT_SIZE] = v.coerceIn(10f, 16f) }
    suspend fun setCoverAspectRatio(s: String) = context.dataStore.edit { it[Keys.COVER_ASPECT_RATIO] = s }
    suspend fun setTabBarStyle(s: TabBarStyle) = context.dataStore.edit { it[Keys.TAB_BAR_STYLE] = s.name }
    suspend fun setDetailParallax(v: Boolean) = context.dataStore.edit { it[Keys.DETAIL_PARALLAX] = v }
    suspend fun setSplashAnim(v: Boolean) = context.dataStore.edit { it[Keys.SPLASH_ANIM] = v }
    // 隐私
    suspend fun setAppLockEnabled(v: Boolean) = context.dataStore.edit { it[Keys.APP_LOCK_ENABLED] = v }
    suspend fun setAppLockPin(pin: String?) = context.dataStore.edit {
        if (pin == null) it.remove(Keys.APP_LOCK_PIN) else it[Keys.APP_LOCK_PIN] = pin
    }
    suspend fun setIncognito(v: Boolean) = context.dataStore.edit { it[Keys.INCOGNITO] = v }
    suspend fun setBlockScreenshots(v: Boolean) = context.dataStore.edit { it[Keys.BLOCK_SCREENSHOTS] = v }
    // 搜索
    suspend fun setSaveSearchHistory(v: Boolean) = context.dataStore.edit { it[Keys.SAVE_SEARCH_HISTORY] = v }
    suspend fun setSearchTagFilter(v: Boolean) = context.dataStore.edit { it[Keys.SEARCH_TAG_FILTER] = v }
    // 下载
    suspend fun setDownloadDirUri(uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(Keys.DOWNLOAD_DIR_URI) else it[Keys.DOWNLOAD_DIR_URI] = uri
    }
    suspend fun setDownloadConcurrency(v: Int) = context.dataStore.edit { it[Keys.DOWNLOAD_CONCURRENCY] = v.coerceIn(1, 4) }
    suspend fun setLocalSearchEnabled(v: Boolean) = context.dataStore.edit { it[Keys.LOCAL_SEARCH_ENABLED] = v }
    // 网络/图片
    suspend fun setImageQuality(q: ImageQuality) = context.dataStore.edit { it[Keys.IMAGE_QUALITY] = q.name }
    suspend fun setPinnedImageCdn(cdn: String?) = context.dataStore.edit {
        if (cdn == null) it.remove(Keys.PINNED_IMAGE_CDN) else it[Keys.PINNED_IMAGE_CDN] = cdn
    }
    suspend fun setProxy(p: String?) = context.dataStore.edit {
        if (p == null) it.remove(Keys.PROXY) else it[Keys.PROXY] = p
    }
    // 排行榜
    suspend fun setRankingPeriod(p: RankingPeriod) = context.dataStore.edit { it[Keys.RANKING_PERIOD] = p.name }
    suspend fun setBlockMode(m: BlockMode) = context.dataStore.edit { it[Keys.BLOCK_MODE] = m.name }

    /** v27.15：开关「通知栏稍后再看」。实际启停 Service 由 MainActivity/JMApp 监听 settings flow 触发。 */
    suspend fun setReadLaterNotificationEnabled(v: Boolean) = context.dataStore.edit {
        it[Keys.READ_LATER_NOTIFICATION] = v
    }
}
