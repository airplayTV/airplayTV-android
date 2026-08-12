package com.airplay.tv.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AirPlayDarkColors = darkColorScheme(
    primary = Color(0xFF66D9FF),
    onPrimary = Color(0xFF001F2A),
    background = Color(0xFF070B12),
    onBackground = Color(0xFFF4F7FB),
    surface = Color(0xFF111824),
    onSurface = Color(0xFFF4F7FB),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF310000),
)

@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AirPlayDarkColors,
        content = content,
    )
}
