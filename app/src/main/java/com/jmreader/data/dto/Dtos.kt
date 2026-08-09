package com.jmreader.data.dto

import androidx.compose.runtime.Immutable
import com.squareup.moshi.JsonClass

/**
 * 列表项 DTO。
 *
 * **关键性能注解**：[Immutable] 告诉 Compose 编译器此类型永不变化，
 * 让 LazyGrid 的 items 在滚动时能跳过 ComicCard 重组。
 *
 * 不加此注解时，因 tags: List<String> 是接口类型，编译器认为不稳定，
 * 滚动期间每个 card 都会反复重组 → rememberAsyncImagePainter 反复评估 → 卡顿。
 * （骁龙8 Gen3 实测从 20fps 恢复到 90+fps）
 */
@Immutable
@JsonClass(generateAdapter = true)
data class ComicBriefDto(
    val id: String = "",
    val name: String = "",
    val author: String? = null,
    val tags: List<String> = emptyList(),
    val cover: String? = null,
    val likes: String? = null,
    val views: String? = null,
    val page_count: Int? = null,
    /** 发布时间（已格式化为可读字符串，如 "2024-03-15"；解析失败为 null）。
     *  列表卡片旁展示，方便用户判断本子新旧。来自列表接口 addtime 字段或异步补全。 */
    val publishTime: String? = null,
)

@JsonClass(generateAdapter = true)
data class ChapterDto(
    val id: String = "",
    val title: String = "",
    val sort: Int = 0,
)

@JsonClass(generateAdapter = true)
data class PageResultDto(
    val page: Int = 1,
    val total: Int? = null,
    val items: List<ComicBriefDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ComicDetailDto(
    val id: String = "",
    val name: String = "",
    val author: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val cover: String? = null,
    val likes: String? = null,
    val views: String? = null,
    val chapters: List<ChapterDto> = emptyList(),
    /** 发布时间（已格式化为可读字符串，如 "2024-03-15"；解析失败为 null）。 */
    val publishTime: String? = null,
    /** 角色（actors）列表，来自 /album 接口的 actors 字段。详情页像官方客户端那样显示。 */
    val actors: List<String> = emptyList(),
    /** 作品（works）列表，即角色所属的登场作品，如「原神」。与 actors 一起显示。 */
    val works: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ChapterImagesDto(
    val id: String = "",
    val title: String? = null,
    val scramble_id: String? = null,
    val images: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val username: String,
    val password: String,
)

@JsonClass(generateAdapter = true)
data class SimpleResult(
    val ok: Boolean = false,
    val msg: String? = null,
)

/**
 * v27.5 #15：收藏夹分组。
 * - id：稳定唯一标识（UUID），方便重命名后 item.folderId 不变
 * - name：分组名（用户可改）
 * - createdAt：用于按创建时间排序展示
 */
@Immutable
@JsonClass(generateAdapter = true)
data class FavoriteFolder(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * v27.5 #15：收藏条目（含分组归属）。
 * folderId 为 null 表示「未分组」。
 *
 * 注意：[FavoriteEntry] 自身不用 @Immutable，因为 [ComicBriefDto] 已经 @Immutable，
 * Compose 编译器能自动推断此 data class 稳定。
 */
@JsonClass(generateAdapter = true)
data class FavoriteEntry(
    val comic: ComicBriefDto,
    val folderId: String? = null,
)

/**
 * v27.5 #15：[FavoritesStore] 文件 v2 顶层结构。
 * Moshi 直接序列化/反序列化整个文件，避免手动拼 JSONObject。
 */
@JsonClass(generateAdapter = true)
data class FavoriteStoreData(
    val version: Int = 2,
    val folders: List<FavoriteFolder> = emptyList(),
    val entries: List<FavoriteEntry> = emptyList(),
)

/**
 * 禁漫评论（来自 /forum JSON API，移植自 jasmine Comment 实体）。
 *
 * v27.9：评论区/讨论区从 HTML 抓取（JmWebFetcher + jm365.work 重定向，不稳定）
 * 改为 JSON API（同 reqApi 通道，token 鉴权 + AES 解密，已验证稳定）。
 *
 * 字段对应禁漫 /forum 接口返回的 Comment 结构：
 * - aid: 所属本子 ID（全局评论流时可能为 null）
 * - cid: 评论 ID
 * - uid: 评论人用户 ID
 * - nickname: 评论人昵称
 * - likes: 点赞数
 * - addtime: 评论时间（已格式化字符串）
 * - content: 评论内容（已清洗为纯文本，去 HTML 标签，emoji 转为 [alt]）
 * - photo: 评论人头像 URL
 * - name: 所属本子标题（全局评论流点击可跳转该本子）
 * - level: 评论人等级
 * - replys: 嵌套回复（树形结构）
 *
 * [Immutable] 让 Compose 跳过评论卡片重组（与 ComicBriefDto 同理）。
 */
@Immutable
@JsonClass(generateAdapter = true)
data class JmCommentDto(
    val aid: String? = null,
    val cid: String = "",
    val uid: String = "",
    val nickname: String = "",
    val likes: Int = 0,
    val addtime: String = "",
    val content: String = "",
    val photo: String? = null,
    val name: String = "",
    val level: Int = 0,
    val replys: List<JmCommentDto> = emptyList(),
)

/** /forum 接口分页结果：list 为评论列表，total 为评论总数。 */
@Immutable
@JsonClass(generateAdapter = true)
data class JmCommentPageDto(
    val list: List<JmCommentDto> = emptyList(),
    val total: Int = 0,
)
