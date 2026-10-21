package com.tradequest.app.core.simulation.path

import com.tradequest.app.core.market.HistoricalCandle

/**
 * One candle's path, plus whether a discontinuity preceded it (Step 4.6).
 *
 * A `MarketPath` is what a chart draws and what execution walks. The gap, when
 * present, sits *before* [path] and is the only thing that may interrupt the
 * line.
 */
data class MarketPathStep(
    val path: IntraminutePath,
    /** The break between the previous candle and this one, or null when contiguous. */
    val precedingGap: MarketGap?,
)

/**
 * Builds paths for a run of candles and records the gaps between them
 * (Steps 4.6, 4.7).
 *
 * # No interpolation across candles
 *
 * Within a minute, price moves along a known path. Between two candles nothing
 * is known: the close of one and the open of the next may differ, and the
 * market may have been closed for a weekend in between. Drawing or simulating a
 * straight line across that break would invent price movement that never
 * happened, which product rule 4.5 forbids. So each break is emitted as a
 * [MarketGap] and the path simply stops at the candle boundary.
 *
 * This also means the engine never needs to know *why* data is missing. It
 * reports a gap and lets the caller supply the reason from the dataset's stored
 * closures, keeping `core.simulation` free of a market-repository dependency.
 */
object MarketPathBuilder {

    /**
     * Paths for [candles], in the order given.
     *
     * [candles] is expected in ascending time order, which is how every
     * repository read returns them. Out-of-order input is rejected rather than
     * silently sorted: a chart that reorders candles would draw a wrong picture,
     * and a caller that sorted them itself would hide the bug.
     *
     * [gapReasonAt] classifies a break that starts at the given UTC millisecond.
     * It defaults to [GapReason.MISSING_MINUTES] so that a caller with no
     * closure information still gets an explicit, honest answer rather than an
     * interpolated line.
     */
    fun build(
        datasetId: String,
        candles: List<HistoricalCandle>,
        gapReasonAt: (Long) -> GapReason = { GapReason.MISSING_MINUTES },
    ): List<MarketPathStep> {
        if (candles.isEmpty()) return emptyList()

        val steps = ArrayList<MarketPathStep>(candles.size)
        var previous: HistoricalCandle? = null

        for (candle in candles) {
            val prior = previous
            require(prior == null || candle.timestampUtcMs > prior.timestampUtcMs) {
                "candles must be strictly ascending in time; got " +
                    "${prior?.timestampUtcMs} then ${candle.timestampUtcMs}"
            }

            val gap = prior?.let { gapBetween(it, candle, gapReasonAt) }
            steps += MarketPathStep(
                path = IntraminutePath.of(datasetId, candle),
                precedingGap = gap,
            )
            previous = candle
        }
        return steps
    }

    /**
     * The gap between [previous] and [next], or null when they are contiguous.
     *
     * Contiguous means the next candle begins exactly one minute after the
     * previous one. Anything else is a break and is reported, including a
     * one-minute jump whose prices happen to match: time is what is missing.
     */
    fun gapBetween(
        previous: HistoricalCandle,
        next: HistoricalCandle,
        reason: GapReason,
    ): MarketGap? {
        val expectedNext = previous.timestampUtcMs + CANDLE_DURATION_MS
        if (next.timestampUtcMs == expectedNext) return null
        return MarketGap(
            fromUtcMs = expectedNext,
            toUtcMs = next.timestampUtcMs,
            fromPriceUnits = previous.closeUnits,
            toPriceUnits = next.openUnits,
            reason = reason,
        )
    }

    private fun gapBetween(
        previous: HistoricalCandle,
        next: HistoricalCandle,
        gapReasonAt: (Long) -> GapReason,
    ): MarketGap? {
        val expectedNext = previous.timestampUtcMs + CANDLE_DURATION_MS
        if (next.timestampUtcMs == expectedNext) return null
        return gapBetween(previous, next, gapReasonAt(expectedNext))
    }
}
