package com.airplay.tv.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.airplay.tv.feature.player.PlayerController
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.protocol.SocketClient

class SessionViewModelFactory(
    private val roomId: String,
    private val socketClient: SocketClient,
    private val videoResolver: VideoResolver,
    private val playerController: PlayerController,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SessionViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return SessionViewModel(
            roomId = roomId,
            socketClient = socketClient,
            videoResolver = videoResolver,
            playerController = playerController,
        ) as T
    }
}
