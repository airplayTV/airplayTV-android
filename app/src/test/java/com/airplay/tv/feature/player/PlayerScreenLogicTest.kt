package com.airplay.tv.feature.player

import com.airplay.tv.session.SessionUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScreenLogicTest {
    @Test
    fun loadingLayerIsVisibleOnlyWhileLoadingWithoutError() {
        val base = SessionUiState(roomId = "room-1")

        assertTrue(shouldShowLoadingOverlay(base.copy(loading = true)))
        assertFalse(shouldShowLoadingOverlay(base.copy(loading = false)))
        assertFalse(shouldShowLoadingOverlay(base.copy(loading = true, error = "fixed")))
    }
}
