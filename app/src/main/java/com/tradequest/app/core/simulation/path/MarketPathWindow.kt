package com.tradequest.app.core.simulation.path

import com.tradequest.app.core.market.HistoricalCandle

/**
 * What is happening at one simulated instant, across the whole dataset.
 *
 * A candle path can answer "where is price within this minute". A window
 * answers the question the chart and the trading engine actually ask: "given
 * simulated time *T*, where is price, and what have I already passed through?"
 * — including the two cases a single candle cannot express:
 *
 *  - **A gap** (Step 4.6). If *T* falls inside a break between candles, there is
 *    no price and no path. [inGap] is true and [priceUnits] is null. The engine
 *    refuses to interpolate, because the break is precisely the interval about
 *    which nothing is known.
 *  - **Before and after the window.** Outside the loaded candles, time has no
 *    price either, and [priceUnits] is null.
 */
data class PathWindowPosition(
    /** The simulated instant, in UTC epoch milliseconds. */
    val utcMs: Long,
    /** Price at [utcMs], or null when inside a gap or outside the window. */
    val priceUnits: Int?,
    /** The candle containing [utcMs], or null. */
    val step: MarketPathStep?,
    /** How far into the containing candle [utcMs] is; null when there is none. */
    val elapsedMs: Int?,
    /** The gap [utcMs] falls inside, or null. */
    val inGap: MarketGap?,
    /** True when [utcMs] is before the first or after the last candle. */
    val outsideWindow: Boolean,
) {
    /** True when there is a price to quote. */
    val hasPrice: Boolean get() = priceUnits != null
}

/**
 * A loaded run of candles, indexed for time-based lookup (Steps 4.4-4.6).
 *
 * # Why this exists rather than a bare list
 *
 * Steps 4.4 and 4.6 impose two rules that a list of paths does not by itself
 * enforce: forming candles must not see the future, and gaps must not be
 * interpolated across. A window holds the candle *timestamps* as well as their
 * paths, so it can answer "what minute is *T* in?" and "is *T* inside a break?"
 * without guessing. A caller cannot accidentally interpolate across a weekend,
 * because there is no path covering the weekend to interpolate along.
 *
 * # Bounded by construction
 *
 * The window holds only the candles it was given. It has no repository access,
 * so it cannot reach forward into the next hour of data or sideways into
 * another dataset. Future information is not hidden from it; it is absent.
 */
