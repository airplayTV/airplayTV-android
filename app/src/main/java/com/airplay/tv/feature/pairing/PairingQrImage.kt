package com.airplay.tv.feature.pairing

internal data class PairingQrImage<T>(
    val content: String,
    val bitmap: T,
)

internal fun <T> PairingQrImage<T>?.bitmapFor(currentContent: String): T? =
    this?.takeIf { it.content == currentContent }?.bitmap
