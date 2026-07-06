package com.jmreader.data.api

import com.jmreader.data.dto.ChapterImagesDto
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.dto.LoginRequest
import com.jmreader.data.dto.PageResultDto
import com.jmreader.data.dto.SimpleResult
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface JMApi {

    @GET("api/health")
    suspend fun health(): Response<Map<String, Any?>>

    @GET("api/search")
    suspend fun search(
        @Query("q") q: String,
        @Query("page") page: Int = 1,
        @Query("order") order: String = "latest",
        @Query("time") time: String = "all",
        @Query("category") category: String = "",
    ): Response<PageResultDto>

    @GET("api/latest")
    suspend fun latest(
        @Query("page") page: Int = 1,
        @Query("category") category: String = "",
    ): Response<PageResultDto>

    @GET("api/ranking")
    suspend fun ranking(
        @Query("time") time: String = "all",
        @Query("category") category: String = "",
        @Query("page") page: Int = 1,
    ): Response<PageResultDto>

    @GET("api/categories")
    suspend fun categories(): Response<Map<String, Any?>>

    @GET("api/comic/{id}")
    suspend fun comicDetail(@Path("id") id: String): Response<ComicDetailDto>

    @GET("api/chapter/{id}/images")
    suspend fun chapterImages(@Path("id") id: String): Response<ChapterImagesDto>

    @POST("api/login")
    suspend fun login(@Body req: LoginRequest): Response<SimpleResult>

    @POST("api/logout")
    suspend fun logout(): Response<SimpleResult>

    @GET("api/favorites")
    suspend fun favorites(@Query("page") page: Int = 1): Response<PageResultDto>

    @POST("api/favorite/{id}")
    suspend fun addFavorite(@Path("id") id: String): Response<SimpleResult>

    @DELETE("api/favorite/{id}")
    suspend fun removeFavorite(@Path("id") id: String): Response<SimpleResult>

    @POST("api/download/{id}")
    suspend fun downloadAlbum(@Path("id") id: String): Response<SimpleResult>
}
