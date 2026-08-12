package com.airplay.tv.protocol

internal data class SocketEnvelope(
    val event: String?,
    val group: String?,
    val vid: String?,
    val pid: String?,
    val source: String?,
    val mode: String?,
    val value: Int?,
)
