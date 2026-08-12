package com.airplay.tv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteTest {
    @Test
    fun exposesPairingAndPlayerRoutes() {
        assertEquals("pairing", AppRoute.Pairing.route)
        assertEquals("player", AppRoute.Player.route)
    }
}
