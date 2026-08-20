package com.airplay.tv.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.airplay.tv.BuildConfig
import com.airplay.tv.core.network.NetworkFactory
import com.airplay.tv.feature.history.DataStorePlaybackProgressRepository
import com.airplay.tv.feature.history.PlaybackProgressRepository
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
    private val playbackDataStore = PreferenceDataStoreFactory.create {
        applicationContext.preferencesDataStoreFile(PLAYBACK_PROGRESS_FILE_NAME)
    }

    val videoResolver = VideoResolver(videoApi)
    val playbackProgressRepository: PlaybackProgressRepository =
        DataStorePlaybackProgressRepository(playbackDataStore)

    fun createSocketClient(): SocketClient =
        OkHttpSocketClient(webSocketClient, SocketMessageParser())

    fun createPlayerController(): PlayerController =
        Media3PlayerController(applicationContext)

    private companion object {
        const val PLAYBACK_PROGRESS_FILE_NAME = "playback_progress.preferences_pb"
    }
}
