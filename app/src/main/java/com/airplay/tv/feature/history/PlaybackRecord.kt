package com.airplay.tv.feature.history

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class PlaybackRecord(
    val source: String,
    val vid: String,
    val pid: String,
    val title: String,
    val episodeName: String,
    val thumb: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtMs: Long,
) {
    fun resumePositionMs(): Long = if (completed) 0L else positionMs.coerceAtLeast(0L)
}

internal fun playbackRecordKey(
    source: String,
    vid: String,
    pid: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(source, vid, pid).forEach { component ->
        val bytes = component.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    val hash = digest.digest()
    return buildString(RECORD_KEY_PREFIX.length + SHA_256_HEX_LENGTH) {
        append(RECORD_KEY_PREFIX)
        hash.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}

internal fun isPlaybackCompleted(
    positionMs: Long,
    durationMs: Long,
    naturalEnd: Boolean,
): Boolean {
    if (naturalEnd) return true
    if (durationMs <= 0) return false
    val position = positionMs.coerceIn(0L, durationMs)
    val percentageThreshold = durationMs - durationMs / COMPLETION_FRACTION_DENOMINATOR
    return durationMs - position <= COMPLETION_REMAINING_MS ||
        position >= percentageThreshold
}

private const val RECORD_KEY_PREFIX = "record_"
private const val SHA_256_HEX_LENGTH = 64
private const val HEX_DIGITS = "0123456789abcdef"
private const val COMPLETION_REMAINING_MS = 30_000L
private const val COMPLETION_FRACTION_DENOMINATOR = 20L
