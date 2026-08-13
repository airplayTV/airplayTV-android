package com.airplay.tv.app

import android.view.KeyEvent
import com.airplay.tv.feature.player.RemoteControlAction
import com.airplay.tv.session.SessionPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRemoteKeyHandlerTest {
    @Test
    fun ignoresRemoteKeysOutsidePlayerPage() {
        val actions = mutableListOf<RemoteControlAction>()

        val consumed = handleTvRemoteKey(
            page = SessionPage.Pairing,
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY,
            keyAction = KeyEvent.ACTION_DOWN,
            repeatCount = 0,
            onRemoteControl = actions::add,
        )

        assertFalse(consumed)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun consumesAndForwardsSupportedKeysOnPlayerPage() {
        val actions = mutableListOf<RemoteControlAction>()

        val consumed = handleTvRemoteKey(
            page = SessionPage.Player,
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            keyAction = KeyEvent.ACTION_DOWN,
            repeatCount = 0,
            onRemoteControl = actions::add,
        )

        assertTrue(consumed)
        assertEquals(listOf(RemoteControlAction.Forward), actions)
    }

    @Test
    fun leavesUnsupportedKeysUnconsumedOnPlayerPage() {
        val actions = mutableListOf<RemoteControlAction>()

        val consumed = handleTvRemoteKey(
            page = SessionPage.Player,
            keyCode = KeyEvent.KEYCODE_MENU,
            keyAction = KeyEvent.ACTION_DOWN,
            repeatCount = 0,
            onRemoteControl = actions::add,
        )

        assertFalse(consumed)
        assertTrue(actions.isEmpty())
    }
}
