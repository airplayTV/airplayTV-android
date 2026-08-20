package com.airplay.tv.protocol

import com.airplay.tv.feature.history.PlaybackRecord
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

data class PlaybackHistoryMessage(
    val requestId: String,
    val group: String,
    val record: PlaybackRecord,
)

data class PlaybackHistoryAck(
    val requestId: String,
    val accepted: Boolean,
    val recipientCount: Int,
)

object PlaybackHistoryProtocol {
    fun toJson(message: PlaybackHistoryMessage): String {
        val record = message.record
        val data = JsonObject().apply {
            addProperty("request_id", message.requestId)
            addProperty("group", message.group)
            addProperty("version", PROTOCOL_VERSION)
            addProperty("source", record.source)
            addProperty("vid", record.vid)
            addProperty("pid", record.pid)
            addProperty("title", record.title)
            addProperty("episode_name", record.episodeName)
            addProperty("thumb", record.thumb)
            addProperty("position_ms", record.positionMs)
            addProperty("duration_ms", record.durationMs)
            addProperty("completed", record.completed)
        }
        return JsonObject().apply {
            addProperty("event", HISTORY_EVENT)
            add("data", data)
        }.toString()
    }

    fun parseAck(text: String): PlaybackHistoryAck? = try {
        val root = JsonParser.parseString(text)
        if (!root.isJsonObject) {
            null
        } else {
            parseAck(root.asJsonObject)
        }
    } catch (_: JsonParseException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun parseAck(root: JsonObject): PlaybackHistoryAck? {
        if (root.strictString("event") != HISTORY_ACK_EVENT) {
            return null
        }
        val dataElement = root.get("data") ?: return null
        if (!dataElement.isJsonObject) {
            return null
        }
        val data = dataElement.asJsonObject
        val requestId = data.strictString("request_id") ?: return null
        val accepted = data.strictBoolean("accepted") ?: return null
        val recipientCount = data.strictNonNegativeInt("recipient_count") ?: return null
        return PlaybackHistoryAck(requestId, accepted, recipientCount)
    }

    private fun JsonObject.strictString(name: String): String? {
        val value = get(name) ?: return null
        return value
            .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
    }

    private fun JsonObject.strictBoolean(name: String): Boolean? {
        val value = get(name) ?: return null
        return value
            .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean
    }

    private fun JsonObject.strictNonNegativeInt(name: String): Int? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            return null
        }
        val raw = value.asNumber.toString()
        if (!NON_NEGATIVE_INTEGER.matches(raw)) {
            return null
        }
        return raw.toIntOrNull()?.takeIf { it >= 0 }
    }

    private const val HISTORY_EVENT = "tv-playback-history"
    private const val HISTORY_ACK_EVENT = "tv-playback-history-ack"
    private const val PROTOCOL_VERSION = 1
    private val NON_NEGATIVE_INTEGER = Regex("\\d+")
}
