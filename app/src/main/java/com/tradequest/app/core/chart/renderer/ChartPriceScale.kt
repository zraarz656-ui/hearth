package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import kotlin.math.exp

/**
 * Manual price scaling: the axis stops following the visible extremes and holds a
 * window the user set by dragging the price gutter (Step 9.7).
 *
 * # Why the drag scales rather than scrolls
 *
 * A vertical drag on a price axis changes how much price fits on the screen — that
 * is what "scaling" means, and it is the gesture every terminal uses. Panning the
 * price window without changing its height would just move the chart up and down
 * inside a fixed frame, which the user can already do by panning the plot.
 *
 * # Why the factor is exponential in the drag distance
 *
 * A linear mapping would make the first pixel of drag feel as strong as the last, so
 * a long drag would run away. `e^(d/k)` is the same shape the pinch zoom uses: the
 * scale changes by a constant *ratio* per unit of travel, so a drag feels equally
 * sensitive at any zoom, and the axis can never be scaled to a zero or negative span
 * by a finite drag.
 *
 * # Why the anchor is a price, not a pixel
 *
 * The user grabs the axis at a price and expects that price to stay where they
 * grabbed it. Anchoring on a price means the result survives a re-layout — a
 * rotation or a resize changes the plot height but not the price the user was
 * looking at.
 */
object ChartPriceScale {

    /**
     * How much the span changes per plot height of drag.
     *
     * One full plot height of drag multiplies the span by `e`, roughly 2.7x. That
     * lets the user span the useful range in one comfortable drag rather than
     * several, while still being fine enough to set a tight window by hand.
     */
    const val DRAG_EXPONENT_PER_PLOT_HEIGHT: Float = 1f

    /**
     * Tightest manual window, in price units.
     *
     * A floor rather than nothing, because a span of zero makes the price-to-pixel
     * transform a division by zero, and a span of a few units is a chart zoomed so
     * far in that it is meaningless. Ten pips is tight enough for any hand-set
     * window a trader would actually want.
     */
    const val MIN_SPAN_UNITS: Float = 1_000f

    /**
     * Widest manual window, in price units.
     *
     * A ceiling so a long drag cannot scale the axis out to a window so wide that
     * every candle is a flat line and the user cannot find their way back without a
     * double tap.
     */
    const val MAX_SPAN_UNITS: Float = 10_000_000f

    /**
     * The span multiplier for a drag of [dragPx] pixels down a gutter [plotHeightPx]
     * tall.
     *
     * Positive [dragPx] (dragging downward) widens the window, which shows more
     * price — the axis "zooms out". Negative narrows it. A non-positive plot height
     * returns 1, so a drag before layout is a no-op rather than a division by zero.
     */
    fun factorForDrag(dragPx: Float, plotHeightPx: Float): Float {
        if (plotHeightPx <= 0f || !dragPx.isFinite()) return 1f
        return exp((dragPx / plotHeightPx) * DRAG_EXPONENT_PER_PLOT_HEIGHT)
    }

    /**
     * [range] scaled by [factor] about [anchorPrice], keeping it at
     * [anchorFraction] of the plot height.
     *
     * [anchorFraction] is 0 at the top of the plot and 1 at the bottom, matching
     * screen y. The result is clamped to [[MIN_SPAN_UNITS], [MAX_SPAN_UNITS]].
     *
     * Returns [range] unchanged when the inputs cannot describe a scaling, so a
     * caller that has not measured its plot yet does nothing rather than producing a
     * degenerate range.
     */
    fun scaled(
        range: PriceAxisRange,
        factor: Float,
        anchorPrice: Float,
        anchorFraction: Float,
    ): PriceAxisRange {
        if (!factor.isFinite() || factor <= 0f) return range
        if (!anchorPrice.isFinite()) return range

        val clampedFraction = anchorFraction.coerceIn(0f, 1f)
        val span = (range.spanUnits * factor)
            .coerceIn(MIN_SPAN_UNITS, MAX_SPAN_UNITS)

        // Keep the anchor at the same fraction of the plot: the top of the window
        // sits `clampedFraction` of the span above the anchor.
        val max = anchorPrice + clampedFraction * span
        val min = max - span

        if (!max.isFinite() || !min.isFinite()) return range
        if (max <= min) return range
        return PriceAxisRange(min, max)
    }

    /**
     * True when a pointer at [xPx] is over the price gutter rather than the plot.
     *
     * The gutter is on the right, so anything at or past [plotWidthPx] is the axis.
     * Used by the gesture loop to route a drag to scaling instead of panning
     * (Step 9.10).
     */
    fun isOnPriceAxis(xPx: Float, plotWidthPx: Float): Boolean =
        plotWidthPx > 0f && xPx >= plotWidthPx

    /**
     * The window a double tap restores, which is the auto-fitted range (Step 9.8).
     *
     * A named function rather than a null return so the intent is explicit at the
     * call site: restoring auto-scale is an action, not the absence of a value.
     */
    fun restored(range: PriceAxisRange): PriceAxisRange = range
}
