package com.airplay.tv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.airplay.tv.feature.pairing.ConnectionStatus
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

        ConnectionStatus(
            connection = state.connection,
            controllerConnected = state.controllerConnected,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 48.dp),
        )

        if (shouldShowPlaybackInfo(state)) {
            PlayerInfoOverlay(
                state = state,
                modifier = Modifier.align(Alignment.BottomCenter),
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

@Composable
private fun PlayerInfoOverlay(state: SessionUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xE6000000)),
                ),
            )
            .padding(start = 56.dp, end = 56.dp, top = 100.dp, bottom = 40.dp)
            .testTag("player-info-overlay"),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { "正在播放" },
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.episodeName.isNotBlank()) {
                    Text(
                        text = state.episodeName,
                        modifier = Modifier.padding(top = 6.dp),
                        color = Color(0xFFC5CDD8),
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.playbackUrl.isNotBlank()) {
                Text(
                    text = state.playbackUrl,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 24.dp),
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
