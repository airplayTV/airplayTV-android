package com.airplay.tv.feature.player

import java.net.URI

internal fun liveProxyUrl(url: String): String? = try {
    val uri = URI(url)
    val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
        uri.rawUserInfo != null || !Regex("/api/iptv/live/[A-Za-z0-9_-]+\\.m3u8").matches(uri.path) ||
        query.any { it.substringBefore('=') == "web" }
    ) null else url.substringBefore('#').substringBefore('?') + "?" + (query + "web=1").joinToString("&")
} catch (_: Exception) {
    null
}

internal class LiveDeliveryPolicy {
    private var proxyUrl: String? = null
    private var lastProgressMs = 0L
    private var lastPositionMs = 0L
    var enabled = false
        private set

    fun reset(url: String?, nowMs: Long) {
        proxyUrl = url
        enabled = url != null
        lastProgressMs = nowMs
        lastPositionMs = 0L
    }

    fun takeFallback(nowMs: Long): String? {
        val target = proxyUrl ?: return null
        proxyUrl = null
        lastProgressMs = nowMs
        lastPositionMs = 0L
        return target
    }

    fun timedOut(nowMs: Long, positionMs: Long, shouldPlay: Boolean): Boolean {
        if (!enabled) return false
        if (!shouldPlay || positionMs != lastPositionMs) {
            lastProgressMs = nowMs
            lastPositionMs = positionMs
        }
        return shouldPlay && nowMs - lastProgressMs >= 20_000L
    }
}
