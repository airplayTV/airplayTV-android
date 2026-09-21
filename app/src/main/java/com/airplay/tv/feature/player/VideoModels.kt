package com.airplay.tv.feature.player

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(
    @SerializedName("code") val code: Int,
    @SerializedName("msg") val msg: String? = null,
    @SerializedName("data") val data: T? = null,
)

data class VideoSourceDto(
    @SerializedName("delivery_mode") val deliveryMode: String? = null,
    @SerializedName("proxy_url") val proxyUrl: String? = null,
    @SerializedName("media_kind") val mediaKind: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("vid") val vid: String? = null,
    @SerializedName("id") val id: String? = null,
)

data class VideoDetailDto(
    @SerializedName("media_kind") val mediaKind: String? = null,
    @SerializedName("channels") val channels: List<LiveChannel> = emptyList(),
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("thumb") val thumb: String? = null,
    @SerializedName("links") val links: List<VideoLinkDto> = emptyList(),
)

data class VideoLinkDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
)

data class LiveChannel(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("group") val group: String = "",
    @SerializedName("pid") val pid: String = "",
)

data class ResolvedVideo(
    val isLive: Boolean = false,
    val vid: String,
    val pid: String,
    val source: String,
    val url: String,
    val mediaType: ResolvedMediaType,
    val title: String = "",
    val episodeName: String = "",
    val proxyUrl: String? = null,
)

enum class ResolvedMediaType {
    HLS,
    MP4,
    UNKNOWN,
}

data class Episode(
    val id: String,
    val name: String,
)

data class VideoDetails(
    val isLive: Boolean = false,
    val channels: List<LiveChannel> = emptyList(),
    val title: String = "",
    val thumb: String = "",
    val episodes: List<Episode> = emptyList(),
)
