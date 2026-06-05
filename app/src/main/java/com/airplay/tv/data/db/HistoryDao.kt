package com.airplay.tv.data.db

import androidx.room.*

@Dao
interface HistoryDao {
    @Upsert
    suspend fun upsert(history: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun list(limit: Int = 20, offset: Int = 0): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE source = :source AND vid = :vid AND pid = :pid LIMIT 1")
    suspend fun findBySourceAndVid(source: String, vid: String, pid: String): HistoryEntity?

    @Query("DELETE FROM history WHERE source = :source AND vid = :vid")
    suspend fun deleteByVid(source: String, vid: String)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}