class MarketPathWindow(
    val datasetId: String,
    val steps: List<MarketPathStep>,
) {

    /** Candle start times, ascending. */
    private val startTimes: LongArray = LongArray(steps.size) { steps[it].path.timestampUtcMs }

    val firstUtcMs: Long? get() = startTimes.firstOrNull()
    val lastUtcMs: Long? get() = startTimes.lastOrNull()

    /** The exclusive end of the window: one minute after the last candle starts. */
    val endExclusiveUtcMs: Long? get() = lastUtcMs?.plus(CANDLE_DURATION_MS)

    /** The step containing [utcMs], or null when it is in a gap or outside. */
    fun stepAt(utcMs: Long): MarketPathStep? {
        val index = indexOfCandleContaining(utcMs)
        return if (index >= 0) steps[index] else null
    }

    /** Where price is at [utcMs] (Steps 4.4, 4.6). */
    fun positionAt(utcMs: Long): PathWindowPosition {
        val index = indexOfCandleContaining(utcMs)
        if (index >= 0) {
            val step = steps[index]
            val elapsed = (utcMs - step.path.timestampUtcMs).toInt()
            return PathWindowPosition(
                utcMs = utcMs,
                priceUnits = step.path.priceAt(elapsed),
                step = step,
                elapsedMs = elapsed,
                inGap = null,
                outsideWindow = false,
            )
        }

        // Not inside a candle. Either inside a break between two of them, or
        // beyond the window entirely. Both mean "no price", but they are
        // different facts and the caller needs to tell them apart.
        val gap = gapContaining(utcMs)
        return PathWindowPosition(
            utcMs = utcMs,
            priceUnits = null,
            step = null,
            elapsedMs = null,
            inGap = gap,
            outsideWindow = gap == null,
        )
    }

    /** The price at [utcMs], or null inside a gap or outside the window. */
    fun priceAt(utcMs: Long): Int? = positionAt(utcMs).priceUnits

    /**
     * The forming candle for the minute containing [utcMs] (Step 4.4).
     *
     * Returns null inside a gap or outside the window, because there is no
     * candle forming there. The returned high and low cover only the elapsed
     * part of the minute.
     */
    fun formingCandleAt(utcMs: Long): FormingCandle? {
        val step = stepAt(utcMs) ?: return null
        val elapsed = (utcMs - step.path.timestampUtcMs).toInt()
        return step.path.formingCandleAt(elapsed)
    }

    /**
     * The earliest instant at or after [fromUtcMs] at which price reaches
     * [thresholdUnits], or null (Step 4.5).
     *
     * Searches candle by candle and, within a candle, solves the crossing
     * exactly. A gap is skipped rather than crossed: a level "reached" by
     * jumping a weekend would be a fabricated fill.
     */
    fun firstTouchAtOrAfter(fromUtcMs: Long, thresholdUnits: Int): Long? {
        val startIndex = indexOfCandleContaining(fromUtcMs).let { if (it >= 0) it else insertionIndex(fromUtcMs) }

        for (index in startIndex until steps.size) {
            val path = steps[index].path
            val fromElapsed = if (path.timestampUtcMs >= fromUtcMs) {
                0
            } else {
                (fromUtcMs - path.timestampUtcMs).toInt()
            }

            val elapsed = path.firstTouchFrom(fromElapsed, thresholdUnits) ?: continue
            return path.timestampUtcMs + elapsed
        }
        return null
    }

    /** The gap covering [utcMs], or null. */
    fun gapContaining(utcMs: Long): MarketGap? {
        // Gaps are the complement of the candle minutes, so at most one can
        // cover a given instant, and it must sit between two adjacent steps.
        val index = insertionIndex(utcMs)
        if (index >= steps.size) return null
        val gap = steps[index].precedingGap ?: return null
        return if (utcMs >= gap.fromUtcMs && utcMs < gap.toUtcMs) gap else null
    }

    /** Index of the candle whose minute contains [utcMs], or -1. */
    private fun indexOfCandleContaining(utcMs: Long): Int {
        val index = insertionIndex(utcMs) // first candle starting at or after utcMs
        if (index < steps.size && startTimes[index] == utcMs) return index
        // Otherwise the containing candle, if any, is the one before it. It
        // contains utcMs only while utcMs is still inside its minute.
        val previous = index - 1
        if (previous >= 0 && utcMs < startTimes[previous] + CANDLE_DURATION_MS) return previous
        return -1
    }

    /**
     * Index of the first candle starting at or after [utcMs], or `steps.size`.
     *
     * Binary search, because a window may hold a whole dataset and a linear scan
     * per lookup would make chart scrolling quadratic.
     */
    private fun insertionIndex(utcMs: Long): Int {
        var low = 0
        var high = startTimes.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (startTimes[mid] < utcMs) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        /**
         * Builds a window over [candles] (Step 4.6).
         *
         * [gapReasonAt] is consulted for the start of each break, so the caller
         * supplies the reason from the dataset's stored closures.
         */
        fun of(
            datasetId: String,
            candles: List<HistoricalCandle>,
            gapReasonAt: (Long) -> GapReason = { GapReason.MISSING_MINUTES },
        ): MarketPathWindow = MarketPathWindow(
            datasetId = datasetId,
            steps = MarketPathBuilder.build(datasetId, candles, gapReasonAt),
        )
    }
}
