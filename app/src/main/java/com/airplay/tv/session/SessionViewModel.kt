package com.airplay.tv.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.airplay.tv.diagnostics.DiagnosticLogEntry
import com.airplay.tv.diagnostics.appendDiagnostic
import com.airplay.tv.diagnostics.toDiagnosticLog
import com.airplay.tv.feature.player.Episode
import com.airplay.tv.feature.player.PlayerController
import com.airplay.tv.feature.player.RemoteControlAction
import com.airplay.tv.feature.player.VideoDetails
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.SocketClient
import com.airplay.tv.protocol.SocketConnectionState
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
    private var diagnosticOverlayJob: Job? = null
    private var pendingLoad: PendingLoad? = null
    private var pendingDetailsCommand: ControlCommand.LoadVideo? = null
    private var currentLoadCommand: ControlCommand.LoadVideo? = null
    private var acceptedLoadCommand: ControlCommand.LoadVideo? = null
    private var episodes: List<Episode> = emptyList()
    private var pendingMediaControls: PendingMediaControls? = null
    private var resolutionError: String? = null
    private var isForeground = false
    private var pendingForegroundPlayIntent: Boolean? = null
    private var overlayRevision = 0L
    private var diagnosticRevision = 0L
    private var cleared = false

    init {
        viewModelScope.launch {
            socketClient.states.collect { connection ->
                val previousConnection = mutableUiState.value.connection
                mutableUiState.update { state ->
                    state.copy(
                        connection = connection,
                        controllerConnected = when (connection) {
                            SocketConnectionState.Reconnecting,
                            SocketConnectionState.Closed,
                            -> false

                            SocketConnectionState.Connecting,
                            SocketConnectionState.Connected,
                            -> state.controllerConnected
                        },
                    )
                }
                if (connection != previousConnection) {
                    appendDiagnostic(connection.toDiagnosticLog())
                }
            }
        }
        viewModelScope.launch {
            socketClient.connectionGeneration.collect {
                mutableUiState.update { it.copy(controllerConnected = false) }
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
            socketClient.commands.collect { received ->
                if (received.generation != socketClient.connectionGeneration.value) {
                    return@collect
                }
                val command = received.command
                if (command != ControlCommand.HistoryIgnored) {
                    appendDiagnostic(command.toDiagnosticLog())
                }
                handleCommand(command)
            }
        }
        socketClient.connect(roomId)
    }

    fun onBack() {
        when {
            mutableUiState.value.qrVisible -> {
                mutableUiState.update { it.copy(qrVisible = false) }
            }
            mutableUiState.value.infoVisible -> hideInfo()
            mutableUiState.value.page == SessionPage.Player -> showPairingPage()
        }
    }

    fun onForegroundChanged(isForeground: Boolean) {
        if (cleared || this.isForeground == isForeground) return
        this.isForeground = isForeground
        if (!isForeground) {
            pendingForegroundPlayIntent = if (pendingLoad != null) {
                pendingMediaControls?.latestPlaybackIntent() ?: pendingForegroundPlayIntent
            } else {
                null
            }
            loadGeneration += 1
            resolveJob?.cancel()
            resolveJob = null
            detailJob?.cancel()
            detailJob = null
            pendingMediaControls = null
            playerController.pause()
            return
        }

        if (pendingLoad != null) {
            resolvePendingLoad()
        } else {
            pendingDetailsCommand?.let { command ->
                loadDetails(command, loadGeneration)
            }
            val playIntent = pendingForegroundPlayIntent
            pendingForegroundPlayIntent = null
            if (playIntent == true) {
                playerController.play()
                showInfoTemporarily()
            }
        }
    }

    override fun onCleared() {
        if (cleared) return
        cleared = true
        invalidateLoads()
        overlayJob?.cancel()
        diagnosticRevision += 1
        diagnosticOverlayJob?.cancel()
        diagnosticOverlayJob = null
        viewModelScope.cancel()
        try {
            socketClient.close()
        } finally {
            playerController.release()
        }
        super.onCleared()
    }

    private fun handleCommand(command: ControlCommand) {
        if (
            command != ControlCommand.HistoryIgnored &&
            command != ControlCommand.ControllerUnpaired
        ) {
            mutableUiState.update { it.copy(controllerConnected = true) }
        }

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
            ControlCommand.ShowQrCode -> showQrOverlay()
            ControlCommand.Previous -> loadAdjacentEpisode(-1)
            ControlCommand.Next -> loadAdjacentEpisode(1)
            ControlCommand.ControllerPaired -> Unit
            ControlCommand.ControllerUnpaired -> {
                mutableUiState.update { it.copy(controllerConnected = false) }
            }
            ControlCommand.HistoryIgnored -> Unit
        }
    }

    private fun loadVideo(
        command: ControlCommand.LoadVideo,
        preserveEpisodes: Boolean = false,
    ) {
        loadGeneration += 1
        resolveJob?.cancel()
        detailJob?.cancel()
        pendingDetailsCommand = null
        pendingMediaControls = null
        pendingForegroundPlayIntent = null
        if (!preserveEpisodes) episodes = emptyList()
        acceptedLoadCommand = command
        pendingLoad = PendingLoad(
            command = command,
            preserveEpisodes = preserveEpisodes,
            overlayRevisionAtAcceptance = overlayRevision,
        )
        resolutionError = null
        mutableUiState.update {
            it.copy(
                page = SessionPage.Player,
                loading = true,
                title = if (preserveEpisodes) it.title else "",
                episodeName = if (preserveEpisodes) {
                    episodes.firstOrNull { episode -> episode.id == command.pid }?.name.orEmpty()
                } else {
                    ""
                },
                playbackUrl = if (preserveEpisodes) it.playbackUrl else "",
                qrVisible = false,
                error = null,
            )
        }

        if (isForeground) resolvePendingLoad()
    }

    private fun resolvePendingLoad() {
        val load = pendingLoad ?: return
        val command = load.command
        val generation = ++loadGeneration
        resolveJob?.cancel()
        detailJob?.cancel()
        pendingMediaControls = PendingMediaControls(generation)
        pendingForegroundPlayIntent?.let { shouldPlay ->
            pendingMediaControls?.add(if (shouldPlay) MediaControl.Play else MediaControl.Pause)
            pendingForegroundPlayIntent = null
        }

        resolveJob = viewModelScope.launch {
            val resolved = try {
                videoResolver.resolve(command)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (generation == loadGeneration && isForeground && pendingLoad === load) {
                    discardPendingMediaControls(generation)
                    pendingLoad = null
                    rollbackAcceptedCursor(load)
                    resolutionError = LOAD_ERROR_MESSAGE
                    mutableUiState.update {
                        it.copy(loading = false, error = LOAD_ERROR_MESSAGE)
                    }
                }
                return@launch
            }

            if (generation != loadGeneration || !isForeground || pendingLoad !== load) {
                return@launch
            }

            playerController.load(resolved.url, resolved.mediaType)
            val pendingControls = pendingMediaControls
                ?.takeIf { it.generation == generation }
                ?.controls
                ?.toList()
                .orEmpty()
            discardPendingMediaControls(generation)
            pendingControls.forEach(::applyMediaControl)
            pendingLoad = null
            currentLoadCommand = command
            acceptedLoadCommand = command
            resolutionError = null
            mutableUiState.update {
                it.copy(
                    loading = false,
                    title = resolved.title.ifEmpty { it.title },
                    episodeName = resolved.episodeName
                        .ifEmpty {
                            episodes.firstOrNull { episode -> episode.id == command.pid }
                                ?.name
                                .orEmpty()
                        }
                        .ifEmpty { it.episodeName },
                    playbackUrl = resolved.url,
                    error = playerController.state.value.error,
                )
            }
            if (load.overlayRevisionAtAcceptance == overlayRevision) {
                showInfoTemporarily()
            }
            if (!load.preserveEpisodes || episodes.isEmpty()) {
                loadDetails(command, generation)
            }
        }
    }

    private fun loadDetails(command: ControlCommand.LoadVideo, generation: Long) {
        pendingDetailsCommand = command
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            val details = try {
                videoResolver.loadDetails(command)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                VideoDetails()
            }

            if (
                generation != loadGeneration ||
                !isForeground ||
                pendingDetailsCommand != command
            ) {
                return@launch
            }

            pendingDetailsCommand = null
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
        val command = acceptedLoadCommand ?: currentLoadCommand ?: return
        val currentIndex = episodes.indexOfFirst { it.id == command.pid }
        if (currentIndex < 0) return
        val target = episodes.getOrNull(currentIndex + offset) ?: return
        showInfoTemporarily()
        loadVideo(command.copy(pid = target.id), preserveEpisodes = true)
    }

    private inline fun playbackControl(action: () -> Unit) {
        action()
        showInfoTemporarily()
    }

    private fun handleMediaControl(control: MediaControl) {
        if (!isForeground) {
            pendingForegroundPlayIntent = when (control) {
                MediaControl.Play -> true
                MediaControl.Pause -> false
                MediaControl.Forward,
                MediaControl.Back,
                -> pendingForegroundPlayIntent
            }
            return
        }
        val pending = pendingMediaControls
        if (mutableUiState.value.loading && pending?.generation == loadGeneration) {
            pending.add(control)
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
        val revision = ++overlayRevision
        overlayJob?.cancel()
        mutableUiState.update { it.copy(infoVisible = true) }
        overlayJob = viewModelScope.launch {
            delay(INFO_TIMEOUT_MS)
            if (revision == overlayRevision) {
                mutableUiState.update { it.copy(infoVisible = false) }
            }
        }
    }

    private fun hideInfo() {
        overlayRevision += 1
        overlayJob?.cancel()
        overlayJob = null
        mutableUiState.update { it.copy(infoVisible = false) }
    }

    fun onRemoteControl(action: RemoteControlAction) {
        if (mutableUiState.value.page != SessionPage.Player) return

        val control = when (action) {
            RemoteControlAction.Play -> MediaControl.Play
            RemoteControlAction.Pause -> MediaControl.Pause
            RemoteControlAction.TogglePlayPause -> if (mutableUiState.value.isPlaying) {
                MediaControl.Pause
            } else {
                MediaControl.Play
            }
            RemoteControlAction.Forward -> MediaControl.Forward
            RemoteControlAction.Back -> MediaControl.Back
        }
        handleMediaControl(control)
    }

    private fun appendDiagnostic(entry: DiagnosticLogEntry) {
        val revision = ++diagnosticRevision
        diagnosticOverlayJob?.cancel()
        mutableUiState.update {
            it.copy(
                diagnosticLogs = it.diagnosticLogs.appendDiagnostic(entry),
                diagnosticVisible = true,
            )
        }
        diagnosticOverlayJob = viewModelScope.launch {
            delay(DIAGNOSTIC_TIMEOUT_MS)
            if (revision == diagnosticRevision) {
                mutableUiState.update { it.copy(diagnosticVisible = false) }
            }
        }
    }

    private fun showQrOverlay() {
        if (mutableUiState.value.page == SessionPage.Player) {
            mutableUiState.update { it.copy(qrVisible = true) }
        }
    }

    private fun showPairingPage() {
        invalidateLoads()
        hideInfo()
        resolutionError = null
        currentLoadCommand = null
        acceptedLoadCommand = null
        pendingLoad = null
        pendingDetailsCommand = null
        pendingForegroundPlayIntent = null
        episodes = emptyList()
        playerController.clear()
        mutableUiState.update {
            it.copy(
                page = SessionPage.Pairing,
                loading = false,
                title = "",
                episodeName = "",
                playbackUrl = "",
                qrVisible = false,
                diagnosticVisible = false,
                error = null,
            )
        }
    }

    private fun invalidateLoads() {
        loadGeneration += 1
        pendingLoad = null
        pendingDetailsCommand = null
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

    private fun rollbackAcceptedCursor(load: PendingLoad) {
        acceptedLoadCommand = if (load.preserveEpisodes) currentLoadCommand else null
        if (load.preserveEpisodes) {
            val committedPid = currentLoadCommand?.pid
            mutableUiState.update {
                it.copy(
                    episodeName = episodes.firstOrNull { episode -> episode.id == committedPid }
                        ?.name
                        .orEmpty()
                        .ifEmpty { it.episodeName },
                )
            }
        }
    }

    private data class PendingMediaControls(
        val generation: Long,
        val controls: MutableList<MediaControl> = mutableListOf(),
    ) {
        fun add(control: MediaControl) {
            if (controls.size == MAX_PENDING_MEDIA_CONTROLS) {
                controls.removeAt(0)
            }
            controls += control
        }

        fun latestPlaybackIntent(): Boolean? = controls
            .asReversed()
            .firstNotNullOfOrNull { control ->
                when (control) {
                    MediaControl.Play -> true
                    MediaControl.Pause -> false
                    MediaControl.Forward,
                    MediaControl.Back,
                    -> null
                }
            }
    }

    private data class PendingLoad(
        val command: ControlCommand.LoadVideo,
        val preserveEpisodes: Boolean,
        val overlayRevisionAtAcceptance: Long,
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
        const val DIAGNOSTIC_TIMEOUT_MS = 5_000L
        const val MAX_PENDING_MEDIA_CONTROLS = 64
        const val LOAD_ERROR_MESSAGE = "视频加载失败，请重试"
    }
}
