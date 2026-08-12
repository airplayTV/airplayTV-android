package com.airplay.tv.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRoomIdTest {
    @Test
    fun creates32CharacterLowercaseHexRoomId() {
        val roomId = newSessionRoomId()

        assertEquals(32, roomId.length)
        assertTrue(roomId.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun createsNewRoomForEachSession() {
        assertNotEquals(newSessionRoomId(), newSessionRoomId())
    }
}
