package com.jmreader.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jmreader.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 阅读方向 */
enum class ReaderDirection { VERTICAL, HORIZONTAL }

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
)

class SettingsStore(private val context: Context) {

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
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            // 默认空：未配置时 app 会明确提示「请先到设置填写后端地址」，
            // 而不是去连一个不存在的 192.168.1.10 让用户困惑。
            serverUrl = p[Keys.SERVER_URL] ?: "",
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            readerDirection = runCatching { ReaderDirection.valueOf(p[Keys.READER_DIR] ?: "VERTICAL") }.getOrDefault(ReaderDirection.VERTICAL),
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: false,
            loggedInUser = p[Keys.LOGGED_USER],
            lastComicId = p[Keys.LAST_COMIC],
            lastChapterId = p[Keys.LAST_CHAPTER],
            lastPageIndex = p[Keys.LAST_PAGE] ?: 0,
            customApiDomains = p[Keys.CUSTOM_API_DOMAINS] ?: emptySet(),
            // 默认开启高刷：国产 ROM（小米/OPPO/vivo）对未显式请求高刷的 App 默认锁 60Hz，
            // 导致列表滚动卡顿。骁龙 8 系等旗舰芯片高刷无功耗压力，默认开启更流畅。
            preferMaxRefreshRate = p[Keys.MAX_REFRESH_RATE] ?: true,
            disclaimerAccepted = p[Keys.DISCLAIMER_ACCEPTED] ?: false,
        )
    }

    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[Keys.SERVER_URL] = url }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setReaderDirection(dir: ReaderDirection) = context.dataStore.edit { it[Keys.READER_DIR] = dir.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setPreferMaxRefreshRate(enabled: Boolean) = context.dataStore.edit { it[Keys.MAX_REFRESH_RATE] = enabled }
    suspend fun setDisclaimerAccepted(accepted: Boolean) = context.dataStore.edit { it[Keys.DISCLAIMER_ACCEPTED] = accepted }
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
}
