package com.jmreader.ui.nav

import android.net.Uri

object Routes {
    // 底部导航
    const val HOME = "home"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"

    // 详情/阅读器
    const val DETAIL = "detail/{comicId}"
    fun detail(comicId: String) = "detail/$comicId"

    const val READER = "reader/{comicId}/{chapterId}"
    fun reader(comicId: String, chapterId: String) = "reader/$comicId/$chapterId"

    const val LOGS = "logs"
    const val DOMAINS = "domains"
    const val FORUM = "forum"
    const val IMAGE_SEARCH = "image_search"

    /**
     * 带初始关键词的搜索路由（用于"点击标签搜索"等场景）。
     * q 为可选查询参数；为空时等价于普通进入搜索页。
     * 标签可能含中文/特殊字符，必须 Uri.encode，否则 Navigation 解析路由会失败。
     */
    const val SEARCH_WITH_Q = "search?q={q}"
    fun search(q: String? = null): String =
        if (q.isNullOrBlank()) SEARCH else "search?q=${Uri.encode(q)}"
}
