package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.chart.timeframe.TimeframeAggregator
import com.tradequest.app.core.chart.viewport.ChartTimeframeAnchor
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
     * @param anchorUtcMs a moment to keep at the centre of the window (Step 9.9),
     *   or null to use [viewport] as given. Used after a timeframe switch, when the
     *   viewport is a bar count that no longer means anything and only the moment
     *   is worth keeping. It is resolved here, against the buffer this call builds,
     *   so the new series is aggregated once rather than twice.
     * @param fitToData whether to narrow the window when the series is too short to
     *   fill it. Set by a caller that is *introducing* a series — an opening view —
     *   and never by one that is refreshing a view the user has already adjusted, or
     *   the fit would undo a pinch-out on the next frame. The bar count is only known
     *   here, after aggregation, which is why this is a flag rather than something
     *   the caller can pre-compute.
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
        anchorUtcMs: Long? = null,
        fitToData: Boolean = false,
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

        val totalBars = buffer.size + if (forming != null) 1 else 0

        // Step 9.9: an anchor overrides the incoming viewport's position. It is
        // resolved here so the switch aggregates once. A null result means the moment
        // is outside the new series, so the live-edge viewport stands.
        //
        // Two things have to happen before the centre is placed:
        //
        //  - The fit, when the caller is introducing this series. Narrowing a window
        //    keeps `firstVisibleIndex`, which moves the centre, so fitting after the
        //    anchor would push the moment off-centre. Fitting first means the centre
        //    is placed using the width that will actually be drawn.
        //  - It must be driven by `totalBars`, the count just aggregated. A caller
        //    cannot pre-compute this: the ViewModel's `openingBarCount` returns the
        //    *minute* count when no season is running, which is 20,548 for a dataset
        //    whose weekly series has 4 bars. Fitting against that number left the
        //    weekly chart opening on a 120-bar window — the reported bug.
        val base = if (fitToData) viewport.openingFor(totalBars) else viewport

        val anchored = anchorUtcMs?.let { moment ->
            ChartTimeframeAnchor.viewportAt(
                centreUtcMs = moment,
                nextBuffer = buffer,
                barsVisible = base.barsVisible,
                rightPaddingBars = base.rightPaddingBars,
            ) ?: base
        } ?: base

        val clamped = anchored.clampedToData(totalBars)
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
    ): PriceAxisRange = ChartAutoScale.priceRangeForViewport(
        buffer = buffer,
        forming = forming,
        viewport = viewport,
        totalBars = buffer.size + if (forming != null) 1 else 0,
        fallback = fallback,
    )
}
