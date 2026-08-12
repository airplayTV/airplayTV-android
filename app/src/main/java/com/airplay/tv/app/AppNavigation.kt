package com.airplay.tv.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.media3.common.Player
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.airplay.tv.feature.pairing.PairingQrContentCache
import com.airplay.tv.feature.pairing.PairingScreen
import com.airplay.tv.feature.pairing.QrCodeGenerator
import com.airplay.tv.feature.player.PlayerScreen
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
    val qrCode by produceState<Bitmap?>(initialValue = null, qrContent) {
        value = withContext(Dispatchers.Default) {
            QrCodeGenerator().generate(qrContent, QR_CODE_PIXELS)
        }
    }
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
}

private const val QR_CODE_PIXELS = 768
