package com.airplay.tv.feature.player

import com.airplay.tv.session.SessionUiState
import com.airplay.tv.diagnostics.DiagnosticLogEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScreenLogicTest {
    @Test
    fun loadingLayerIsVisibleOnlyWhileLoadingWithoutError() {
        val base = SessionUiState(roomId = "room-1")

        assertTrue(shouldShowLoadingOverlay(base.copy(loading = true)))
        assertFalse(shouldShowLoadingOverlay(base.copy(loading = false)))
        assertFalse(shouldShowLoadingOverlay(base.copy(loading = true, error = "fixed")))
    }

    @Test
    fun playbackInfoDependsOnlyOnExplicitInfoVisibility() {
        val base = SessionUiState(roomId = "room-1")

        assertFalse(shouldShowPlaybackInfo(base))
        assertTrue(shouldShowPlaybackInfo(base.copy(infoVisible = true)))
        assertFalse(shouldShowPlaybackInfo(base.copy(diagnosticVisible = true)))
    }

    @Test
    fun playerConnectionAndDiagnosticsFollowInfoVisibility() {
        val base = SessionUiState(
            roomId = "room-1",
            diagnosticVisible = false,
            diagnosticLogs = listOf(DiagnosticLogEntry("CTL", "暂停播放")),
        )

        assertFalse(shouldShowPlayerConnection(base))
        assertFalse(shouldShowPlayerDiagnostics(base))
        assertTrue(shouldShowPlayerConnection(base.copy(infoVisible = true)))
        assertTrue(shouldShowPlayerDiagnostics(base.copy(infoVisible = true)))
        assertFalse(
            shouldShowPlayerDiagnostics(
                base.copy(infoVisible = true, diagnosticLogs = emptyList()),
            ),
        )
    }

    @Test
    fun sourceKeepsDiagnosticLayerVisibleWithoutRecentLogs() {
        val state = SessionUiState(
            roomId = "room-1",
            infoVisible = true,
            sourceName = "ffzy",
        )

        assertTrue(shouldShowPlayerDiagnostics(state))
        assertFalse(shouldShowPlayerDiagnostics(state.copy(infoVisible = false)))
    }

    @Test
    fun diagnosticLayerStaysAbovePlayerInfoGradient() {
        assertTrue(PLAYER_DIAGNOSTIC_LAYER_Z_INDEX > PLAYER_INFO_LAYER_Z_INDEX)
    }
}
