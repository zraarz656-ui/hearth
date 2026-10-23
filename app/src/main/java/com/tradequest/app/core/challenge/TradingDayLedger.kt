package com.tradequest.app.core.challenge

import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar

/**
 * One trading day's realised activity, derived from closed trades.
 *
 * A domain type rather than the `DailyStatsEntity` Room row: the row is written by
 * the database layer and carries bookkeeping fields (a strike flag, an opening
 * equity) that the *ledger* does not need. This is the subset the challenge rules
 * read.
 */
data class TradingDaySummary(
    /** The trading date this day belongs to, as an epoch day in New York terms. */
    val tradingDayEpochDay: Long,

    /** Net realised PnL of every trade closed on this day, in micro-dollars. */
    val netPnlMicros: Long,

    /** How many trades closed on this day. */
    val tradeCount: Int,
)

/**
 * A season's realised activity, aggregated by trading day (Step 11.4).
 *
 * # Why the day count is derived rather than incremented
 *
 * The plan requires at least 20 trading days before a challenge can pass. The
 * tempting implementation is a counter the engine bumps at each rollover, but that
 * counts *days elapsed*, not days traded: a trader who opens the app, waits three
 * weeks and trades once would satisfy a 20-day minimum without having traded on
 * twenty days. The minimum exists to make the challenge a consistency test, so the
 * count must be days on which the trader actually did something.
 *
 * Deriving it from the closed trades makes the count a pure function of the
 * season's own history. It cannot drift from the trades, it survives a resume
 * without a stored counter, and it is trivially testable.
 *
 * # What counts as a trading day
 *
 * A day on which at least one trade **closed**. Closing is the moment a trade's
 * result is realised, and realised PnL is what every other challenge rule is
 * measured on, so counting on the same event keeps the definition single. The
 * consequence, stated plainly because it is a real limitation: a day on which a
 * position is opened but not yet closed does not count. That is deliberate — an
 * open position has no result yet, and counting it would let a trader satisfy the
 * minimum by opening twenty positions and closing none.
 *
 * # Why the day boundary is New York's
 *
 * `AGENTS.md` section 4.4 and the trading calendar roll the day at 17:00 New York.
 * Using UTC would put a 20:00 New York trade on the following day, so a trader
 * active in the New York session would see their day count disagree with the
 * daily-loss budget, which rolls at 17:00 New York. One boundary, used by both.
 */
object TradingDayLedger {

    /**
     * Aggregates [closedTradeTimesUtcMs] and their net PnL into per-day summaries.
     *
     * Both lists are parallel and must be the same length. Taking two lists rather
     * than a list of pairs keeps the call site a direct read of the engine's
     * parallel collections, with no intermediate allocation per trade.
     */
    fun summarize(
        closedTradeTimesUtcMs: List<Long>,
        netPnlMicros: List<Long>,
    ): List<TradingDaySummary> {
        require(closedTradeTimesUtcMs.size == netPnlMicros.size) {
            "a time is required for every trade: ${closedTradeTimesUtcMs.size} times, " +
                "${netPnlMicros.size} amounts"
        }
        if (closedTradeTimesUtcMs.isEmpty()) return emptyList()

        val byDay = LinkedHashMap<Long, MutableDay>()
        closedTradeTimesUtcMs.forEachIndexed { index, timeUtcMs ->
            val day = NewYorkTradingCalendar.tradingDateOf(timeUtcMs).toEpochDay()
            val entry = byDay.getOrPut(day) { MutableDay() }
            entry.netPnlMicros += netPnlMicros[index]
            entry.tradeCount += 1
        }

        return byDay.entries
            .sortedBy { it.key }
            .map { (day, totals) ->
                TradingDaySummary(
                    tradingDayEpochDay = day,
                    netPnlMicros = totals.netPnlMicros,
                    tradeCount = totals.tradeCount,
                )
            }
    }

    /** The number of distinct trading days in [summaries]. */
    fun tradingDayCount(summaries: List<TradingDaySummary>): Int = summaries.size

    /**
     * The best single day's net PnL, floored at zero.
     *
     * Floored because the consistency dimension asks what share of the *profit*
     * came from one day, and a season whose best day lost money has no profit to
     * attribute. Returning the raw maximum would let a losing best day reduce the
     * share and inflate the score, which is the wrong direction.
     */
    fun bestDayPnlMicros(summaries: List<TradingDaySummary>): Long =
        summaries.maxOfOrNull { it.netPnlMicros }?.coerceAtLeast(0L) ?: 0L

    /** Total net realised PnL across every day. */
    fun totalPnlMicros(summaries: List<TradingDaySummary>): Long =
        summaries.sumOf { it.netPnlMicros }

    private class MutableDay {
        var netPnlMicros: Long = 0L
        var tradeCount: Int = 0
    }
}
