package com.tradequest.app.core.trading

/**
 * The spread at an instant, and the bid/ask pair derived from it (Steps 5.5, 5.6).
 *
 * # Bid, ask, and which one a trade uses
 *
 * Stored historical price is the **bid**. The ask is `bid + spread`. Then:
 *
 * | Action | Price used |
 * | --- | --- |
 * | Long entry | ask |
 * | Long exit | bid |
 * | Short entry | bid |
 * | Short exit | ask |
 *
 * This is the only place that arithmetic happens. Every other component asks
 * this type for the price it needs, so a subsystem cannot invent its own
 * convention and silently disagree about what a fill cost.
 *
 * [spreadUnits] is in price units. The dollar figures live in [SpreadModel] as
 * USD and are converted once, at the boundary, so no engine code carries a
 * `1000` or a `300` literal.
 */
data class Quote(
    val bidPriceUnits: Int,
    val spreadUnits: Int,
) {
    val askPriceUnits: Int get() = bidPriceUnits + spreadUnits

    /** The price a position on [side] pays to *enter*. */
    fun entryPriceUnits(side: OrderSide): Int = when (side) {
        OrderSide.BUY -> askPriceUnits
        OrderSide.SELL -> bidPriceUnits
    }

    /** The price a position on [side] receives to *exit*. */
    fun exitPriceUnits(side: OrderSide): Int = when (side) {
        OrderSide.BUY -> bidPriceUnits
        OrderSide.SELL -> askPriceUnits
    }
}

/**
 * Spread regimes (Step 5.6).
 *
 * Defined in USD and converted to price units exactly once, by
 * [SpreadModel.spreadUnitsFor]. The plan's figures:
 *
 * ```
 * normal   $0.30 -> 300 units
 * rollover $0.60 -> 600 units
 * news     $0.80 -> 800 units
 * ```
 *
 * # Overlap
 *
 * The plan says to use the larger spread when windows overlap. The rollover
 * window is 17:00 New York +/- 10 minutes; the news window is release time
 * +/- 5 minutes. A high-impact release at 17:00 ET therefore sits inside both,
 * and the answer is the news spread, 800 units. The rule is implemented as
 * "widen only", so adding a fourth regime later cannot accidentally narrow a
 * spread that another regime had already widened.
 */
object SpreadModel {

    /** $0.30 in price units. */
    const val NORMAL_UNITS: Int = 300

    /** $0.60 in price units. */
    const val ROLLOVER_UNITS: Int = 600

    /** $0.80 in price units. */
    const val NEWS_UNITS: Int = 800

    /** Half-width of the rollover window, in milliseconds. +/- 10 minutes. */
    const val ROLLOVER_HALF_WINDOW_MS: Long = 10 * 60_000L

    /** Half-width of the news window, in milliseconds. +/- 5 minutes. */
    const val NEWS_HALF_WINDOW_MS: Long = 5 * 60_000L

    /**
     * The spread in price units at [simUtcMs].
     *
     * [newsReleaseTimesUtcMs] are the release instants of high-impact events for
     * the current dataset. They are passed in rather than looked up, so this
     * function stays pure and testable, and so `core.trading` does not depend on
     * a news repository.
     */
    fun spreadUnitsFor(
        simUtcMs: Long,
        newsReleaseTimesUtcMs: Collection<Long> = emptyList(),
    ): Int {
        var spread = NORMAL_UNITS
        if (isInRolloverWindow(simUtcMs)) spread = maxOf(spread, ROLLOVER_UNITS)
        if (isInNewsWindow(simUtcMs, newsReleaseTimesUtcMs)) spread = maxOf(spread, NEWS_UNITS)
        return spread
    }

    /**
     * True inside the 17:00 New York rollover window, +/- 10 minutes.
     *
     * Computed from the New York *trading day* boundary rather than by comparing
     * local hours, so it follows daylight saving without a second copy of the DST
     * rules.
     *
     * # Which boundaries to test
     *
     * A trading day labelled `D` runs from `D-1` 17:00 to `D` 17:00. So an instant
     * can sit near the boundary that *starts* its trading day or the one that
     * *ends* it, and those are `dayStart(tradingDateOf(t))` and the next trading
     * day's start respectively. Testing only the first would miss every instant in
     * the ten minutes before a rollover — the common case, since that is when a
     * trader is watching the clock.
     */
    fun isInRolloverWindow(simUtcMs: Long): Boolean {
        val calendar = com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
        val tradingDate = calendar.tradingDateOf(simUtcMs)
        val startOfThisDay = calendar.tradingDayStartUtcMs(tradingDate)
        val startOfNextDay = calendar.tradingDayStartUtcMs(tradingDate.plusDays(1))

        return kotlin.math.abs(simUtcMs - startOfThisDay) <= ROLLOVER_HALF_WINDOW_MS ||
            kotlin.math.abs(simUtcMs - startOfNextDay) <= ROLLOVER_HALF_WINDOW_MS
    }

    /** True within +/- 5 minutes of any high-impact release. */
    fun isInNewsWindow(
        simUtcMs: Long,
        newsReleaseTimesUtcMs: Collection<Long>,
    ): Boolean = newsReleaseTimesUtcMs.any {
        kotlin.math.abs(simUtcMs - it) <= NEWS_HALF_WINDOW_MS
    }
}
