package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.simulation.path.CANDLE_DURATION_MS

/**
 * Splits one dataset into "what the trader may see" and "the candle forming now"
 * (Step 8.10, product rule 2.2).
 *
 * # The rule
 *
 * At simulated time `T`, the chart may show candles that *completed* strictly
 * before the bucket containing `T`, plus one partial candle covering the bucket
 * so far. Nothing at or after `T` may influence any pixel.
 *
 * # Why this is its own class rather than a few lines in the ViewModel
 *
 * The failure mode is not a crash, it is a **silent leak**: aggregate the whole
 * bucket into the buffer and the chart draws the bucket's true high and low —
 * the future — while looking perfectly normal. A leak cannot be caught by
 * asserting "it did not throw"; it can only be caught by asserting on the exact
 * boundary. Keeping the arithmetic here, with no Compose, database or clock
 * dependency, means the boundary is tested directly, once.
 *
 * # The two lists, and why they differ
 *
 * [Plan.revealed] is what goes into the chart's buffer, and it stops *before the
 * forming bucket*. [Plan.formingMinutes] is what the forming-candle builder needs,
 * and it includes the completed minutes of the forming bucket — that is how a
 * forming 1h candle shows the hours's finished minutes plus the partial current
 * minute, rather than only the current minute.
 *
 * For `M1` the forming bucket *is* the current minute, so `revealed` and
 * `formingMinutes` coincide. For `H1` and above they do not, and confusing them is
 * the bug this class exists to prevent.
 */
object ChartRevealPlanner {

    /**
     * The buffer window and the forming minutes for [simulatedUtcMs].
     *
     * [candles] is the dataset's one-minute series, ascending, with no gaps
     * removed. [simulatedUtcMs] is a real instant, not snapped to a bucket.
     */
    fun plan(
        candles: List<HistoricalCandle>,
        timeframe: Timeframe,
        simulatedUtcMs: Long,
    ): Plan {
        if (candles.isEmpty()) return Plan(emptyList(), emptyList())

        val bucketStart = timeframe.bucketStartUtcMs(simulatedUtcMs)

        // The first index at or after the forming bucket's start. Everything
        // before it is a bucket that has finished, so it may be aggregated into
        // the buffer in full.
        val revealedEnd = firstIndexAtOrAfter(candles, bucketStart)

        // Everything strictly before the minute containing T. The minute
        // containing T is still forming, so it must not be treated as complete
        // even when the bucket it belongs to started earlier.
        val currentMinuteStart = simulatedUtcMs - (simulatedUtcMs % CANDLE_DURATION_MS)
        val completedEnd = firstIndexAtOrAfter(candles, currentMinuteStart)

        return Plan(
            revealed = candles.subList(0, revealedEnd),
            formingMinutes = candles.subList(0, completedEnd),
        )
    }

    /**
     * The first index whose timestamp is at or after [utcMs], or `size` when
     * there is none.
     *
     * Binary search rather than `indexOfFirst`, because this runs on every tick
     * over a series that may hold 200,000 minutes and a linear scan per frame is
     * exactly the cost Step 8.12 asks to avoid.
     */
    private fun firstIndexAtOrAfter(candles: List<HistoricalCandle>, utcMs: Long): Int {
        var low = 0
        var high = candles.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (candles[mid].timestampUtcMs < utcMs) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * The two views of the series a frame needs.
     *
     * @property revealed Minutes whose bucket has fully completed. Aggregate
     *   these to build the chart's buffer. Never contains a minute at or after
     *   the forming bucket's start.
     * @property formingMinutes Minutes strictly before the minute containing the
     *   simulated instant. Passed to [FormingCandleBuilder], which adds the
     *   partial current minute itself.
     */
    data class Plan(
        val revealed: List<HistoricalCandle>,
        val formingMinutes: List<HistoricalCandle>,
    )
}
