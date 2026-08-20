package com.airplay.tv.protocol

import java.io.Closeable
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

enum class SocketConnectionState {
    Connecting,
    Connected,
    Reconnecting,
    Closed,
}

data class ReceivedControlCommand(
    val command: ControlCommand,
    val generation: Long,
)

interface SocketClient : Closeable {
    val states: StateFlow<SocketConnectionState>
    val connectionGeneration: StateFlow<Long>
    val commands: Flow<ReceivedControlCommand>
    val playbackHistoryAcks: Flow<PlaybackHistoryAck>
        get() = emptyFlow()

    fun connect(roomId: String)

    fun sendPlaybackHistory(message: PlaybackHistoryMessage): Boolean = false

    override fun close()
}

data class ReconnectPolicy(
    val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000),
    val jitterRatio: Double = 0.2,
) {
    init {
        require(delaysMs.isNotEmpty()) { "delaysMs must not be empty" }
        require(delaysMs.all { it >= 0 }) { "delaysMs must not contain negative values" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0 and 1" }
    }

    fun delayForAttempt(attempt: Int, randomUnit: Double): Long {
        require(attempt >= 0) { "attempt must not be negative" }
        require(randomUnit in 0.0..1.0) { "randomUnit must be between 0 and 1" }

        val baseDelay = delaysMs[attempt.coerceAtMost(delaysMs.lastIndex)]
        val jitterFactor = 1.0 + ((randomUnit * 2.0 - 1.0) * jitterRatio)
        return (baseDelay * jitterFactor)
            .roundToLong()
            .coerceIn(0L, delaysMs.last())
    }
}
