package com.airplay.tv.app

import android.content.Context
import com.airplay.tv.BuildConfig
import com.airplay.tv.core.network.NetworkFactory
import com.airplay.tv.feature.player.Media3PlayerController
import com.airplay.tv.feature.player.PlayerController
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.protocol.OkHttpSocketClient
import com.airplay.tv.protocol.SocketClient
import com.airplay.tv.protocol.SocketMessageParser

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val apiClient = NetworkFactory.apiClient(BuildConfig.DEBUG)
    private val webSocketClient = NetworkFactory.webSocketClient(BuildConfig.DEBUG)
    private val videoApi = NetworkFactory.videoApi(apiClient)

    val videoResolver = VideoResolver(videoApi)

    fun createSocketClient(): SocketClient =
        OkHttpSocketClient(webSocketClient, SocketMessageParser())

    fun createPlayerController(): PlayerController =
        Media3PlayerController(applicationContext)
}
