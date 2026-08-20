package com.airplay.tv.feature.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvRemoteKeyMapperTest {
    @Test
    fun mapsMediaAndDpadKeysOnKeyDown() {
        assertEquals(
            RemoteControlAction.Play,
            mapTvRemoteKey(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_DOWN, 0, false),
        )
        assertEquals(
            RemoteControlAction.Pause,
            mapTvRemoteKey(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.ACTION_DOWN, 0, false),
        )
        assertEquals(
            RemoteControlAction.TogglePlayPause,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 0, false),
        )
        assertEquals(
            RemoteControlAction.Forward,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 0, false),
        )
        assertEquals(
            RemoteControlAction.Back,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, 0, false),
        )
    }

    @Test
    fun mapsEpisodeNavigationAccordingToPanelFocus() {
        assertEquals(
            RemoteControlAction.OpenEpisodes,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, 0, false),
        )
        assertEquals(
            RemoteControlAction.EpisodeUp,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, 0, true),
        )
        assertEquals(
            RemoteControlAction.EpisodeDown,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, 0, true),
        )
        assertEquals(
            RemoteControlAction.ExitEpisodes,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, 0, true),
        )
        assertEquals(
            RemoteControlAction.SelectEpisode,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 0, true),
        )
    }

    @Test
    fun ignoresKeyUpUnknownKeysAndRepeatedToggle() {
        assertNull(mapTvRemoteKey(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_UP, 0, false))
        assertNull(mapTvRemoteKey(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, 0, false))
        assertNull(mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 1, false))
        assertEquals(
            RemoteControlAction.Forward,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 3, false),
        )
    }
}
