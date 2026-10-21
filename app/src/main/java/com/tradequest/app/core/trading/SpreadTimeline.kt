package com.tradequest.app.core.trading

/**
 * The instants at which the spread changes, so they can be treated as events.
 *
 * # Why this exists
 *
 * A spread change can *cause* a fill. If the bid is flat at 2000.000 and the
 * spread widens from 300 to 800 units, the ask jumps from 2000.300 to 2000.800,
 * and a buy stop resting at 2000.500 is now triggered even though the bid never
 * moved. An engine that only looked for bid crossings would miss that fill, and
 * would also disagree with what the chart showed.
 *
 * So spread changes are event boundaries in their own right. Between two
 * boundaries the spread is constant, which is what lets the engine convert an
 * order's trigger into a fixed bid threshold and solve the crossing exactly.
 *
 * # Determinism
 *
 * The boundaries are a pure function of the trading day and the dataset's news
 * times. No state, no clock.
 */
object SpreadTimeline {

    /**
     * The next instant strictly after [afterUtcMs] at which the spread may
     * change, or null when there is none within [searchUntilUtcMs].
     *
     * Returning the window *edges* is enough: the spread is a step function
     * whose value can only change at the edge of a rollover or news window.
     */
    fun nextChangeAfter(
        afterUtcMs: Long,
        newsReleaseTimesUtcMs: Collection<Long>,
        searchUntilUtcMs: Long,
    ): Long? {
        var best: Long? = null

        fun consider(candidate: Long) {
            if (candidate <= afterUtcMs || candidate > searchUntilUtcMs) return
            val current = best
            if (current == null || candidate < current) best = candidate
        }

        // Rollover window edges for the days whose boundaries could fall in the
        // search range. A trading day labelled D runs from D-1 17:00 to D 17:00, so
        // both directions have to be covered.
        for (dayOffset in -2L..2L) {
            val anchor = afterUtcMs + dayOffset * 86_400_000L
            val dayStart = NewYorkDay.startUtcMsFor(anchor)
            consider(dayStart - SpreadModel.ROLLOVER_HALF_WINDOW_MS)
            consider(dayStart + SpreadModel.ROLLOVER_HALF_WINDOW_MS + 1L)
            val nextStart = NewYorkDay.startUtcMsFor(anchor + 86_400_000L)
            consider(nextStart - SpreadModel.ROLLOVER_HALF_WINDOW_MS)
            consider(nextStart + SpreadModel.ROLLOVER_HALF_WINDOW_MS + 1L)
        }

        for (release in newsReleaseTimesUtcMs) {
            consider(release - SpreadModel.NEWS_HALF_WINDOW_MS)
            consider(release + SpreadModel.NEWS_HALF_WINDOW_MS + 1L)
        }

        return best
    }
}

/** Thin indirection so this file does not import the calendar directly. */
internal object NewYorkDay {
    fun startUtcMsFor(utcMs: Long): Long {
        val calendar = com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
        return calendar.tradingDayStartUtcMs(calendar.tradingDateOf(utcMs))
    }
}
