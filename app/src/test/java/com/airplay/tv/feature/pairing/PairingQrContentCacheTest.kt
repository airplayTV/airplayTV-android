package com.airplay.tv.feature.pairing

import com.airplay.tv.core.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairingQrContentCacheTest {
    @Test
    fun keepsContentStableForSameRoomAndRefreshesForDifferentRoom() {
        var timestamp = 100L
        val cache = PairingQrContentCache { timestamp++ }

        val initial = cache.contentFor("room-1")
        val sameRoom = cache.contentFor("room-1")
        val differentRoom = cache.contentFor("room-2")

        assertEquals(initial, sameRoom)
        assertEquals("${AppConfig.H5_BASE_URL}/join?room_id=room-1&t=100", initial)
        assertEquals("${AppConfig.H5_BASE_URL}/join?room_id=room-2&t=101", differentRoom)
        assertNotEquals(initial, differentRoom)
    }
}
