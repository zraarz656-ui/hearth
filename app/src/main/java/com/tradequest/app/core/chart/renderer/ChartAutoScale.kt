package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.coordinates.PriceAxisRange

/**
 * The price window for the bars currently on screen (Step 8.7, auto-scale).
 *
 * # Why this is computed from the viewport and not stored
 *
 * Auto-scale means "fit the axis to what is visible". The visible window is the
 * viewport's business, and the viewport is owned by the chart, so the range has
 * to be derived at draw time rather than handed in. Storing a range computed over
 * the whole dataset instead would make candles collapse into a flat line as soon
 * as the user zoomed in — the extremes of the entire series would still set the
 * scale.
 *
 * # The forming candle is included
 *
 * A forming candle can set a new extreme for the visible window, and if it were
 * excluded the axis would clip the very candle the trader is watching.
 */
object ChartAutoScale {

    /**
     * The price window covering the visible bars, padded.
     *
     * Returns [fallback] when nothing is visible — an empty dataset, or a viewport
     * panned past the data — because there is nothing to fit and a zero-span range
     * is not drawable.
     */
    fun priceRange(
        visibleWindow: IntRange,
        lowOf: (IntRange) -> Float?,
        highOf: (IntRange) -> Float?,
        formingLow: Float?,
        formingHigh: Float?,
        fallback: PriceAxisRange,
    ): PriceAxisRange {
        val windowLow = lowOf(visibleWindow)
        val windowHigh = highOf(visibleWindow)

        // Fold in the forming candle, which sits outside the buffer's arrays.
        val low = minOfNotNull(windowLow, formingLow)
        val high = maxOfNotNull(windowHigh, formingHigh)

        if (low == null || high == null) return fallback
        return PriceAxisRange.of(low = low, high = high)
    }

    private fun minOfNotNull(a: Float?, b: Float?): Float? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    private fun maxOfNotNull(a: Float?, b: Float?): Float? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}
