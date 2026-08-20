package com.airplay.tv.feature.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airplay.tv.protocol.SocketConnectionState
import com.airplay.tv.session.SessionUiState

@Composable
fun PairingScreen(
    state: SessionUiState,
    qrCode: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 64.dp, vertical = 48.dp)
            .testTag("pairing-screen"),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(0.44f)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.White)
                    .padding(18.dp)
                    .testTag("pairing-qr-container"),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = qrCode
                if (bitmap == null) {
                    CircularProgressIndicator(color = Color(0xFF141A22))
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "投屏连接二维码",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Spacer(Modifier.width(72.dp))

            Column(
                modifier = Modifier.weight(0.56f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "AIRPLAY TV",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                )
                Text(
                    text = "扫码投屏",
                    modifier = Modifier.padding(top = 14.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "手机选片，电视即刻播放",
                    modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
                    color = Color(0xFFAAB5C5),
                    fontSize = 20.sp,
                )
                PairingStep(index = "01", text = "打开手机相机或扫码工具")
                PairingStep(index = "02", text = "扫描左侧二维码进入投屏页")
                PairingStep(index = "03", text = "选择视频并发送到电视")
                Text(
                    text = "房间号：${middleEllipsizeRoomId(state.roomId)}",
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .testTag("pairing-room-id"),
                    color = Color(0xFFBBC4D0),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }

        ConnectionStatus(
            connection = state.connection,
            controllerConnected = state.controllerConnected,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun PairingStep(index: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color(0xFF172334)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = text,
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 19.sp,
        )
    }
}

@Composable
fun ConnectionStatus(
    connection: SocketConnectionState,
    controllerConnected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (connection) {
        SocketConnectionState.Connecting -> "连接中" to Color(0xFFFFC857)
        SocketConnectionState.Connected -> if (controllerConnected) {
            "已连接" to Color(0xFF56E39F)
        } else {
            "等待连接" to Color(0xFFFFC857)
        }
        SocketConnectionState.Reconnecting -> "重连中" to Color(0xFFFFC857)
        SocketConnectionState.Closed -> "已断开" to Color(0xFFFF7B7B)
    }

    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color(0xCC151D29))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("connection-status"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(color),
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 10.dp),
            color = Color(0xFFE4EAF2),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
