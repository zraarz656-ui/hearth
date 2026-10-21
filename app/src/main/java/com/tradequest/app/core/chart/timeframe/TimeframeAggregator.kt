package com.tradequest.app.core.chart.timeframe

import com.tradequest.app.core.market.HistoricalCandle

/**
 * Aggregates one-minute candles into a higher timeframe (Step 8.11).
 *
 * # The rule this class exists to obey
 *
 * `AGENTS.md` section 8 forbids revealing future information, and it names this
 * case specifically: a visible forming 1h, 4h, Daily or Weekly candle must never
 * be built from unrevealed future 1m candles.
 *
 * So aggregation is split in two, and the split is the design:
 *
 *  - [aggregate] takes a *completed* series and returns completed candles. It is
 *    the only place that sums volumes and takes highs and lows across minutes.
 *  - [formingFrom] takes the minutes revealed so far inside the current bucket
 *    and returns a candle whose high and low are the extremes *so far* and whose
 *    close is the latest revealed close. It cannot see a minute it was not given.
 *
 * A caller with a partially-revealed minute uses [formingFrom] and cannot
 * accidentally leak, because there is no code path from a future candle into it.
 *
 * # Volume
 *
 * [HistoricalCandle.tickVolume] is a total for a whole minute, so a forming
 * bucket's volume would itself be a partial figure that implies how much of the
 * minute is left. The forming candle therefore reports zero volume, matching
 * `FormingCandle`, which carries no volume field for the same reason.
 */
object TimeframeAggregator {

    /**
     * Aggregates [candles] into [timeframe], one candle per bucket.
     *
     * Only buckets that have at least one minute produce a candle. A bucket with
     * no minutes is absent from the result rather than present as a flat candle,
     * because a fabricated flat candle would be invented market data
     * (`AGENTS.md` product rule 4.5).
     *
     * [candles] must be ascending by timestamp. The caller supplies a series it
     * already holds in that order; this does not re-sort a 90,000-element list on
     * every timeframe switch.
     */
    fun aggregate(
        candles: List<HistoricalCandle>,
        timeframe: Timeframe,
    ): List<HistoricalCandle> {
        if (candles.isEmpty()) return emptyList()
        if (timeframe == Timeframe.M1) return candles

        val out = ArrayList<HistoricalCandle>(estimateBucketCount(candles.size, timeframe))
        var bucketStart = timeframe.bucketStartUtcMs(candles[0].timestampUtcMs)
        var open = candles[0].openUnits
        var high = candles[0].highUnits
        var low = candles[0].lowUnits
        var close = candles[0].closeUnits
        var volume = candles[0].tickVolume
        var count = 1

        for (i in 1 until candles.size) {
            val candle = candles[i]
            val start = timeframe.bucketStartUtcMs(candle.timestampUtcMs)
            if (start == bucketStart) {
                if (candle.highUnits > high) high = candle.highUnits
                if (candle.lowUnits < low) low = candle.lowUnits
                close = candle.closeUnits
                volume += candle.tickVolume
                count++
            } else {
                out += HistoricalCandle(bucketStart, open, high, low, close, volume)
                bucketStart = start
                open = candle.openUnits
                high = candle.highUnits
                low = candle.lowUnits
                close = candle.closeUnits
                volume = candle.tickVolume
                count = 1
            }
        }
        out += HistoricalCandle(bucketStart, open, high, low, close, volume)
        return out
    }

    /**
     * The forming candle for the bucket containing [revealed], built only from
     * the minutes in that bucket.
     *
     * [revealed] must contain only minutes at or before the simulated instant.
     * Returns null when the bucket holds no minutes, which means the current
     * bucket has not started in the revealed data yet.
     *
     * Volume is zero. See the class comment.
     */
    fun formingFrom(
        revealed: List<HistoricalCandle>,
        timeframe: Timeframe,
    ): HistoricalCandle? {
        if (revealed.isEmpty()) return null
        val bucketStart = timeframe.bucketStartUtcMs(revealed[0].timestampUtcMs)

        var open = revealed[0].openUnits
        var high = revealed[0].highUnits
        var low = revealed[0].lowUnits
        var close = revealed[0].closeUnits

        for (i in 1 until revealed.size) {
            val candle = revealed[i]
            if (candle.highUnits > high) high = candle.highUnits
            if (candle.lowUnits < low) low = candle.lowUnits
            close = candle.closeUnits
        }

        return HistoricalCandle(
            timestampUtcMs = bucketStart,
            openUnits = open,
            highUnits = high,
            lowUnits = low,
            closeUnits = close,
            tickVolume = 0,
        )
    }

    /**
     * The minutes of [revealed] that belong to the bucket containing the last
     * revealed minute.
     *
     * Walks backwards from the end, so it touches only the tail of a long series
     * rather than filtering all 90,000 candles per frame.
     */
    fun currentBucketMinutes(
        revealed: List<HistoricalCandle>,
        timeframe: Timeframe,
    ): List<HistoricalCandle> {
        if (revealed.isEmpty()) return emptyList()
        val last = revealed.last()
        val bucketStart = timeframe.bucketStartUtcMs(last.timestampUtcMs)

        var first = revealed.size - 1
        while (first > 0 && revealed[first - 1].timestampUtcMs >= bucketStart) {
            first--
        }
        return revealed.subList(first, revealed.size)
    }

    /**
     * A rough capacity for the result, so the aggregation does not repeatedly
     * grow a large `ArrayList` as it builds.
     */
    private fun estimateBucketCount(minutes: Int, timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.M1 -> minutes
        Timeframe.M15 -> minutes / 15 + 1
        Timeframe.H1 -> minutes / 60 + 1
        Timeframe.H4 -> minutes / 240 + 1
        // A trading day holds 1,440 minutes and a trading week five of those.
        // Holidays make the real count smaller; over-estimating only wastes a
        // little capacity, while under-estimating forces a reallocation.
        Timeframe.D1 -> minutes / 1_440 + 1
        Timeframe.W1 -> minutes / 7_200 + 1
    }
}
