package com.airplay.tv.feature.pairing

private const val ELLIPSIS = "..."
private const val MINIMUM_MAX_CHARS = ELLIPSIS.length + 2

fun middleEllipsizeRoomId(roomId: String, maxChars: Int = 18): String {
    require(maxChars >= MINIMUM_MAX_CHARS) { "maxChars must be at least $MINIMUM_MAX_CHARS" }
    if (roomId.length <= maxChars) return roomId

    val preservedCharacters = maxChars - ELLIPSIS.length
    val prefixLength = (preservedCharacters + 1) / 2
    val suffixLength = preservedCharacters - prefixLength
    return roomId.take(prefixLength) + ELLIPSIS + roomId.takeLast(suffixLength)
}
