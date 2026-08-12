package com.airplay.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airplay.tv.app.App
import com.airplay.tv.session.SessionViewModel
import com.airplay.tv.session.SessionViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val factory = SessionViewModelFactory(
            (application as AirPlayTVApp).appContainer,
        )
        setContent {
            val sessionViewModel: SessionViewModel = viewModel(factory = factory)
            val state by sessionViewModel.uiState.collectAsStateWithLifecycle()

            App(
                state = state,
                player = sessionViewModel.player,
                onBack = sessionViewModel::onBack,
            )
        }
    }
}
