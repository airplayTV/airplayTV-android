package com.airplay.tv.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airplay.tv.protocol.SocketConnectionState
import com.airplay.tv.diagnostics.DiagnosticLogEntry
import com.airplay.tv.feature.player.playerConnectionStatusTopPadding
import com.airplay.tv.session.SessionPage
import com.airplay.tv.session.SessionUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun pairingStatusUsesShortLabelsAndRequiresControllerAssociation() {
        var state by mutableStateOf(
            SessionUiState(
                roomId = "room-1",
                connection = SocketConnectionState.Connecting,
            ),
        )
        composeRule.setContent {
            AppNavigation(
                state = state,
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithText("连接中").assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(connection = SocketConnectionState.Reconnecting)
        }
        composeRule.onNodeWithText("重连中").assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(connection = SocketConnectionState.Closed)
        }
        composeRule.onNodeWithText("已断开").assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(connection = SocketConnectionState.Connected)
        }
        composeRule.onNodeWithText("等待连接").assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(controllerConnected = true)
        }
        composeRule.onNodeWithText("已连接").assertIsDisplayed()
    }

    @Test
    fun pairingMiddleEllipsizesLongRoomId() {
        val roomId = "0123456789abcdef0123456789abcdef"

        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(roomId = roomId),
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithText("房间号：01234567...9abcdef").assertIsDisplayed()
    }

    @Test
    fun playerLoadingStateShowsLoadingLayerImmediately() {
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    page = SessionPage.Player,
                    loading = true,
                ),
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("player-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("player-loading-overlay").assertIsDisplayed()
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

    @Test
    fun playerHudLayersFollowInfoVisibleAndIconState() {
        var state by mutableStateOf(
            SessionUiState(
                roomId = "room-1",
                page = SessionPage.Player,
                connection = SocketConnectionState.Connected,
                infoVisible = false,
                diagnosticVisible = true,
                diagnosticLogs = listOf(DiagnosticLogEntry("CTL", "暂停播放")),
                playbackUrl = "https://cdn.example/video.m3u8",
                isPlaying = true,
                positionMs = 10_000,
                durationMs = 20_000,
            ),
        )
        composeRule.setContent {
            AppNavigation(
                state = state,
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("connection-status").assertDoesNotExist()
        composeRule.onNodeWithTag("diagnostic-overlay-container").assertDoesNotExist()
        composeRule.onNodeWithTag("player-info-overlay").assertDoesNotExist()

        composeRule.runOnIdle { state = state.copy(infoVisible = true) }

        composeRule.onNodeWithTag("connection-status").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-overlay-container").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-log-overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("player-info-overlay").assertIsDisplayed()
        composeRule.onNodeWithText("https://cdn.example/video.m3u8").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("暂停").assertIsDisplayed()
        composeRule.onNodeWithText("播放中").assertDoesNotExist()
        composeRule.onNodeWithText("已暂停").assertDoesNotExist()
    }

    @Test
    fun pairingShowsAndHidesGlobalDiagnosticOverlayWithoutHidingStatus() {
        var state by mutableStateOf(
            SessionUiState(
                roomId = "room-1",
                connection = SocketConnectionState.Connected,
                diagnosticVisible = true,
                diagnosticLogs = listOf(
                    DiagnosticLogEntry("WS", "已连接"),
                    DiagnosticLogEntry("CTL", "手机控制器已关联"),
                ),
            ),
        )
        composeRule.setContent {
            AppNavigation(state = state, player = player, onBack = {})
        }

        composeRule.onNodeWithTag("diagnostic-overlay-container").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-log-overlay").assertIsDisplayed()
        composeRule.onNodeWithText("手机控制器已关联").assertIsDisplayed()
        composeRule.onNodeWithText("已连接").assertDoesNotExist()
        composeRule.onNodeWithTag("connection-status").assertIsDisplayed()
        assertNoOverlap(
            "pairing diagnostic and QR",
            "diagnostic-overlay-container",
            "pairing-qr-container",
        )
        assertNoOverlap(
            "pairing diagnostic and room ID",
            "diagnostic-overlay-container",
            "pairing-room-id",
        )
        assertNoOverlap(
            "pairing diagnostic and connection status",
            "diagnostic-overlay-container",
            "connection-status",
        )
        composeRule.runOnIdle { state = state.copy(diagnosticVisible = false) }
        composeRule.onNodeWithTag("diagnostic-log-overlay").assertDoesNotExist()
        composeRule.onNodeWithTag("connection-status").assertIsDisplayed()
    }

    @Test
    fun playerDiagnosticUsesLatestFiveRowsAndDoesNotOverlapHud() {
        val logs = (1..6).map { index -> DiagnosticLogEntry("CTL", "日志-$index") }
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    page = SessionPage.Player,
                    connection = SocketConnectionState.Connected,
                    infoVisible = true,
                    diagnosticVisible = false,
                    diagnosticLogs = logs,
                    durationMs = 30_000,
                ),
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithText("日志-1").assertDoesNotExist()
        (2..6).forEach { index ->
            composeRule.onNodeWithText("日志-$index").assertIsDisplayed()
        }
        assertNoOverlap(
            "player diagnostic and info",
            "diagnostic-overlay-container",
            "player-info-overlay",
        )
        assertNoOverlap(
            "player diagnostic and progress",
            "diagnostic-overlay-container",
            "player-progress",
        )
        assertNoOverlap(
            "player diagnostic and connection status",
            "diagnostic-overlay-container",
            "connection-status",
        )
    }

    @Test
    fun playerDiagnosticsRequireInfoVisibility() {
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    page = SessionPage.Player,
                    infoVisible = false,
                    diagnosticVisible = true,
                    diagnosticLogs = listOf(DiagnosticLogEntry("CTL", "暂停播放")),
                ),
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("diagnostic-log-overlay").assertDoesNotExist()
        composeRule.onNodeWithTag("player-info-overlay").assertDoesNotExist()
    }

    @Test
    fun playerConnectionStatusFollowsInfoVisibility() {
        var state by mutableStateOf(
            SessionUiState(
                roomId = "room-1",
                page = SessionPage.Player,
                connection = SocketConnectionState.Reconnecting,
                infoVisible = false,
            ),
        )
        composeRule.setContent {
            AppNavigation(
                state = state,
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("connection-status").assertDoesNotExist()
        composeRule.onNodeWithTag("diagnostic-log-overlay").assertDoesNotExist()
        composeRule.onNodeWithTag("player-info-overlay").assertDoesNotExist()

        composeRule.runOnIdle { state = state.copy(infoVisible = true) }

        composeRule.onNodeWithTag("connection-status").assertIsDisplayed()
        composeRule.onNodeWithText("重连中").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-log-overlay").assertDoesNotExist()
        composeRule.onNodeWithTag("player-info-overlay").assertIsDisplayed()
    }

    @Test
    fun qrCardKeepsPlayerVisibleAndUsesTheMiddleEllipsizedRoomId() {
        val roomId = "0123456789abcdef0123456789abcdef"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.playWhenReady = true
        }

        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = roomId,
                    page = SessionPage.Player,
                    qrVisible = true,
                ),
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("player-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("player-qr-card").assertIsDisplayed()
        composeRule.onNodeWithTag("player-qr-overlay").assertDoesNotExist()
        composeRule.onNodeWithTag("pairing-screen").assertDoesNotExist()
        composeRule.onNodeWithText("房间号：01234567...9abcdef").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(player.playWhenReady)
        }
    }

    @Test
    fun qrCardUsesTheTopRightCornerAndMovesConnectionStatusBelowIt() {
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    page = SessionPage.Player,
                    qrVisible = true,
                    infoVisible = true,
                ),
                player = player,
                onBack = {},
            )
        }

        val cardBounds = composeRule.onNodeWithTag("player-qr-card")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val statusBounds = composeRule.onNodeWithTag("connection-status")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        assertEquals(40.dp, cardBounds.top)
        assertEquals(
            48.dp,
            composeRule.onRoot().getUnclippedBoundsInRoot().right - cardBounds.right,
        )
        assertEquals(292.dp, statusBounds.top)
        assertEquals(292.dp, playerConnectionStatusTopPadding(qrVisible = true))
        assertEquals(40.dp, playerConnectionStatusTopPadding(qrVisible = false))
    }

    private fun assertNoOverlap(
        description: String,
        firstTag: String,
        secondTag: String,
    ) {
        val firstNode = composeRule.onNodeWithTag(firstTag).assertIsDisplayed()
        val secondNode = composeRule.onNodeWithTag(secondTag).assertIsDisplayed()
        val first = firstNode.getUnclippedBoundsInRoot()
        val second = secondNode.getUnclippedBoundsInRoot()
        assertTrue(
            "$description has empty bounds for $firstTag: bounds=$first",
            first.right - first.left > 0.dp && first.bottom - first.top > 0.dp,
        )
        assertTrue(
            "$description has empty bounds for $secondTag: bounds=$second",
            second.right - second.left > 0.dp && second.bottom - second.top > 0.dp,
        )
        assertTrue(
            "$description overlaps: first=$first second=$second",
            !first.intersects(second),
        )
    }

    private fun DpRect.intersects(other: DpRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

}
