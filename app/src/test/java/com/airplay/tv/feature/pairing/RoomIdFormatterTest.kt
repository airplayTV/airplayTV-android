package com.airplay.tv.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomIdFormatterTest {
    @Test
    fun keepsShortRoomIdUnchanged() {
        assertEquals("room-123", middleEllipsizeRoomId("room-123"))
    }

    @Test
    fun middleEllipsizesLongRoomIdAndPreservesBothEnds() {
        assertEquals(
            "room-123...0abcdef",
            middleEllipsizeRoomId("room-1234567890abcdef"),
        )
    }

    @Test
    fun honorsCustomCharacterLimit() {
        assertEquals(
            "abcd...nop",
            middleEllipsizeRoomId("abcdefghijklmnop", maxChars = 10),
        )
    }

    @Test
    fun supportsTheMinimumCharacterLimit() {
        assertEquals("a...f", middleEllipsizeRoomId("abcdef", maxChars = 5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCharacterLimitsBelowTheMinimum() {
        middleEllipsizeRoomId("abcdef", maxChars = 4)
    }
}
