package com.tradequest.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.tradequest.app.core.chart.renderer.ChartStyle

/**
 * Builds the chart's colours and measurements from the active theme.
 *
 * # Why this lives in `ui.theme` and not in the chart
 *
 * The chart is drawn by `Canvas`, which cannot read `MaterialTheme`. The screen
 * resolves the theme here, once per composition, and passes the result down. That
 * keeps `core.chart` free of any Compose theme dependency while still honouring
 * the Dark, Light, OLED and colour-blind modes.
 */


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

fun chartStyleFor(mode: ThemeMode, darkTheme: Boolean = mode.isDark): ChartStyle {
    val chart = chartColorsFor(mode)
    val grid = if (darkTheme) Color(0xFF2A3038) else Color(0xFFD8DCE0)
    val axis = if (darkTheme) Color(0xFF3A4149) else Color(0xFFB8BEC4)
    val text = if (darkTheme) Color(0xFFB0B8C0) else Color(0xFF5A6068)
    val background = when (mode) {
        ThemeMode.OLED -> OledSurface
        ThemeMode.LIGHT -> LightSurface
        else -> DarkSurface
    }

    return ChartStyle(
        bullish = chart.bullish,
        bearish = chart.bearish,
        doji = if (darkTheme) Color(0xFF9E9E9E) else Color(0xFF757575),
        background = background,
        gridLine = grid,
        axisLine = axis,
        axisText = text,
        crosshair = if (darkTheme) Color(0xFFCCCCCC) else Color(0xFF404040),
        currentPriceLine = GoldAccent,
        asiaSession = chart.bullish.copy(alpha = 1f),
        londonSession = BullishBlue.copy(alpha = 1f),
        newYorkSession = GoldAccent.copy(alpha = 1f),
        weekendBand = if (darkTheme) Color(0xFF6A7078) else Color(0xFFA0A6AC),
        wickWidthPx = 1f,
        gridWidthPx = 1f,
        axisTextSizePx = 10f,
        axisLabelPaddingPx = 4f,
        minBodyHeightPx = 1f,
        bodyWidthFraction = 0.7f,
        sessionBandAlpha = 0.12f,
    )
}
