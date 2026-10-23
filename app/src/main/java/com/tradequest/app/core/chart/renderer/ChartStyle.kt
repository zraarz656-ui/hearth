package com.tradequest.app.core.chart.renderer

import androidx.compose.ui.graphics.Color

/**
 * Colours and measurements for the chart (Step 8.1).
 *
 * # Why this is a plain data class and not a Compose theme
 *
 * The chart is drawn by `Canvas`, which cannot read `MaterialTheme` — those
 * tokens resolve inside a composable scope, and the draw lambda runs outside it.
 * The screen therefore builds a [ChartStyle] from the theme once per
 * composition and passes it down. Keeping it a plain value also means the
 * renderer can be exercised without a Compose runtime.
 *
 * # Colour-blind mode
 *
 * `ThemeMode.COLOR_BLIND` exists because red and green are the one pair a
 * deuteranope cannot separate, and the chart is where that matters most. The
 * bullish and bearish colours are therefore style inputs rather than constants,
 * so the mode can swap them for a blue and orange pair without the renderer
 * knowing why.
 */
data class ChartStyle(
    val bullish: Color,
    val bearish: Color,
    val doji: Color,
    val background: Color,
    val gridLine: Color,
    val axisLine: Color,
    val axisText: Color,
    val crosshair: Color,
    val currentPriceLine: Color,
    val asiaSession: Color,
    val londonSession: Color,
    val newYorkSession: Color,
    val weekendBand: Color,
    /** Stroke width of a candle wick, in pixels. 1f gives a crisp hairline. */
    val wickWidthPx: Float,
    /** Stroke width of a gridline, in pixels. */
    val gridWidthPx: Float,
    /** Height of the time-axis strip and width of the price-axis gutter. */
    val axisTextSizePx: Float,
    /** Padding between an axis label and the plot edge. */
    val axisLabelPaddingPx: Float,
    /**
     * Smallest body height that is still drawn as a body.
     *
     * A one-unit-range candle would otherwise be a sub-pixel sliver, and a doji
     * would vanish entirely. Below this the body is drawn as a flat line, which
     * is what a real terminal does.
     */
    val minBodyHeightPx: Float,
    /**
     * Fraction of the candle slot the body occupies.
     *
     * 0.7 leaves a visible gap between candles at default zoom while still
     * looking solid when zoomed out.
     */
    val bodyWidthFraction: Float,
    /** Session bands are drawn at this alpha so candles stay readable over them. */
    val sessionBandAlpha: Float,
)
