package com.airplay.tv.feature.player

import android.view.KeyEvent

enum class RemoteControlAction {
    Play,
    Pause,
    TogglePlayPause,
    Forward,
    Back,
    OpenEpisodes,
    EpisodeUp,
    EpisodeDown,
    SelectEpisode,
    ExitEpisodes,
}

fun mapTvRemoteKey(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    episodePanelFocused: Boolean,
): RemoteControlAction? {
    if (action != KeyEvent.ACTION_DOWN) return null

    if (episodePanelFocused) {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> RemoteControlAction.EpisodeUp
            KeyEvent.KEYCODE_DPAD_DOWN -> RemoteControlAction.EpisodeDown
            KeyEvent.KEYCODE_DPAD_LEFT -> RemoteControlAction.ExitEpisodes.takeIf { repeatCount == 0 }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> RemoteControlAction.SelectEpisode.takeIf { repeatCount == 0 }
            else -> mapPlaybackKey(keyCode, repeatCount)
        }
    }

    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
        return RemoteControlAction.OpenEpisodes.takeIf { repeatCount == 0 }
    }

    return mapPlaybackKey(keyCode, repeatCount)
}

private fun mapPlaybackKey(keyCode: Int, repeatCount: Int): RemoteControlAction? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> RemoteControlAction.Play.takeIf { repeatCount == 0 }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteControlAction.Pause.takeIf { repeatCount == 0 }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        -> RemoteControlAction.TogglePlayPause.takeIf { repeatCount == 0 }
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        -> RemoteControlAction.Forward
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_DPAD_LEFT,
        -> RemoteControlAction.Back
        else -> null
}
