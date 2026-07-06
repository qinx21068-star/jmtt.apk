package com.jmreader.data.api.forum

import com.jmreader.core.Logger
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 论坛数据模型
 */
data class ForumThread(
    val id: String,           // 帖子 ID（URL 末尾 .数字）
    val title: String,        // 帖子标题
    val author: String,       // 发帖人
    val replyCount: Int,      // 回复数
    val viewCount: Int,       // 浏览数
    val lastReplyTime: String,// 最后回复时间（已格式化为字符串）
    val excerpt: String,      // 预览文本（前 80 字）
    val forumName: String,    // 所属板块
)

data class ForumPost(
    val id: String,           // 楼层 ID
    val floor: Int,           // 楼层号（1=主楼）
    val author: String,       // 回复人
    val time: String,         // 回复时间
    val content: String,      // 纯文本内容（已去 HTML 标签，保留换行）
)

data class ThreadPage(
    val threadId: String,
    val title: String,
    val posts: List<ForumPost>,
    val totalPages: Int,
    val currentPage: Int,
)

/**
 * 论坛 Repository：解析禁漫网页端论坛 HTML（XenForo 2.x 结构）。
 *
 * **HTML 来源由 WebView 提供**（[ForumHtmlFetcher]）：
 * 禁漫网页端论坛受 Cloudflare 保护，纯 OkHttp 请求会被拦截返回 401/403
 * 或 JS 挑战页。WebView 能自动执行 Cloudflare 的 JS 挑战并维护
 * `cf_clearance` cookie，所以让 WebView 加载页面拿到 HTML 字符串，
 * 再交给这里的 Jsoup 解析。这样既绕过 CF，又能用原生 Compose 渲染
 * （无广告/侧边栏，JM 编号可点跳转）。
 */
object ForumRepository {

