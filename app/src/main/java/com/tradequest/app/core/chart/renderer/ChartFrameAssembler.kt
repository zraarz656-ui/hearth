package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.chart.timeframe.TimeframeAggregator
import com.tradequest.app.core.chart.viewport.ChartViewport
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.simulation.path.MarketPathWindow

/**
 * Turns a dataset and a simulated instant into the one [ChartRenderState] a frame
 * draws (Steps 8.1, 8.10).
 *
 * # Why this is separate from the ViewModel
 *
 * The interesting decisions here are the ones that are wrong silently: which
 * minutes reach the buffer, whether the forming candle is folded into the price
 * axis, and whether the visible window is allowed to address a candle the buffer
 * does not hold. None of that needs a ViewModel, a coroutine or a database, so it
 * lives here and is tested directly.
 *
 * # The two ways this can go wrong, and how they are prevented
 *
 * 1. **A future leak.** The buffer is built from [ChartRevealPlanner], which stops
 *    before the forming bucket. Aggregating the raw series would draw the bucket's
 *    true high and low — the future — while looking perfectly normal.
 *
 * 2. **A crash from the forming slot.** The forming candle sits at index
 *    `buffer.size`, one past the buffer's arrays, and
 *    [CandleBuffer.highestHigh] does not clamp its arguments. Passing a visible
 *    window that includes that index would read past the arrays. The window is
 *    therefore clamped to the buffer, and the forming candle is folded into the
 *    price axis through `formingLow`/`formingHigh` instead.
 */
object ChartFrameAssembler {

    /**
     * @param candles the dataset's one-minute series, ascending.
     * @param window the path window for the same dataset, or null when the chart
     *   is showing pure history and no forming candle can be derived.
     * @param simulatedUtcMs the simulated instant, or null for a historical view.
     * @param viewport the viewport to honour. It is clamped to the bar count, so a
     *   caller may pass one carried over from a larger buffer.
     * @param fallback used only when no visible candle yields a price.
     */
    fun build(
        candles: List<HistoricalCandle>,
        timeframe: Timeframe,
        style: ChartStyle,
        window: MarketPathWindow?,
        simulatedUtcMs: Long?,
        viewport: ChartViewport,
        fallback: PriceAxisRange,
    ): ChartRenderState {
        val buffer: CandleBuffer
        val forming: FormingCandleView?

        if (simulatedUtcMs == null) {
            // Historical view: the whole dataset is available, so there is no
            // boundary to respect and no forming candle.
            buffer = CandleBuffer.of(
                TimeframeAggregator.aggregate(candles, timeframe),
            )
            forming = null
        } else {
            val plan = ChartRevealPlanner.plan(candles, timeframe, simulatedUtcMs)
            buffer = CandleBuffer.of(TimeframeAggregator.aggregate(plan.revealed, timeframe))
            forming = window?.let {
                FormingCandleBuilder().build(
                    window = it,
                    revealedMinutes = plan.formingMinutes,
                    timeframe = timeframe,
                    simulatedUtcMs = simulatedUtcMs,
                )
            }
        }

        val clamped = viewport.clampedToData(buffer.size + if (forming != null) 1 else 0)
        val priceRange = priceRangeFor(buffer, forming, clamped, fallback)

        return ChartRenderState(
            buffer = buffer,
            timeframe = timeframe,
            viewport = clamped,
            priceRange = priceRange,
            style = style,
            formingCandle = forming,
            simulatedUtcMs = simulatedUtcMs,
        )
    }

    /**
     * The price axis for the visible bars, including the forming candle.
     *
     * The buffer window is clamped so the forming slot at index `buffer.size` is
     * never handed to [CandleBuffer.highestHigh], which would index past the end.
     * The forming candle contributes only through `formingLow`/`formingHigh`.
     */
    private fun priceRangeFor(
        buffer: CandleBuffer,
        forming: FormingCandleView?,
        viewport: ChartViewport,
        fallback: PriceAxisRange,
    ): PriceAxisRange {
        val window = viewport.visibleWindow(buffer.size + if (forming != null) 1 else 0)

        // An empty buffer means the only bar on screen is the forming one, and
        // IntRange.EMPTY makes the two accessors return null rather than read.
        val bufferWindow = if (buffer.size == 0) {
            IntRange.EMPTY
        } else {
            window.first.coerceAtLeast(0)..window.last.coerceAtMost(buffer.size - 1)
        }

        return ChartAutoScale.priceRange(
            visibleWindow = bufferWindow,
            lowOf = { range -> buffer.lowestLow(range.first, range.last) },
            highOf = { range -> buffer.highestHigh(range.first, range.last) },
            formingLow = forming?.lowUnits?.toFloat(),
            formingHigh = forming?.highUnits?.toFloat(),
            fallback = fallback,
        )
    }
}
