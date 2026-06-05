package com.airplay.tv.data.api

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Body

interface AirPlayApi {
    @GET("/api/video/provider")
    suspend fun getSourceList(@Query("_source") source: String = ""): ApiResponse<List<Source>>

    @GET("/api/video/list")
    suspend fun getVideoList(
        @Query("tag") tag: String,
        @Query("page") page: Int,
        @Query("_source") source: String
    ): ApiResponse<List<Video>>

    @GET("/api/video/detail")
    suspend fun getVideoDetail(
        @Query("id") id: String,
        @Query("_source") source: String
    ): ApiResponse<Video>

    @GET("/api/video/source")
    suspend fun getVideoSource(
        @Query("vid") vid: String,
        @Query("pid") pid: String,
        @Query("_source") source: String,
        @Query("_m3u8p") m3u8p: Boolean = false
    ): ApiResponse<VideoSource>

    @GET("/api/video/search")
    suspend fun searchVideo(
        @Query(value = "query", encoded = true) query: String
    ): ApiResponse<List<SearchResult>>

    @POST("/api/collect/add")
    suspend fun addCollect(@Body body: Map<String, String>): ApiResponse<Any>

    @GET("/api/collect/list")
    suspend fun getCollectList(): ApiResponse<List<Any>>

    @POST("/api/collect/remove")
    suspend fun removeCollect(@Body body: Map<String, String>): ApiResponse<Any>
}
