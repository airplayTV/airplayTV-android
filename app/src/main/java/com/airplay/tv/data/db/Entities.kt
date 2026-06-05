package com.airplay.tv.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "history", indices = [Index(value = ["source", "vid", "pid"], unique = true)])
data class HistoryEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Int = 0,
    val source: String = "",
    val vid: String = "",
    val pid: String = "",
    val name: String = "",
    val pname: String = "",
    val thumb: String = "",
    val url: String = "",
    val type: String = "",
    val duration: Long = 0,
    val lastTime: Long = 0,
    val updatedAt: Long = 0
)

@Entity(tableName = "timeline", indices = [Index(value = ["source", "vid", "pid"], unique = true)])
data class TimelineEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Int = 0,
    val source: String = "",
    val vid: String = "",
    val pid: String = "",
    val duration: Long = 0,
    val lastTime: Long = 0,
    val updatedAt: Long = 0
)
