package com.airplay.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.airplay.tv.data.db.AppDatabase
import com.airplay.tv.data.preferences.AppPreferences
import com.airplay.tv.data.repository.VideoRepository
import com.airplay.tv.ui.screens.HomeScreen
import com.airplay.tv.ui.screens.PlayerScreen
import com.airplay.tv.ui.screens.SearchScreen
import com.airplay.tv.ui.screens.SettingsScreen
import com.airplay.tv.ui.screens.HistoryScreen

@Composable
fun AppNavigation(
    prefs: AppPreferences,
    db: AppDatabase,
    repo: VideoRepository,
    modifier: Modifier = Modifier
) {
    val currentScreen = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("home") }
    val currentVideoId = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val currentVideoPid = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val currentSource = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    when (currentScreen.value) {
        "home" -> HomeScreen(
            prefs = prefs, repo = repo,
            onVideoClick = { vid, pid, source ->
                currentVideoId.value = vid; currentVideoPid.value = pid
                currentSource.value = source; currentScreen.value = "player"
            },
            onSearchClick = { currentScreen.value = "search" },
            onHistoryClick = { currentScreen.value = "history" },
            onSettingsClick = { currentScreen.value = "settings" }
        )
        "player" -> PlayerScreen(
            vid = currentVideoId.value, pid = currentVideoPid.value,
            source = currentSource.value,
            repo = repo, db = db, prefs = prefs,
            onBack = { currentScreen.value = "home" }
        )
        "search" -> SearchScreen(
            repo = repo, source = currentSource.value,
            onVideoClick = { vid, pid, source ->
                currentVideoId.value = vid; currentVideoPid.value = pid
                currentSource.value = source; currentScreen.value = "player"
            },
            onBack = { currentScreen.value = "home" }
        )
        "history" -> HistoryScreen(
            db = db,
            onVideoClick = { vid, pid, source ->
                currentVideoId.value = vid; currentVideoPid.value = pid
                currentSource.value = source; currentScreen.value = "player"
            },
            onBack = { currentScreen.value = "home" }
        )
        "settings" -> SettingsScreen(
            prefs = prefs, db = db,
            onBack = { currentScreen.value = "home" }
        )
    }
}
