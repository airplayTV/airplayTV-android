package com.airplay.tv.session

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionRoomIdTest {
    @Test
    fun createsCanonicalUuid() {
        val roomId = newSessionRoomId()

        assertEquals(roomId, UUID.fromString(roomId).toString())
    }

    @Test
    fun createsNewRoomForEachSession() {
        assertNotEquals(newSessionRoomId(), newSessionRoomId())
    }
}
