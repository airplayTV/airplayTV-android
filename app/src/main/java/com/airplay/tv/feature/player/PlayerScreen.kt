package com.airplay.tv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.airplay.tv.diagnostics.DiagnosticLogOverlay
import com.airplay.tv.diagnostics.DiagnosticLogEntry
import com.airplay.tv.session.SessionUiState
import java.util.Locale

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
fun PlayerScreen(
    state: SessionUiState,
    player: Player,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("player-screen"),
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
            },
            onRelease = { view ->
                view.player = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (shouldShowPlayerDiagnostics(state)) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .zIndex(PLAYER_DIAGNOSTIC_LAYER_Z_INDEX)
                    .padding(start = 48.dp, bottom = 40.dp)
                    .testTag("diagnostic-overlay-container"),
            ) {
                PlayerDiagnosticOverlay(state = state)
            }
        }

        if (shouldShowPlaybackInfo(state)) {
            PlayerInfoOverlay(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(PLAYER_INFO_LAYER_Z_INDEX),
            )
        }

        if (shouldShowEpisodePanel(state)) {
            EpisodePanel(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 104.dp, end = 48.dp),
            )
        }

        if (state.error != null) {
            ErrorOverlay(modifier = Modifier.align(Alignment.Center))
        } else if (shouldShowLoadingOverlay(state)) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag("player-loading-overlay"),
            )
        }
    }
}

internal fun shouldShowLoadingOverlay(state: SessionUiState): Boolean =
    state.loading && state.error == null

internal fun shouldShowPlaybackInfo(state: SessionUiState): Boolean =
    state.infoVisible

internal fun shouldShowPlayerConnection(state: SessionUiState): Boolean =
    state.infoVisible

internal fun playerConnectionStatusTopPadding(qrVisible: Boolean) =
    if (qrVisible) 292.dp else 40.dp

internal fun shouldShowPlayerDiagnostics(state: SessionUiState): Boolean =
    state.infoVisible && state.diagnosticLogs.isNotEmpty()

internal fun shouldShowEpisodePanel(state: SessionUiState): Boolean =
    state.infoVisible && state.episodes.size > 1

internal fun isEpisodeFocused(state: SessionUiState, episodeId: String): Boolean =
    state.episodePanelFocused &&
        state.episodes.getOrNull(state.focusedEpisodeIndex)?.id == episodeId

internal data class PlayerOverlayContent(
    val sourceLabel: String,
    val logs: List<DiagnosticLogEntry>,
)

internal fun playerOverlayContent(state: SessionUiState) = PlayerOverlayContent(
    sourceLabel = state.sourceName.ifBlank { "--" },
    logs = state.diagnosticLogs.takeLast(1),
)

internal const val PLAYER_INFO_LAYER_Z_INDEX = 1f
internal const val PLAYER_DIAGNOSTIC_LAYER_Z_INDEX = 2f

@Composable
private fun PlayerDiagnosticOverlay(
    state: SessionUiState,
    modifier: Modifier = Modifier,
) {
    val content = playerOverlayContent(state)
    if (content.logs.isNotEmpty()) {
        DiagnosticLogOverlay(
            logs = content.logs,
            modifier = modifier,
        )
    }
}

@Composable
private fun EpisodePanel(
    state: SessionUiState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val panelShape = MaterialTheme.shapes.medium
    LaunchedEffect(state.focusedEpisodeIndex, state.episodes.size) {
        if (state.focusedEpisodeIndex in state.episodes.indices) {
            listState.animateScrollToItem(state.focusedEpisodeIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .widthIn(min = 180.dp, max = 240.dp)
            .heightIn(max = 240.dp)
            .clip(panelShape)
            .background(Color(0xE6121B25))
            .testTag("episode-panel"),
    ) {
        items(state.episodes, key = Episode::id) { episode ->
            EpisodeRow(state = state, episode = episode)
        }
    }
}

@Composable
private fun EpisodeRow(
    state: SessionUiState,
    episode: Episode,
) {
    val focused = isEpisodeFocused(state, episode.id)
    val current = state.currentPid == episode.id
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (focused) Color(0x3329D3C2) else Color.Transparent)
            .testTag("episode-row-${episode.id}"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = episode.name,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .then(
                    if (focused) Modifier.testTag("episode-focus-${episode.id}") else Modifier,
                ),
            color = if (focused || current) MaterialTheme.colorScheme.primary else Color.White,
            fontSize = 16.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlayerInfoOverlay(state: SessionUiState, modifier: Modifier = Modifier) {
    val content = playerOverlayContent(state)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xE6000000)),
                ),
            )
            .testTag("player-info-overlay")
            .padding(start = 56.dp, end = 56.dp, top = 100.dp, bottom = 104.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag("player-title-column"),
            ) {
                Text(
                    text = state.title.ifBlank { "正在播放" },
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.episodeName.isNotBlank() || state.sourceName.isNotBlank()) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.episodeName.isNotBlank()) {
                            Text(
                                text = state.episodeName,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .testTag("player-episode-name"),
                                color = Color(0xFFC5CDD8),
                                fontSize = 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (state.sourceName.isNotBlank()) {
                            val sourceShape = MaterialTheme.shapes.small
                            Text(
                                text = content.sourceLabel,
                                modifier = Modifier
                                    .padding(start = if (state.episodeName.isBlank()) 0.dp else 8.dp)
                                    .widthIn(max = 140.dp)
                                    .background(Color(0xB31B2532), sourceShape)
                                    .border(1.dp, Color(0xFF526274), sourceShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag("player-source"),
                                color = Color(0xFFD6DEE8),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (state.playbackUrl.isNotBlank()) {
                Text(
                    text = state.playbackUrl,
                    modifier = Modifier
                        .weight(1.8f)
                        .padding(start = 24.dp)
                        .testTag("player-playback-url"),
                    color = Color(0xFF9DAAB9),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaybackStateIcon(
                isPlaying = state.isPlaying,
                modifier = Modifier.testTag("playback-state-icon"),
            )
            Spacer(Modifier.width(18.dp))
            Text(
                text = formatDuration(state.positionMs),
                color = Color.White,
                fontSize = 15.sp,
            )
            PlaybackProgress(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            )
            Text(
                text = formatDuration(state.durationMs),
                color = Color(0xFFC5CDD8),
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun PlaybackStateIcon(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .size(22.dp)
            .semantics {
                contentDescription = if (isPlaying) "暂停" else "播放"
            },
    ) {
        if (isPlaying) {
            val barWidth = size.width * 0.28f
            drawRect(color = color, size = Size(barWidth, size.height))
            drawRect(
                color = color,
                topLeft = Offset(size.width - barWidth, 0f),
                size = Size(barWidth, size.height),
            )
        } else {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path = path, color = color)
        }
    }
}

@Composable
private fun PlaybackProgress(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0) {
        positionMs.toFloat().div(durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = modifier
            .height(5.dp)
            .background(Color(0xFF4A515C))
            .testTag("player-progress"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun ErrorOverlay(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xE61B222C), MaterialTheme.shapes.large)
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .testTag("player-error-overlay"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "播放遇到问题，请稍后重试",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "可在手机端重新选择视频",
            modifier = Modifier.padding(top = 10.dp),
            color = Color(0xFFBBC4D0),
            fontSize = 16.sp,
        )
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
