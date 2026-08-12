package com.airplay.tv.session

import com.airplay.tv.feature.player.ApiResponse
import com.airplay.tv.feature.player.FakePlayerController
import com.airplay.tv.feature.player.PlayerState
import com.airplay.tv.feature.player.VideoApi
import com.airplay.tv.feature.player.VideoDetailDto
import com.airplay.tv.feature.player.VideoLinkDto
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.feature.player.VideoSourceDto
import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.SocketClient
import com.airplay.tv.protocol.SocketConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var socket: FakeSocketClient
    private lateinit var api: FakeVideoApi
    private lateinit var playerController: FakePlayerController
    private lateinit var viewModel: SessionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        socket = FakeSocketClient()
        api = FakeVideoApi()
        playerController = FakePlayerController()
        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsOnPairingAndLatestLoadWinsEvenWhenOldResolverIgnoresCancellation() =
        runTest(dispatcher) {
            api.sourceResponse = { vid, pid ->
                if (vid == "slow") {
                    withContext(NonCancellable) { delay(1_000) }
                } else {
                    delay(10)
                }
                successfulSource("https://cdn/$vid-$pid.m3u8")
            }
            startCollectors()

            assertEquals(SessionPage.Pairing, viewModel.uiState.value.page)
            assertEquals(listOf("room-1"), socket.connectedRooms)
            socket.emit(load("slow", "p1", mode = "old-mode"))
            runCurrent()
            socket.emit(load("latest", "p2", mode = "latest-mode"))
            advanceUntilIdle()

            assertEquals("https://cdn/latest-p2.m3u8", playerController.loadedUrl)
            assertEquals(listOf("https://cdn/latest-p2.m3u8"), playerController.loadedUrls)
            assertEquals(SessionPage.Player, viewModel.uiState.value.page)
            assertFalse(viewModel.uiState.value.loading)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun detailsDoNotBlockPlaybackAndStaleDetailsCannotOverwriteLatestVideo() =
        runTest(dispatcher) {
            api.detailResponse = { vid ->
                if (vid == "old") {
                    withContext(NonCancellable) { delay(1_000) }
                    successfulDetail("Old", "p1" to "Old episode")
                } else {
                    delay(10)
                    successfulDetail("Latest", "p2" to "Latest episode")
                }
            }
            startCollectors()

            socket.emit(load("old", "p1"))
            runCurrent()

            assertEquals("https://cdn/old-p1.m3u8", playerController.loadedUrl)
            assertEquals(SessionPage.Player, viewModel.uiState.value.page)
            assertEquals("", viewModel.uiState.value.episodeName)

            socket.emit(load("latest", "p2"))
            advanceUntilIdle()

            assertEquals("https://cdn/latest-p2.m3u8", playerController.loadedUrl)
            assertEquals("Latest", viewModel.uiState.value.title)
            assertEquals("Latest episode", viewModel.uiState.value.episodeName)
        }

    @Test
    fun mapsPlaybackVolumeMuteAndQrCommands() = runTest(dispatcher) {
        startCollectors()

        socket.emit(ControlCommand.Play)
        socket.emit(ControlCommand.Pause)
        socket.emit(ControlCommand.Forward)
        socket.emit(ControlCommand.Back)
        socket.emit(ControlCommand.Volume(-1))
        socket.emit(ControlCommand.Mute)
        runCurrent()

        assertEquals(
            listOf("play", "pause", "seek:15000", "seek:-15000", "volume:-1", "toggleMute"),
            playerController.calls,
        )
        assertTrue(viewModel.uiState.value.infoVisible)

        socket.emit(ControlCommand.ShowQrCode)
        runCurrent()

        assertEquals("clear", playerController.calls.last())
        assertEquals(SessionPage.Pairing, viewModel.uiState.value.page)
        assertFalse(viewModel.uiState.value.infoVisible)
    }

    @Test
    fun buffersMediaControlsDuringLoadAndAppliesThemInOrderAfterPlayerLoad() =
        runTest(dispatcher) {
            api.sourceResponse = { vid, pid ->
                delay(1_000)
                successfulSource("https://cdn/$vid-$pid.m3u8")
            }
            startCollectors()

            socket.emit(load("slow", "p1"))
            runCurrent()
            socket.emit(ControlCommand.Pause)
            socket.emit(ControlCommand.Forward)
            runCurrent()

            assertTrue(playerController.calls.isEmpty())

            advanceUntilIdle()

            assertEquals(
                listOf("load:https://cdn/slow-p1.m3u8", "pause", "seek:15000"),
                playerController.calls,
            )
        }

    @Test
    fun newLoadDiscardsPendingControlsFromPreviousGeneration() = runTest(dispatcher) {
        api.sourceResponse = { vid, pid ->
            if (vid == "old") withContext(NonCancellable) { delay(1_000) }
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()

        socket.emit(load("old", "p1"))
        runCurrent()
        socket.emit(ControlCommand.Pause)
        socket.emit(load("latest", "p2"))
        socket.emit(ControlCommand.Forward)
        advanceUntilIdle()

        assertEquals(
            listOf("load:https://cdn/latest-p2.m3u8", "seek:15000"),
            playerController.calls,
        )
    }

    @Test
    fun failedLoadDiscardsItsPendingControls() = runTest(dispatcher) {
        var shouldFail = true
        api.sourceResponse = { vid, pid ->
            if (shouldFail) throw IllegalStateException("failure")
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()

        socket.emit(load("failed", "p1"))
        socket.emit(ControlCommand.Pause)
        advanceUntilIdle()
        shouldFail = false
        socket.emit(load("recovered", "p2"))
        advanceUntilIdle()

        assertEquals(listOf("load:https://cdn/recovered-p2.m3u8"), playerController.calls)
    }

    @Test
    fun showQrCancelsLoadAndDiscardsPendingControls() = runTest(dispatcher) {
        api.sourceResponse = { vid, pid ->
            withContext(NonCancellable) { delay(1_000) }
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()

        socket.emit(load("slow", "p1"))
        runCurrent()
        socket.emit(ControlCommand.Pause)
        socket.emit(ControlCommand.ShowQrCode)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("clear"), playerController.calls)
        assertEquals(SessionPage.Pairing, viewModel.uiState.value.page)
    }

    @Test
    fun playbackOverlayUsesCancelableFiveSecondTimer() = runTest(dispatcher) {
        startCollectors()

        socket.emit(ControlCommand.Play)
        runCurrent()
        assertTrue(viewModel.uiState.value.infoVisible)

        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(viewModel.uiState.value.infoVisible)

        socket.emit(ControlCommand.Pause)
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(viewModel.uiState.value.infoVisible)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(viewModel.uiState.value.infoVisible)
    }

    @Test
    fun mapsFullscreenAndToggleInfoOverlayRules() = runTest(dispatcher) {
        startCollectors()

        socket.emit(ControlCommand.FullscreenExit)
        runCurrent()
        assertTrue(viewModel.uiState.value.infoVisible)

        socket.emit(ControlCommand.Fullscreen)
        runCurrent()
        assertFalse(viewModel.uiState.value.infoVisible)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(viewModel.uiState.value.infoVisible)

        socket.emit(ControlCommand.ToggleInfo)
        runCurrent()
        assertTrue(viewModel.uiState.value.infoVisible)
        socket.emit(ControlCommand.ToggleInfo)
        runCurrent()
        assertFalse(viewModel.uiState.value.infoVisible)
    }

    @Test
    fun previousAndNextPreserveOriginalModeAndRespectEpisodeBoundaries() =
        runTest(dispatcher) {
            api.detailResponse = {
                successfulDetail(
                    "Series",
                    "p1" to "Episode 1",
                    "p2" to "Episode 2",
                    "p3" to "Episode 3",
                )
            }
            startCollectors()
            socket.emit(load("series", "p2", source = "source-a", mode = "private-mode"))
            advanceUntilIdle()
            playerController.clearCalls()

            socket.emit(ControlCommand.Previous)
            advanceUntilIdle()
            assertEquals("https://cdn/series-p1.m3u8", playerController.loadedUrl)
            assertEquals("private-mode", api.sourceCalls.last().mode)

            socket.emit(ControlCommand.Previous)
            advanceUntilIdle()
            assertEquals(1, playerController.calls.count { it.startsWith("load:") })

            socket.emit(ControlCommand.Next)
            advanceUntilIdle()
            socket.emit(ControlCommand.Next)
            advanceUntilIdle()
            assertEquals("https://cdn/series-p3.m3u8", playerController.loadedUrl)
            assertEquals("private-mode", api.sourceCalls.last().mode)

            socket.emit(ControlCommand.Next)
            advanceUntilIdle()
            assertEquals(3, playerController.calls.count { it.startsWith("load:") })
        }

    @Test
    fun acceptedAdjacentEpisodeResetsOverlayTimerButBoundaryHasNoSideEffect() =
        runTest(dispatcher) {
            api.detailResponse = {
                successfulDetail(
                    "Series",
                    "p1" to "Episode 1",
                    "p2" to "Episode 2",
                )
            }
            startCollectors()
            socket.emit(load("series", "p2"))
            advanceUntilIdle()
            socket.emit(ControlCommand.FullscreenExit)
            runCurrent()
            advanceTimeBy(4_900)
            runCurrent()

            socket.emit(ControlCommand.Previous)
            runCurrent()
            assertTrue(viewModel.uiState.value.infoVisible)
            advanceTimeBy(4_999)
            runCurrent()
            assertTrue(viewModel.uiState.value.infoVisible)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(viewModel.uiState.value.infoVisible)

            val beforeBoundary = viewModel.uiState.value
            playerController.clearCalls()
            socket.emit(ControlCommand.Previous)
            runCurrent()

            assertEquals(beforeBoundary, viewModel.uiState.value)
            assertTrue(playerController.calls.isEmpty())
        }

    @Test
    fun historyIgnoredHasNoSideEffects() = runTest(dispatcher) {
        startCollectors()
        val before = viewModel.uiState.value

        socket.emit(ControlCommand.HistoryIgnored)
        runCurrent()

        assertEquals(before, viewModel.uiState.value)
        assertTrue(playerController.calls.isEmpty())
        assertTrue(api.sourceCalls.isEmpty())
    }

    @Test
    fun onBackHidesInfoBeforeClearingPlayerAndReturningToPairing() = runTest(dispatcher) {
        startCollectors()
        socket.emit(load("video", "p1"))
        advanceUntilIdle()
        socket.emit(ControlCommand.FullscreenExit)
        runCurrent()
        playerController.clearCalls()

        viewModel.onBack()

        assertFalse(viewModel.uiState.value.infoVisible)
        assertEquals(SessionPage.Player, viewModel.uiState.value.page)
        assertTrue(playerController.calls.isEmpty())

        viewModel.onBack()

        assertEquals(listOf("clear"), playerController.calls)
        assertEquals(SessionPage.Pairing, viewModel.uiState.value.page)
    }

    @Test
    fun mapsSocketAndPlayerStatesIntoUi() = runTest(dispatcher) {
        startCollectors()

        socket.mutableStates.value = SocketConnectionState.Connected
        playerController.setState(
            PlayerState(
                isPlaying = true,
                positionMs = 12_000,
                durationMs = 30_000,
                error = "播放失败，请稍后重试",
            ),
        )
        runCurrent()

        assertEquals(SocketConnectionState.Connected, viewModel.uiState.value.connection)
        assertTrue(viewModel.uiState.value.isPlaying)
        assertEquals(12_000, viewModel.uiState.value.positionMs)
        assertEquals(30_000, viewModel.uiState.value.durationMs)
        assertEquals("播放失败，请稍后重试", viewModel.uiState.value.error)
        assertSame(playerController.player, viewModel.player)
    }

    @Test
    fun resolveFailureKeepsPairingAndUsesFixedErrorMessage() = runTest(dispatcher) {
        api.sourceResponse = { _, _ -> throw IllegalStateException("mode=do-not-leak") }
        startCollectors()

        socket.emit(load("video", "p1", mode = "do-not-leak"))
        advanceUntilIdle()

        assertEquals(SessionPage.Pairing, viewModel.uiState.value.page)
        assertFalse(viewModel.uiState.value.loading)
        assertEquals("视频加载失败，请重试", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.toString().contains("do-not-leak"))
        assertTrue(playerController.loadedUrls.isEmpty())
    }

    @Test
    fun activeResolutionErrorOverridesPlayerErrorUntilNextLoadSucceeds() =
        runTest(dispatcher) {
            var shouldFail = true
            api.sourceResponse = { vid, pid ->
                if (shouldFail) throw IllegalStateException("resolve failure")
                successfulSource("https://cdn/$vid-$pid.m3u8")
            }
            startCollectors()
            playerController.setState(PlayerState(error = "旧播放器错误"))
            runCurrent()

            socket.emit(load("failed", "p1"))
            advanceUntilIdle()
            playerController.setState(PlayerState(positionMs = 1, error = "更新后的旧播放器错误"))
            runCurrent()

            assertEquals("视频加载失败，请重试", viewModel.uiState.value.error)

            shouldFail = false
            socket.emit(load("recovered", "p2"))
            advanceUntilIdle()

            assertEquals("更新后的旧播放器错误", viewModel.uiState.value.error)
        }

    @Test
    fun onClearedClosesSocketAndReleasesPlayerOnlyOnce() = runTest(dispatcher) {
        startCollectors()

        invokeOnCleared(viewModel)
        invokeOnCleared(viewModel)

        assertEquals(1, socket.closeCalls)
        assertEquals(listOf("release"), playerController.calls)
    }

    @Test
    fun onClearedPreventsNonCooperativeResolveFromLoadingPlayer() = runTest(dispatcher) {
        api.sourceResponse = { vid, pid ->
            withContext(NonCancellable) { delay(1_000) }
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()
        socket.emit(load("late", "p1"))
        runCurrent()

        invokeOnCleared(viewModel)
        advanceUntilIdle()

        assertTrue(playerController.loadedUrls.isEmpty())
        assertEquals(SessionPage.Pairing, viewModel.uiState.value.page)
    }

    @Test
    fun onClearedPreventsNonCooperativeDetailsFromUpdatingUi() = runTest(dispatcher) {
        api.detailResponse = {
            withContext(NonCancellable) { delay(1_000) }
            successfulDetail("Late title", "p1" to "Late episode")
        }
        startCollectors()
        socket.emit(load("video", "p1"))
        runCurrent()
        val beforeClear = viewModel.uiState.value

        invokeOnCleared(viewModel)
        advanceUntilIdle()

        assertEquals(beforeClear, viewModel.uiState.value)
        assertEquals(listOf("https://cdn/video-p1.m3u8"), playerController.loadedUrls)
    }

    @Test
    fun factoryCreatesViewModelFromExplicitDependencies() {
        val factory = SessionViewModelFactory(
            roomId = "room-1",
            socketClient = socket,
            videoResolver = VideoResolver(api),
            playerController = playerController,
        )

        val created = factory.create(SessionViewModel::class.java)

        assertEquals("room-1", created.uiState.value.roomId)
    }

    private fun createViewModel(): SessionViewModel = SessionViewModel(
        roomId = "room-1",
        socketClient = socket,
        videoResolver = VideoResolver(api),
        playerController = playerController,
    )

    private fun load(
        vid: String,
        pid: String,
        source: String = "source",
        mode: String = "mode",
    ) = ControlCommand.LoadVideo(vid, pid, source, mode)

    private fun startCollectors() {
        dispatcher.scheduler.runCurrent()
    }

    private fun invokeOnCleared(target: SessionViewModel) {
        val method = SessionViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(target)
    }

    private class FakeSocketClient : SocketClient {
        val mutableStates = MutableStateFlow(SocketConnectionState.Connecting)
        private val mutableCommands = MutableSharedFlow<ControlCommand>(extraBufferCapacity = 32)
        val connectedRooms = mutableListOf<String>()
        var closeCalls = 0

        override val states: StateFlow<SocketConnectionState> = mutableStates
        override val commands: Flow<ControlCommand> = mutableCommands

        suspend fun emit(command: ControlCommand) {
            mutableCommands.emit(command)
        }

        override fun connect(roomId: String) {
            connectedRooms += roomId
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class FakeVideoApi : VideoApi {
        var sourceResponse: suspend (vid: String, pid: String) -> ApiResponse<VideoSourceDto> =
            { vid, pid -> successfulSource("https://cdn/$vid-$pid.m3u8") }
        var detailResponse: suspend (vid: String) -> ApiResponse<VideoDetailDto> =
            { successfulDetail(it) }
        val sourceCalls = mutableListOf<SourceCall>()

        override suspend fun source(
            vid: String,
            pid: String,
            source: String,
            proxy: Boolean,
            mode: String,
            client: String,
        ): ApiResponse<VideoSourceDto> {
            sourceCalls += SourceCall(vid, pid, source, mode)
            return sourceResponse(vid, pid)
        }

        override suspend fun detail(vid: String, source: String): ApiResponse<VideoDetailDto> =
            try {
                detailResponse(vid)
            } catch (exception: CancellationException) {
                throw exception
            }
    }

    private data class SourceCall(
        val vid: String,
        val pid: String,
        val source: String,
        val mode: String,
    )

    private companion object {
        fun successfulSource(url: String) = ApiResponse(
            code = 200,
            data = VideoSourceDto(url = url),
        )

        fun successfulDetail(title: String, vararg episodes: Pair<String, String>) = ApiResponse(
            code = 200,
            data = VideoDetailDto(
                name = title,
                links = episodes.map { (id, name) -> VideoLinkDto(id = id, name = name) },
            ),
        )
    }
}
