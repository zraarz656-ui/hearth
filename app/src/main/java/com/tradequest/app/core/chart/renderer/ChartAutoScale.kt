package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import com.tradequest.app.core.chart.viewport.ChartViewport

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

    /**
     * The price window for [viewport] over an existing [buffer].
     *
     * # Why this exists separately from [ChartFrameAssembler]
     *
     * Auto-scale is a function of the viewport, and the viewport changes on every
     * gesture frame while the buffer does not. Rebuilding the frame to re-fit the
     * axis would re-aggregate the whole series per frame, which is the work
     * [ChartFrameAssembler] exists to avoid.
     *
     * This is the same computation `build` performs, over the same inputs, so it
     * is factored out rather than copied: two implementations of "what range does
     * this viewport show" would drift, and the drift would be invisible until the
     * axis and the candles disagreed.
     *
     * The window is clamped so the forming slot at index `buffer.size` is never
     * handed to [CandleBuffer.lowestLow], which would index past the arrays. The
     * forming candle contributes only through `formingLow`/`formingHigh`.
     */
    fun priceRangeForViewport(
        buffer: CandleBuffer,
        forming: FormingCandleView?,
        viewport: ChartViewport,
        totalBars: Int,
        fallback: PriceAxisRange,
    ): PriceAxisRange {
        val window = viewport.visibleWindow(totalBars)
        // An empty buffer means the only bar on screen is the forming one, and
        // IntRange.EMPTY makes the two accessors return null rather than read.
        val bufferWindow = if (buffer.size == 0 || window.isEmpty()) {
            IntRange.EMPTY
        } else {
            window.first.coerceAtLeast(0)..window.last.coerceAtMost(buffer.size - 1)
        }

        return priceRange(
            visibleWindow = bufferWindow,
            lowOf = { range -> if (range.isEmpty()) null else buffer.lowestLow(range.first, range.last) },
            highOf = { range -> if (range.isEmpty()) null else buffer.highestHigh(range.first, range.last) },
            formingLow = forming?.lowUnits?.toFloat(),
            formingHigh = forming?.highUnits?.toFloat(),
            fallback = fallback,
        )
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
