package com.airplay.tv

import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBuildConfigTest {
    @Test
    fun debugVariantUsesDebugBuildConfig() {
        assertTrue(BuildConfig.DEBUG)
    }
}
