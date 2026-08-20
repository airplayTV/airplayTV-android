package com.airplay.tv.session

import com.airplay.tv.feature.player.FakePlayerController
import com.airplay.tv.feature.player.PlayerController
import com.airplay.tv.protocol.PlaybackHistoryAck
import com.airplay.tv.protocol.PlaybackHistoryMessage
import com.airplay.tv.protocol.ReceivedControlCommand
import com.airplay.tv.protocol.SocketClient
import com.airplay.tv.protocol.SocketConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionConstructionCleanupTest {
    @Test
    fun preservesOriginalFailureAndAttemptsBothCleanupsInOrder() {
        val calls = mutableListOf<String>()
        val original = IllegalStateException("construction")
        val closeFailure = IllegalArgumentException("close")
        val releaseFailure = IllegalStateException("release")
        val socket = ThrowingSocketClient(calls, closeFailure)
        val player = ThrowingPlayerController(calls, releaseFailure)

        val thrown = assertThrows(IllegalStateException::class.java) {
            cleanupAfterConstructionFailure(socket, player, original)
        }

        assertSame(original, thrown)
        assertEquals(listOf("socket", "player"), calls)
        assertArrayEquals(arrayOf(closeFailure, releaseFailure), thrown.suppressed)
    }

    @Test
    fun releasesPlayerWhenSocketWasNotCreated() {
        val calls = mutableListOf<String>()
        val original = IllegalStateException("socket construction")
        val releaseFailure = IllegalArgumentException("release")
        val player = ThrowingPlayerController(calls, releaseFailure)

        val thrown = assertThrows(IllegalStateException::class.java) {
            cleanupAfterConstructionFailure(null, player, original)
        }

        assertSame(original, thrown)
        assertEquals(listOf("player"), calls)
        assertArrayEquals(arrayOf(releaseFailure), thrown.suppressed)
    }

    private class ThrowingSocketClient(
        private val calls: MutableList<String>,
        private val closeFailure: Throwable,
    ) : SocketClient {
        override val states: StateFlow<SocketConnectionState> =
            MutableStateFlow(SocketConnectionState.Closed)
        override val connectionGeneration: StateFlow<Long> = MutableStateFlow(0L)
        override val commands: Flow<ReceivedControlCommand> = emptyFlow()
        override val playbackHistoryAcks: Flow<PlaybackHistoryAck> = emptyFlow()

        override fun connect(roomId: String) = Unit

        override fun sendPlaybackHistory(message: PlaybackHistoryMessage): Boolean = false

        override fun close() {
            calls += "socket"
            throw closeFailure
        }
    }

    private class ThrowingPlayerController(
        private val calls: MutableList<String>,
        private val releaseFailure: Throwable,
    ) : PlayerController by FakePlayerController() {
        override fun release() {
            calls += "player"
            throw releaseFailure
        }
    }
}
