package com.mothandgravel.sohva.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mothandgravel.sohva.R

/*
 * "OLED Ink": true black, hairline outlines and one amber accent, so the remote stays dark in a
 * dark living room and draws little power on an OLED phone. The wallpaper (dynamic) colours are not used:
 * they would replace this palette on Android 12+.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB547),
    onPrimary = Color(0xFF241500),
    primaryContainer = Color(0xFF3A2A0E),
    onPrimaryContainer = Color(0xFFFFD9A0),
    secondary = Color(0xFFFFD9A0),
    onSecondary = Color(0xFF241500),
    secondaryContainer = Color(0xFF3A2A0E),
    onSecondaryContainer = Color(0xFFFFD9A0),
    tertiary = Color(0xFF6FE3A5),
    onTertiary = Color(0xFF00210F),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1C1C1F),
    onSurfaceVariant = Color(0xFFA3A3A8),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A2A2E),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF08080A),
    surfaceContainer = Color(0xFF0E0E10),
    surfaceContainerHigh = Color(0xFF151518),
    surfaceContainerHighest = Color(0xFF1C1C1F),
    outline = Color(0xFF5A5A60),
    outlineVariant = Color(0xFF2C2C30),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    inverseSurface = Color(0xFFF2F2F2),
    inverseOnSurface = Color(0xFF111111),
    inversePrimary = Color(0xFF8A4F00),
    scrim = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A4F00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE2BD),
    onPrimaryContainer = Color(0xFF2E1A00),
    secondary = Color(0xFF6B4A1A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE2BD),
    onSecondaryContainer = Color(0xFF2E1A00),
    tertiary = Color(0xFF0B7A45),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE6E6E8),
    onSurfaceVariant = Color(0xFF55555B),
    surfaceDim = Color(0xFFDCDCDE),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8F9),
    surfaceContainer = Color(0xFFF3F3F4),
    surfaceContainerHigh = Color(0xFFEDEDEE),
    surfaceContainerHighest = Color(0xFFE6E6E8),
    outline = Color(0xFF8B8B91),
    outlineVariant = Color(0xFFD6D6DA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF1C1C1F),
    inverseOnSurface = Color(0xFFF2F2F2),
    inversePrimary = Color(0xFFFFB547),
    scrim = Color(0xFF000000),
)

private val InterTight = FontFamily(
    Font(R.font.inter_tight_regular, FontWeight.Normal),
    Font(R.font.inter_tight_medium, FontWeight.Medium),
    Font(R.font.inter_tight_semibold, FontWeight.SemiBold),
    Font(R.font.inter_tight_bold, FontWeight.Bold),
)

private val AppTypography = Typography().run {
    fun TextStyle.inter() = copy(fontFamily = InterTight)
    Typography(
        displayLarge = displayLarge.inter(),
        displayMedium = displayMedium.inter(),
        displaySmall = displaySmall.inter(),
        headlineLarge = headlineLarge.inter(),
        headlineMedium = headlineMedium.inter(),
        headlineSmall = headlineSmall.inter().copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.inter(),
        titleMedium = titleMedium.inter().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.inter(),
        bodyLarge = bodyLarge.inter(),
        bodyMedium = bodyMedium.inter(),
        bodySmall = bodySmall.inter(),
        labelLarge = labelLarge.inter().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.inter().copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
        labelSmall = labelSmall.inter().copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
    )
}

@Composable
fun BraviaRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
