package com.airplay.tv.diagnostics

import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.SocketConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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
        val log = ControlCommand.LoadVideo(
            vid = "video-1",
            pid = "episode-2",
            source = "source-a",
            mode = "secret-mode",
        ).toDiagnosticLog()

        assertEquals("CTL", log.stage)
        assertEquals("收到加载视频指令", log.message)
        assertFalse(log.toString().contains("secret-mode"))
        assertFalse(log.toString().contains("http"))
    }

    @Test
    fun connectionLogsUseShortFixedText() {
        assertEquals("已连接", SocketConnectionState.Connected.toDiagnosticLog().message)
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
