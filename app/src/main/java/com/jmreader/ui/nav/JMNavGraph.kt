package com.jmreader.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.jmreader.ui.screen.detail.DetailScreen
import com.jmreader.ui.screen.downloads.DownloadsScreen
import com.jmreader.ui.screen.favorites.FavoritesScreen
import com.jmreader.ui.screen.home.HomeScreen
import com.jmreader.ui.screen.logs.LogsScreen
import com.jmreader.ui.screen.reader.ReaderScreen
import com.jmreader.ui.screen.search.SearchScreen
import com.jmreader.ui.screen.settings.SettingsScreen

private data class Tab(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    // 搜索 tab 的 route 用带可选参数的模板，选中态判断才能匹配 currentRoute。
    // 实际 navigate 用 Routes.search()（无参 → "search"），由 Navigation 解析为 q=null。
    Tab(Routes.SEARCH_WITH_Q, R.string.nav_search, Icons.Outlined.Search),
    Tab(Routes.FAVORITES, R.string.nav_favorites, Icons.Outlined.Bookmark),
    Tab(Routes.DOWNLOADS, R.string.nav_downloads, Icons.Outlined.Download),
    // 讨论区暂时隐藏：禁漫论坛是自家 AVS 模板（非 XenForo），结构未摸清，
    // 选择器写不出来，先下线避免误导。等拿到论坛 body HTML 结构再恢复。
    // Tab(Routes.FORUM, R.string.nav_forum, Icons.Outlined.Forum),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
)

@Composable
fun JMApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // 详情/阅读器为全屏，不显示底部栏
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                // 搜索 tab 用 Routes.search() 生成无参 "search"，
                                // 避免把 {q} 占位符当字面量传给 navigate。
                                val target = if (tab.route == Routes.SEARCH_WITH_Q) Routes.search() else tab.route
                                navController.navigate(target) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
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
                // 讨论区：WebView 加载禁漫网页端论坛（绕 CF）→ Jsoup 解析 → 原生 Compose 渲染。
                // forumUrl 用 jmcomic-zzz.one（禁漫当前实际域名，18comic.vip 已不可达）。
                // 禁漫论坛不是 XenForo，是自家 AVS 模板（/templates/frontend/airav/），
                // parseThreadList 已加多套选择器兜底。
                com.jmreader.ui.screen.forum.ForumScreen(
                    forumUrl = "https://jmcomic-zzz.one/forum/",
                    onBack = { navController.popBackStack() },
                    onOpenComic = { id -> navController.navigate(Routes.detail(id)) },
                    container = container,
                )
            }
            composable(Routes.IMAGE_SEARCH) {
                com.jmreader.ui.screen.imagesearch.ImageSearchScreen(
                    container = container,
                    navController = navController,
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
                        navController.navigate(Routes.reader(comicId, chapterId))
                    },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    // 单击标签 → 用标签词搜索；导航到搜索页并带上 q 参数
                    onSearchByTag = { tag -> navController.navigate(Routes.search(tag)) },
                    // 单击作者名 → 用作者名搜索该作者其它作品
                    onSearchByAuthor = { author -> navController.navigate(Routes.search(author)) },
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
