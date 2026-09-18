package com.bagent.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Developer-workbench palette. No neon, no gradients, high legibility. */
object BColors {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF151A21)
    val SurfaceVariant = Color(0xFF1E242C)
    val Outline = Color(0xFF30363D)
    val Primary = Color(0xFF4C8DFF)
    val OnPrimary = Color(0xFF06121F)
    val Secondary = Color(0xFF3FB950)
    val Tertiary = Color(0xFFD29922)
    val Error = Color(0xFFF85149)
    val OnSurface = Color(0xFFE6EDF3)
    val OnSurfaceVariant = Color(0xFF9DA7B3)
    val Success = Color(0xFF3FB950)
    val Warning = Color(0xFFD29922)
}

private val DarkScheme = darkColorScheme(
    primary = BColors.Primary,
    onPrimary = BColors.OnPrimary,
    primaryContainer = Color(0xFF17324F),
    onPrimaryContainer = BColors.OnSurface,
    secondary = BColors.Secondary,
    onSecondary = Color(0xFF06180A),
    tertiary = BColors.Tertiary,
    onTertiary = Color(0xFF1A1200),
    background = BColors.Background,
    onBackground = BColors.OnSurface,
    surface = BColors.Surface,
    onSurface = BColors.OnSurface,
    surfaceVariant = BColors.SurfaceVariant,
    onSurfaceVariant = BColors.OnSurfaceVariant,
    outline = BColors.Outline,
    outlineVariant = Color(0xFF262C34),
    error = BColors.Error,
    onError = Color(0xFF2A0603)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1D5FD6),
    secondary = Color(0xFF1F7A34),
    tertiary = Color(0xFF8A6100),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEFF2),
    outline = Color(0xFFC4CBD4)
)

val Mono = FontFamily.Monospace

private val BTypography = Typography(
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun BAgentTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = BTypography,
        content = content
    )
}