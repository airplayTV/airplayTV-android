package com.airplay.tv.core.network

import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFactoryTest {
    @Test
    fun apiAndWebSocketClientsRejectRedirects() {
        val api = NetworkFactory.apiClient(debug = false)
        val socket = NetworkFactory.webSocketClient(debug = false)

        listOf(api, socket).forEach { client ->
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
        }
        assertEquals(0, api.pingIntervalMillis)
        assertEquals(20_000, socket.pingIntervalMillis)
    }

    @Test
    fun debugLoggingIsBasicAndReleaseStyleClientHasNone() {
        val debugLogging = NetworkFactory.apiClient(debug = true)
            .interceptors
            .filterIsInstance<HttpLoggingInterceptor>()
        val releaseLogging = NetworkFactory.apiClient(debug = false)
            .interceptors
            .filterIsInstance<HttpLoggingInterceptor>()

        assertEquals(listOf(HttpLoggingInterceptor.Level.BASIC), debugLogging.map { it.level })
        assertTrue(releaseLogging.isEmpty())
    }
}
