package com.airplay.tv.app

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.media3.common.Player
import com.airplay.tv.core.ui.AirPlayTheme
import com.airplay.tv.feature.player.RemoteControlAction
import com.airplay.tv.feature.player.mapTvRemoteKey
import com.airplay.tv.session.SessionPage
import com.airplay.tv.session.SessionUiState

@Composable
fun App(
    state: SessionUiState,
    player: Player,
    onBack: () -> Unit,
    onRemoteControl: (RemoteControlAction) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val remoteKeyHandler = remember { TvRemoteKeyHandler() }
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    AirPlayTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    val nativeEvent = event.nativeKeyEvent
                    remoteKeyHandler.handle(
                        page = state.page,
                        keyCode = nativeEvent.keyCode,
                        keyAction = nativeEvent.action,
                        repeatCount = nativeEvent.repeatCount,
                        episodePanelFocused = state.episodePanelFocused,
                        onRemoteControl = onRemoteControl,
                    )
                }
                .focusable()
                .testTag("app-root"),
        ) {
            AppNavigation(
                state = state,
                player = player,
                onBack = onBack,
            )
        }
    }
}

internal class TvRemoteKeyHandler {
    private var episodeActionKeyCode: Int? = null

    fun handle(
        page: SessionPage,
        keyCode: Int,
        keyAction: Int,
        repeatCount: Int,
        episodePanelFocused: Boolean,
        onRemoteControl: (RemoteControlAction) -> Unit,
    ): Boolean {
        if (episodeActionKeyCode == keyCode) {
            if (keyAction == KeyEvent.ACTION_UP) {
                episodeActionKeyCode = null
            }
            return true
        }
        if (page != SessionPage.Player) return false

        val action = mapTvRemoteKey(keyCode, keyAction, repeatCount, episodePanelFocused) ?: return false
        if (action == RemoteControlAction.ExitEpisodes) {
            episodeActionKeyCode = keyCode
        }
        onRemoteControl(action)
        return true
    }
}

internal fun handleTvRemoteKey(
    page: SessionPage,
    keyCode: Int,
    keyAction: Int,
    repeatCount: Int,
    episodePanelFocused: Boolean,
    onRemoteControl: (RemoteControlAction) -> Unit,
): Boolean {
    if (page != SessionPage.Player) return false
    val action = mapTvRemoteKey(keyCode, keyAction, repeatCount, episodePanelFocused) ?: return false
    onRemoteControl(action)
    return true
}
