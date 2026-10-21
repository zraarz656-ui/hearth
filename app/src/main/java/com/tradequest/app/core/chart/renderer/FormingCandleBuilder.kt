package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.simulation.path.MarketPathWindow
import com.tradequest.app.core.simulation.path.CANDLE_DURATION_MS
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.chart.timeframe.TimeframeAggregator

/**
 * Builds the forming candle from the path engine (Step 8.10).
 *
 * # The rule, and why the higher timeframes are the hard part
 *
 * `AGENTS.md` section 8: at simulated time T only candles completed before T are
 * available, and the forming candle contains only movement revealed by T. Future
 * highs and lows stay hidden.
 *
 * For a 1m chart that is nearly free — `MarketPathWindow.formingCandleAt` already
 * returns a candle whose extremes cover only the elapsed part of the minute. For
 * a higher timeframe it is not: the visible forming 1h candle must combine the
 * completed 1m candles of the hour with the partial current minute, and must use
 * *only* those. Reaching for the hour's full data would reveal the future.
 *
 * So the higher-timeframe path is built as:
 *
 *  1. take the completed 1m candles in the current bucket, strictly before the
 *     minute containing T;
 *  2. append the forming 1m candle for T;
 *  3. aggregate that prefix.
 *
 * Step 2 is a `FormingCandle`, whose high and low are the extremes so far, and it
 * is converted to a `HistoricalCandle` with **zero volume** — volume is a
 * whole-minute total, so any figure would leak how much of the minute remains.
 * This mirrors `TimeframeAggregator.formingFrom`.
 */
class FormingCandleBuilder {

    /**
     * The forming candle for [timeframe] at simulated instant [simulatedUtcMs].
     *
     * [revealedMinutes] must contain only one-minute candles strictly before the
     * minute containing [simulatedUtcMs] — that is, only minutes that have fully
     * completed. [completedPrefix] produces exactly that list.
     *
     * Returns null when the path window has no price at [simulatedUtcMs] — inside
     * a market gap, or outside the dataset — because no candle is forming there
     * and drawing one would invent data.
     */
    fun build(
        window: MarketPathWindow,
        revealedMinutes: List<HistoricalCandle>,
        timeframe: Timeframe,
        simulatedUtcMs: Long,
    ): FormingCandleView? {
        val formingMinute = window.formingCandleAt(simulatedUtcMs) ?: return null

        if (timeframe == Timeframe.M1) {
            return FormingCandleView(
                timestampUtcMs = formingMinute.timestampUtcMs,
                openUnits = formingMinute.openUnits,
                highUnits = formingMinute.highUnits,
                lowUnits = formingMinute.lowUnits,
                closeUnits = formingMinute.closeUnits,
            )
        }

        val bucketStart = timeframe.bucketStartUtcMs(simulatedUtcMs)

        // The completed minutes of the current bucket, and nothing from any other
        // bucket. takeLastWhile stops at the first minute outside it, so a long
        // series costs only the tail.
        val inBucket = revealedMinutes.takeLastWhile { it.timestampUtcMs >= bucketStart }

        // The forming minute, as a candle with no volume: tickVolume is a
        // whole-minute total, so any figure would leak how much remains.
        val formingAsCandle = HistoricalCandle(
            timestampUtcMs = formingMinute.timestampUtcMs,
            openUnits = formingMinute.openUnits,
            highUnits = formingMinute.highUnits,
            lowUnits = formingMinute.lowUnits,
            closeUnits = formingMinute.closeUnits,
            tickVolume = 0,
        )

        val revealed = if (inBucket.isEmpty()) {
            listOf(formingAsCandle)
        } else {
            inBucket + formingAsCandle
        }

        // formingFrom cannot see a candle it was not given, so the aggregate
        // covers exactly the revealed prefix and nothing beyond it.
        val aggregated = TimeframeAggregator.formingFrom(revealed, timeframe) ?: return null

        return FormingCandleView(
            timestampUtcMs = aggregated.timestampUtcMs,
            openUnits = aggregated.openUnits,
            highUnits = aggregated.highUnits,
            lowUnits = aggregated.lowUnits,
            closeUnits = aggregated.closeUnits,
        )
    }

    /**
     * The completed minutes to pass to [build]: everything strictly before the
     * minute containing [simulatedUtcMs].
     *
     * The boundary is the whole point. The minute containing T is still forming,
     * so including it would let the forming candle inherit that minute's
     * completed extremes — the leak the rule forbids.
     */
    fun completedPrefix(
        candles: List<HistoricalCandle>,
        simulatedUtcMs: Long,
    ): List<HistoricalCandle> {
        val currentMinuteStart = simulatedUtcMs - (simulatedUtcMs % CANDLE_DURATION_MS)
        val end = candles.indexOfFirst { it.timestampUtcMs >= currentMinuteStart }
        return if (end < 0) candles else candles.subList(0, end)
    }
}
