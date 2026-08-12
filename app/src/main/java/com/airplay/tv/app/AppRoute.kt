package com.airplay.tv.app

sealed class AppRoute(val route: String) {
    data object Pairing : AppRoute("pairing")
    data object Player : AppRoute("player")
}
