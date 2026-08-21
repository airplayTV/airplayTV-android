package com.airplay.tv.feature.player

import com.airplay.tv.session.SessionUiState
import com.airplay.tv.diagnostics.DiagnosticLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScreenLogicTest {
    @Test
    fun loadingLayerIsVisibleOnlyWhileLoadingWithoutError() {
        val base = SessionUiState(roomId = "room-1")

        assertTrue(shouldShowLoadingOverlay(base.copy(loading = true)))
        assertTrue(shouldShowLoadingOverlay(base.copy(isBuffering = true)))
        assertFalse(shouldShowLoadingOverlay(base.copy(loading = false)))
        assertFalse(shouldShowLoadingOverlay(base.copy(loading = true, error = "fixed")))
        assertFalse(shouldShowLoadingOverlay(base.copy(isBuffering = true, error = "fixed")))
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
    fun sourceDoesNotCreateDiagnosticLayerWithoutRecentLogs() {
        val state = SessionUiState(
            roomId = "room-1",
            infoVisible = true,
            sourceName = "ffzy",
        )

        assertFalse(shouldShowPlayerDiagnostics(state))
        assertFalse(shouldShowPlayerDiagnostics(state.copy(infoVisible = false)))
    }

    @Test
    fun diagnosticLayerStaysAbovePlayerInfoGradient() {
        assertTrue(PLAYER_DIAGNOSTIC_LAYER_Z_INDEX > PLAYER_INFO_LAYER_Z_INDEX)
    }

    @Test
    fun episodePanelFollowsInfoVisibilityRatherThanFocus() {
        val state = SessionUiState(
            roomId = "room-1",
            infoVisible = true,
            episodes = listOf(Episode("p1", "Episode 1"), Episode("p2", "Episode 2")),
        )

        assertTrue(shouldShowEpisodePanel(state))
        assertFalse(shouldShowEpisodePanel(state.copy(infoVisible = false)))
        assertFalse(shouldShowEpisodePanel(state.copy(episodes = state.episodes.take(1))))
    }

    @Test
    fun episodeFocusHighlightRequiresPanelFocus() {
        val state = SessionUiState(
            roomId = "room-1",
            infoVisible = true,
            episodes = listOf(Episode("p1", "Episode 1"), Episode("p2", "Episode 2")),
            focusedEpisodeIndex = 0,
        )

        assertFalse(isEpisodeFocused(state, "p1"))
        assertTrue(isEpisodeFocused(state.copy(episodePanelFocused = true), "p1"))
        assertFalse(isEpisodeFocused(state.copy(episodePanelFocused = true), "p2"))
    }

    @Test
    fun diagnosticRowKeepsLatestSafeLogAndSourceLabelHasNoPrefix() {
        val log = DiagnosticLogEntry("SYNC", "已同步")
        val oldLog = DiagnosticLogEntry("WS", "old")
        val state = SessionUiState(
            roomId = "room-1",
            sourceName = "ffzy",
            playbackUrl = "https://secret.example/video.m3u8?token=private",
            diagnosticLogs = listOf(oldLog, log),
        )

        val content = playerOverlayContent(state)

        assertEquals("ffzy", content.sourceLabel)
        assertEquals(listOf(log), content.logs)
        assertFalse(content.toString().contains("secret.example"))
        assertFalse(content.toString().contains("token=private"))
    }
}
