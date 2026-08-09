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
    // 关键修复（Bug 27）：comicId/chapterId 统一 Uri.encode，避免含特殊字符
    // （如从论坛/外部链接带入的 ID 含 / ? # 等）破坏路由解析。
    // Navigation 库读取 navArgument(StringType) 时会自动 decode，调用方无需手动 decode。
    const val DETAIL = "detail/{comicId}"
    fun detail(comicId: String) = "detail/${Uri.encode(comicId)}"

    const val READER = "reader/{comicId}/{chapterId}"
    fun reader(comicId: String, chapterId: String) =
        "reader/${Uri.encode(comicId)}/${Uri.encode(chapterId)}"

    const val LOGS = "logs"
    const val DOMAINS = "domains"
    const val FORUM = "forum"
    const val IMAGE_SEARCH = "image_search"
    const val COMMENTS = "comments/{comicId}"
    fun comments(comicId: String) = "comments/${Uri.encode(comicId)}"

    // v27.5 #13：作者主页（作者名作为路径段，Uri.encode 处理特殊字符）
    const val AUTHOR = "author/{name}"
    fun author(name: String) = "author/${Uri.encode(name)}"

    /**
     * 带初始关键词的搜索路由（用于"点击标签搜索"等场景）。
     * q 为可选查询参数；为空时等价于普通进入搜索页。
     * 标签可能含中文/特殊字符，必须 Uri.encode，否则 Navigation 解析路由会失败。
     */
    const val SEARCH_WITH_Q = "search?q={q}"
    fun search(q: String? = null): String =
        if (q.isNullOrBlank()) SEARCH else "search?q=${Uri.encode(q)}"
}
