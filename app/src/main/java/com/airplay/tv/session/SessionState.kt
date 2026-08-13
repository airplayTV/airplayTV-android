package com.airplay.tv.session

import com.airplay.tv.diagnostics.DiagnosticLogEntry
import com.airplay.tv.protocol.SocketConnectionState

enum class SessionPage {
    Pairing,
    Player,
}

data class SessionUiState(
    val roomId: String,
    val page: SessionPage = SessionPage.Pairing,
    val connection: SocketConnectionState = SocketConnectionState.Connecting,
    val controllerConnected: Boolean = false,
    val loading: Boolean = false,
    val title: String = "",
    val episodeName: String = "",
    val infoVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    val playbackUrl: String = "",
    val qrVisible: Boolean = false,
    val diagnosticLogs: List<DiagnosticLogEntry> = emptyList(),
    val diagnosticVisible: Boolean = false,
)
