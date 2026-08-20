package com.airplay.tv

import android.content.Context
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airplay.tv.feature.history.PlaybackProgressRepository
import com.airplay.tv.feature.history.PlaybackRecord
import com.airplay.tv.feature.player.ApiResponse
import com.airplay.tv.feature.player.Media3PlayerController
import com.airplay.tv.feature.player.PlayerController
import com.airplay.tv.feature.player.ResolvedMediaType
import com.airplay.tv.feature.player.VideoApi
import com.airplay.tv.feature.player.VideoDetailDto
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.feature.player.VideoSourceDto
import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.PlaybackHistoryAck
import com.airplay.tv.protocol.PlaybackHistoryMessage
import com.airplay.tv.protocol.ReceivedControlCommand
import com.airplay.tv.protocol.SocketClient
import com.airplay.tv.protocol.SocketConnectionState
import com.airplay.tv.session.SessionPage
import com.airplay.tv.session.SessionViewModel
import com.airplay.tv.session.SessionViewModelFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLifecycleTest {
    @Test
    fun keepScreenOnStateAddsAndClearsTheRealWindowFlag() {
        val application = ApplicationProvider.getApplicationContext<AirPlayTVApp>()
        val socket = RecordingSocketClient()
        lateinit var playerController: RecordingPlayerController
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            playerController = RecordingPlayerController(application)
        }
        application.sessionViewModelFactoryOverride = SessionViewModelFactory(
            roomId = "0123456789abcdef0123456789abcdef",
            socketClient = socket,
            videoResolver = VideoResolver(NoOpVideoApi),
            playerController = playerController,
            playbackProgressRepository = NoOpPlaybackProgressRepository,
        )

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var viewModel: SessionViewModel
                lateinit var beforeRecreate: MainActivity
                scenario.onActivity { activity ->
                    assertFalse(activity.hasKeepScreenOnFlag())
                    viewModel = ViewModelProvider(activity)[SessionViewModel::class.java]
                    beforeRecreate = activity
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                socket.emit(
                    ReceivedControlCommand(
                        command = ControlCommand.LoadVideo(
                            vid = "video",
                            pid = "p1",
                            source = "source",
                            mode = "",
                        ),
                        generation = 0L,
                    ),
                )
                waitUntil {
                    viewModel.uiState.value.page == SessionPage.Player &&
                        viewModel.uiState.value.keepScreenOn
                }
                scenario.waitForKeepScreenOn(expected = true)

                scenario.moveToState(Lifecycle.State.CREATED)
                waitForKeepScreenOn(beforeRecreate, expected = false)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    beforeRecreate.invokeKeepScreenOnSideEffect(desired = true)
                }
                waitForKeepScreenOn(beforeRecreate, expected = false)

                scenario.moveToState(Lifecycle.State.RESUMED)
                waitUntil { viewModel.uiState.value.keepScreenOn }
                scenario.waitForKeepScreenOn(expected = true)

                scenario.recreate()
                scenario.onActivity { activity ->
                    assertNotSame(beforeRecreate, activity)
                    assertSame(viewModel, ViewModelProvider(activity)[SessionViewModel::class.java])
                }
                scenario.waitForKeepScreenOn(expected = true)

                scenario.onActivity {
                    viewModel.onBack()
                }
                scenario.waitForKeepScreenOn(expected = false)

                scenario.onActivity { activity ->
                    assertFalse(activity.hasKeepScreenOnFlag())
                }
            }
        } finally {
            application.sessionViewModelFactoryOverride = null
        }
    }

    @Test
    fun recreationRetainsSessionAndLifecycleGatePausesWithoutResuming() {
        val application = ApplicationProvider.getApplicationContext<AirPlayTVApp>()
        val socket = RecordingSocketClient()
        lateinit var playerController: RecordingPlayerController
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            playerController = RecordingPlayerController(application)
        }
        application.sessionViewModelFactoryOverride = SessionViewModelFactory(
            roomId = "0123456789abcdef0123456789abcdef",
            socketClient = socket,
            videoResolver = VideoResolver(NoOpVideoApi),
            playerController = playerController,
            playbackProgressRepository = NoOpPlaybackProgressRepository,
        )

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var before: SessionViewModel
                scenario.onActivity { activity ->
                    before = ViewModelProvider(activity)[SessionViewModel::class.java]
                }

                scenario.moveToState(Lifecycle.State.CREATED)
                assertEquals(listOf("pause"), playerController.calls)

                playerController.calls.clear()
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue(playerController.calls.isEmpty())

                scenario.recreate()

                scenario.onActivity { activity ->
                    val after = ViewModelProvider(activity)[SessionViewModel::class.java]
                    assertSame(before, after)
                    assertEquals("0123456789abcdef0123456789abcdef", after.uiState.value.roomId)
                    assertEquals(listOf("pause"), playerController.calls)
                    assertEquals(0, socket.closeCalls)
                    assertEquals(0, playerController.releaseCalls)
                }
            }
        } finally {
            application.sessionViewModelFactoryOverride = null
        }
    }

    private class RecordingSocketClient : SocketClient {
        private val mutableStates = MutableStateFlow(SocketConnectionState.Connecting)
        private val mutableCommands =
            MutableSharedFlow<ReceivedControlCommand>(extraBufferCapacity = 1)

        override val states: StateFlow<SocketConnectionState> = mutableStates
        override val connectionGeneration: StateFlow<Long> = MutableStateFlow(0L)
        override val commands: Flow<ReceivedControlCommand> = mutableCommands
        override val playbackHistoryAcks: Flow<PlaybackHistoryAck> = emptyFlow()
        var closeCalls = 0

        override fun connect(roomId: String) = Unit

        override fun sendPlaybackHistory(message: PlaybackHistoryMessage): Boolean = false

        fun emit(command: ReceivedControlCommand) {
            check(mutableCommands.tryEmit(command))
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class RecordingPlayerController(context: Context) : PlayerController {
        private val delegate = Media3PlayerController(context)

        override val state = delegate.state
        override val events = delegate.events
        override val player = delegate.player
        val calls = mutableListOf<String>()
        var releaseCalls = 0

        override fun load(
            url: String,
            mediaType: ResolvedMediaType,
            startPositionMs: Long,
            mediaToken: Long,
        ) = delegate.load(url, mediaType, startPositionMs, mediaToken)

        override fun play() {
            calls += "play"
            delegate.play()
        }

        override fun pause() {
            calls += "pause"
            delegate.pause()
        }

        override fun seekBy(deltaMs: Long) = delegate.seekBy(deltaMs)

        override fun adjustVolume(direction: Int) = delegate.adjustVolume(direction)

        override fun toggleMute() = delegate.toggleMute()

        override fun clear() = delegate.clear()

        override fun release() {
            releaseCalls += 1
            delegate.release()
        }
    }

    private object NoOpVideoApi : VideoApi {
        override suspend fun source(
            vid: String,
            pid: String,
            source: String,
            proxy: Boolean,
            mode: String,
            client: String,
        ): ApiResponse<VideoSourceDto> = ApiResponse(code = 500)

        override suspend fun detail(
            vid: String,
            source: String,
            mode: String,
            client: String,
        ): ApiResponse<VideoDetailDto> = ApiResponse(code = 500)
    }

    private fun MainActivity.hasKeepScreenOnFlag(): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    private fun MainActivity.invokeKeepScreenOnSideEffect(desired: Boolean) {
        MainActivity::class.java
            .getDeclaredMethod("setKeepScreenOn", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, desired)
    }

    private fun ActivityScenario<MainActivity>.waitForKeepScreenOn(expected: Boolean) {
        waitUntil {
            var actual = !expected
            onActivity { activity -> actual = activity.hasKeepScreenOnFlag() }
            actual == expected
        }
    }

    private fun waitForKeepScreenOn(activity: MainActivity, expected: Boolean) {
        waitUntil {
            var actual = !expected
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                actual = activity.hasKeepScreenOnFlag()
            }
            actual == expected
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L)
        }
        assertTrue("Condition was not met within 5 seconds", condition())
    }

    private object NoOpPlaybackProgressRepository : PlaybackProgressRepository {
        override suspend fun find(source: String, vid: String, pid: String): PlaybackRecord? = null

        override suspend fun latest(): PlaybackRecord? = null

        override suspend fun save(record: PlaybackRecord) = Unit

        override fun enqueueSave(record: PlaybackRecord) = Unit

        override suspend fun drain() = Unit

        override fun close() = Unit
    }
}
