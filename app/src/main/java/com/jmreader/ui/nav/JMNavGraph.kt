package com.jmreader.ui.nav

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jmreader.R
import com.jmreader.data.AppContainer
import com.jmreader.ui.screen.author.AuthorScreen
import com.jmreader.ui.screen.comment.CommentsScreen
import com.jmreader.ui.screen.detail.DetailScreen
import com.jmreader.ui.screen.downloads.DownloadsScreen
import com.jmreader.ui.screen.favorites.FavoritesScreen
import com.jmreader.ui.screen.home.HomeScreen
import com.jmreader.ui.screen.logs.LogsScreen
import com.jmreader.ui.screen.reader.ReaderScreen
import com.jmreader.ui.screen.search.SearchScreen
import com.jmreader.ui.screen.settings.SettingsScreen

/**
 * 底部导航 tab：选中态用 filled 图标，未选中态用 outlined 图标，
 * 让当前 tab 视觉更突出（Material 3 推荐做法）。
 */
private data class Tab(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,        // 未选中（outlined）
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector, // 选中（filled）
)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    // 搜索 tab 的 route 用带可选参数的模板，选中态判断才能匹配 currentRoute。
    // 实际 navigate 用 Routes.search()（无参 → "search"），由 Navigation 解析为 q=null。
    Tab(Routes.SEARCH_WITH_Q, R.string.nav_search, Icons.Outlined.Search, Icons.Filled.Search),
    Tab(Routes.FAVORITES, R.string.nav_favorites, Icons.Outlined.Bookmark, Icons.Filled.Bookmark),
    Tab(Routes.DOWNLOADS, R.string.nav_downloads, Icons.Outlined.Download, Icons.Filled.Download),
    // 讨论区：v27.9 改用 /forum JSON API（reqApi 通道，token 鉴权 + AES 解密 + 域名轮换），
    // 替代之前不稳定的 HTML 抓取（JmWebFetcher + jm365 重定向，CF 拦截频繁失效）。
    Tab(Routes.FORUM, R.string.nav_forum, Icons.Outlined.Forum, Icons.Filled.Forum),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun JMApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // v27.6：读取 tabBarStyle 设置，控制底部导航栏标签显隐
    val settings by container.settingsStore.settings.collectAsState()
    val tabBarStyle = settings.tabBarStyle
    // COMPACT / ICON_ONLY 模式下隐藏文字标签，仅显示图标
    val showTabLabel = tabBarStyle == com.jmreader.data.local.TabBarStyle.DEFAULT

    // 预计算 tab 路由集合，避免每次重组都 tabs.map { } 创建新 List。
    val tabRoutes = remember { tabs.map { it.route }.toSet() }

    // v27.7：切换 tab 时立即收起键盘，双重保险防止键盘遮挡底部导航栏。
    // imePadding 已让 Scaffold 随键盘上移，但键盘收起动画期间仍可能拦截点击事件，
    // 显式 hide() 确保点击 tab 的瞬间键盘开始收起，不阻挡导航。
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // 详情/阅读器为全屏，不显示底部栏
    val showBottomBar = currentRoute != null && currentRoute in tabRoutes

    Scaffold(
        // v27.6 修复：键盘弹出时推高底部导航栏，避免遮挡 tab 导致点不到
        // 根因：edge-to-edge 模式下 IME insets 不会自动缩小窗口，
        // Scaffold 默认不处理 IME insets → 键盘直接覆盖 NavigationBar
        // → 用户在搜索页输入 tag 时点首页 tab 实际点到键盘 → "无法回到首页"
        modifier = Modifier.imePadding(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                // v27.7：切换 tab 前先收键盘，防止键盘遮挡导致点击无效
                                keyboardController?.hide()
                                // 搜索 tab 用 Routes.search() 生成无参 "search"，
                                // 避免把 {q} 占位符当字面量传给 navigate。
                                val target = if (tab.route == Routes.SEARCH_WITH_Q) Routes.search() else tab.route
                                navController.navigate(target) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                // 选中态显示 filled 图标，未选中显示 outlined 图标，
                                // 让当前 tab 视觉更突出（Material 3 推荐做法）。
                                Icon(
                                    if (selected) tab.selectedIcon else tab.icon,
                                    contentDescription = null,
                                )
                            },
                            label = if (showTabLabel) { { Text(stringResource(tab.labelRes)) } } else null,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        }
    ) { inner ->
        // 全局页面切换动画：默认仅淡入淡出（轻量，不拖累底部 tab 切换）。
        // 进入详情/阅读器这类"压栈"语义的场景，由具体 composable 自己加 slide。
        // 之前的 slideInHorizontally 在每次切 tab 都跑，反而让底部导航显得卡。
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(inner),
            enterTransition = {
                androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(180),
                )
            },
            exitTransition = {
                androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(140),
                )
            },
            popEnterTransition = {
                androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(180),
                )
            },
            popExitTransition = {
                androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(140),
                )
            },
        ) {
            composable(Routes.HOME) { HomeScreen(container, navController) }
            // 搜索页支持可选初始关键词 q：从详情页点标签搜索时传入。
            // q 为 null（底部 tab 进入）或具体标签词（详情页点标签进入）。
            composable(
                route = Routes.SEARCH_WITH_Q,
                arguments = listOf(
                    androidx.navigation.navArgument("q") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
            ) { entry ->
                SearchScreen(
                    container = container,
                    navController = navController,
                    initialQuery = entry.arguments?.getString("q"),
                )
            }
            composable(Routes.FAVORITES) { FavoritesScreen(container, navController) }
            composable(Routes.DOWNLOADS) { DownloadsScreen(container, navController) }
            composable(Routes.FORUM) {
                // 讨论区：v27.9 从 HTML 抓取禁漫网页端论坛（JmWebFetcher + jm365.work 重定向，
                // CF 拦截频繁失效）改为 /forum JSON API（mode="manhua", aid=null → 全局评论流）。
                // 走 reqApi 通道（token 鉴权 + AES 解密 + 域名轮换），与搜索/详情同款，已验证稳定。
                com.jmreader.ui.screen.forum.ForumScreen(
                    onBack = { navController.popBackStack() },
                    // launchSingleTop：与 CommentsScreen 同款，避免连点 JM 链接堆积 Detail 实例
                    onOpenComic = { id ->
                        navController.navigate(Routes.detail(id)) {
                            launchSingleTop = true
                        }
                    },
                    container = container,
                )
            }
            composable(Routes.IMAGE_SEARCH) {
                com.jmreader.ui.screen.imagesearch.ImageSearchScreen(
                    container = container,
                    navController = navController,
                )
            }
            composable(
                route = Routes.COMMENTS,
                arguments = listOf(navArgument("comicId") { type = NavType.StringType }),
            ) { entry ->
                CommentsScreen(
                    albumId = entry.arguments?.getString("comicId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    // launchSingleTop：避免连点 JM 链接堆积多个 Detail 实例
                    onOpenComic = { id ->
                        navController.navigate(Routes.detail(id)) {
                            launchSingleTop = true
                        }
                    },
                    container = container,
                )
            }
            // v27.5 #13：作者主页
            composable(
                route = Routes.AUTHOR,
                arguments = listOf(navArgument("name") { type = NavType.StringType }),
            ) { entry ->
                AuthorScreen(
                    container = container,
                    navController = navController,
                    author = entry.arguments?.getString("name").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container,
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    onOpenDomains = { navController.navigate(Routes.DOMAINS) },
                )
            }
            composable(Routes.LOGS) { LogsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.DOMAINS) {
                com.jmreader.ui.screen.settings.DomainManageScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("comicId") { type = NavType.StringType }),
            ) { entry ->
                DetailScreen(
                    container = container,
                    comicId = entry.arguments?.getString("comicId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onRead = { comicId, chapterId ->
                        navController.navigate(Routes.reader(comicId, chapterId)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    // 单击标签 → 用标签词搜索；导航到搜索页并带上 q 参数
                    onSearchByTag = { tag -> navController.navigate(Routes.search(tag)) },
                    // v27.5 #13：单击作者名 → 跳作者主页（独立 Screen，基于搜索 API）
                    onSearchByAuthor = { author -> navController.navigate(Routes.author(author)) },
                    // 查看评论区（完整分页）
                    onOpenComments = { id -> navController.navigate(Routes.comments(id)) },
                    // v27.5 修复：详情页内部跳转（相似推荐/作者其它作品/内嵌评论 JM 链接）失效。
                    // 原代码 `launchSingleTop = true` 按"路由模式 detail/{comicId}"去重，
                    // 不区分 comicId 是否相同：从详情页 A 点击推荐卡片跳到详情页 B 时，
                    // 详情页已是栈顶 → NavController 静默拦截，不创建新 entry、不更新参数 →
                    // UI 仍停留在 A，用户感知"点了没反应"。
                    //
                    // 改为手动去重：跳转前检查栈顶是否已是同一 comicId（避免连点同一漫画
                    // 堆积 Detail 实例，保留原注释要解决的"退出时回到很多页前"问题）；
                    // 不同 comicId 正常入栈，相似推荐可正常跳转。
                    onOpenComic = { id ->
                        val currentRoute = navController.currentBackStackEntry?.destination?.route
                        val currentId = navController.currentBackStackEntry?.arguments?.getString("comicId")
                        // 同一漫画已在栈顶则不重复入栈（避免连点堆积 Detail 实例）
                        if (!(currentRoute == Routes.DETAIL && currentId == id)) {
                            navController.navigate(Routes.detail(id))
                        }
                    },
                )
            }
            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("comicId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                ),
            ) { entry ->
                ReaderScreen(
                    container = container,
                    comicId = entry.arguments?.getString("comicId").orEmpty(),
                    chapterId = entry.arguments?.getString("chapterId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                )
            }
        }
    }
}
