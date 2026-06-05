package com.airplay.tv.data.db

import androidx.room.*

@Dao
interface TimelineDao {
    @Upsert
    suspend fun upsert(timeline: TimelineEntity)

    @Query("SELECT * FROM timeline WHERE source = :source AND vid = :vid AND pid = :pid LIMIT 1")
    suspend fun findBySourceAndVid(source: String, vid: String, pid: String): TimelineEntity?

    @Query("DELETE FROM timeline WHERE source = :source AND vid = :vid")
    suspend fun deleteByVid(source: String, vid: String)

    @Query("DELETE FROM timeline")
    suspend fun clearAll()
}