    /**
     * 解析帖子列表页 HTML。
     *
     * 禁漫论坛不是标准 XenForo，是自家 AVS 模板（/templates/frontend/airav/），
     * 选择器不固定，所以采用"多套选择器 + 链接特征兜底"策略：
     *
     * 1. XenForo 标准帖子列表：`div.structItem--thread`
     * 2. XenForo 节点列表：`div.node`
     * 3. 禁漫/AVS 模板常见帖子项：`.forum-thread, .thread-item, .topic-item, .list-item`
     * 4. **通用兜底**：扫描所有 `<a>` 链接，href 含 `/threads/` `/topic/` `/post/`
     *    的当作帖子链接，去重后取前 50 个
     *
     * 做容错：选择器命中不到返回空列表。
     */
    fun parseThreadList(html: String, baseUrl: String = "https://jmcomic-zzz.one"): List<ForumThread> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, baseUrl)
        val threads = mutableListOf<ForumThread>()
        val seenIds = mutableSetOf<String>()

        // 1. 优先：XenForo structItem--thread
        doc.select("div.structItem--thread").forEach { item ->
            try {
                val titleLink = item.selectFirst(".structItem-title a")
                    ?: item.selectFirst("a.structItem-title") ?: return@forEach
                val title = titleLink.text()?.trim() ?: return@forEach
                val href = titleLink.absUrl("href")
                val id = extractThreadId(href) ?: return@forEach
                if (!seenIds.add(id)) return@forEach

                val author = item.selectFirst(".structItem-secondary a.username")?.text()?.trim()
                    ?: item.selectFirst("[data-user-id]")?.text()?.trim() ?: "匿名"
                val time = item.selectFirst("time")?.text()?.trim()
                    ?: item.selectFirst(".structItem-latestDate")?.text()?.trim() ?: ""
                val stats = item.select(".structItem-stats dd")
                val replyCount = stats.getOrNull(0)?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                val viewCount = stats.getOrNull(1)?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                val forumName = item.selectFirst(".structItem-forumName a")?.text()?.trim() ?: ""

                threads.add(ForumThread(id, title, author, replyCount, viewCount, time, "", forumName))
            } catch (e: Exception) {
                Logger.w("Forum", "structItem 解析失败: ${e.message}")
            }
        }
        if (threads.isNotEmpty()) return threads

        // 2. 禁漫/AVS 模板常见帖子项选择器
        val avsSelectors = listOf(
            ".forum-thread", ".thread-item", ".topic-item", ".list-item",
            ".forum-topic", ".topic-row", ".thread-row", ".post-item",
            ".forum_list_item", ".forum-list-item", ".thread_list_item",
        )
        for (sel in avsSelectors) {
            val items = doc.select(sel)
            if (items.isEmpty()) continue
            Logger.d("Forum", "选择器 $sel 命中 ${items.size} 个")
            for (item in items) {
                try {
                    val titleLink = item.selectFirst("a") ?: continue
                    val title = titleLink.text()?.trim() ?: continue
                    if (title.length < 4) continue  // 过滤导航链接
                    val href = titleLink.absUrl("href")
                    if (href.isBlank()) continue
                    val id = extractThreadId(href) ?: href.hashCode().toString()
                    if (!seenIds.add(id)) continue

                    val author = item.selectFirst(".author, .username, .user, [data-user-id]")?.text()?.trim() ?: "匿名"
                    val time = item.selectFirst("time, .time, .date, .timestamp")?.text()?.trim() ?: ""
                    val replyCount = item.select(".replies, .reply-count, .comments").firstOrNull()
                        ?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0

                    threads.add(ForumThread(id, title, author, replyCount, 0, time, "", ""))
                } catch (e: Exception) {
                    // 忽略单项失败
                }
            }
            if (threads.isNotEmpty()) return threads
        }

        // 3. XenForo 节点列表 div.node（板块分类）
        doc.select("div.node").forEach { node ->
            try {
                val titleLink = node.selectFirst(".node-title a")
                    ?: node.selectFirst("h3.node-title a") ?: return@forEach
                val title = titleLink.text()?.trim() ?: return@forEach
                val href = titleLink.absUrl("href")
                if (href.isBlank()) return@forEach
                val id = Regex("""/forums/(?:[^/]*\.)?(\d+)/?""").find(href)?.groupValues?.getOrNull(1)
                    ?: href.hashCode().toString()
                if (!seenIds.add(id)) return@forEach
                threads.add(ForumThread(id, title, "板块", 0, 0, "", "", "板块"))
            } catch (e: Exception) {
                // 忽略
            }
        }
        if (threads.isNotEmpty()) return threads

        // 4. 通用兜底：扫描所有 a 链接，href 含 threads/topic/post 的当作帖子
        Logger.d("Forum", "所有选择器未命中，启用通用链接兜底")
        val linkPattern = Regex("""/(?:threads|topic|post|forum/threads)/([^/?#]+)""")
        val allLinks = doc.select("a[href]")
        for (link in allLinks) {
            try {
                val href = link.absUrl("href")
                if (href.isBlank()) continue
                val match = linkPattern.find(href) ?: continue
                val id = match.groupValues[1]
                if (id.length < 2) continue
                if (!seenIds.add(id)) continue
                val title = link.text()?.trim() ?: continue
                if (title.length < 4) continue  // 过滤导航/按钮
                if (title.length > 200) continue  // 过滤异常长文本

                threads.add(ForumThread(id, title, "匿名", 0, 0, "", "", ""))
                if (threads.size >= 50) break
            } catch (e: Exception) {
                // 忽略
            }
        }
        Logger.d("Forum", "通用兜底解析到 ${threads.size} 个链接")
        return threads
    }

    /**
     * 解析帖子详情页 HTML（主楼 + 回复）。
     * XenForo 2.x 回复结构：`article.message` > `.message-inner` > `.message-main` > `.message-body .bbWrapper`
     */
    fun parseThreadDetail(html: String, threadId: String, currentPage: Int, baseUrl: String = "https://18comic.vip"): ThreadPage {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.selectFirst(".p-title-value")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "帖子 $threadId"

        val posts = mutableListOf<ForumPost>()
        // XenForo 2.x：每条回复是一个 article.message 或 div.message
        val messages = doc.select("article.message, div.message")
        Logger.d("Forum", "解析到 ${messages.size} 条回复")

        messages.forEachIndexed { index, msg ->
            try {
                val author = msg.selectFirst(".message-name")?.text()?.trim()
                    ?: msg.selectFirst("[data-user-id]")?.text()?.trim()
                    ?: "匿名"

                val time = msg.selectFirst("time")?.text()?.trim()
                    ?: msg.selectFirst(".message-attribution-main")?.text()?.trim()
                    ?: msg.selectFirst(".u-srOnly")?.nextElementSibling()?.text()?.trim()
                    ?: ""

                val bodyEl = msg.selectFirst(".message-body .bbWrapper")
                    ?: msg.selectFirst(".message-body")
                    ?: msg.selectFirst(".bbWrapper")
                val content = bodyEl?.let { cleanContent(it) } ?: ""

                // 楼层号：XenForo 在 .message-attribution-opposite 显示楼层
                val floor = msg.attr("data-content").substringAfter("post-").toIntOrNull()
                    ?: (index + 1)

                val postId = msg.attr("data-content").substringAfter("post-")
                    .ifBlank { "${threadId}_$index" }

                posts.add(ForumPost(
                    id = postId,
                    floor = floor,
                    author = author,
                    time = time,
                    content = content,
                ))
            } catch (e: Exception) {
                Logger.w("Forum", "解析单条回复失败: ${e.message}")
            }
        }

        // 总页数：XenForo 分页 .pageNav
        val totalPages = doc.select(".pageNav-page a").lastOrNull()?.text()?.toIntOrNull()
            ?: doc.select(".pageNav").select("a").maxByOrNull { it.text().toIntOrNull() ?: 0 }?.text()?.toIntOrNull()
            ?: 1

        return ThreadPage(
            threadId = threadId,
            title = title,
            posts = posts,
            totalPages = totalPages,
            currentPage = currentPage,
        )
    }

    /**
     * 把 HTML 正文节点转成纯文本：
     * - <br> / <p> / <div> 转换行
     * - <a> 提取链接文本（如果是 JM 链接，保留原始文本让 UI 层 linkify）
     * - <blockquote>（引用）加 "> " 前缀
     * - 去掉其他标签
     */
    private fun cleanContent(el: Element): String {
        val sb = StringBuilder()
        fun walk(node: org.jsoup.nodes.Node) {
            when (node) {
                is org.jsoup.nodes.TextNode -> sb.append(node.text())
                is Element -> {
                    when (node.tagName()) {
                        "br" -> sb.append("\n")
                        "p", "div" -> {
                            node.childNodes().forEach { walk(it) }
                            sb.append("\n")
                        }
                        "blockquote" -> {
                            sb.append("> ")
                            node.childNodes().forEach { walk(it) }
                            sb.append("\n")
                        }
                        "img" -> {
                            val alt = node.attr("alt")
                            if (alt.isNotBlank()) sb.append("[$alt]")
                        }
                        "a" -> node.childNodes().forEach { walk(it) }  // 只取链接文本
                        "script", "style" -> { /* 跳过 */ }
                        else -> node.childNodes().forEach { walk(it) }
                    }
                }
            }
        }
        el.childNodes().forEach { walk(it) }
        // 合并多余空行，限制长度避免单条回复过长
        val cleaned = sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        return if (cleaned.length > 2000) cleaned.take(2000) + "…" else cleaned
    }

    /**
     * 从 URL 提取帖子 ID。
     * 禁漫 URL 格式：/forum/threads/{标题}.{id}/  或  /forum/threads/{id}/
     */
    private fun extractThreadId(url: String): String? {
        val regex = Regex("""/threads/(?:[^/]*\.)?(\d+)/?""")
        return regex.find(url)?.groupValues?.getOrNull(1)
    }
}
