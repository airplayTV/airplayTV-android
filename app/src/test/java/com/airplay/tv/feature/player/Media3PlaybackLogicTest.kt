package com.airplay.tv.feature.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PlaybackLogicTest {
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
