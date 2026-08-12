package com.airplay.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FakePlayerControllerContractTest {
    @Test
    fun recordsLastLoadedUrlAndPreciseGlobalCallOrder() {
        val fakePlayer = FakePlayerController()

        fakePlayer.load("https://cdn.example/first.m3u8", ResolvedMediaType.HLS)
        fakePlayer.play()
        fakePlayer.seekBy(15_000L)
        fakePlayer.adjustVolume(-1)
        fakePlayer.clear()

        assertEquals("https://cdn.example/first.m3u8", fakePlayer.loadedUrl)
        assertEquals(
            listOf(
                "load:https://cdn.example/first.m3u8",
                "play",
                "seek:15000",
                "volume:-1",
                "clear",
            ),
            fakePlayer.calls,
        )
    }

    @Test
    fun latestLoadWinsAndCallsCanBeClearedIndependently() {
        val fakePlayer = FakePlayerController()

        fakePlayer.load("https://cdn.example/first.m3u8", ResolvedMediaType.HLS)
        fakePlayer.load("https://cdn.example/latest.m3u8", ResolvedMediaType.UNKNOWN)
        fakePlayer.clearCalls()
        fakePlayer.play()
        fakePlayer.seekBy(15_000L)
        fakePlayer.clear()

        assertEquals("https://cdn.example/latest.m3u8", fakePlayer.loadedUrl)
        assertEquals(
            listOf("play", "seek:15000", "clear"),
            fakePlayer.calls,
        )
        assertEquals(
            listOf(
                "https://cdn.example/first.m3u8",
                "https://cdn.example/latest.m3u8",
            ),
            fakePlayer.loadedUrls,
        )
        assertEquals(
            listOf(ResolvedMediaType.HLS, ResolvedMediaType.UNKNOWN),
            fakePlayer.loadedMediaTypes,
        )
    }
}
