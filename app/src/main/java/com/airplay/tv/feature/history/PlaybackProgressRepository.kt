package com.airplay.tv.feature.history

interface PlaybackProgressRepository : AutoCloseable {
    suspend fun find(source: String, vid: String, pid: String): PlaybackRecord?

    suspend fun latest(): PlaybackRecord?

    suspend fun save(record: PlaybackRecord)

    fun enqueueSave(record: PlaybackRecord)

    suspend fun drain()
}
