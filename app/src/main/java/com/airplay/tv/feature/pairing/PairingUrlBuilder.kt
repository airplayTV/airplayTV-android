package com.airplay.tv.feature.pairing

import com.airplay.tv.core.config.AppConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object PairingUrlBuilder {
    private const val JOIN_URL = "${AppConfig.H5_BASE_URL}/join"
    const val JOIN_URL_PREFIX = "$JOIN_URL?"

    fun build(roomId: String, timestampMillis: Long): String {
        require(roomId.isNotBlank()) { "roomId must not be blank" }
        require(timestampMillis >= 0) { "timestampMillis must not be negative" }

        return buildString {
            append(JOIN_URL)
            append("?room_id=")
            append(roomId.encodeQueryComponent())
            append("&t=")
            append(timestampMillis)
        }
    }

    private fun String.encodeQueryComponent(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
