package com.ubuntuterm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = UbuntuOrange,
    onPrimary = Color.White,
    secondary = Aubergine,
    onSecondary = Color.White,
    tertiary = UbuntuOrangeDark,
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1F2530),
    onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFFF5252),
    onError = Color.White
)

@Composable
fun UbuntuTerminalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content
    )
}
