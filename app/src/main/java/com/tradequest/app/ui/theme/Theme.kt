package com.tradequest.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Chart-facing colour tokens exposed through composition so the Canvas renderer
 * never reads Material roles.
 */
data class ChartColors(
    val bullish: Color,
    val bearish: Color,
)

val LocalChartColors = staticCompositionLocalOf {
    ChartColors(bullish = BullishGreen, bearish = BearishRed)
}

private val DarkColors = darkColorScheme(
    primary = GoldAccent,
    onPrimary = Color.Black,
    secondary = GoldAccentDark,
    background = DarkBackground,
    onBackground = Color(0xFFE6E6E6),
    surface = DarkSurface,
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB8C0C8),
)

private val LightColors = lightColorScheme(
    primary = GoldAccentDark,
    onPrimary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF1A1C1E),
    surface = LightSurface,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF44474A),
)

private val OledColors = darkColorScheme(
    primary = GoldAccent,
    onPrimary = Color.Black,
    secondary = GoldAccentDark,
    background = OledSurface,
    onBackground = Color(0xFFE6E6E6),
    surface = OledSurface,
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = OledSurfaceVariant,
    onSurfaceVariant = Color(0xFFB8C0C8),
)

/** Resolves the colour scheme for [mode], honouring the system setting for DARK. */
fun colorSchemeFor(mode: ThemeMode, systemDark: Boolean) = when (mode) {
    ThemeMode.DARK -> DarkColors
    ThemeMode.OLED -> OledColors
    ThemeMode.COLOR_BLIND -> DarkColors
    ThemeMode.LIGHT -> if (systemDark) DarkColors else LightColors
}

/** Resolves chart tokens for [mode]. */
fun chartColorsFor(mode: ThemeMode) = when (mode) {
    ThemeMode.COLOR_BLIND -> ChartColors(bullish = BullishBlue, bearish = BearishOrange)
    else -> ChartColors(bullish = BullishGreen, bearish = BearishRed)
}

@Composable
fun TradeQuestTheme(
    mode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val scheme = colorSchemeFor(mode, isSystemInDarkTheme())
    CompositionLocalProvider(LocalChartColors provides chartColorsFor(mode)) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TradeQuestTypography,
            content = content,
        )
    }
}
