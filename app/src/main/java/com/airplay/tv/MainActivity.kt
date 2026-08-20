package com.airplay.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.viewModels
import com.airplay.tv.app.App
import com.airplay.tv.session.SessionViewModel

class MainActivity : ComponentActivity() {
    private val sessionViewModel: SessionViewModel by viewModels {
        (application as AirPlayTVApp).sessionViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            val state by sessionViewModel.uiState.collectAsStateWithLifecycle()
            SideEffect {
                setKeepScreenOn(state.keepScreenOn)
            }

            App(
                state = state,
                player = sessionViewModel.player,
                onBack = sessionViewModel::onBack,
                onRemoteControl = sessionViewModel::onRemoteControl,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        sessionViewModel.onForegroundChanged(true)
    }

    override fun onStop() {
        sessionViewModel.onForegroundChanged(false)
        setKeepScreenOn(false)
        super.onStop()
    }

    override fun onDestroy() {
        sessionViewModel.onForegroundChanged(false)
        setKeepScreenOn(false)
        super.onDestroy()
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
