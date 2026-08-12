package com.airplay.tv.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingUrlBuilderTest {
    @Test
    fun encodesRoomIdAndAddsStableTimestamp() {
        val url = PairingUrlBuilder.build(
            roomId = "room id/中文",
            timestampMillis = 1_723_456_789_012L,
        )

        assertEquals(
            "https://airplay-tv.pages.dev/join?room_id=room%20id%2F%E4%B8%AD%E6%96%87&t=1723456789012",
            url,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankRoomId() {
        PairingUrlBuilder.build(roomId = "  ", timestampMillis = 1L)
    }
}
