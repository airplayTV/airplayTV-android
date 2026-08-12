package com.airplay.tv.feature.player

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

data class PlayerState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

interface PlayerController {
    val state: StateFlow<PlayerState>
    val player: Player

    fun load(url: String, mediaType: ResolvedMediaType)

    fun play()

    fun pause()

    fun seekBy(deltaMs: Long)

    fun adjustVolume(direction: Int)

    fun toggleMute()

    fun clear()

    fun release()
}
