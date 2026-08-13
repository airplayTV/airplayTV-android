package com.airplay.tv.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.airplay.tv.feature.pairing.PairingQrContentCache
import com.airplay.tv.feature.pairing.PairingQrImage
import com.airplay.tv.feature.pairing.PairingScreen
import com.airplay.tv.feature.pairing.QrCodeGenerator
import com.airplay.tv.feature.pairing.bitmapFor
import com.airplay.tv.feature.player.PlayerScreen
import com.airplay.tv.diagnostics.DiagnosticLogOverlay
import com.airplay.tv.session.SessionPage
import com.airplay.tv.session.SessionUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppNavigation(
    state: SessionUiState,
    player: Player,
    onBack: () -> Unit,
) {
    val navController = rememberNavController()
    val qrContentCache = remember { PairingQrContentCache() }
    val qrContent = remember(state.roomId) {
        qrContentCache.contentFor(state.roomId)
    }
    val generatedQrImage by produceState<PairingQrImage<Bitmap>?>(
        initialValue = null,
        qrContent,
    ) {
        value = withContext(Dispatchers.Default) {
            PairingQrImage(
                content = qrContent,
                bitmap = QrCodeGenerator().generate(qrContent, QR_CODE_PIXELS),
            )
        }
    }
    val qrCode = generatedQrImage.bitmapFor(qrContent)
    val targetRoute = when (state.page) {
        SessionPage.Pairing -> AppRoute.Pairing.route
        SessionPage.Player -> AppRoute.Player.route
    }

    LaunchedEffect(targetRoute) {
        if (navController.currentDestination?.route == targetRoute) return@LaunchedEffect

        when (targetRoute) {
            AppRoute.Pairing.route -> {
                if (!navController.popBackStack(AppRoute.Pairing.route, inclusive = false)) {
                    navController.navigate(AppRoute.Pairing.route) {
                        launchSingleTop = true
                    }
                }
            }
            AppRoute.Player.route -> navController.navigate(AppRoute.Player.route) {
                launchSingleTop = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = AppRoute.Pairing.route,
        ) {
            composable(AppRoute.Pairing.route) {
                PairingScreen(state = state, qrCode = qrCode)
            }
            composable(AppRoute.Player.route) {
                PlayerScreen(state = state, player = player)
                BackHandler(enabled = true, onBack = onBack)
            }
        }

        if (state.page == SessionPage.Player && state.qrVisible) {
            PlayerQrOverlay(
                qrCode = qrCode,
                roomId = state.roomId,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (state.diagnosticVisible && state.diagnosticLogs.isNotEmpty()) {
            val overlayAlignment = when (state.page) {
                SessionPage.Pairing -> Alignment.BottomStart
                SessionPage.Player -> Alignment.TopStart
            }
            val overlayPadding = when (state.page) {
                SessionPage.Pairing -> Modifier.padding(start = 64.dp, bottom = 2.dp)
                SessionPage.Player -> Modifier.padding(start = 48.dp, top = 40.dp)
            }
            Box(
                modifier = Modifier
                    .align(overlayAlignment)
                    .then(overlayPadding)
                    .testTag("diagnostic-overlay-container"),
            ) {
                DiagnosticLogOverlay(
                    logs = when (state.page) {
                        SessionPage.Pairing -> state.diagnosticLogs.takeLast(1)
                        SessionPage.Player -> state.diagnosticLogs
                    },
                )
            }
        }
    }
}

@Composable
private fun PlayerQrOverlay(
    qrCode: Bitmap?,
    roomId: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0xB3000000))
            .testTag("player-qr-overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF121B25))
                .widthIn(min = 420.dp)
                .padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .background(Color.White)
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qrCode == null) {
                    CircularProgressIndicator(color = Color(0xFF141A22))
                } else {
                    Image(
                        bitmap = qrCode.asImageBitmap(),
                        contentDescription = "投屏连接二维码",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            Text(
                text = "扫码连接控制器",
                modifier = Modifier.padding(top = 18.dp),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "房间号：$roomId",
                modifier = Modifier.padding(top = 10.dp),
                color = Color(0xFFBBC4D0),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 14.sp,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
            )
            Text(
                text = "按遥控器返回键关闭",
                modifier = Modifier.padding(top = 8.dp),
                color = Color(0xFFBBC4D0),
                fontSize = 16.sp,
            )
        }
    }
}

private const val QR_CODE_PIXELS = 768
