package com.phototinder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OledColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color(0xFFB0B0B0),
    onSecondary = Color.Black,
    tertiary = Color(0xFF808080),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0A0A0A),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFCCCCCC),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF222222),
)

@Composable
fun PhotoTinderTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = OledColorScheme,
        content = content
    )
}
