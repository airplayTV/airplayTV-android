package com.airplay.tv.feature.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecordTest {
    @Test
    fun completionUsesBothBoundaries() {
        assertTrue(isPlaybackCompleted(95_000, 100_000, false))
        assertTrue(isPlaybackCompleted(70_000, 100_000, false))
        assertFalse(isPlaybackCompleted(69_999, 100_000, false))
        assertTrue(isPlaybackCompleted(1, 0, true))
    }

    @Test
    fun completionRejectsUnknownDurationWithoutNaturalEnd() {
        assertFalse(isPlaybackCompleted(1, 0, false))
        assertFalse(isPlaybackCompleted(1, -1, false))
    }

    @Test
    fun resumePositionIsZeroForCompletedAndClampedForIncomplete() {
        assertEquals(0L, record(positionMs = 80_000, completed = true).resumePositionMs())
        assertEquals(0L, record(positionMs = -1, completed = false).resumePositionMs())
        assertEquals(80_000L, record(positionMs = 80_000, completed = false).resumePositionMs())
    }

    @Test
    fun playbackRecordKeyIsStableAndNamespaced() {
        val key = playbackRecordKey("source", "vid", "pid")

        assertTrue(key.startsWith("record_"))
        assertEquals(71, key.length)
        assertEquals(key, playbackRecordKey("source", "vid", "pid"))
        assertNotEquals(key, playbackRecordKey("source", "vid", "other"))
    }

    private fun record(
        positionMs: Long,
        completed: Boolean,
    ) = PlaybackRecord(
        source = "source",
        vid = "vid",
        pid = "pid",
        title = "title",
        episodeName = "episode",
        thumb = "thumb",
        positionMs = positionMs,
        durationMs = 100_000,
        completed = completed,
        updatedAtMs = 1,
    )
}
