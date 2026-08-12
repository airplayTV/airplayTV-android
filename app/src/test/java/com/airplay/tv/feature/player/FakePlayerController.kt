package com.airplay.tv.feature.player

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakePlayerController(
    override val player: Player = createNoOpPlayer(),
) : PlayerController {
    private val mutableState = MutableStateFlow(PlayerState())

    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    val calls = mutableListOf<String>()
    val loadedUrls = mutableListOf<String>()
    val seekDeltas = mutableListOf<Long>()
    val volumeDirections = mutableListOf<Int>()

    fun setState(value: PlayerState) {
        mutableState.value = value
    }

    override fun load(url: String) {
        calls += "load"
        loadedUrls += url
    }

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun seekBy(deltaMs: Long) {
        calls += "seekBy"
        seekDeltas += deltaMs
    }

    override fun adjustVolume(direction: Int) {
        calls += "adjustVolume"
        volumeDirections += direction
    }

    override fun toggleMute() {
        calls += "toggleMute"
    }

    override fun clear() {
        calls += "clear"
    }

    override fun release() {
        calls += "release"
    }
}

private fun createNoOpPlayer(): Player = Proxy.newProxyInstance(
    Player::class.java.classLoader,
    arrayOf(Player::class.java),
) { proxy, method, arguments ->
    when (method.name) {
        "equals" -> proxy === arguments?.firstOrNull()
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "FakePlayerController.player"
        else -> primitiveDefault(method.returnType)
    }
} as Player

private fun primitiveDefault(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0F
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> '\u0000'
    else -> null
}
