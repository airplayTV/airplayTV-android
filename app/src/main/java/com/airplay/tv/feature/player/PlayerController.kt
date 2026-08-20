package com.airplay.tv.feature.player

import androidx.media3.common.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class PlayerState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

sealed interface PlaybackEvent {
    data object Ended : PlaybackEvent
    data object Error : PlaybackEvent
}

interface PlayerController {
    val state: StateFlow<PlayerState>
    val events: Flow<PlaybackEvent>
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
