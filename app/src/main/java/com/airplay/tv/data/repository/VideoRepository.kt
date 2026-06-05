package com.airplay.tv.data.repository

import com.airplay.tv.data.api.AirPlayApi
import com.airplay.tv.data.api.ApiResponse
import com.airplay.tv.data.api.Source
import com.airplay.tv.data.api.Video
import com.airplay.tv.data.api.VideoSource
import com.airplay.tv.data.api.SearchResult

class VideoRepository(private val api: AirPlayApi) {
    private var sourceList: List<Source>? = null

    suspend fun getSourceList(): List<Source> {
        if (sourceList == null) {
            sourceList = api.getSourceList().data ?: emptyList()
        }
        return sourceList ?: emptyList()
    }

    suspend fun refreshSourceList(): List<Source> {
        sourceList = api.getSourceList().data ?: emptyList()
        return sourceList ?: emptyList()
    }

    suspend fun getVideoList(tag: String, page: Int, source: String): List<Video> {
        val resp = api.getVideoList(tag, page, source)
        return resp.data ?: emptyList()
    }

    suspend fun getVideoDetail(id: String, source: String): Video? {
        return api.getVideoDetail(id, source).data
    }

    suspend fun getVideoSource(vid: String, pid: String, source: String, m3u8p: Boolean = false): VideoSource? {
        return api.getVideoSource(vid, pid, source, m3u8p).data
    }

    suspend fun search(query: String): List<SearchResult> {
        val resp = api.searchVideo(query)
        return resp.data ?: emptyList()
    }
}
