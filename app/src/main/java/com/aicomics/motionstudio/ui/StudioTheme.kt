package com.aicomics.motionstudio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioColors = darkColorScheme(
    primary = Color(0xFFB9A7FF),
    onPrimary = Color(0xFF21134B),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF101116),
    surface = Color(0xFF191A22),
    surfaceVariant = Color(0xFF252631)
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioColors,
        content = content
    )
}
