package com.airplay.tv.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airplay.tv.protocol.SocketConnectionState
import com.airplay.tv.diagnostics.DiagnosticLogEntry
import com.airplay.tv.feature.player.Episode
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
    fun focusedEpisodePanelIsRightAlignedSingleColumnAndScrollsToFocus() {
        val episodes = (1..12).map { index -> Episode("p$index", "第 $index 集") }
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    page = SessionPage.Player,
                    infoVisible = true,
                    episodes = episodes,
                    currentPid = "p1",
                    episodePanelFocused = true,
                    focusedEpisodeIndex = episodes.lastIndex,
                ),
                player = player,
                onBack = {},
            )
        }

        val panel = composeRule.onNodeWithTag("episode-panel")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val row = composeRule.onNodeWithTag("episode-row-p12")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        assertTrue("episode panel is wider than 240dp: $panel", panel.right - panel.left <= 240.dp)
        assertTrue("episode panel is narrower than 180dp: $panel", panel.right - panel.left >= 180.dp)
        assertEquals(panel.left, row.left)
        assertEquals(panel.right, row.right)
        composeRule.onNodeWithTag("episode-focus-p12").assertIsDisplayed()
        composeRule.onNodeWithTag("episode-focus-p1").assertDoesNotExist()
        assertRightAligned("episode-panel")
    }

    @Test
    fun episodePanelClipsFocusedFirstAndLastRowsToRoundedCorners() {
        var state by mutableStateOf(
            SessionUiState(
                roomId = "room-1",
                page = SessionPage.Player,
                infoVisible = true,
                episodes = listOf(Episode("p1", "第 1 集"), Episode("p2", "第 2 集")),
                currentPid = "p1",
                episodePanelFocused = true,
                focusedEpisodeIndex = 0,
            ),
        )
        composeRule.setContent {
            AppNavigation(state = state, player = player, onBack = {})
        }

        val firstImage = composeRule.onNodeWithTag("episode-panel").captureToImage().toPixelMap()
        assertBlackCorner(firstImage[0, 0])

        composeRule.runOnIdle { state = state.copy(focusedEpisodeIndex = 1) }
        val lastImage = composeRule.onNodeWithTag("episode-panel").captureToImage().toPixelMap()
        assertBlackCorner(lastImage[0, lastImage.height - 1])
    }

    @Test
    fun singleEpisodeDoesNotShowEpisodePanel() {
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    page = SessionPage.Player,
                    infoVisible = true,
                    episodes = listOf(Episode("p1", "第 1 集")),
                    currentPid = "p1",
                    episodePanelFocused = true,
                ),
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("episode-panel").assertDoesNotExist()
    }

    @Test
    fun visibleInfoShowsUnfocusedEpisodePanelWithoutFocusHighlight() {
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    page = SessionPage.Player,
                    infoVisible = true,
                    episodes = listOf(Episode("p1", "Episode 1"), Episode("p2", "Episode 2")),
                    currentPid = "p1",
                    episodePanelFocused = false,
                    focusedEpisodeIndex = 0,
                ),
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("episode-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("episode-focus-p1").assertDoesNotExist()
        composeRule.onNodeWithTag("episode-focus-p2").assertDoesNotExist()
    }

    @Test
    fun playerHudStaysLowWithSourceAfterEpisodeAndDiagnosticAtBottomLeft() {
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    page = SessionPage.Player,
                    infoVisible = true,
                    episodeName = "第 3 集",
                    sourceName = "very-long-source-name-that-must-be-compact",
                    diagnosticLogs = listOf(DiagnosticLogEntry("SYNC", "已同步")),
                    durationMs = 30_000,
                ),
                player = player,
                onBack = {},
            )
        }

        composeRule.onNodeWithText("源 very-long-source-name-that-must-be-compact").assertDoesNotExist()
        composeRule.onNodeWithText("very-long-source-name-that-must-be-compact").assertIsDisplayed()
        val episode = composeRule.onNodeWithTag("player-episode-name")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val source = composeRule.onNodeWithTag("player-source")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue("source must follow episode: episode=$episode source=$source", source.left >= episode.right)
        assertTrue("source capsule is wider than 140dp: source=$source", source.right - source.left <= 140.dp)
        composeRule.onNodeWithTag("diagnostic-log-overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-overlay-container").assertIsDisplayed()
        assertLeftAligned("diagnostic-overlay-container")
        assertNoOverlap(
            "diagnostic and progress",
            "diagnostic-overlay-container",
            "player-progress",
        )
        val progress = composeRule.onNodeWithTag("player-progress")
            .getUnclippedBoundsInRoot()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "progress must stay in the bottom safe area: progress=$progress root=$root",
            root.bottom - progress.bottom <= 140.dp,
        )
    }

    @Test
    fun playbackUrlGetsMoreWidthThanTitle() {
        composeRule.setContent {
            AppNavigation(
                state = SessionUiState(
                    roomId = "room-1",
                    page = SessionPage.Player,
                    infoVisible = true,
                    title = "影片标题",
                    playbackUrl = "https://cdn.example/video/long-path/index.m3u8",
                ),
                player = player,
                onBack = {},
            )
        }

        val title = composeRule.onNodeWithTag("player-title-column")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val url = composeRule.onNodeWithTag("player-playback-url")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        assertTrue("URL must be wider than title: title=$title url=$url", url.right - url.left > title.right - title.left)
    }

    @Test
    fun pairingAndPlayerConnectionStatusShareTopRightCoordinates() {
        var state by mutableStateOf(
            SessionUiState(
                roomId = "room-1",
                connection = SocketConnectionState.Connected,
            ),
        )
        composeRule.setContent {
            AppNavigation(state = state, player = player, onBack = {})
        }

        val pairingStatus = composeRule.onNodeWithTag("connection-status")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertRightAligned("connection-status")
        assertEquals(40.dp, pairingStatus.top)

        composeRule.runOnIdle {
            state = state.copy(page = SessionPage.Player, infoVisible = true)
        }
        val playerStatus = composeRule.onNodeWithTag("connection-status")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        assertEquals(pairingStatus.top, playerStatus.top)
        assertEquals(pairingStatus.right, playerStatus.right)
        assertRightAligned("connection-status")
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
        val diagnostic = composeRule.onNodeWithTag("diagnostic-log-overlay")
            .getUnclippedBoundsInRoot()
        assertTrue("diagnostic is wider than 420dp: diagnostic=$diagnostic", diagnostic.right - diagnostic.left <= 420.dp)
        assertTrue("diagnostic is taller than 36dp: diagnostic=$diagnostic", diagnostic.bottom - diagnostic.top <= 36.dp)
        assertLeftAligned("diagnostic-overlay-container")
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
    fun playerDiagnosticUsesOnlyLatestRowAndDoesNotOverlapHud() {
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
        (2..5).forEach { index ->
            composeRule.onNodeWithText("日志-$index").assertDoesNotExist()
        }
        composeRule.onNodeWithText(logs.last().message).assertIsDisplayed()
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

    private fun assertRightAligned(tag: String) {
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val node = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertEquals(48.dp, root.right - node.right)
    }

    private fun assertLeftAligned(tag: String) {
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val node = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertEquals(48.dp, node.left - root.left)
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

    private fun assertBlackCorner(color: Color) {
        assertTrue("rounded corner leaked row highlight: color=$color", color.red < 0.02f)
        assertTrue("rounded corner leaked row highlight: color=$color", color.green < 0.02f)
        assertTrue("rounded corner leaked row highlight: color=$color", color.blue < 0.02f)
    }

}
