package com.tradequest.app.core.simulation.calendar

import com.tradequest.app.core.market.DatasetClosure
import com.tradequest.app.core.market.MarketRepository

/**
 * The calendar for one dataset, combining rule-based sessions with the closures
 * actually observed in that dataset (Step 3.6).
 *
 * # Why both sources are needed
 *
 * [NewYorkTradingCalendar] knows the *rules*: the week runs Sunday evening to
 * Friday 17:00 ET, and Saturday is always closed. It cannot know holidays,
 * because a holiday is not a rule — it is a fact about a particular date, and
 * the plan is explicit that absent data must not be excused by assumption.
 *
 * The stored [DatasetClosure] rows are that fact. They were produced by
 * `MarketClosureDetector` from the real candles at import time, and they record
 * what was genuinely missing and why. This class joins the two: the rules
 * decide what is normally open, and the stored closures decide what was
 * actually closed.
 *
 * # Boundary semantics
 *
 * A closure is `[startUtcMs, endUtcMs)`. The end is exclusive because the
 * stored end is the first minute *after* the gap, and treating it as inclusive
 * would mark one tradable minute as closed.
 */
class DatasetTradingCalendar(
    private val closures: List<DatasetClosure>,
) {

    /** Closures sorted by start, so [isClosedAt] can binary-search. */
    private val ordered: List<DatasetClosure> = closures.sortedBy { it.startUtcMs }

    /**
     * True when [utcMs] is inside a stored closure.
     *
     * Deliberately *not* combined with [NewYorkTradingCalendar.isMarketOpen]
     * here. The two answer different questions — "was this specific minute
     * absent from the data" versus "is this a normal trading hour" — and a
     * caller that needs both should ask both, so the reason for a gap is never
     * conflated.
     */
    fun isClosedAt(utcMs: Long): Boolean {
        // Binary search for the last closure starting at or before utcMs.
        var low = 0
        var high = ordered.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (ordered[mid].startUtcMs <= utcMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return false
        return utcMs < ordered[candidate].endUtcMs
    }

    /**
     * True when [utcMs] is tradable: a normal trading hour and not a stored
     * closure.
     */
    fun isTradableAt(utcMs: Long): Boolean =
        NewYorkTradingCalendar.isMarketOpen(utcMs) && !isClosedAt(utcMs)

    /** The stored closure covering [utcMs], or null. */
    fun closureAt(utcMs: Long): DatasetClosure? =
        ordered.lastOrNull { it.startUtcMs <= utcMs && utcMs < it.endUtcMs }

    /**
     * The next tradable instant at or after [utcMs], or null when the dataset
     * has no tradable time left.
     *
     * Scans minute by minute because the closures are minute-granular and a
     * dataset spans weeks; the scan is bounded by [maxScanMinutes] so a
     * malformed closure list cannot make this loop unbounded.
     */
    fun nextTradableUtcMs(utcMs: Long, maxScanMinutes: Int = DEFAULT_SCAN_MINUTES): Long? {
        var cursor = utcMs
        repeat(maxScanMinutes) {
            if (isTradableAt(cursor)) return cursor
            cursor += MINUTE_MS
        }
        return null
    }

    companion object {
        const val MINUTE_MS: Long = 60_000L

        /**
         * Ten days of minutes. Long enough to cross a holiday plus a weekend,
         * short enough that a corrupt closure list cannot hang a caller.
         */
        const val DEFAULT_SCAN_MINUTES: Int = 10 * 24 * 60

        /** Loads the calendar for [datasetId], or null when the dataset is unknown. */
        suspend fun load(
            marketRepository: MarketRepository,
            datasetId: String,
        ): DatasetTradingCalendar? {
            if (marketRepository.getDataset(datasetId) == null) return null
            return DatasetTradingCalendar(marketRepository.closures(datasetId))
        }
    }
}
