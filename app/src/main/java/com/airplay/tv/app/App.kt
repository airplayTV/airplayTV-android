package com.airplay.tv.app

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
                    handleTvRemoteKey(
                        page = state.page,
                        keyCode = nativeEvent.keyCode,
                        keyAction = nativeEvent.action,
                        repeatCount = nativeEvent.repeatCount,
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

internal fun handleTvRemoteKey(
    page: SessionPage,
    keyCode: Int,
    keyAction: Int,
    repeatCount: Int,
    onRemoteControl: (RemoteControlAction) -> Unit,
): Boolean {
    if (page != SessionPage.Player) return false
    val action = mapTvRemoteKey(keyCode, keyAction, repeatCount) ?: return false
    onRemoteControl(action)
    return true
}
