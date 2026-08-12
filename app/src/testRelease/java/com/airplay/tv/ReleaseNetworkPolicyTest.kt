package com.airplay.tv

import com.airplay.tv.core.network.NetworkFactory
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNetworkPolicyTest {
    @Test
    fun releaseVariantDisablesDebugLoggingAndRedirects() {
        assertFalse(BuildConfig.DEBUG)
        val apiClient = NetworkFactory.apiClient(debug = BuildConfig.DEBUG)
        val webSocketClient = NetworkFactory.webSocketClient(debug = BuildConfig.DEBUG)

        listOf(apiClient, webSocketClient).forEach { client ->
            assertTrue(client.interceptors.filterIsInstance<HttpLoggingInterceptor>().isEmpty())
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
        }
        assertEquals(20_000, webSocketClient.pingIntervalMillis)
    }
}
