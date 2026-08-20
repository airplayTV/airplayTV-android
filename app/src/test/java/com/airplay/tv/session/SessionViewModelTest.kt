package com.airplay.tv.session

import com.airplay.tv.feature.player.ApiResponse
import com.airplay.tv.feature.player.FakePlayerController
import com.airplay.tv.feature.player.PlayerState
import com.airplay.tv.feature.player.RemoteControlAction
import com.airplay.tv.feature.player.ResolvedMediaType
import com.airplay.tv.feature.player.VideoApi
import com.airplay.tv.feature.player.VideoDetailDto
import com.airplay.tv.feature.player.VideoLinkDto
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.feature.player.VideoSourceDto
import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.PlaybackHistoryAck
import com.airplay.tv.protocol.PlaybackHistoryMessage
import com.airplay.tv.protocol.ReceivedControlCommand
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
        viewModel.onForegroundChanged(true)
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
    fun validLoadImmediatelyShowsPlayerLoadingBeforeResolveCompletes() = runTest(dispatcher) {
        api.sourceResponse = { vid, pid ->
            delay(1_000)
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()

        socket.emit(load("slow", "p1"))
        runCurrent()

        assertEquals(SessionPage.Player, viewModel.uiState.value.page)
        assertTrue(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.error)
        assertTrue(playerController.loadedUrls.isEmpty())
    }

    @Test
    fun backgroundKeepsOnlyLatestLoadUntilForeground() = runTest(dispatcher) {
        startCollectors()
        viewModel.onForegroundChanged(false)
        playerController.clearCalls()

        socket.emit(load("old", "p1"))
        socket.emit(load("latest", "p2"))
        runCurrent()

        assertEquals(SessionPage.Player, viewModel.uiState.value.page)
        assertTrue(viewModel.uiState.value.loading)
        assertTrue(api.sourceCalls.isEmpty())
        assertTrue(playerController.calls.isEmpty())

        viewModel.onForegroundChanged(true)
        advanceUntilIdle()

        assertEquals(listOf("latest"), api.sourceCalls.map { it.vid })
        assertEquals("https://cdn/latest-p2.m3u8", playerController.loadedUrl)
    }

    @Test
    fun backgroundPausesAndForegroundDoesNotResumeWithoutExplicitPlay() = runTest(dispatcher) {
        startCollectors()
        socket.emit(load("video", "p1"))
        advanceUntilIdle()
        playerController.clearCalls()

        viewModel.onForegroundChanged(false)
        viewModel.onForegroundChanged(true)

        assertEquals(listOf("pause"), playerController.calls)
    }

    @Test
    fun explicitBackgroundPlayRunsOnlyAfterForeground() = runTest(dispatcher) {
        startCollectors()
        socket.emit(load("video", "p1"))
        advanceUntilIdle()
        viewModel.onForegroundChanged(false)
        playerController.clearCalls()

        socket.emit(ControlCommand.Play)
        runCurrent()
        assertTrue(playerController.calls.isEmpty())

        viewModel.onForegroundChanged(true)

        assertEquals(listOf("play"), playerController.calls)
    }

    @Test
    fun latestBackgroundPauseCancelsDeferredPlay() = runTest(dispatcher) {
        startCollectors()
        viewModel.onForegroundChanged(false)
        playerController.clearCalls()

        socket.emit(ControlCommand.Play)
        socket.emit(ControlCommand.Pause)
        runCurrent()
        viewModel.onForegroundChanged(true)

        assertTrue(playerController.calls.isEmpty())
    }

    @Test
    fun backgroundLoadFollowedByPauseLoadsPausedWhenForegrounded() = runTest(dispatcher) {
        startCollectors()
        viewModel.onForegroundChanged(false)
        playerController.clearCalls()

        socket.emit(load("video", "p1"))
        socket.emit(ControlCommand.Pause)
        runCurrent()

        assertTrue(api.sourceCalls.isEmpty())
        assertTrue(playerController.calls.isEmpty())

        viewModel.onForegroundChanged(true)
        advanceUntilIdle()

        assertEquals(
            listOf("load:https://cdn/video-p1.m3u8", "pause"),
            playerController.calls,
        )
    }

    @Test
    fun pauseDuringForegroundResolveSurvivesStopStart() = runTest(dispatcher) {
        api.sourceResponse = { vid, pid ->
            delay(1_000)
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()

        socket.emit(load("video", "p1"))
        socket.emit(ControlCommand.Pause)
        runCurrent()
        viewModel.onForegroundChanged(false)
        playerController.clearCalls()

        viewModel.onForegroundChanged(true)
        advanceUntilIdle()

        assertEquals(
            listOf("load:https://cdn/video-p1.m3u8", "pause"),
            playerController.calls,
        )
    }

    @Test
    fun backgroundInvalidatesNonCooperativeResolveAndRestartsLatestLoadOnForeground() =
        runTest(dispatcher) {
            var sourceAttempts = 0
            api.sourceResponse = { vid, pid ->
                sourceAttempts += 1
                if (sourceAttempts == 1) withContext(NonCancellable) { delay(1_000) }
                successfulSource("https://cdn/$vid-$pid.m3u8")
            }
            startCollectors()

            socket.emit(load("video", "p1"))
            runCurrent()
            viewModel.onForegroundChanged(false)
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(playerController.loadedUrls.isEmpty())
            assertEquals(1, sourceAttempts)

            viewModel.onForegroundChanged(true)
            advanceUntilIdle()

            assertEquals(2, sourceAttempts)
            assertEquals(listOf("https://cdn/video-p1.m3u8"), playerController.loadedUrls)
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
    fun backgroundDuringDetailsRetriesDetailsWithoutResumingPlayback() = runTest(dispatcher) {
        var detailAttempts = 0
        api.detailResponse = {
            detailAttempts += 1
            if (detailAttempts == 1) {
                withContext(NonCancellable) { delay(1_000) }
            }
            successfulDetail("Series", "p1" to "Episode 1", "p2" to "Episode 2")
        }
        startCollectors()

        socket.emit(load("series", "p1"))
        runCurrent()
        assertEquals(1, detailAttempts)
        assertEquals(listOf("https://cdn/series-p1.m3u8"), playerController.loadedUrls)

        viewModel.onForegroundChanged(false)
        playerController.clearCalls()
        advanceTimeBy(1_000)
        runCurrent()

        viewModel.onForegroundChanged(true)
        advanceUntilIdle()

        assertEquals(2, detailAttempts)
        assertEquals(listOf("https://cdn/series-p1.m3u8"), playerController.loadedUrls)
        assertTrue(playerController.calls.isEmpty())
        assertEquals("Episode 1", viewModel.uiState.value.episodeName)

        socket.emit(ControlCommand.Next)
        advanceUntilIdle()

        assertEquals("https://cdn/series-p2.m3u8", playerController.loadedUrl)
    }

    @Test
    fun opaqueHlsTypeReachesPlayerControllerAcrossSession() = runTest(dispatcher) {
        val opaqueUrl = "https://cdn.example/media-token"
        api.sourceResponse = { _, _ -> successfulSource(opaqueUrl, type = " HLS ") }
        startCollectors()

        socket.emit(load("video", "p1"))
        advanceUntilIdle()

        assertEquals(listOf(opaqueUrl), playerController.loadedUrls)
        assertEquals(listOf(ResolvedMediaType.HLS), playerController.loadedMediaTypes)
    }

    @Test
    fun mapsPlaybackVolumeMuteAndQrCommandsWithoutClearingPlayback() = runTest(dispatcher) {
        startCollectors()
        socket.emit(load("video", "p1"))
        advanceUntilIdle()
        playerController.clearCalls()

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
        playerController.clearCalls()

        socket.emit(ControlCommand.ShowQrCode)
        runCurrent()

        assertTrue(viewModel.uiState.value.qrVisible)
        assertEquals(SessionPage.Player, viewModel.uiState.value.page)
        assertTrue(playerController.calls.isEmpty())
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
    fun pendingControlReplayDoesNotOverrideLaterFullscreenCommand() = runTest(dispatcher) {
        api.sourceResponse = { vid, pid ->
            delay(1_000)
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()

        socket.emit(load("slow", "p1"))
        runCurrent()
        socket.emit(ControlCommand.Pause)
        socket.emit(ControlCommand.Fullscreen)
        runCurrent()
        assertFalse(viewModel.uiState.value.infoVisible)

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("load:https://cdn/slow-p1.m3u8", "pause"), playerController.calls)
        assertFalse(viewModel.uiState.value.infoVisible)
    }

    @Test
    fun pendingControlReplayDoesNotOverrideLaterToggleInfoCommand() = runTest(dispatcher) {
        api.sourceResponse = { vid, pid ->
            delay(1_000)
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()

        socket.emit(load("slow", "p1"))
        runCurrent()
        socket.emit(ControlCommand.Pause)
        socket.emit(ControlCommand.ToggleInfo)
        runCurrent()
        assertFalse(viewModel.uiState.value.infoVisible)

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("load:https://cdn/slow-p1.m3u8", "pause"), playerController.calls)
        assertFalse(viewModel.uiState.value.infoVisible)
    }

    @Test
    fun pendingControlReplayDoesNotExtendOverlayTimer() = runTest(dispatcher) {
        api.sourceResponse = { vid, pid ->
            delay(4_900)
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()

        socket.emit(load("slow", "p1"))
        runCurrent()
        socket.emit(ControlCommand.Pause)
        runCurrent()
        advanceTimeBy(4_900)
        runCurrent()
        assertTrue(viewModel.uiState.value.infoVisible)

        advanceTimeBy(100)
        runCurrent()

        assertFalse(viewModel.uiState.value.infoVisible)
        assertEquals(listOf("load:https://cdn/slow-p1.m3u8", "pause"), playerController.calls)
    }

    @Test
    fun pendingControlBufferKeepsOnlyLatestSixtyFourCommands() = runTest(dispatcher) {
        api.sourceResponse = { vid, pid ->
            delay(1_000)
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()

        socket.emit(load("slow", "p1"))
        runCurrent()
        repeat(64) { socket.emit(ControlCommand.Pause) }
        socket.emit(ControlCommand.Forward)
        advanceUntilIdle()

        assertEquals(65, playerController.calls.size)
        assertEquals("load:https://cdn/slow-p1.m3u8", playerController.calls.first())
        assertEquals(63, playerController.calls.count { it == "pause" })
        assertEquals("seek:15000", playerController.calls.last())
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
    fun showQrDuringLoadPreservesLoadAndPendingControls() = runTest(dispatcher) {
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

        assertEquals(listOf("load:https://cdn/slow-p1.m3u8", "pause"), playerController.calls)
        assertEquals(SessionPage.Player, viewModel.uiState.value.page)
        assertTrue(viewModel.uiState.value.qrVisible)
    }

    @Test
    fun remoteControlsUseSamePlayerPathAsSocketControls() = runTest(dispatcher) {
        startCollectors()
        socket.emit(load("video", "p1"))
        advanceUntilIdle()
        playerController.clearCalls()

        viewModel.onRemoteControl(RemoteControlAction.Play)
        viewModel.onRemoteControl(RemoteControlAction.Pause)
        viewModel.onRemoteControl(RemoteControlAction.Forward)
        viewModel.onRemoteControl(RemoteControlAction.Back)
        playerController.setState(PlayerState(isPlaying = false))
        runCurrent()
        viewModel.onRemoteControl(RemoteControlAction.TogglePlayPause)

        assertEquals(
            listOf("play", "pause", "seek:15000", "seek:-15000", "play"),
            playerController.calls,
        )
    }

    @Test
    fun backClosesQrBeforeInfoOrPlayer() = runTest(dispatcher) {
        startCollectors()
        socket.emit(load("video", "p1"))
        advanceUntilIdle()
        socket.emit(ControlCommand.ShowQrCode)
        runCurrent()
        playerController.clearCalls()

        viewModel.onBack()

        assertFalse(viewModel.uiState.value.qrVisible)
        assertEquals(SessionPage.Player, viewModel.uiState.value.page)
        assertTrue(playerController.calls.isEmpty())
    }

    @Test
    fun acceptedResolutionPublishesAndClearRemovesPlaybackUrl() = runTest(dispatcher) {
        startCollectors()
        socket.emit(load("video", "p1"))
        advanceUntilIdle()

        assertEquals("https://cdn/video-p1.m3u8", viewModel.uiState.value.playbackUrl)

        viewModel.onBack()

        assertEquals(SessionPage.Pairing, viewModel.uiState.value.page)
        assertEquals("", viewModel.uiState.value.playbackUrl)
    }

    @Test
    fun diagnosticLogHidesFiveSecondsAfterLatestEvent() = runTest(dispatcher) {
        startCollectors()
        socket.emit(ControlCommand.Play)
        runCurrent()
        assertTrue(viewModel.uiState.value.diagnosticVisible)

        advanceTimeBy(4_999)
        runCurrent()
        socket.emit(ControlCommand.Pause)
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(viewModel.uiState.value.diagnosticVisible)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(viewModel.uiState.value.diagnosticVisible)
        assertTrue(viewModel.uiState.value.diagnosticLogs.isNotEmpty())
    }

    @Test
    fun diagnosticAndPlayerInfoTimersDoNotCancelEachOther() = runTest(dispatcher) {
        startCollectors()
        socket.emit(ControlCommand.Play)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()

        socket.mutableStates.value = SocketConnectionState.Reconnecting
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(viewModel.uiState.value.infoVisible)
        assertTrue(viewModel.uiState.value.diagnosticVisible)
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
    fun successfulLoadShowsOverlayForExactlyFiveSeconds() = runTest(dispatcher) {
        startCollectors()

        socket.emit(load("video", "p1"))
        runCurrent()
        assertTrue(viewModel.uiState.value.infoVisible)

        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(viewModel.uiState.value.infoVisible)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(viewModel.uiState.value.infoVisible)
    }

    @Test
    fun everySuccessfulLoadShowsDefaultOverlayWhenNoLaterOverlayCommandArrives() =
        runTest(dispatcher) {
            startCollectors()
            socket.emit(load("first", "p1"))
            advanceUntilIdle()
            advanceTimeBy(5_000)
            runCurrent()
            assertFalse(viewModel.uiState.value.infoVisible)

            socket.emit(load("second", "p2"))
            runCurrent()

            assertTrue(viewModel.uiState.value.infoVisible)
        }

    @Test
    fun fullscreenDuringResolveSuppressesSuccessfulLoadDefaultOverlay() = runTest(dispatcher) {
        api.sourceResponse = { vid, pid ->
            delay(1_000)
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }
        startCollectors()

        socket.emit(load("video", "p1"))
        runCurrent()
        socket.emit(ControlCommand.Fullscreen)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(viewModel.uiState.value.infoVisible)
    }

    @Test
    fun toggleInfoDuringResolveIsNotOverwrittenOrExtendedByLoadSuccess() =
        runTest(dispatcher) {
            api.sourceResponse = { vid, pid ->
                delay(1_000)
                successfulSource("https://cdn/$vid-$pid.m3u8")
            }
            startCollectors()

            socket.emit(load("video", "p1"))
            runCurrent()
            socket.emit(ControlCommand.ToggleInfo)
            runCurrent()
            assertTrue(viewModel.uiState.value.infoVisible)

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
    fun playbackEndAutomaticallyLoadsNextEpisodeAndPreservesSeriesContext() =
        runTest(dispatcher) {
            api.detailResponse = {
                successfulDetail(
                    "Series title",
                    "p1" to "Episode 1",
                    "p2" to "Episode 2",
                    "p3" to "Episode 3",
                )
            }
            startCollectors()
            socket.emit(load("series", "p1", source = "source-a", mode = "private-mode"))
            advanceUntilIdle()
            api.sourceCalls.clear()

            playerController.emitEnded()
            advanceUntilIdle()

            assertEquals(listOf("p2"), api.sourceCalls.map { it.pid })
            assertEquals("source-a", api.sourceCalls.single().source)
            assertEquals("private-mode", api.sourceCalls.single().mode)
            assertEquals("Series title", viewModel.uiState.value.title)
            assertEquals("Episode 2", viewModel.uiState.value.episodeName)

            playerController.emitEnded()
            advanceUntilIdle()
            assertEquals(listOf("p2", "p3"), api.sourceCalls.map { it.pid })
            assertEquals("private-mode", api.sourceCalls.last().mode)
        }

    @Test
    fun playbackEndOnFinalEpisodeDoesNotLoopOrResolveAnotherSource() = runTest(dispatcher) {
        api.detailResponse = {
            successfulDetail("Series", "p1" to "Episode 1", "p2" to "Episode 2")
        }
        startCollectors()
        socket.emit(load("series", "p2", mode = "private-mode"))
        advanceUntilIdle()
        api.sourceCalls.clear()

        playerController.emitEnded()
        advanceUntilIdle()

        assertTrue(api.sourceCalls.isEmpty())
        assertEquals("https://cdn/series-p2.m3u8", playerController.loadedUrl)
        assertEquals("已是最后一集", viewModel.uiState.value.diagnosticLogs.last().message)
    }

    @Test
    fun playbackEndWithUnavailableEpisodeListDoesNotClaimFinalEpisode() = runTest(dispatcher) {
        api.detailResponse = { throw IllegalStateException("temporary detail failure") }
        startCollectors()
        socket.emit(load("series", "p1"))
        advanceUntilIdle()

        playerController.emitEnded()
        advanceUntilIdle()

        assertEquals("剧集列表不可用", viewModel.uiState.value.diagnosticLogs.last().message)
        assertFalse(viewModel.uiState.value.toString().contains("temporary detail failure"))
    }

    @Test
    fun terminalPlaybackErrorWritesOneSafeDiagnosticPerCommittedMedia() = runTest(dispatcher) {
        startCollectors()
        socket.emit(load("series", "p1"))
        advanceUntilIdle()

        playerController.emitError()
        playerController.emitError()
        advanceUntilIdle()

        assertEquals(
            1,
            viewModel.uiState.value.diagnosticLogs.count {
                it.stage == "ERR" && it.message == "播放器播放失败"
            },
        )
    }

    @Test
    fun duplicatePlaybackEndDuringReplacementStartsOnlyOneNextLoad() = runTest(dispatcher) {
        api.detailResponse = {
            successfulDetail("Series", "p1" to "Episode 1", "p2" to "Episode 2")
        }
        startCollectors()
        socket.emit(load("series", "p1"))
        advanceUntilIdle()
        api.sourceCalls.clear()
        api.sourceResponse = { vid, pid ->
            delay(1_000)
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }

        playerController.emitEnded()
        playerController.emitEnded()
        runCurrent()

        assertEquals(listOf("p2"), api.sourceCalls.map { it.pid })
        advanceUntilIdle()
        assertEquals(listOf("p2"), api.sourceCalls.map { it.pid })
    }

    @Test
    fun playbackEndWaitsForMatchingDelayedDetailsThenAdvancesOnce() = runTest(dispatcher) {
        api.detailResponse = {
            delay(1_000)
            successfulDetail("Series", "p1" to "Episode 1", "p2" to "Episode 2")
        }
        startCollectors()
        socket.emit(load("series", "p1", mode = "private-mode"))
        runCurrent()
        assertEquals("https://cdn/series-p1.m3u8", playerController.loadedUrl)
        api.sourceCalls.clear()

        playerController.emitEnded()
        playerController.emitEnded()
        runCurrent()
        assertTrue(api.sourceCalls.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf("p2"), api.sourceCalls.map { it.pid })
        assertEquals("private-mode", api.sourceCalls.single().mode)
    }

    @Test
    fun manualReplacementCancelsPendingAutoAdvanceFromDelayedDetails() = runTest(dispatcher) {
        api.detailResponse = { vid ->
            if (vid == "series") {
                withContext(NonCancellable) {
                    delay(1_000)
                    successfulDetail("Series", "p1" to "Episode 1", "p2" to "Episode 2")
                }
            } else {
                successfulDetail("Replacement", "q1" to "Replacement 1")
            }
        }
        startCollectors()
        socket.emit(load("series", "p1"))
        runCurrent()
        playerController.emitEnded()
        runCurrent()

        socket.emit(load("replacement", "q1", mode = "new-mode"))
        advanceUntilIdle()

        assertEquals(
            listOf("series" to "p1", "replacement" to "q1"),
            api.sourceCalls.map { it.vid to it.pid },
        )
        assertEquals("https://cdn/replacement-q1.m3u8", playerController.loadedUrl)
    }

    @Test
    fun nextCommandCancelsPendingAutoAdvanceFromDelayedDetails() = runTest(dispatcher) {
        api.detailResponse = {
            delay(1_000)
            successfulDetail("Series", "p1" to "Episode 1", "p2" to "Episode 2")
        }
        startCollectors()
        socket.emit(load("series", "p1"))
        runCurrent()
        playerController.emitEnded()
        runCurrent()

        socket.emit(ControlCommand.Next)
        advanceUntilIdle()

        assertEquals(listOf("p1"), api.sourceCalls.map { it.pid })
        assertEquals("https://cdn/series-p1.m3u8", playerController.loadedUrl)
    }

    @Test
    fun failedAutomaticNextRollsBackAndDuplicateEndDoesNotRetry() = runTest(dispatcher) {
        api.detailResponse = {
            successfulDetail("Series", "p1" to "Episode 1", "p2" to "Episode 2")
        }
        startCollectors()
        socket.emit(load("series", "p1"))
        advanceUntilIdle()
        api.sourceCalls.clear()
        api.sourceResponse = { _, _ -> throw IllegalStateException("secret failure") }

        playerController.emitEnded()
        advanceUntilIdle()
        playerController.emitEnded()
        advanceUntilIdle()

        assertEquals(listOf("p2"), api.sourceCalls.map { it.pid })
        assertEquals("Episode 1", viewModel.uiState.value.episodeName)
        assertEquals("下一集加载失败", viewModel.uiState.value.diagnosticLogs.last().message)
        assertFalse(viewModel.uiState.value.toString().contains("secret failure"))
    }

    @Test
    fun playbackEndOutsidePlayerPageDoesNotResolveSource() = runTest(dispatcher) {
        startCollectors()

        playerController.emitEnded()
        advanceUntilIdle()

        assertTrue(api.sourceCalls.isEmpty())
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
    fun consecutiveNextCommandsAdvanceAcceptedCursorWithoutWaitingForResolve() =
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
            socket.emit(load("series", "p1", mode = "private-mode"))
            advanceUntilIdle()
            api.sourceCalls.clear()
            api.sourceResponse = { vid, pid ->
                delay(1_000)
                successfulSource("https://cdn/$vid-$pid.m3u8")
            }

            socket.emit(ControlCommand.Next)
            runCurrent()
            socket.emit(ControlCommand.Next)
            runCurrent()
            advanceUntilIdle()

            assertEquals(listOf("p2", "p3"), api.sourceCalls.map { it.pid })
            assertEquals("private-mode", api.sourceCalls.last().mode)
            assertEquals("https://cdn/series-p3.m3u8", playerController.loadedUrl)
            assertEquals("Episode 3", viewModel.uiState.value.episodeName)
        }

    @Test
    fun failedAdjacentLoadRollsCursorBackToLastCommittedEpisode() = runTest(dispatcher) {
        api.detailResponse = {
            successfulDetail(
                "Series",
                "p1" to "Episode 1",
                "p2" to "Episode 2",
                "p3" to "Episode 3",
            )
        }
        startCollectors()
        socket.emit(load("series", "p1"))
        advanceUntilIdle()
        api.sourceCalls.clear()
        var p2Attempts = 0
        api.sourceResponse = { vid, pid ->
            if (pid == "p2" && p2Attempts++ == 0) throw IllegalStateException("failure")
            successfulSource("https://cdn/$vid-$pid.m3u8")
        }

        socket.emit(ControlCommand.Next)
        advanceUntilIdle()
        socket.emit(ControlCommand.Next)
        advanceUntilIdle()

        assertEquals(listOf("p2", "p2"), api.sourceCalls.map { it.pid })
        assertEquals("https://cdn/series-p2.m3u8", playerController.loadedUrl)
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

            val afterBoundary = viewModel.uiState.value
            assertEquals(
                beforeBoundary,
                afterBoundary.copy(
                    diagnosticLogs = beforeBoundary.diagnosticLogs,
                    diagnosticVisible = beforeBoundary.diagnosticVisible,
                ),
            )
            assertEquals("上一集", afterBoundary.diagnosticLogs.last().message)
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
    fun controllerConnectionTracksAcceptedCommandsAndSocketLifecycle() = runTest(dispatcher) {
        startCollectors()

        assertFalse(viewModel.uiState.value.controllerConnected)
        socket.mutableStates.value = SocketConnectionState.Connected
        runCurrent()
        assertFalse(viewModel.uiState.value.controllerConnected)

        socket.emit(ControlCommand.HistoryIgnored)
        runCurrent()
        assertFalse(viewModel.uiState.value.controllerConnected)

        socket.emit(ControlCommand.ControllerPaired)
        runCurrent()
        assertTrue(viewModel.uiState.value.controllerConnected)
        assertTrue(playerController.calls.isEmpty())

        socket.mutableStates.value = SocketConnectionState.Reconnecting
        runCurrent()
        assertFalse(viewModel.uiState.value.controllerConnected)
        socket.mutableStates.value = SocketConnectionState.Connecting
        runCurrent()
        socket.mutableStates.value = SocketConnectionState.Connected
        runCurrent()
        assertFalse(viewModel.uiState.value.controllerConnected)

        socket.emit(ControlCommand.Play)
        runCurrent()
        assertTrue(viewModel.uiState.value.controllerConnected)

        socket.mutableStates.value = SocketConnectionState.Closed
        runCurrent()
        assertFalse(viewModel.uiState.value.controllerConnected)
    }

    @Test
    fun repeatedPairHeartbeatsAreIdempotentAndDoNotControlPlayback() = runTest(dispatcher) {
        startCollectors()

        socket.emit(ControlCommand.ControllerPaired)
        socket.emit(ControlCommand.ControllerPaired)
        runCurrent()

        assertTrue(viewModel.uiState.value.controllerConnected)
        assertEquals("手机控制器已关联", viewModel.uiState.value.diagnosticLogs.last().message)
        assertEquals(
            1,
            viewModel.uiState.value.diagnosticLogs.count { it.message == "手机控制器已关联" },
        )

        socket.emit(ControlCommand.ControllerUnpaired)
        socket.emit(ControlCommand.ControllerPaired)
        runCurrent()

        assertEquals(
            2,
            viewModel.uiState.value.diagnosticLogs.count { it.message == "手机控制器已关联" },
        )
        assertTrue(playerController.calls.isEmpty())
    }

    @Test
    fun playbackCommandDoesNotConsumeFirstAssociationLogEdge() = runTest(dispatcher) {
        startCollectors()

        socket.emit(ControlCommand.Play)
        socket.emit(ControlCommand.ControllerPaired)
        socket.emit(ControlCommand.ControllerPaired)
        runCurrent()

        assertEquals(
            1,
            viewModel.uiState.value.diagnosticLogs.count { it.message == "手机控制器已关联" },
        )
    }

    @Test
    fun unpairThenPlaybackCommandStillAllowsNewAssociationLogEdge() = runTest(dispatcher) {
        startCollectors()
        socket.emit(ControlCommand.ControllerPaired)
        socket.emit(ControlCommand.ControllerUnpaired)
        socket.emit(ControlCommand.Play)
        socket.emit(ControlCommand.ControllerPaired)
        runCurrent()

        assertEquals(
            2,
            viewModel.uiState.value.diagnosticLogs.count { it.message == "手机控制器已关联" },
        )
    }

    @Test
    fun unpairOnlyClearsControllerAssociationAndPreservesPlaybackState() = runTest(dispatcher) {
        startCollectors()
        socket.emit(load("video", "p1"))
        advanceUntilIdle()
        playerController.setState(
            PlayerState(isPlaying = true, positionMs = 12_000, durationMs = 30_000),
        )
        runCurrent()
        socket.emit(ControlCommand.ControllerPaired)
        runCurrent()
        playerController.clearCalls()
        val before = viewModel.uiState.value

        socket.emit(ControlCommand.ControllerUnpaired)
        runCurrent()

        val after = viewModel.uiState.value
        assertFalse(after.controllerConnected)
        assertEquals("手机控制器已断开", after.diagnosticLogs.last().message)
        assertEquals(
            before,
            after.copy(
                controllerConnected = before.controllerConnected,
                diagnosticLogs = before.diagnosticLogs,
                diagnosticVisible = before.diagnosticVisible,
            ),
        )
        assertTrue(playerController.calls.isEmpty())
    }

    @Test
    fun newConnectionGenerationClearsAssociationAndRejectsQueuedOldCommands() = runTest(dispatcher) {
        socket.mutableConnectionGeneration.value = 1L
        startCollectors()
        socket.mutableStates.value = SocketConnectionState.Connected
        runCurrent()
        socket.emit(ControlCommand.ControllerPaired, generation = 1L)
        runCurrent()
        assertTrue(viewModel.uiState.value.controllerConnected)

        socket.mutableConnectionGeneration.value = 2L
        socket.mutableStates.value = SocketConnectionState.Reconnecting
        socket.mutableStates.value = SocketConnectionState.Connecting
        socket.mutableStates.value = SocketConnectionState.Connected
        runCurrent()
        assertFalse(viewModel.uiState.value.controllerConnected)

        socket.emit(ControlCommand.Play, generation = 1L)
        runCurrent()
        assertFalse(viewModel.uiState.value.controllerConnected)
        assertTrue(playerController.calls.isEmpty())

        socket.emit(ControlCommand.ControllerPaired, generation = 2L)
        runCurrent()
        assertTrue(viewModel.uiState.value.controllerConnected)
        assertEquals(
            2,
            viewModel.uiState.value.diagnosticLogs.count { it.message == "手机控制器已关联" },
        )
    }

    @Test
    fun resolveFailureStaysOnPlayerAndUsesFixedErrorMessage() = runTest(dispatcher) {
        api.sourceResponse = { _, _ -> throw IllegalStateException("mode=do-not-leak") }
        startCollectors()

        socket.emit(load("video", "p1", mode = "do-not-leak"))
        advanceUntilIdle()

        assertEquals(SessionPage.Player, viewModel.uiState.value.page)
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
        assertEquals(SessionPage.Player, viewModel.uiState.value.page)
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
        val mutableConnectionGeneration = MutableStateFlow(0L)
        private val mutableCommands = MutableSharedFlow<ReceivedControlCommand>(extraBufferCapacity = 32)
        private val mutablePlaybackHistoryAcks =
            MutableSharedFlow<PlaybackHistoryAck>(extraBufferCapacity = 32)
        val connectedRooms = mutableListOf<String>()
        val playbackHistorySendAttempts = mutableListOf<PlaybackHistoryMessage>()
        var playbackHistorySendResult = true
        var closeCalls = 0

        override val states: StateFlow<SocketConnectionState> = mutableStates
        override val connectionGeneration: StateFlow<Long> = mutableConnectionGeneration
        override val commands: Flow<ReceivedControlCommand> = mutableCommands
        override val playbackHistoryAcks: Flow<PlaybackHistoryAck> = mutablePlaybackHistoryAcks

        suspend fun emit(
            command: ControlCommand,
            generation: Long = mutableConnectionGeneration.value,
        ) {
            mutableCommands.emit(ReceivedControlCommand(command, generation))
        }

        suspend fun emitPlaybackHistoryAck(ack: PlaybackHistoryAck) {
            mutablePlaybackHistoryAcks.emit(ack)
        }

        override fun connect(roomId: String) {
            connectedRooms += roomId
        }

        override fun sendPlaybackHistory(message: PlaybackHistoryMessage): Boolean {
            playbackHistorySendAttempts += message
            return playbackHistorySendResult
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

        override suspend fun detail(
            vid: String,
            source: String,
            mode: String,
            client: String,
        ): ApiResponse<VideoDetailDto> = try {
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
        fun successfulSource(url: String, type: String? = null) = ApiResponse(
            code = 200,
            data = VideoSourceDto(url = url, type = type),
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
