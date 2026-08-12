package com.airplay.tv.app

import androidx.compose.runtime.Composable
import androidx.media3.common.Player
import com.airplay.tv.core.ui.AirPlayTheme
import com.airplay.tv.session.SessionUiState

@Composable
fun App(
    state: SessionUiState,
    player: Player,
    onBack: () -> Unit,
) {
    AirPlayTheme {
        AppNavigation(
            state = state,
            player = player,
            onBack = onBack,
        )
    }
}
