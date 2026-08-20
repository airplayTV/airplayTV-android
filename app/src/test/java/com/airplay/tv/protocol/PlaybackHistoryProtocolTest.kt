package com.airplay.tv.protocol

import com.airplay.tv.feature.history.PlaybackRecord
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryProtocolTest {
    @Test
    fun messageContainsOnlyAllowlistedFields() {
        val json = PlaybackHistoryProtocol.toJson(message())
        val root = JsonParser.parseString(json).asJsonObject
        val data = root.getAsJsonObject("data")

        assertEquals(setOf("event", "data"), root.keySet())
        assertEquals("tv-playback-history", root.get("event").asString)
        assertEquals(
            setOf(
                "request_id",
                "group",
                "version",
                "source",
                "vid",
                "pid",
                "title",
                "episode_name",
                "thumb",
                "position_ms",
                "duration_ms",
                "completed",
            ),
            data.keySet(),
        )
        assertEquals("request-1", data.get("request_id").asString)
        assertEquals("room-1", data.get("group").asString)
        assertEquals(1, data.get("version").asInt)
        assertEquals("source-1", data.get("source").asString)
        assertEquals("vid-1", data.get("vid").asString)
        assertEquals("pid-1", data.get("pid").asString)
        assertEquals("Title", data.get("title").asString)
        assertEquals("Episode 1", data.get("episode_name").asString)
        assertEquals("https://images.example/thumb.jpg", data.get("thumb").asString)
        assertEquals(12_345L, data.get("position_ms").asLong)
        assertEquals(60_000L, data.get("duration_ms").asLong)
        assertFalse(data.get("completed").asBoolean)
        listOf("playbackUrl", "mode", "header", "sourceSecret", "updatedAtMs").forEach {
            assertFalse(json.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun parsesPlaybackHistoryAck() {
        val ack = PlaybackHistoryProtocol.parseAck(
            """{"event":"tv-playback-history-ack","data":{"request_id":"request-1","accepted":true,"recipient_count":2}}""",
        )

        assertEquals(PlaybackHistoryAck("request-1", accepted = true, recipientCount = 2), ack)
    }

    @Test
    fun rejectsNonAckAndMalformedAckMessages() {
        assertNull(
            PlaybackHistoryProtocol.parseAck(
                """{"event":"/ctl_play","group":"room-1"}""",
            ),
        )
        assertNull(
            PlaybackHistoryProtocol.parseAck(
                """{"event":"tv-playback-history-ack","data":{"request_id":1,"accepted":true,"recipient_count":2}}""",
            ),
        )
        assertNull(
            PlaybackHistoryProtocol.parseAck(
                """{"event":"tv-playback-history-ack","data":{"request_id":"request-1","accepted":"true","recipient_count":2}}""",
            ),
        )
        assertNull(
            PlaybackHistoryProtocol.parseAck(
                """{"event":"tv-playback-history-ack","data":{"request_id":"request-1","accepted":true,"recipient_count":-1}}""",
            ),
        )
        assertNull(PlaybackHistoryProtocol.parseAck("not-json"))
    }

    private fun message(): PlaybackHistoryMessage = PlaybackHistoryMessage(
        requestId = "request-1",
        group = "room-1",
        record = PlaybackRecord(
            source = "source-1",
            vid = "vid-1",
            pid = "pid-1",
            title = "Title",
            episodeName = "Episode 1",
            thumb = "https://images.example/thumb.jpg",
            positionMs = 12_345L,
            durationMs = 60_000L,
            completed = false,
            updatedAtMs = 1_787_190_000_000L,
        ),
    )
}
