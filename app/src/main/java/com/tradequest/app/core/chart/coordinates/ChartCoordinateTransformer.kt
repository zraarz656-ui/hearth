package com.tradequest.app.core.chart.coordinates

import com.tradequest.app.core.chart.viewport.ChartViewport

/**
 * Maps between chart space and screen pixels (Step 8.1).
 *
 * # Why this is a separate, pure class
 *
 * Every drawing call needs the same two conversions, and getting one of them
 * wrong by a half-pixel is visible as a chart that sits slightly wrong against
 * its axis. Keeping the arithmetic in one tested place, with no Compose or
 * Android dependency, means the axis, the grid, the candles and the crosshair
 * all agree by construction, and the whole thing is unit-testable on the JVM.
 *
 * # Axes
 *
 * Price increases upward on screen and y increases downward, so the price
 * transform flips sign. That single fact is the source of most chart bugs, and
 * it is stated once, here.
 *
 * # Landmarks
 *
 * `x = 0` is the left edge of the plot area, `y = 0` the top, matching Compose
 * Canvas. The caller passes the plot area's size, so an axis gutter or a
 * toolbar reduces the plot area rather than shifting every coordinate.
 */
class ChartCoordinateTransformer(
    /** Width of the plot area in pixels. */
    val plotWidthPx: Float,
    /** Height of the plot area in pixels. */
    val plotHeightPx: Float,
    /** The viewport defining which bars are visible and how wide each is. */
    val viewport: ChartViewport,
    /** The price window the vertical axis spans. */
    val priceRange: PriceAxisRange,
) {

    /**
     * Horizontal distance between adjacent bar centres, in pixels.
     *
     * Zero when the viewport has no width, which happens for one frame during
     * layout. Callers guard on [hasArea] rather than dividing by this.
     */
    val candleWidthPx: Float
        get() = if (viewport.barsVisible <= 0f) 0f else plotWidthPx / viewport.barsVisible

    /** True when there is a plot area to draw into. */
    val hasArea: Boolean get() = plotWidthPx > 0f && plotHeightPx > 0f

    /**
     * Screen x of the centre of bar [index].
     *
     * Fractional indices are accepted so a smooth pan draws correctly rather
     * than snapping: the renderer passes the viewport's fractional first index
     * offset by whole-bar steps.
     */
    fun xOfIndex(index: Float): Float =
        (index - viewport.firstVisibleIndex + 0.5f) * candleWidthPx

    /**
     * Screen y of [priceUnits].
     *
     * Flips, because price rises and screen y falls. The top of the plot is
     * [PriceAxisRange.maxUnits].
     */
    fun yOfPrice(priceUnits: Float): Float =
        plotHeightPx * (priceRange.maxUnits - priceUnits) / priceRange.spanUnits

    /** The bar index at screen x [xPx]. The inverse of [xOfIndex]. */
    fun indexAtX(xPx: Float): Float =
        if (candleWidthPx <= 0f) {
            viewport.firstVisibleIndex
        } else {
            xPx / candleWidthPx + viewport.firstVisibleIndex - 0.5f
        }

    /** The price at screen y [yPx]. The inverse of [yOfPrice]. */
    fun priceAtY(yPx: Float): Float =
        priceRange.maxUnits - (yPx / plotHeightPx) * priceRange.spanUnits

    /**
     * True when bar [index] could be visible.
     *
     * Uses the bar's centre plus its half-width, so a bar whose centre is just
     * off-screen but whose body reaches into the plot is still drawn. Combined
     * with the viewport's integer [ChartViewport.visibleWindow], this is the
     * cheap per-candle rejection Step 8.4 asks for.
     */
    fun isBarVisible(index: Int): Boolean {
        val centre = xOfIndex(index.toFloat())
        val half = candleWidthPx / 2f
        return centre + half >= 0f && centre - half <= plotWidthPx
    }
}
