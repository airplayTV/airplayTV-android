package com.airplay.tv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocketMessageParserTest {
    private val parser = SocketMessageParser()

    @Test
    fun parsesLoadVideoWithOptionalMode() {
        val withMode = """{"event":"/ctl_load_Video","group":"room-1","vid":"v1","pid":"p2","source":"s","mode":"m"}"""
        val withoutMode = """{"event":"/ctl_load_Video","group":"room-1","vid":"v1","pid":"p2","source":"s"}"""
        val nullMode = """{"event":"/ctl_load_Video","group":"room-1","vid":"v1","pid":"p2","source":"s","mode":null}"""

        assertEquals(ControlCommand.LoadVideo("v1", "p2", "s", "m"), parser.parse(withMode, "room-1"))
        assertEquals(ControlCommand.LoadVideo("v1", "p2", "s", ""), parser.parse(withoutMode, "room-1"))
        assertEquals(ControlCommand.LoadVideo("v1", "p2", "s", ""), parser.parse(nullMode, "room-1"))
    }

    @Test
    fun mapsEachSupportedControlEvent() {
        val expected = mapOf(
            "/ctl_mute" to ControlCommand.Mute,
            "/ctl_fullscreen" to ControlCommand.Fullscreen,
            "/ctl_fullscreen_exit" to ControlCommand.FullscreenExit,
            "/ctl_qrCode" to ControlCommand.ShowQrCode,
            "/ctl_info" to ControlCommand.ToggleInfo,
            "/ctl_back" to ControlCommand.Back,
            "/ctl_play" to ControlCommand.Play,
            "/ctl_pause" to ControlCommand.Pause,
            "/ctl_forward" to ControlCommand.Forward,
            "/ctl_history" to ControlCommand.HistoryIgnored,
            "/ctl_prev" to ControlCommand.Previous,
            "/ctl_next" to ControlCommand.Next,
        )

        expected.forEach { (event, command) ->
            assertEquals(command, parser.parse("""{"event":"$event","group":"room-1"}""", "room-1"))
        }
        assertEquals(
            ControlCommand.Volume(1),
            parser.parse("""{"event":"/ctl_volume","group":"room-1","value":1}""", "room-1"),
        )
    }

    @Test
    fun parsesPairCommandOnlyForCurrentRoom() {
        assertEquals(
            ControlCommand.ControllerPaired,
            parser.parse("""{"event":"/ctl_pair","group":"room-1"}""", "room-1"),
        )
        assertNull(parser.parse("""{"event":"/ctl_pair","group":"other"}""", "room-1"))
    }

    @Test
    fun parsesUnpairCommandOnlyForCurrentRoom() {
        assertEquals(
            ControlCommand.ControllerUnpaired,
            parser.parse("""{"event":"/ctl_unpair","group":"room-1"}""", "room-1"),
        )
        assertNull(parser.parse("""{"event":"/ctl_unpair","group":"other"}""", "room-1"))
        assertNull(parser.parse("""{"event":"/ctl_unpair"}""", "room-1"))
    }

    @Test
    fun rejectsMessagesOutsideCurrentRoomOrWithInvalidPayload() {
        val oversized = "x".repeat(513)

        assertNull(parser.parse("""{"event":"/ctl_play","group":"other"}""", "room-1"))
        assertNull(parser.parse("""{"event":"/ctl_load_Video","group":"room-1","vid":"$oversized","pid":"p","source":"s"}""", "room-1"))
        assertNull(parser.parse("""{"event":"/ctl_load_Video","group":"room-1","vid":"","pid":"p","source":"s"}""", "room-1"))
        assertNull(parser.parse("""{"event":"/ctl_load_Video","group":"room-1","vid":"v","pid":"p","source":"s","mode":"$oversized"}""", "room-1"))
        assertNull(parser.parse("""{"event":"/ctl_load_Video","group":"room-1","vid":"v","pid":"p","source":"s","mode":1}""", "room-1"))
        assertNull(parser.parse("""{"event":"/ctl_volume","group":"room-1","value":0}""", "room-1"))
        assertNull(parser.parse("""{"event":"/ctl_volume","group":"room-1","value":"1"}""", "room-1"))
        assertNull(parser.parse("""{"event":"/ctl_delete","group":"room-1"}""", "room-1"))
        assertNull(parser.parse("""{"event":"/ctl_play","group":"room-1""", "room-1"))
    }
}
