package com.airplay.tv.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTest {
    @Test
    fun exposesCastingEndpointAndPlaybackConstants() {
        assertEquals("https://airplay-tv.pages.dev", AppConfig.H5_BASE_URL)
        assertEquals("https://airplay-api.artools.cc/", AppConfig.API_BASE_URL)
        assertEquals("wss://airplay-api.artools.cc/api/wss", AppConfig.WEBSOCKET_URL)
        assertEquals(15_000L, AppConfig.SEEK_INCREMENT_MS)
        assertEquals(5_000L, AppConfig.INFO_OVERLAY_TIMEOUT_MS)
    }
}
