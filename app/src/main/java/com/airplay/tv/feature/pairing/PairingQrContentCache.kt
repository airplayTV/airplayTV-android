package com.airplay.tv.feature.pairing

class PairingQrContentCache(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private var cachedRoomId: String? = null
    private var cachedContent: String? = null

    fun contentFor(roomId: String): String {
        if (roomId != cachedRoomId) {
            cachedRoomId = roomId
            cachedContent = PairingUrlBuilder.build(roomId, currentTimeMillis())
        }
        return checkNotNull(cachedContent)
    }
}
