package com.airplay.tv.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.airplay.tv.app.AppContainer
import com.airplay.tv.feature.history.PlaybackProgressRepository
import com.airplay.tv.feature.player.PlayerController
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.protocol.SocketClient
import java.util.UUID

class SessionViewModelFactory private constructor(
    private val roomIdFactory: () -> String,
    private val socketClientFactory: () -> SocketClient,
    private val videoResolver: VideoResolver,
    private val playerControllerFactory: () -> PlayerController,
    private val playbackProgressRepository: PlaybackProgressRepository,
) : ViewModelProvider.Factory {
    constructor(appContainer: AppContainer) : this(
        roomIdFactory = ::newSessionRoomId,
        socketClientFactory = appContainer::createSocketClient,
        videoResolver = appContainer.videoResolver,
        playerControllerFactory = appContainer::createPlayerController,
        playbackProgressRepository = appContainer.playbackProgressRepository,
    )

    internal constructor(
        roomId: String,
        socketClient: SocketClient,
        videoResolver: VideoResolver,
        playerController: PlayerController,
        playbackProgressRepository: PlaybackProgressRepository,
    ) : this(
        roomIdFactory = { roomId },
        socketClientFactory = { socketClient },
        videoResolver = videoResolver,
        playerControllerFactory = { playerController },
        playbackProgressRepository = playbackProgressRepository,
    )

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SessionViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        val roomId = roomIdFactory()
        val playerController = playerControllerFactory()
        val socketClient = try {
            socketClientFactory()
        } catch (failure: Throwable) {
            cleanupAfterConstructionFailure(null, playerController, failure)
        }

        @Suppress("UNCHECKED_CAST")
        return try {
            SessionViewModel(
                roomId = roomId,
                socketClient = socketClient,
                videoResolver = videoResolver,
                playerController = playerController,
                playbackProgressRepository = playbackProgressRepository,
            ) as T
        } catch (failure: Throwable) {
            cleanupAfterConstructionFailure(socketClient, playerController, failure)
        }
    }
}

internal fun newSessionRoomId(): String = UUID.randomUUID().toString().replace("-", "")

internal fun cleanupAfterConstructionFailure(
    socketClient: SocketClient?,
    playerController: PlayerController,
    originalFailure: Throwable,
): Nothing {
    runCleanup(originalFailure) { socketClient?.close() }
    runCleanup(originalFailure) { playerController.release() }
    throw originalFailure
}

private inline fun runCleanup(originalFailure: Throwable, cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== originalFailure) {
            originalFailure.addSuppressed(cleanupFailure)
        }
    }
}
