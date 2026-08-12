package com.airplay.tv.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.airplay.tv.app.AppContainer
import com.airplay.tv.feature.player.PlayerController
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.protocol.SocketClient
import java.util.UUID

class SessionViewModelFactory private constructor(
    private val roomIdFactory: () -> String,
    private val socketClientFactory: () -> SocketClient,
    private val videoResolver: VideoResolver,
    private val playerControllerFactory: () -> PlayerController,
) : ViewModelProvider.Factory {
    constructor(appContainer: AppContainer) : this(
        roomIdFactory = ::newSessionRoomId,
        socketClientFactory = appContainer::createSocketClient,
        videoResolver = appContainer.videoResolver,
        playerControllerFactory = appContainer::createPlayerController,
    )

    internal constructor(
        roomId: String,
        socketClient: SocketClient,
        videoResolver: VideoResolver,
        playerController: PlayerController,
    ) : this(
        roomIdFactory = { roomId },
        socketClientFactory = { socketClient },
        videoResolver = videoResolver,
        playerControllerFactory = { playerController },
    )

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SessionViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        val roomId = roomIdFactory()
        val playerController = playerControllerFactory()
        val socketClient = try {
            socketClientFactory()
        } catch (exception: Exception) {
            playerController.release()
            throw exception
        }

        @Suppress("UNCHECKED_CAST")
        return try {
            SessionViewModel(
                roomId = roomId,
                socketClient = socketClient,
                videoResolver = videoResolver,
                playerController = playerController,
            ) as T
        } catch (exception: Exception) {
            socketClient.close()
            playerController.release()
            throw exception
        }
    }
}

internal fun newSessionRoomId(): String = UUID.randomUUID().toString()
