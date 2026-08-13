package com.airplay.tv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestPermissionTest {
    @Test
    fun manifestAllowsChangingMusicStreamVolume() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(
            manifest.contains(
                "<uses-permission android:name=\"android.permission.MODIFY_AUDIO_SETTINGS\" />",
            ),
        )
    }
}
