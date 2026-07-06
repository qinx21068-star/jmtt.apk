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
