package com.airplay.tv.protocol

sealed interface ControlCommand {
    data class LoadVideo(
        val vid: String,
        val pid: String,
        val source: String,
        val mode: String,
    ) : ControlCommand

    data class Volume(val direction: Int) : ControlCommand

    data object Play : ControlCommand
    data object Pause : ControlCommand
    data object Forward : ControlCommand
    data object Back : ControlCommand
    data object Mute : ControlCommand
    data object Fullscreen : ControlCommand
    data object FullscreenExit : ControlCommand
    data object ToggleInfo : ControlCommand
    data object ShowQrCode : ControlCommand
    data object Previous : ControlCommand
    data object Next : ControlCommand
    data object HistoryIgnored : ControlCommand
}
