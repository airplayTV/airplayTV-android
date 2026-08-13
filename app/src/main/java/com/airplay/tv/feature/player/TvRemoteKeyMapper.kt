package com.airplay.tv.feature.player

import android.view.KeyEvent

enum class RemoteControlAction {
    Play,
    Pause,
    TogglePlayPause,
    Forward,
    Back,
}

fun mapTvRemoteKey(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
): RemoteControlAction? {
    if (action != KeyEvent.ACTION_DOWN) return null

    return when (keyCode) {
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
}
