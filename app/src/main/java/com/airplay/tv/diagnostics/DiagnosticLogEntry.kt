package com.airplay.tv.diagnostics

import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.SocketConnectionState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class DiagnosticLogEntry(
    val stage: String,
    val message: String,
    val timestampMillis: Long = System.currentTimeMillis(),
)

internal const val MAX_DIAGNOSTIC_LOGS = 20

fun formatDiagnosticTime(
    timestampMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).run {
    this.timeZone = timeZone
    format(timestampMillis)
}

fun List<DiagnosticLogEntry>.appendDiagnostic(
    entry: DiagnosticLogEntry,
): List<DiagnosticLogEntry> = (this + entry).takeLast(MAX_DIAGNOSTIC_LOGS)

fun SocketConnectionState.toDiagnosticLog(
    timestampMillis: Long = System.currentTimeMillis(),
): DiagnosticLogEntry = when (this) {
    SocketConnectionState.Connecting -> DiagnosticLogEntry("WS", "连接中", timestampMillis)
    SocketConnectionState.Connected -> DiagnosticLogEntry("WS", "已连接", timestampMillis)
    SocketConnectionState.Reconnecting -> DiagnosticLogEntry("WS", "重连中", timestampMillis)
    SocketConnectionState.Closed -> DiagnosticLogEntry("WS", "已断开", timestampMillis)
}

fun ControlCommand.toDiagnosticLog(
    timestampMillis: Long = System.currentTimeMillis(),
): DiagnosticLogEntry = DiagnosticLogEntry(
    stage = "CTL",
    message = when (this) {
        is ControlCommand.LoadVideo -> "收到加载视频指令"
        is ControlCommand.Volume -> if (direction > 0) "调高音量" else "调低音量"
        ControlCommand.Play -> "继续播放"
        ControlCommand.Pause -> "暂停播放"
        ControlCommand.Forward -> "快进 15 秒"
        ControlCommand.Back -> "快退 15 秒"
        ControlCommand.Mute -> "切换静音"
        ControlCommand.Fullscreen -> "隐藏播放信息"
        ControlCommand.FullscreenExit -> "显示播放信息"
        ControlCommand.ToggleInfo -> "切换播放信息"
        ControlCommand.ShowQrCode -> "显示二维码"
        ControlCommand.Previous -> "上一集"
        ControlCommand.Next -> "下一集"
        ControlCommand.ControllerPaired -> "手机控制器已关联"
        ControlCommand.ControllerUnpaired -> "手机控制器已断开"
        ControlCommand.HistoryIgnored -> "收到历史指令"
    },
    timestampMillis = timestampMillis,
)
