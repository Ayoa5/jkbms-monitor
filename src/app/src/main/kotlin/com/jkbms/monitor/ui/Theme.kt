package com.jkbms.monitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3482FF),
    onPrimary = Color.White,
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF191919),
    surface = Color.White,
    onSurface = Color(0xFF191919),
    surfaceVariant = Color(0xFFF2F3F5),
    onSurfaceVariant = Color(0xFF8A8A8E),
    error = Color(0xFFE24B4A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    onPrimary = Color(0xFF00204D),
    background = Color(0xFF101012),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2C),
    onSurfaceVariant = Color(0xFF9A9A9E),
    error = Color(0xFFF09595),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

