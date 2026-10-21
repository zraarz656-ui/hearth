package com.tradequest.app.core.chart.buffer

import com.tradequest.app.core.market.HistoricalCandle

/**
 * Candles in primitive arrays, for drawing (Step 8.2).
 *
 * # These arrays are for drawing only, and are never authoritative
 *
 * The market's real prices are integer thousandths of a USD
 * ([com.tradequest.app.core.market.PriceScale.UNITS_PER_USD]). This class copies
 * them into `FloatArray`s so a draw loop can read them without boxing, which is
 * the whole point of the type. Nothing may compute PnL, fills, stops, margin or
 * any other money value from these arrays — read the integer
 * [HistoricalCandle] fields instead.
 *
 * The conversion is lossless, and not by luck. `Float` represents every integer
 * up to 2^24 = 16,777,216 exactly, and XAUUSD peaks near 2,050,000 units, which
 * is under that bound by a factor of eight. [MAX_EXACT_FLOAT_INT] states the
 * bound and [of] enforces it, so if the price scale is ever changed to something
 * coarser the failure is loud rather than a silent rounding of every candle.
 *
 * # Why not just hold the `List<HistoricalCandle>`
 *
 * A `List<HistoricalCandle>` of 90,000 candles is 90,000 objects with six fields
 * each. Iterating it in a draw loop dereferences two levels of reference per
 * candle per frame. The parallel arrays are eight allocations total, and the
 * draw loop indexes them directly. See Step 8.12.
 */
class CandleBuffer private constructor(
    /** Number of candles held. All arrays are exactly this long. */
    val size: Int,
    /** UTC epoch milliseconds at each candle's open. Ascending. */
    val timestamps: LongArray,
    /** Open, in price units, as a `Float` for drawing only. */
    val opens: FloatArray,
    val highs: FloatArray,
    val lows: FloatArray,
    val closes: FloatArray,
    val volumes: IntArray,
) {

    /** True when there is nothing to draw. */
    val isEmpty: Boolean get() = size == 0

    /**
     * The last index at or before [utcMs], or `-1` when every candle is later.
     *
     * Binary search, because a linear scan per frame over 90,000 candles is the
     * thing Step 8.4 exists to avoid. Assumes ascending [timestamps], which
     * [of] guarantees.
     */
    fun indexAtOrBefore(utcMs: Long): Int {
        var low = 0
        var high = size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (timestamps[mid] <= utcMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * The first index at or after [utcMs], or [size] when every candle is
     * earlier.
     */
    fun indexAtOrAfter(utcMs: Long): Int {
        var low = 0
        var high = size - 1
        var found = size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (timestamps[mid] >= utcMs) {
                found = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return found
    }

    /** True when the candle at [index] closed above its open. */
    fun isBullish(index: Int): Boolean = closes[index] > opens[index]

    /** True when the candle at [index] closed below its open. */
    fun isBearish(index: Int): Boolean = closes[index] < opens[index]

    /** Highest high across `[fromIndex, toIndex]`, or null when the range is empty. */
    fun highestHigh(fromIndex: Int, toIndex: Int): Float? {
        if (fromIndex > toIndex) return null
        var best = highs[fromIndex]
        for (i in fromIndex + 1..toIndex) {
            if (highs[i] > best) best = highs[i]
        }
        return best
    }

    /** Lowest low across `[fromIndex, toIndex]`, or null when the range is empty. */
    fun lowestLow(fromIndex: Int, toIndex: Int): Float? {
        if (fromIndex > toIndex) return null
        var best = lows[fromIndex]
        for (i in fromIndex + 1..toIndex) {
            if (lows[i] < best) best = lows[i]
        }
        return best
    }

    companion object {
        /**
         * The largest integer a `Float` represents exactly: 2^24.
         *
         * A `Float` has a 24-bit significand, so every integer up to and
         * including this value is exact and the next one is not.
         */
        const val MAX_EXACT_FLOAT_INT: Int = 1 shl 24

        /** An empty buffer, for a chart with no data yet. */
        val EMPTY: CandleBuffer = CandleBuffer(
            size = 0,
            timestamps = LongArray(0),
            opens = FloatArray(0),
            highs = FloatArray(0),
            lows = FloatArray(0),
            closes = FloatArray(0),
            volumes = IntArray(0),
        )

        /**
         * Copies [candles] into parallel arrays.
         *
         * Sorts by timestamp first. The repository returns candles in ascending
         * order, but the binary searches above and the viewport's index
         * arithmetic both silently misbehave on unsorted input, so the ordering
         * is established here rather than assumed. A dataset already in order
         * costs one linear pass to confirm.
         *
         * Rejects any price outside the exactly-representable range rather than
         * letting it round, because a rounded price would draw a candle at the
         * wrong height with nothing to indicate the error.
         */
        fun of(candles: List<HistoricalCandle>): CandleBuffer {
            if (candles.isEmpty()) return EMPTY

            val ordered = if (isAscending(candles)) candles else candles.sortedBy { it.timestampUtcMs }
            val count = ordered.size

            val timestamps = LongArray(count)
            val opens = FloatArray(count)
            val highs = FloatArray(count)
            val lows = FloatArray(count)
            val closes = FloatArray(count)
            val volumes = IntArray(count)

            for (i in 0 until count) {
                val candle = ordered[i]
                requireExactlyRepresentable(candle)
                timestamps[i] = candle.timestampUtcMs
                opens[i] = candle.openUnits.toFloat()
                highs[i] = candle.highUnits.toFloat()
                lows[i] = candle.lowUnits.toFloat()
                closes[i] = candle.closeUnits.toFloat()
                volumes[i] = candle.tickVolume
            }

            return CandleBuffer(
                size = count,
                timestamps = timestamps,
                opens = opens,
                highs = highs,
                lows = lows,
                closes = closes,
                volumes = volumes,
            )
        }

        private fun isAscending(candles: List<HistoricalCandle>): Boolean {
            for (i in 1 until candles.size) {
                if (candles[i].timestampUtcMs < candles[i - 1].timestampUtcMs) return false
            }
            return true
        }

        private fun requireExactlyRepresentable(candle: HistoricalCandle) {
            val values = intArrayOf(
                candle.openUnits,
                candle.highUnits,
                candle.lowUnits,
                candle.closeUnits,
            )
            for (value in values) {
                require(value in -MAX_EXACT_FLOAT_INT..MAX_EXACT_FLOAT_INT) {
                    "price $value is outside the range a Float represents exactly " +
                        "(+/-$MAX_EXACT_FLOAT_INT); the drawing buffer would round it"
                }
            }
        }
    }
}
