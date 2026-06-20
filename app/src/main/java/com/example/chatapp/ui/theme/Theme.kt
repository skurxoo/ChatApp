package com.example.chatapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = OrangeDark,
    onPrimary = Color(0xFF3D1900),
    primaryContainer = OrangeContainerDark,
    onPrimaryContainer = Color(0xFFFFDBC2),
    secondary = Color(0xFFFFB68A),
    background = WarmDarkBackground,
    surface = WarmDarkSurface,
)

private val LightColorScheme = lightColorScheme(
    primary = OrangePrimary,
    onPrimary = Color.White,
    primaryContainer = OrangeContainer,
    onPrimaryContainer = Ink,
    secondary = Color(0xFF9C4400),
    background = WarmBackground,
    surface = WarmSurface,
    surfaceVariant = Cream,
    onSurface = Ink,
)

@Composable
fun ChatAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
