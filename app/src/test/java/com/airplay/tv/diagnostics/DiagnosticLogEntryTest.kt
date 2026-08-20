package com.airplay.tv.diagnostics

import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.SocketConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class DiagnosticLogEntryTest {
    @Test
    fun appendKeepsOnlyLatestTwentyEntries() {
        val logs = (1..21).fold(emptyList<DiagnosticLogEntry>()) { current, index ->
            current.appendDiagnostic(DiagnosticLogEntry("RX", "event-$index"))
        }

        assertEquals(20, logs.size)
        assertEquals("event-2", logs.first().message)
        assertEquals("event-21", logs.last().message)
    }

    @Test
    fun loadVideoLogDoesNotExposeUrlOrMode() {
        val timestampMillis = 1_723_469_368_000
        val log = ControlCommand.LoadVideo(
            vid = "https://private.example/video-1?token=secret",
            pid = "episode-2&token=secret",
            source = "source-a?authorization=secret",
            mode = "secret-mode",
        ).toDiagnosticLog(timestampMillis)

        assertEquals("CTL", log.stage)
        assertEquals("收到加载视频指令", log.message)
        assertEquals(timestampMillis, log.timestampMillis)
        assertFalse(log.toString().contains("secret-mode"))
        assertFalse(log.toString().contains("http"))
        assertFalse(log.toString().contains("token"))
    }

    @Test
    fun formatsTimestampInDeviceLocalTimeUsingRequestedZone() {
        val timestampMillis = Instant.parse("2026-08-12T13:36:08Z").toEpochMilli()

        assertEquals(
            "21:36:08",
            formatDiagnosticTime(timestampMillis, TimeZone.getTimeZone("Asia/Shanghai")),
        )
    }

    @Test
    fun connectionLogsUseShortFixedText() {
        val timestampMillis = 1_723_469_368_000
        val connected = SocketConnectionState.Connected.toDiagnosticLog(timestampMillis)

        assertEquals("已连接", connected.message)
        assertEquals(timestampMillis, connected.timestampMillis)
        assertEquals("重连中", SocketConnectionState.Reconnecting.toDiagnosticLog().message)
    }

    @Test
    fun controllerPairedLogUsesFixedSafeText() {
        val log = ControlCommand.ControllerPaired.toDiagnosticLog()

        assertEquals("CTL", log.stage)
        assertEquals("手机控制器已关联", log.message)
    }

    @Test
    fun controllerUnpairedLogUsesFixedSafeText() {
        val log = ControlCommand.ControllerUnpaired.toDiagnosticLog()

        assertEquals("CTL", log.stage)
        assertEquals("手机控制器已断开", log.message)
        assertFalse(log.toString().contains("room"))
        assertFalse(log.toString().contains("socket"))
    }
}
