package com.airplay.tv.data.api

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(
    @SerializedName("code") val code: Int,
    @SerializedName("msg") val msg: String?,
    @SerializedName("data") val data: T?
)

data class Source(
    @SerializedName("name") val name: String,
    @SerializedName("id") val id: String?,
    @SerializedName("tags") val tags: List<Tag>?
)

data class Tag(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: String
)

data class Video(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String?,
    @SerializedName("thumb") val thumb: String?,
    @SerializedName("intro") val intro: String? = null,
    @SerializedName("actors") val actors: String? = null,
    @SerializedName("links") val links: List<VideoLink>? = null
)

/**
 * 视频列表分页响应包装
 * API 实际返回结构：{ total, pages, page, limit, list }
 */
data class VideoListResponse(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("pages") val pages: Int = 0,
    @SerializedName("page") val page: Int = 1,
    @SerializedName("limit") val limit: Int = 25,
    @SerializedName("list") val list: List<Video> = emptyList()
)

data class VideoLink(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String?,
    @SerializedName("group") val group: String?,
    @SerializedName("url") val url: String?
)

data class VideoSource(
    @SerializedName("url") val url: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("source") val source: String?,
    @SerializedName("vid") val vid: String?,
    @SerializedName("id") val id: String?
)

data class SearchResult(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String?,
    @SerializedName("thumb") val thumb: String?,
    @SerializedName("source") val source: String?,
    @SerializedName("type") val type: String?
)
