package com.airplay.tv.feature.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.airplay.tv.feature.pairing.ConnectionStatus
import com.airplay.tv.session.SessionUiState
import java.util.Locale

@Composable
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

        if (state.infoVisible) {
            ConnectionStatus(
                connection = state.connection,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 48.dp),
            )
            PlayerInfoOverlay(
                state = state,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (state.error != null) {
            ErrorOverlay(modifier = Modifier.align(Alignment.Center))
        }
    }
}

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

        Row(
            modifier = Modifier.padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.isPlaying) "播放中" else "已暂停",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
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
            .background(Color(0xFF4A515C)),
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
