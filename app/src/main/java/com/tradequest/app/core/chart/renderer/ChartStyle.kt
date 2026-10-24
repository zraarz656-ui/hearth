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

    // ---------- Phase 10: trade overlays (Steps 10.3, 10.5, 10.8) ----------

    /**
     * Colour of a position's or order's entry line.
     *
     * Deliberately not the bullish or bearish colour: the entry is a fact about the
     * trade, not a direction, and colouring it by side would make it indistinguishable
     * from the stop and target lines that are also coloured by side.
     */
    val entryLine: Color = Color(0xFF2196F3),

    /** Colour of a stop-loss line and its risk zone. */
    val stopLine: Color = Color(0xFFE53935),

    /** Colour of a take-profit line and its reward zone. */
    val targetLine: Color = Color(0xFF43A047),

    /**
     * Alpha of the shaded risk and reward zones between the bracket lines.
     *
     * Low enough that candles read through it: the zone is context, and a solid
     * fill would hide the price action the trader is using to place the bracket.
     */
    val zoneAlpha: Float = 0.10f,

    /**
     * Colour of an entry marker arrow (Step 10.8).
     *
     * Separate from [entryLine] because a marker is a historical annotation on a
     * specific bar, while the entry line spans the plot.
     */
    val markerEntry: Color = Color(0xFF2196F3),

    /** Colour of an exit marker arrow (Step 10.8). */
    val markerExit: Color = Color(0xFF9E9E9E),

    /** Colour of a high-impact news flag (Step 12.3). Red, as the plan specifies. */
    val newsFlagHigh: Color = Color(0xFFE53935),

    /** Colour of a medium-impact news flag (Step 12.3). Amber, as the plan specifies. */
    val newsFlagMedium: Color = Color(0xFFFFB300),

    /**
     * Half-height of a draggable line's grab handle, in pixels.
     *
     * The hit region for a drag is this tall on each side of the line. A hairline
     * is far too thin for a finger, so the handle is a visible target rather than
     * the line itself being touchable.
     */
    val handleRadiusPx: Float = 14f,
)
