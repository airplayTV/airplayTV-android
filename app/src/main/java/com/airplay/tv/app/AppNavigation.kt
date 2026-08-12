package com.airplay.tv.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.Player
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.airplay.tv.feature.pairing.PairingScreen
import com.airplay.tv.feature.player.PlayerScreen
import com.airplay.tv.session.SessionPage
import com.airplay.tv.session.SessionUiState

@Composable
fun AppNavigation(
    state: SessionUiState,
    player: Player,
    onBack: () -> Unit,
) {
    val navController = rememberNavController()
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

    BackHandler(enabled = state.page == SessionPage.Player, onBack = onBack)

    NavHost(
        navController = navController,
        startDestination = AppRoute.Pairing.route,
    ) {
        composable(AppRoute.Pairing.route) {
            PairingScreen(state = state)
        }
        composable(AppRoute.Player.route) {
            PlayerScreen(state = state, player = player)
        }
    }
}
