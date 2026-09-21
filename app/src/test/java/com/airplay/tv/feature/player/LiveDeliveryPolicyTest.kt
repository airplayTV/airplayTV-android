package com.airplay.tv.feature.player

import org.junit.Assert.*
import org.junit.Test

class LiveDeliveryPolicyTest {
    @Test
    fun manualReloadAfterTerminalFailureRearmsTimeoutAndFallback() {
        val gate = LiveDeliveryPolicy()
        gate.reset("proxy", 0)
        assertTrue(gate.timedOut(20000, 0, true))
        assertEquals("proxy", gate.takeFallback(20000))
        assertTrue(gate.timedOut(40000, 0, true))
        gate.reset(null, 40000)
        gate.reset("proxy", 50000)
        assertFalse(gate.timedOut(69000, 0, true))
        assertTrue(gate.timedOut(70000, 0, true))
        assertEquals("proxy", gate.takeFallback(70000))
    }

    @Test
    fun onlyNativeIptvManifestGetsProxyFallback() {
        assertEquals("https://api.test/api/iptv/live/cctv1.m3u8?x=1&web=1", liveProxyUrl("https://api.test/api/iptv/live/cctv1.m3u8?x=1"))
        assertNull(liveProxyUrl("https://cdn.test/movie.m3u8"))
        assertNull(liveProxyUrl("https://api.test/api/iptv/live/cctv1.m3u8?web=1"))
        assertNull(liveProxyUrl("file:///api/iptv/live/cctv1.m3u8"))
        assertNull(liveProxyUrl("https://user:pass@api.test/api/iptv/live/cctv1.m3u8"))
    }

    @Test
    fun fallbackOncePerLoadAndClearCannotReusePreviousChannel() {
        val gate = LiveDeliveryPolicy()
        gate.reset("proxy-a", 0)
        assertEquals("proxy-a", gate.takeFallback(1))
        assertNull(gate.takeFallback(2))
        gate.reset("proxy-b", 3)
        assertEquals("proxy-b", gate.takeFallback(4))
        gate.reset(null, 5)
        assertFalse(gate.enabled)
        assertNull(gate.takeFallback(6))
    }

    @Test
    fun progressAndPauseResetTimeoutAndProxyStartsFreshDeadline() {
        val gate = LiveDeliveryPolicy()
        gate.reset("proxy", 0)
        assertFalse(gate.timedOut(19000, 0, true))
        assertTrue(gate.timedOut(20000, 0, true))
        gate.takeFallback(20000)
        assertFalse(gate.timedOut(21000, 0, true))
        assertFalse(gate.timedOut(60000, 0, false))
        assertFalse(gate.timedOut(79000, 0, true))
        assertFalse(gate.timedOut(80000, 1000, true))
        assertTrue(gate.timedOut(100000, 1000, true))
    }
}
