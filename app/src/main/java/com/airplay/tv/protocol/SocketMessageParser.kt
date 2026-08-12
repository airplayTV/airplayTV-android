package com.airplay.tv.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

class SocketMessageParser {
    fun parse(text: String, roomId: String): ControlCommand? =
        try {
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) {
                null
            } else {
                parseEnvelope(root.asJsonObject)?.toCommand(roomId)
            }
        } catch (_: JsonParseException) {
            null
        } catch (_: IllegalStateException) {
            null
        }

    private fun parseEnvelope(root: JsonObject): SocketEnvelope? = SocketEnvelope(
        event = root.optionalString("event"),
        group = root.optionalString("group"),
        vid = root.optionalString("vid"),
        pid = root.optionalString("pid"),
        source = root.optionalString("source"),
        mode = root.optionalMode(),
        value = root.optionalInt("value"),
    )

    private fun SocketEnvelope.toCommand(roomId: String): ControlCommand? {
        if (group != roomId) {
            return null
        }

        return when (event) {
            "/ctl_load_Video" -> loadVideo()
            "/ctl_volume" -> value?.takeIf { it == -1 || it == 1 }?.let(ControlCommand::Volume)
            "/ctl_mute" -> ControlCommand.Mute
            "/ctl_fullscreen" -> ControlCommand.Fullscreen
            "/ctl_fullscreen_exit" -> ControlCommand.FullscreenExit
            "/ctl_qrCode" -> ControlCommand.ShowQrCode
            "/ctl_info" -> ControlCommand.ToggleInfo
            "/ctl_back" -> ControlCommand.Back
            "/ctl_play" -> ControlCommand.Play
            "/ctl_pause" -> ControlCommand.Pause
            "/ctl_forward" -> ControlCommand.Forward
            "/ctl_history" -> ControlCommand.HistoryIgnored
            "/ctl_prev" -> ControlCommand.Previous
            "/ctl_next" -> ControlCommand.Next
            else -> null
        }
    }

    private fun SocketEnvelope.loadVideo(): ControlCommand.LoadVideo? {
        val validVid = vid?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD_LENGTH } ?: return null
        val validPid = pid?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD_LENGTH } ?: return null
        val validSource = source?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD_LENGTH } ?: return null
        val validMode = (mode ?: "").takeIf { it.length <= MAX_FIELD_LENGTH } ?: return null

        return ControlCommand.LoadVideo(validVid, validPid, validSource, validMode)
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) {
            return null
        }
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw JsonParseException("$name must be a string")
        }
        return value.asString
    }

    private fun JsonObject.optionalMode(): String? {
        if (has("mode") && get("mode").isJsonNull) {
            throw JsonParseException("mode must be a string")
        }
        return optionalString("mode")
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull) {
            return null
        }
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw JsonParseException("$name must be an integer")
        }

        val rawValue = value.asNumber.toString()
        if (!INTEGER_PATTERN.matches(rawValue)) {
            throw JsonParseException("$name must be an integer")
        }
        return rawValue.toIntOrNull() ?: throw JsonParseException("$name is out of range")
    }

    private companion object {
        const val MAX_FIELD_LENGTH = 512
        val INTEGER_PATTERN = Regex("-?\\d+")
    }
}
