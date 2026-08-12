package com.airplay.tv.feature.player

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface VideoApi {
    @GET("api/video/source")
    suspend fun source(
        @Query("vid") vid: String,
        @Query("pid") pid: String,
        @Query("_source") source: String,
        @Query("_m3u8p") proxy: Boolean = false,
        @Header("X-Source-Mode") mode: String,
        @Header("X-Client") client: String = CLIENT_NAME,
    ): ApiResponse<VideoSourceDto>

    @GET("api/video/detail")
    suspend fun detail(
        @Query("id") vid: String,
        @Query("_source") source: String,
        @Header("X-Source-Mode") mode: String,
        @Header("X-Client") client: String = CLIENT_NAME,
    ): ApiResponse<VideoDetailDto>

    private companion object {
        const val CLIENT_NAME = "airplayTV-android"
    }
}
