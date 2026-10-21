package com.tradequest.app.core.market

/**
 * Synthetic fixture builders for Phase 1 tests.
 *
 * Everything here is fabricated and must never be presented as historical
 * data. Fixtures exist to exercise edge cases that real captures do not
 * reliably contain.
 *
 * [SYNTHETIC_MARKER] is embedded in every generated description so that a
 * synthetic dataset is identifiable in a manifest.
 */
object SyntheticFixtures {

    const val SYNTHETIC_MARKER = "SYNTHETIC — NOT HISTORICAL DATA"

    const val MINUTE_MS = 60_000L

    /** Monday 2024-01-15 00:00:00 UTC, a normal trading day. */
    const val MONDAY_00_UTC = 1_705_276_800_000L

    fun candle(
        timestampUtcMs: Long,
        open: Int,
        high: Int,
        low: Int,
        close: Int,
        volume: Int = 100,
    ) = HistoricalCandle(timestampUtcMs, open, high, low, close, volume)

    /** Builds [count] minute candles starting at [startUtcMs] using [builder]. */
    fun series(
        startUtcMs: Long,
        count: Int,
        builder: (index: Int, timestampUtcMs: Long) -> HistoricalCandle,
    ): List<HistoricalCandle> = (0 until count).map { i ->
        builder(i, startUtcMs + i * MINUTE_MS)
    }

    /** A run of bullish candles, each closing above the previous close. */
    fun bullish(startUtcMs: Long = MONDAY_00_UTC, count: Int = 10): List<HistoricalCandle> =
        series(startUtcMs, count) { i, ts ->
            val base = 2047_000 + i * 500
            candle(ts, base, base + 800, base - 200, base + 600)
        }

    /** A run of bearish candles, each closing below the previous close. */
    fun bearish(startUtcMs: Long = MONDAY_00_UTC, count: Int = 10): List<HistoricalCandle> =
        series(startUtcMs, count) { i, ts ->
            val base = 2047_000 - i * 500
            candle(ts, base, base + 200, base - 800, base - 600)
        }

    /** Doji candles: open equals close, with a real range. */
    fun doji(startUtcMs: Long = MONDAY_00_UTC, count: Int = 5): List<HistoricalCandle> =
        series(startUtcMs, count) { i, ts ->
            val base = 2047_000 + i * 100
            candle(ts, base, base + 500, base - 500, base)
        }

    /**
     * A Friday-to-Sunday weekend gap.
     *
     * Friday 2024-01-19 21:00 UTC through Sunday 2024-01-21 22:00 UTC, which
     * is the normal weekly closure for spot gold.
     */
    fun weekendGap(): List<HistoricalCandle> {
        val fridayClose = 1_705_698_000_000L // Fri 2024-01-19 21:00 UTC
        val sundayOpen = 1_705_874_400_000L  // Sun 2024-01-21 22:00 UTC
        return bullish(fridayClose, 5) + bullish(sundayOpen, 5)
    }

    /** An unexpected gap in the middle of a trading day. */
    fun unexpectedGap(
        startUtcMs: Long = MONDAY_00_UTC,
        before: Int = 30,
        missingMinutes: Int = 45,
        after: Int = 30,
    ): List<HistoricalCandle> {
        val first = bullish(startUtcMs, before)
        val secondStart = startUtcMs + (before + missingMinutes) * MINUTE_MS
        return first + bullish(secondStart, after)
    }

    /** A single wildly out-of-range candle, used to test spike detection. */
    fun priceSpike(startUtcMs: Long = MONDAY_00_UTC, count: Int = 20): List<HistoricalCandle> {
        val normal = bullish(startUtcMs, count)
        val spikeIndex = count / 2
        val ts = startUtcMs + spikeIndex * MINUTE_MS
        val spike = candle(ts, 2047_000, 2099_000, 1990_000, 2048_000)
        return normal.toMutableList().also { it[spikeIndex] = spike }
    }

    /**
     * Candles spanning a US DST transition.
     *
     * 2024-03-10 07:00 UTC is when US Eastern shifts to daylight time. The
     * UTC timeline stays continuous, so this fixture checks that import does
     * not introduce or drop a minute at the boundary.
     */
    fun dstTransition(): List<HistoricalCandle> {
        val start = 1_710_046_800_000L // 2024-03-10 07:00 UTC
        return series(start, 120) { i, ts ->
            val base = 2047_000 + i * 50
            candle(ts, base, base + 300, base - 300, base + 100)
        }
    }

    /** Candles that are structurally invalid and must be rejected. */
    fun invalidOhlc(): List<HistoricalCandle> = listOf(
        candle(MONDAY_00_UTC, 2047_000, 2046_000, 2045_000, 2047_500),
        candle(MONDAY_00_UTC + MINUTE_MS, 2047_000, 2047_500, 2048_000, 2047_000),
        candle(MONDAY_00_UTC + 2 * MINUTE_MS, 2047_000, 2046_000, 2048_000, 2047_000),
    )

    /** A series with a duplicated timestamp. */
    fun duplicateTimestamps(): List<HistoricalCandle> {
        val base = bullish(MONDAY_00_UTC, 5)
        return base + base[2]
    }

    /** Serialises candles to CSV in the format `tools/dukascopy/` writes. */
    fun toCsv(
        candles: List<HistoricalCandle>,
        header: String = "timestamp_utc,open,high,low,close,tick_volume",
        includeHeader: Boolean = true,
    ): String {
        val sb = StringBuilder()
        if (includeHeader) sb.append(header).append('\n')
        candles.forEach { c ->
            sb.append(java.time.Instant.ofEpochMilli(c.timestampUtcMs).toString())
                .append(',')
                .append(PriceParser.unitsToCanonicalString(c.openUnits)).append(',')
                .append(PriceParser.unitsToCanonicalString(c.highUnits)).append(',')
                .append(PriceParser.unitsToCanonicalString(c.lowUnits)).append(',')
                .append(PriceParser.unitsToCanonicalString(c.closeUnits)).append(',')
                .append(c.tickVolume).append('\n')
        }
        return sb.toString()
    }
}
