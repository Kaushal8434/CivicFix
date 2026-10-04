package com.civicfix.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Teal = Color(0xFF0B5D4B)
val Amber = Color(0xFFE0A100)
val Danger = Color(0xFFC62828)
val Ok = Color(0xFF2E7D32)
val Info = Color(0xFF1565C0)

private val Light = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBE3), onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4A635C), tertiary = Amber,
    background = Color(0xFFF7FAF8), surface = Color(0xFFF7FAF8),
    error = Danger,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF7FD6BF), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF00513F), onPrimaryContainer = Color(0xFFCDEBE3),
    secondary = Color(0xFFB1CCC3), tertiary = Color(0xFFFFCB4F),
    error = Color(0xFFFF8A80),
)

@Composable
fun CivicFixTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
