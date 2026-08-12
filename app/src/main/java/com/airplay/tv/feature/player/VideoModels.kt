package com.airplay.tv.feature.player

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(
    @SerializedName("code") val code: Int,
    @SerializedName("msg") val msg: String? = null,
    @SerializedName("data") val data: T? = null,
)

data class VideoSourceDto(
    @SerializedName("url") val url: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("vid") val vid: String? = null,
    @SerializedName("id") val id: String? = null,
)

data class VideoDetailDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("links") val links: List<VideoLinkDto> = emptyList(),
)

data class VideoLinkDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
)

data class ResolvedVideo(
    val vid: String,
    val pid: String,
    val source: String,
    val url: String,
    val title: String = "",
    val episodeName: String = "",
)

data class Episode(
    val id: String,
    val name: String,
)

data class VideoDetails(
    val title: String = "",
    val episodes: List<Episode> = emptyList(),
)
