package com.airplay.tv.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airplay.tv.protocol.SocketConnectionState
import com.airplay.tv.session.SessionPage
import com.airplay.tv.session.SessionUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayer.Builder(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).build()
        }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(player::release)
    }

    @Test
    fun pairingIsDefaultAndPlayerAppearsAfterStateChange() {
        var state by mutableStateOf(SessionUiState(roomId = "room-1"))
        composeRule.setContent {
            AppNavigation(state = state, player = player, onBack = {})
        }

        composeRule.onNodeWithTag("pairing-screen").assertIsDisplayed()

        composeRule.runOnIdle {
            state = state.copy(page = SessionPage.Player)
        }

        composeRule.onNodeWithTag("player-screen").assertIsDisplayed()
    }

    @Test
    fun pairingShowsConnectionStatus() {
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    connection = SocketConnectionState.Connected,
                ),
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithText("已连接 · 等待投屏").assertIsDisplayed()
    }

    @Test
    fun playerErrorUsesFixedFriendlyMessageWithoutLeakingUrl() {
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    page = SessionPage.Player,
                    error = "https://private.example/video.m3u8",
                ),
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("player-error-overlay").assertIsDisplayed()
        composeRule.onNodeWithText("播放遇到问题，请稍后重试").assertIsDisplayed()
        composeRule.onNodeWithText("https://private.example/video.m3u8").assertDoesNotExist()
    }

    @Test
    fun playerInfoOverlayFollowsInfoVisible() {
        var state by mutableStateOf(
            SessionUiState(
                roomId = "room-1",
                page = SessionPage.Player,
                infoVisible = false,
            ),
        )
        composeRule.setContent {
            AppNavigation(state = state, player = player, onBack = {})
        }

        composeRule.onNodeWithTag("player-info-overlay").assertDoesNotExist()

        composeRule.runOnIdle {
            state = state.copy(infoVisible = true)
        }

        composeRule.onNodeWithTag("player-info-overlay").assertIsDisplayed()
    }

    @Test
    fun tvBackDelegatesToSessionCallback() {
        var calls = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        composeRule.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
                .onBackPressedDispatcher
            AppNavigation(
                state = SessionUiState(roomId = "room-1", page = SessionPage.Player),
                player = player,
                onBack = { calls += 1 },
            )
        }

        composeRule.runOnIdle(dispatcher::onBackPressed)

        composeRule.runOnIdle {
            assertEquals(1, calls)
        }
    }
}
