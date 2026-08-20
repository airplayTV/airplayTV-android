package com.airplay.tv

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airplay.tv.feature.player.ApiResponse
import com.airplay.tv.feature.player.Media3PlayerController
import com.airplay.tv.feature.player.PlayerController
import com.airplay.tv.feature.player.ResolvedMediaType
import com.airplay.tv.feature.player.VideoApi
import com.airplay.tv.feature.player.VideoDetailDto
import com.airplay.tv.feature.player.VideoResolver
import com.airplay.tv.feature.player.VideoSourceDto
import com.airplay.tv.protocol.ReceivedControlCommand
import com.airplay.tv.protocol.SocketClient
import com.airplay.tv.protocol.SocketConnectionState
import com.airplay.tv.session.SessionViewModel
import com.airplay.tv.session.SessionViewModelFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLifecycleTest {
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

        override val states: StateFlow<SocketConnectionState> = mutableStates
        override val connectionGeneration: StateFlow<Long> = MutableStateFlow(0L)
        override val commands: Flow<ReceivedControlCommand> = emptyFlow()
        var closeCalls = 0

        override fun connect(roomId: String) = Unit

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

        override fun load(url: String, mediaType: ResolvedMediaType) =
            delegate.load(url, mediaType)

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
}
