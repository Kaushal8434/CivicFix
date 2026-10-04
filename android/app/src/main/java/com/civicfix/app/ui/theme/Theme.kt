package com.civicfix.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Teal = Color(0xFF0E6B5E)
val TealDeep = Color(0xFF07443C)
val TealBright = Color(0xFF18A68D)
val Amber = Color(0xFFE39B00)
val Danger = Color(0xFFD32F2F)
val Ok = Color(0xFF2E8B57)
val Info = Color(0xFF2563EB)

/** Header gradient used by the top bars and hero sections. */
val HeroGradient = Brush.linearGradient(listOf(TealDeep, Teal, TealBright))

/** One accent colour per complaint category (icons, chips, chart bars). */
fun categoryColor(key: String): Color = when (key) {
    "pothole_road_damage" -> Color(0xFF8D5B3F)
    "streetlight" -> Color(0xFFE39B00)
    "water_leakage" -> Color(0xFF0284C7)
    "drainage" -> Color(0xFF5B5BD6)
    "garbage" -> Color(0xFF2E8B57)
    "road_blockage" -> Color(0xFFE2571E)
    "damaged_infrastructure" -> Color(0xFF5E6B7A)
    else -> Color(0xFF8B5CF6)
}

private val Light = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F1E9), onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF3F5F58), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE9E5), onSecondaryContainer = Color(0xFF14201D),
    tertiary = Amber, onTertiary = Color.White,
    background = Color(0xFFF3F6F5), onBackground = Color(0xFF151C1A),
    surface = Color(0xFFF3F6F5), onSurface = Color(0xFF151C1A),
    surfaceVariant = Color(0xFFE3EAE7), onSurfaceVariant = Color(0xFF55615D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEDF2F0), surfaceContainerHigh = Color(0xFFE7EDEA),
    outline = Color(0xFFBCC8C4), outlineVariant = Color(0xFFDCE4E1),
    error = Danger,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF6FD8C1), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0B5147), onPrimaryContainer = Color(0xFFC9F2E7),
    secondary = Color(0xFFB2CCC5), tertiary = Color(0xFFFFC14D),
    background = Color(0xFF0E1513), onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1513), onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF2A3431), onSurfaceVariant = Color(0xFFAEBBB6),
    surfaceContainerLowest = Color(0xFF0A100E), surfaceContainerLow = Color(0xFF161E1C),
    surfaceContainer = Color(0xFF1A2320), surfaceContainerHigh = Color(0xFF232D2A),
    outline = Color(0xFF52605B), outlineVariant = Color(0xFF2F3A37),
    error = Color(0xFFFF8A80),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = bodyMedium.copy(lineHeight = 20.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CivicFixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
