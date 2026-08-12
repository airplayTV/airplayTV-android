package com.airplay.tv.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.airplay.tv.feature.player.Episode
import com.airplay.tv.feature.player.PlayerController
import com.airplay.tv.feature.player.VideoDetails
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.SocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionViewModel(
    roomId: String,
    private val socketClient: SocketClient,
    private val videoResolver: VideoResolver,
    private val playerController: PlayerController,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SessionUiState(roomId = roomId))

    val uiState: StateFlow<SessionUiState> = mutableUiState.asStateFlow()
    val player: Player
        get() = playerController.player

    private var loadGeneration = 0L
    private var resolveJob: Job? = null
    private var detailJob: Job? = null
    private var overlayJob: Job? = null
    private var currentLoadCommand: ControlCommand.LoadVideo? = null
    private var episodes: List<Episode> = emptyList()
    private var pendingMediaControls: PendingMediaControls? = null
    private var resolutionError: String? = null
    private var cleared = false

    init {
        viewModelScope.launch {
            socketClient.states.collect { connection ->
                mutableUiState.update { it.copy(connection = connection) }
            }
        }
        viewModelScope.launch {
            playerController.state.collect { playerState ->
                mutableUiState.update {
                    it.copy(
                        isPlaying = playerState.isPlaying,
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs,
                        error = resolutionError ?: playerState.error,
                    )
                }
            }
        }
        viewModelScope.launch {
            socketClient.commands.collect(::handleCommand)
        }
        socketClient.connect(roomId)
    }

    fun onBack() {
        if (mutableUiState.value.infoVisible) {
            hideInfo()
        } else if (mutableUiState.value.page == SessionPage.Player) {
            showPairingPage()
        }
    }

    override fun onCleared() {
        if (cleared) return
        cleared = true
        invalidateLoads()
        overlayJob?.cancel()
        viewModelScope.cancel()
        try {
            socketClient.close()
        } finally {
            playerController.release()
        }
        super.onCleared()
    }

    private fun handleCommand(command: ControlCommand) {
        when (command) {
            is ControlCommand.LoadVideo -> loadVideo(command)
            is ControlCommand.Volume -> playbackControl {
                playerController.adjustVolume(command.direction)
            }
            ControlCommand.Play -> handleMediaControl(MediaControl.Play)
            ControlCommand.Pause -> handleMediaControl(MediaControl.Pause)
            ControlCommand.Forward -> handleMediaControl(MediaControl.Forward)
            ControlCommand.Back -> handleMediaControl(MediaControl.Back)
            ControlCommand.Mute -> playbackControl(playerController::toggleMute)
            ControlCommand.Fullscreen -> hideInfo()
            ControlCommand.FullscreenExit -> showInfoTemporarily()
            ControlCommand.ToggleInfo -> toggleInfo()
            ControlCommand.ShowQrCode -> showPairingPage()
            ControlCommand.Previous -> loadAdjacentEpisode(-1)
            ControlCommand.Next -> loadAdjacentEpisode(1)
            ControlCommand.HistoryIgnored -> Unit
        }
    }

    private fun loadVideo(command: ControlCommand.LoadVideo) {
        val generation = ++loadGeneration
        resolveJob?.cancel()
        detailJob?.cancel()
        pendingMediaControls = PendingMediaControls(generation)
        resolutionError = null
        mutableUiState.update {
            it.copy(loading = true, error = playerController.state.value.error)
        }

        resolveJob = viewModelScope.launch {
            val resolved = try {
                videoResolver.resolve(command)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (generation == loadGeneration) {
                    discardPendingMediaControls(generation)
                    resolutionError = LOAD_ERROR_MESSAGE
                    mutableUiState.update {
                        it.copy(loading = false, error = LOAD_ERROR_MESSAGE)
                    }
                }
                return@launch
            }

            if (generation != loadGeneration) return@launch

            playerController.load(resolved.url)
            val pendingControls = pendingMediaControls
                ?.takeIf { it.generation == generation }
                ?.controls
                ?.toList()
                .orEmpty()
            discardPendingMediaControls(generation)
            pendingControls.forEach(::applyMediaControl)
            if (pendingControls.isNotEmpty()) showInfoTemporarily()
            currentLoadCommand = command
            episodes = emptyList()
            resolutionError = null
            mutableUiState.update {
                it.copy(
                    page = SessionPage.Player,
                    loading = false,
                    title = resolved.title,
                    episodeName = resolved.episodeName,
                    error = playerController.state.value.error,
                )
            }
            loadDetails(command, generation)
        }
    }

    private fun loadDetails(command: ControlCommand.LoadVideo, generation: Long) {
        detailJob = viewModelScope.launch {
            val details = try {
                videoResolver.loadDetails(command)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                VideoDetails()
            }

            if (generation != loadGeneration) return@launch

            episodes = details.episodes
            mutableUiState.update {
                it.copy(
                    title = details.title.ifEmpty { it.title },
                    episodeName = details.episodes
                        .firstOrNull { episode -> episode.id == command.pid }
                        ?.name
                        .orEmpty()
                        .ifEmpty { it.episodeName },
                )
            }
        }
    }

    private fun loadAdjacentEpisode(offset: Int) {
        val command = currentLoadCommand ?: return
        val currentIndex = episodes.indexOfFirst { it.id == command.pid }
        if (currentIndex < 0) return
        val target = episodes.getOrNull(currentIndex + offset) ?: return
        showInfoTemporarily()
        loadVideo(command.copy(pid = target.id))
    }

    private inline fun playbackControl(action: () -> Unit) {
        action()
        showInfoTemporarily()
    }

    private fun handleMediaControl(control: MediaControl) {
        val pending = pendingMediaControls
        if (mutableUiState.value.loading && pending?.generation == loadGeneration) {
            pending.controls += control
            showInfoTemporarily()
            return
        }
        applyMediaControl(control)
        showInfoTemporarily()
    }

    private fun applyMediaControl(control: MediaControl) {
        when (control) {
            MediaControl.Play -> playerController.play()
            MediaControl.Pause -> playerController.pause()
            MediaControl.Forward -> playerController.seekBy(SEEK_STEP_MS)
            MediaControl.Back -> playerController.seekBy(-SEEK_STEP_MS)
        }
    }

    private fun toggleInfo() {
        if (mutableUiState.value.infoVisible) {
            hideInfo()
        } else {
            showInfoTemporarily()
        }
    }

    private fun showInfoTemporarily() {
        overlayJob?.cancel()
        mutableUiState.update { it.copy(infoVisible = true) }
        overlayJob = viewModelScope.launch {
            delay(INFO_TIMEOUT_MS)
            mutableUiState.update { it.copy(infoVisible = false) }
        }
    }

    private fun hideInfo() {
        overlayJob?.cancel()
        overlayJob = null
        mutableUiState.update { it.copy(infoVisible = false) }
    }

    private fun showPairingPage() {
        invalidateLoads()
        hideInfo()
        resolutionError = null
        currentLoadCommand = null
        episodes = emptyList()
        playerController.clear()
        mutableUiState.update {
            it.copy(
                page = SessionPage.Pairing,
                loading = false,
                title = "",
                episodeName = "",
                error = null,
            )
        }
    }

    private fun invalidateLoads() {
        loadGeneration += 1
        pendingMediaControls = null
        resolveJob?.cancel()
        resolveJob = null
        detailJob?.cancel()
        detailJob = null
    }

    private fun discardPendingMediaControls(generation: Long) {
        if (pendingMediaControls?.generation == generation) {
            pendingMediaControls = null
        }
    }

    private data class PendingMediaControls(
        val generation: Long,
        val controls: MutableList<MediaControl> = mutableListOf(),
    )

    private enum class MediaControl {
        Play,
        Pause,
        Forward,
        Back,
    }

    private companion object {
        const val SEEK_STEP_MS = 15_000L
        const val INFO_TIMEOUT_MS = 5_000L
        const val LOAD_ERROR_MESSAGE = "视频加载失败，请重试"
    }
}
