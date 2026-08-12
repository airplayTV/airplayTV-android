package com.airplay.tv.session

import com.airplay.tv.protocol.SocketConnectionState

enum class SessionPage {
    Pairing,
    Player,
}

data class SessionUiState(
    val roomId: String,
    val page: SessionPage = SessionPage.Pairing,
    val connection: SocketConnectionState = SocketConnectionState.Connecting,
    val loading: Boolean = false,
    val title: String = "",
    val episodeName: String = "",
    val infoVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)
