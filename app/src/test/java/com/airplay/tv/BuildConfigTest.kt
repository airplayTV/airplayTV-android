package com.airplay.tv

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigTest {
    @Test
    fun debugUnitTestsUseDebugBuildConfig() {
        assertTrue(BuildConfig.DEBUG)
    }
}
