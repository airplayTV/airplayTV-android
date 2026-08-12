package com.airplay.tv.feature.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PlaybackLogicTest {
    @Test
    fun retryPreservesCurrentPlaybackIntent() {
        val foregroundCalls = mutableListOf<String>()
        val backgroundCalls = mutableListOf<String>()

        retryPlayer(recordingPlayer(playWhenReady = true, foregroundCalls))
        retryPlayer(recordingPlayer(playWhenReady = false, backgroundCalls))

        assertEquals(listOf("prepare", "play"), foregroundCalls)
        assertEquals(listOf("prepare", "pause"), backgroundCalls)
    }

    @Test
    fun opaqueHlsMapsToExplicitMedia3MimeType() {
        assertEquals(MimeTypes.APPLICATION_M3U8, ResolvedMediaType.HLS.media3MimeType())
    }

    @Test
    fun knownMp4MapsToExplicitMedia3MimeType() {
        assertEquals(MimeTypes.VIDEO_MP4, ResolvedMediaType.MP4.media3MimeType())
    }

    @Test
    fun unknownTypeLeavesMedia3MimeTypeUnsetForInference() {
        assertEquals(null, ResolvedMediaType.UNKNOWN.media3MimeType())
    }

    @Test
    fun seekTargetIsBoundedByKnownDuration() {
        assertEquals(10_000L, calculateSeekTarget(8_000L, 5_000L, 10_000L))
        assertEquals(0L, calculateSeekTarget(2_000L, -5_000L, 10_000L))
    }

    @Test
    fun seekTargetWithUnknownDurationOnlyHasLowerBound() {
        assertEquals(13_000L, calculateSeekTarget(8_000L, 5_000L, C.TIME_UNSET))
        assertEquals(0L, calculateSeekTarget(2_000L, -5_000L, C.TIME_UNSET))
    }

    @Test
    fun seekTargetSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, calculateSeekTarget(Long.MAX_VALUE - 1, 10L, C.TIME_UNSET))
        assertEquals(0L, calculateSeekTarget(0L, Long.MIN_VALUE, C.TIME_UNSET))
    }

    @Test
    fun restoreVolumeUsesLastAudibleValueWithinCurrentMaximum() {
        assertEquals(7, calculateRestoreVolume(lastAudibleVolume = 7, maxVolume = 10))
        assertEquals(4, calculateRestoreVolume(lastAudibleVolume = 7, maxVolume = 4))
        assertEquals(0, calculateRestoreVolume(lastAudibleVolume = 7, maxVolume = 0))
    }

    @Test
    fun retryGateAllowsOneRetryUntilNextMediaIsLoaded() {
        val retryGate = SingleRetryGate()

        assertTrue(retryGate.tryAcquire())
        assertFalse(retryGate.tryAcquire())

        retryGate.reset()

        assertTrue(retryGate.tryAcquire())
        assertFalse(retryGate.tryAcquire())
    }

    @Test
    fun lifecycleGateMakesClearAndReleaseIdempotent() {
        val lifecycle = ControllerLifecycleGate()

        assertTrue(lifecycle.tryClear())
        assertFalse(lifecycle.tryClear())

        lifecycle.onLoad()
        assertTrue(lifecycle.tryClear())

        assertTrue(lifecycle.tryRelease())
        assertFalse(lifecycle.tryRelease())
        assertFalse(lifecycle.tryClear())
        assertTrue(lifecycle.isReleased)
    }
}

private fun recordingPlayer(playWhenReady: Boolean, calls: MutableList<String>): Player =
    Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "getPlayWhenReady" -> playWhenReady
            "prepare" -> calls += "prepare"
            "play" -> calls += "play"
            "pause" -> calls += "pause"
            "equals" -> proxy === arguments?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "recordingPlayer"
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0F
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
        }
    } as Player
