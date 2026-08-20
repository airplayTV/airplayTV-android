package com.airplay.tv.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.airplay.tv.diagnostics.DiagnosticLogEntry
import com.airplay.tv.diagnostics.appendDiagnostic
import com.airplay.tv.diagnostics.toDiagnosticLog
import com.airplay.tv.feature.history.PlaybackProgressRepository
import com.airplay.tv.feature.history.PlaybackRecord
import com.airplay.tv.feature.history.isPlaybackCompleted
import com.airplay.tv.feature.player.Episode
import com.airplay.tv.feature.player.PlaybackEvent
import com.airplay.tv.feature.player.PlayerController
import com.airplay.tv.feature.player.PlayerState
import com.airplay.tv.feature.player.RemoteControlAction
import com.airplay.tv.feature.player.VideoDetails
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.PlaybackHistoryMessage
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
import java.util.UUID

class SessionViewModel(
    roomId: String,
    private val socketClient: SocketClient,
    private val videoResolver: VideoResolver,
    private val playerController: PlayerController,
    private val playbackProgressRepository: PlaybackProgressRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
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
    private var localProgressJob: Job? = null
    private var remoteProgressJob: Job? = null
    private var syncTimeoutJob: Job? = null
    private var pendingLoad: PendingLoad? = null
    private var pendingDetailsCommand: ControlCommand.LoadVideo? = null
    private var currentLoadCommand: ControlCommand.LoadVideo? = null
    private var currentLoadGeneration: Long? = null
    private var currentPlaybackIdentity: PlaybackIdentity? = null
    private var currentPlaybackContext: PlaybackContext? = null
    private var acceptedLoadCommand: ControlCommand.LoadVideo? = null
    private var episodes: List<Episode> = emptyList()
    private var currentThumb = ""
    private var pendingSync: PendingSync? = null
    private var handledPlaybackEndGeneration: Long? = null
    private var handledPlaybackErrorGeneration: Long? = null
    private var pendingAutoAdvance: PendingAutoAdvance? = null
    private var pendingMediaControls: PendingMediaControls? = null
    private var resolutionError: String? = null
    private var isForeground = false
    private var pendingForegroundPlayIntent: Boolean? = null
    private var controllerAssociationLogged = false
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
                if (
                    connection == SocketConnectionState.Reconnecting ||
                    connection == SocketConnectionState.Closed
                ) {
                    controllerAssociationLogged = false
                    failPendingSync()
                }
                if (connection != previousConnection) {
                    appendDiagnostic(connection.toDiagnosticLog())
                }
            }
        }
        viewModelScope.launch {
            socketClient.connectionGeneration.collect {
                controllerAssociationLogged = false
                mutableUiState.update { it.copy(controllerConnected = false) }
            }
        }
        viewModelScope.launch {
            playerController.state.collect { playerState ->
                val wasPlaying = mutableUiState.value.isPlaying
                mutableUiState.update {
                    it.copy(
                        isPlaying = playerState.isPlaying,
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs,
                        error = resolutionError ?: playerState.error,
                    )
                }
                when {
                    playerState.isPlaying && !wasPlaying -> {
                        currentPlaybackIdentity?.let(::startProgressJobs)
                    }
                    !playerState.isPlaying && wasPlaying -> stopProgressJobs()
                }
            }
        }
        viewModelScope.launch {
            playerController.events.collect { event ->
                when (event) {
                    is PlaybackEvent.Ended -> handlePlaybackEnded(event.mediaToken)
                    PlaybackEvent.Error -> handlePlaybackError()
                }
            }
        }
        viewModelScope.launch {
            socketClient.playbackHistoryAcks.collect { ack ->
                val pending = pendingSync?.takeIf { it.requestId == ack.requestId }
                    ?: return@collect
                pendingSync = null
                syncTimeoutJob?.cancel()
                syncTimeoutJob = null
                if (isCurrent(pending.identity)) {
                    mutableUiState.update {
                        it.copy(
                            syncStatus = if (ack.accepted) {
                                PlaybackSyncStatus.Synced
                            } else {
                                PlaybackSyncStatus.Failed
                            },
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            socketClient.commands.collect { received ->
                if (received.generation != socketClient.connectionGeneration.value) {
                    return@collect
                }
                val command = received.command
                val shouldAppendDiagnostic = when (command) {
                    ControlCommand.HistoryIgnored -> false
                    ControlCommand.ControllerPaired -> !controllerAssociationLogged
                    else -> true
                }
                if (shouldAppendDiagnostic) {
                    appendDiagnostic(command.toDiagnosticLog())
                }
                if (command == ControlCommand.ControllerPaired) {
                    controllerAssociationLogged = true
                }
                handleCommand(command)
            }
        }
        socketClient.connect(roomId)
    }

    fun onBack() {
        when {
            mutableUiState.value.episodePanelFocused -> exitEpisodes()
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
            flushCurrentPlayback()
            stopProgressJobs()
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
        flushCurrentPlayback()
        invalidateLoads()
        stopProgressJobs()
        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
        pendingSync = null
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
                controllerAssociationLogged = false
                mutableUiState.update { it.copy(controllerConnected = false) }
            }
            ControlCommand.HistoryIgnored -> Unit
        }
    }

    private fun loadVideo(
        command: ControlCommand.LoadVideo,
        preserveEpisodes: Boolean = false,
        automatic: Boolean = false,
        flushPrevious: Boolean = true,
    ) {
        if (!automatic) pendingAutoAdvance = null
        if (flushPrevious) flushCurrentPlayback()
        stopProgressJobs()
        loadGeneration += 1
        resolveJob?.cancel()
        detailJob?.cancel()
        pendingDetailsCommand = null
        pendingMediaControls = null
        pendingForegroundPlayIntent = null
        if (!preserveEpisodes) {
            episodes = emptyList()
            currentThumb = ""
        }
        acceptedLoadCommand = command
        pendingLoad = PendingLoad(
            command = command,
            preserveEpisodes = preserveEpisodes,
            automatic = automatic,
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
                sourceName = command.source,
                episodes = episodes,
                currentPid = command.pid,
                episodePanelFocused = false,
                focusedEpisodeIndex = 0,
                syncStatus = PlaybackSyncStatus.Idle,
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
                    if (load.automatic) {
                        appendDiagnostic(DiagnosticLogEntry("ERR", AUTO_NEXT_ERROR_MESSAGE))
                    }
                    resolutionError = LOAD_ERROR_MESSAGE
                    mutableUiState.update {
                        it.copy(loading = false, error = LOAD_ERROR_MESSAGE)
                    }
                    currentPlaybackIdentity
                        ?.takeIf { playerController.state.value.isPlaying }
                        ?.let(::startProgressJobs)
                }
                return@launch
            }

            if (generation != loadGeneration || !isForeground || pendingLoad !== load) {
                return@launch
            }

            appendDiagnostic(DiagnosticLogEntry("API", SOURCE_RESOLVED_MESSAGE))
            val resumePositionMs = try {
                playbackProgressRepository.find(command.source, command.vid, command.pid)
                    ?.resumePositionMs()
                    ?: 0L
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                0L
            }
            if (generation != loadGeneration || !isForeground || pendingLoad !== load) {
                return@launch
            }
            playerController.load(
                url = resolved.url,
                mediaType = resolved.mediaType,
                startPositionMs = resumePositionMs,
                mediaToken = generation,
            )
            val pendingControls = pendingMediaControls
                ?.takeIf { it.generation == generation }
                ?.controls
                ?.toList()
                .orEmpty()
            discardPendingMediaControls(generation)
            pendingControls.forEach(::applyMediaControl)
            val identity = PlaybackIdentity(generation, command)
            val previousState = mutableUiState.value
            val context = PlaybackContext(
                identity = identity,
                title = resolved.title.ifEmpty { previousState.title },
                episodeName = resolved.episodeName
                    .ifEmpty {
                        episodes.firstOrNull { episode -> episode.id == command.pid }
                            ?.name
                            .orEmpty()
                    }
                    .ifEmpty { previousState.episodeName },
                thumb = currentThumb,
                episodes = episodes.toList(),
            )
            pendingLoad = null
            currentLoadCommand = command
            currentLoadGeneration = generation
            currentPlaybackIdentity = identity
            currentPlaybackContext = context
            acceptedLoadCommand = command
            resolutionError = null
            mutableUiState.update {
                it.copy(
                    loading = false,
                    title = context.title,
                    episodeName = context.episodeName,
                    playbackUrl = resolved.url,
                    sourceName = command.source,
                    currentPid = command.pid,
                    error = playerController.state.value.error,
                )
            }
            if (playerController.state.value.isPlaying) {
                startProgressJobs(checkNotNull(currentPlaybackIdentity))
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
            currentThumb = details.thumb
            currentPlaybackContext
                ?.takeIf { context ->
                    context.identity.generation == generation &&
                        context.identity.command == command
                }
                ?.let { context ->
                    currentPlaybackContext = context.copy(
                        title = details.title.ifEmpty { context.title },
                        episodeName = details.episodes
                            .firstOrNull { episode -> episode.id == command.pid }
                            ?.name
                            .orEmpty()
                            .ifEmpty { context.episodeName },
                        thumb = details.thumb,
                        episodes = details.episodes.toList(),
                    )
                }
            if (details.title.isNotEmpty() || details.episodes.isNotEmpty()) {
                appendDiagnostic(DiagnosticLogEntry("API", DETAILS_LOADED_MESSAGE))
            }
            mutableUiState.update {
                it.copy(
                    title = details.title.ifEmpty { it.title },
                    episodeName = details.episodes
                        .firstOrNull { episode -> episode.id == command.pid }
                        ?.name
                        .orEmpty()
                        .ifEmpty { it.episodeName },
                    episodes = details.episodes,
                )
            }
            val pendingAdvance = pendingAutoAdvance
                ?.takeIf {
                    it.command == command &&
                        currentLoadGeneration == it.committedGeneration
                }
            if (pendingAdvance != null) {
                pendingAutoAdvance = null
                advanceAutomatically(pendingAdvance.command, pendingAdvance.committedGeneration)
            }
        }
    }

    private fun loadAdjacentEpisode(offset: Int) {
        pendingAutoAdvance = null
        val command = acceptedLoadCommand ?: currentLoadCommand ?: return
        val currentIndex = episodes.indexOfFirst { it.id == command.pid }
        if (currentIndex < 0) return
        val target = episodes.getOrNull(currentIndex + offset) ?: return
        showInfoTemporarily()
        loadVideo(command.copy(pid = target.id), preserveEpisodes = true)
    }

    private fun handlePlaybackEnded(mediaToken: Long) {
        if (mutableUiState.value.page != SessionPage.Player || pendingLoad != null) return
        val command = currentLoadCommand ?: return
        val committedGeneration = currentLoadGeneration ?: return
        if (mediaToken != committedGeneration) return
        if (handledPlaybackEndGeneration == committedGeneration) return

        handledPlaybackEndGeneration = committedGeneration
        flushCurrentPlayback(naturalEnd = true)
        appendDiagnostic(DiagnosticLogEntry("PLAY", PLAYBACK_ENDED_MESSAGE))
        if (pendingDetailsCommand == command) {
            pendingAutoAdvance = PendingAutoAdvance(command, committedGeneration)
            return
        }
        advanceAutomatically(command, committedGeneration)
    }

    private fun handlePlaybackError() {
        if (mutableUiState.value.page != SessionPage.Player || pendingLoad != null) return
        val committedGeneration = currentLoadGeneration ?: return
        if (handledPlaybackErrorGeneration == committedGeneration) return

        handledPlaybackErrorGeneration = committedGeneration
        appendDiagnostic(DiagnosticLogEntry("ERR", PLAYER_ERROR_MESSAGE))
    }

    private fun advanceAutomatically(
        command: ControlCommand.LoadVideo,
        committedGeneration: Long,
    ) {
        if (
            mutableUiState.value.page != SessionPage.Player ||
            pendingLoad != null ||
            currentLoadCommand != command ||
            currentLoadGeneration != committedGeneration
        ) {
            return
        }
        val currentIndex = episodes.indexOfFirst { it.id == command.pid }
        if (currentIndex < 0) {
            appendDiagnostic(DiagnosticLogEntry("SKIP", EPISODE_LIST_UNAVAILABLE_MESSAGE))
            return
        }
        val nextEpisode = episodes.getOrNull(currentIndex + 1)
        if (nextEpisode == null) {
            appendDiagnostic(DiagnosticLogEntry("SKIP", FINAL_EPISODE_MESSAGE))
            return
        }

        appendDiagnostic(DiagnosticLogEntry("PLAY", AUTO_NEXT_MESSAGE))
        loadVideo(
            command = command.copy(pid = nextEpisode.id),
            preserveEpisodes = true,
            automatic = true,
            flushPrevious = false,
        )
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
            MediaControl.Pause -> {
                flushCurrentPlayback()
                stopProgressJobs()
                playerController.pause()
            }
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
        if (mutableUiState.value.episodePanelFocused) {
            overlayJob?.cancel()
            overlayJob = null
            mutableUiState.update { it.copy(infoVisible = true) }
            return
        }
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
        if (mutableUiState.value.episodePanelFocused) {
            overlayJob?.cancel()
            overlayJob = null
            return
        }
        overlayRevision += 1
        overlayJob?.cancel()
        overlayJob = null
        mutableUiState.update { it.copy(infoVisible = false) }
    }

    fun onRemoteControl(action: RemoteControlAction) {
        if (mutableUiState.value.page != SessionPage.Player) return

        when (action) {
            RemoteControlAction.OpenEpisodes -> openEpisodes()
            RemoteControlAction.EpisodeUp -> moveEpisodeFocus(-1)
            RemoteControlAction.EpisodeDown -> moveEpisodeFocus(1)
            RemoteControlAction.SelectEpisode -> selectFocusedEpisode()
            RemoteControlAction.ExitEpisodes -> exitEpisodes()
            else -> handleMediaControl(
                when (action) {
                    RemoteControlAction.Play -> MediaControl.Play
                    RemoteControlAction.Pause -> MediaControl.Pause
                    RemoteControlAction.TogglePlayPause -> if (mutableUiState.value.isPlaying) {
                        MediaControl.Pause
                    } else {
                        MediaControl.Play
                    }
                    RemoteControlAction.Forward -> MediaControl.Forward
                    RemoteControlAction.Back -> MediaControl.Back
                    else -> error("Unhandled remote control action: $action")
                },
            )
        }
    }

    private fun openEpisodes() {
        if (episodes.size <= 1) return
        overlayRevision += 1
        overlayJob?.cancel()
        overlayJob = null
        mutableUiState.update {
            it.copy(
                infoVisible = true,
                episodePanelFocused = true,
                focusedEpisodeIndex = episodes.indexOfFirst { episode ->
                    episode.id == currentLoadCommand?.pid
                }.coerceAtLeast(0),
            )
        }
    }

    private fun moveEpisodeFocus(offset: Int) {
        if (!mutableUiState.value.episodePanelFocused || episodes.isEmpty()) return
        mutableUiState.update {
            it.copy(
                focusedEpisodeIndex = (it.focusedEpisodeIndex + offset)
                    .coerceIn(0, episodes.lastIndex),
            )
        }
    }

    private fun selectFocusedEpisode() {
        val state = mutableUiState.value
        if (!state.episodePanelFocused) return
        val target = episodes.getOrNull(state.focusedEpisodeIndex)
        val command = currentLoadCommand
        if (target == null || command == null || target.id == command.pid) {
            exitEpisodes()
            return
        }
        mutableUiState.update { it.copy(episodePanelFocused = false) }
        loadVideo(command.copy(pid = target.id), preserveEpisodes = true)
        showInfoTemporarily()
    }

    private fun exitEpisodes() {
        if (!mutableUiState.value.episodePanelFocused) return
        mutableUiState.update { it.copy(episodePanelFocused = false) }
        showInfoTemporarily()
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

    private fun startProgressJobs(identity: PlaybackIdentity) {
        stopProgressJobs()
        localProgressJob = viewModelScope.launch {
            while (isCurrent(identity) && playerController.state.value.isPlaying) {
                delay(LOCAL_PROGRESS_INTERVAL_MS)
                if (!isCurrent(identity) || !playerController.state.value.isPlaying) break
                persistSnapshot(identity, playerController.state.value)
            }
        }
        remoteProgressJob = viewModelScope.launch {
            while (isCurrent(identity) && playerController.state.value.isPlaying) {
                delay(REMOTE_PROGRESS_INTERVAL_MS)
                if (!isCurrent(identity) || !playerController.state.value.isPlaying) break
                val record = playbackRecord(identity, playerController.state.value) ?: break
                syncSnapshot(record, identity)
            }
        }
    }

    private fun stopProgressJobs() {
        localProgressJob?.cancel()
        localProgressJob = null
        remoteProgressJob?.cancel()
        remoteProgressJob = null
    }

    private fun isCurrent(identity: PlaybackIdentity): Boolean =
        !cleared && pendingLoad == null && currentPlaybackIdentity == identity

    private fun persistSnapshot(
        identity: PlaybackIdentity,
        playerState: PlayerState,
    ) {
        playbackRecord(identity, playerState)?.let(playbackProgressRepository::enqueueSave)
    }

    private fun flushCurrentPlayback(
        naturalEnd: Boolean = false,
    ) {
        val identity = currentPlaybackIdentity ?: return
        if (pendingLoad != null) return
        val record = playbackRecord(identity, playerController.state.value, naturalEnd) ?: return
        stopProgressJobs()
        playbackProgressRepository.enqueueSave(record)
        syncSnapshot(record, identity)
    }

    private fun syncSnapshot(record: PlaybackRecord, identity: PlaybackIdentity) {
        val message = PlaybackHistoryMessage(
            requestId = requestIdFactory(),
            group = mutableUiState.value.roomId,
            record = record,
        )
        val accepted = socketClient.sendPlaybackHistory(message)
        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
        pendingSync = null
        if (!accepted) {
            if (isCurrent(identity)) {
                mutableUiState.update { it.copy(syncStatus = PlaybackSyncStatus.Failed) }
            }
            return
        }

        val pending = PendingSync(message.requestId, identity)
        pendingSync = pending
        if (isCurrent(identity)) {
            mutableUiState.update { it.copy(syncStatus = PlaybackSyncStatus.Syncing) }
        }
        syncTimeoutJob = viewModelScope.launch {
            delay(SYNC_ACK_TIMEOUT_MS)
            if (pendingSync == pending) {
                pendingSync = null
                syncTimeoutJob = null
                if (isCurrent(identity)) {
                    mutableUiState.update { it.copy(syncStatus = PlaybackSyncStatus.Failed) }
                }
            }
        }
    }

    private fun failPendingSync() {
        val pending = pendingSync ?: return
        pendingSync = null
        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
        if (isCurrent(pending.identity)) {
            mutableUiState.update { it.copy(syncStatus = PlaybackSyncStatus.Failed) }
        }
    }

    private fun playbackRecord(
        identity: PlaybackIdentity,
        playerState: PlayerState,
        naturalEnd: Boolean = false,
    ): PlaybackRecord? {
        val context = currentPlaybackContext?.takeIf { it.identity == identity } ?: return null
        val command = context.identity.command
        return PlaybackRecord(
            source = command.source,
            vid = command.vid,
            pid = command.pid,
            title = context.title,
            episodeName = context.episodeName,
            thumb = context.thumb,
            positionMs = playerState.positionMs.coerceAtLeast(0L),
            durationMs = playerState.durationMs.coerceAtLeast(0L),
            completed = isPlaybackCompleted(
                positionMs = playerState.positionMs,
                durationMs = playerState.durationMs,
                naturalEnd = naturalEnd,
            ),
            updatedAtMs = nowMs(),
        )
    }

    private fun showQrOverlay() {
        if (mutableUiState.value.page == SessionPage.Player) {
            mutableUiState.update { it.copy(qrVisible = true) }
        }
    }

    private fun showPairingPage() {
        flushCurrentPlayback()
        invalidateLoads()
        hideInfo()
        resolutionError = null
        currentLoadCommand = null
        currentLoadGeneration = null
        currentPlaybackIdentity = null
        currentPlaybackContext = null
        acceptedLoadCommand = null
        pendingLoad = null
        pendingDetailsCommand = null
        pendingForegroundPlayIntent = null
        pendingAutoAdvance = null
        episodes = emptyList()
        currentThumb = ""
        playerController.clear()
        mutableUiState.update {
            it.copy(
                page = SessionPage.Pairing,
                loading = false,
                title = "",
                episodeName = "",
                playbackUrl = "",
                sourceName = "",
                episodes = emptyList(),
                currentPid = "",
                syncStatus = PlaybackSyncStatus.Idle,
                qrVisible = false,
                diagnosticVisible = false,
                error = null,
            )
        }
    }

    private fun invalidateLoads() {
        stopProgressJobs()
        loadGeneration += 1
        pendingLoad = null
        pendingDetailsCommand = null
        pendingMediaControls = null
        pendingAutoAdvance = null
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
                    currentPid = committedPid.orEmpty(),
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
        val automatic: Boolean,
        val overlayRevisionAtAcceptance: Long,
    )

    private data class PlaybackIdentity(
        val generation: Long,
        val command: ControlCommand.LoadVideo,
    )

    private data class PlaybackContext(
        val identity: PlaybackIdentity,
        val title: String,
        val episodeName: String,
        val thumb: String,
        val episodes: List<Episode>,
    )

    private data class PendingSync(
        val requestId: String,
        val identity: PlaybackIdentity,
    )

    private data class PendingAutoAdvance(
        val command: ControlCommand.LoadVideo,
        val committedGeneration: Long,
    )

    private enum class MediaControl {
        Play,
        Pause,
        Forward,
        Back,
    }

    private companion object {
        const val SEEK_STEP_MS = 15_000L
        const val INFO_TIMEOUT_MS = 10_000L
        const val DIAGNOSTIC_TIMEOUT_MS = 5_000L
        const val LOCAL_PROGRESS_INTERVAL_MS = 5_000L
        const val REMOTE_PROGRESS_INTERVAL_MS = 30_000L
        const val SYNC_ACK_TIMEOUT_MS = 5_000L
        const val MAX_PENDING_MEDIA_CONTROLS = 64
        const val LOAD_ERROR_MESSAGE = "视频加载失败，请重试"
        const val PLAYBACK_ENDED_MESSAGE = "当前剧集播放结束"
        const val AUTO_NEXT_MESSAGE = "自动播放下一集"
        const val FINAL_EPISODE_MESSAGE = "已是最后一集"
        const val AUTO_NEXT_ERROR_MESSAGE = "下一集加载失败"
        const val PLAYER_ERROR_MESSAGE = "播放器播放失败"
        const val EPISODE_LIST_UNAVAILABLE_MESSAGE = "剧集列表不可用"
        const val SOURCE_RESOLVED_MESSAGE = "视频地址解析成功"
        const val DETAILS_LOADED_MESSAGE = "剧集信息加载成功"
    }
}
