package com.tradequest.app.core.chart

import com.tradequest.app.core.market.HistoricalCandle
import java.time.ZoneId

/**
 * Fixtures for the Phase 8 chart tests.
 *
 * # Where the constants come from
 *
 * `MONDAY_OPEN_UTC_MS` is Sunday 2024-01-07 17:00 America/New_York, the start of
 * Monday 8 January's trading day, computed independently from the IANA database
 * rather than read back out of the code under test. January is chosen because
 * New York is on EST (UTC-5) with no DST transition nearby, so the arithmetic in
 * a test failure is easy to reason about by hand.
 *
 * All prices are integer thousandths of a USD. Nothing here is historical data.
 */
internal object ChartFixtures {

    /** Sunday 2024-01-07 17:00 ET = Monday's trading day start. */
    val MONDAY_OPEN_UTC_MS: Long =
        java.time.LocalDateTime.parse("2024-01-07T17:00:00")
            .atZone(ZoneId.of("America/New_York"))
            .toInstant()
            .toEpochMilli()

    const val MINUTE_MS: Long = 60_000L

    /** A gold price near the level the plan's examples use. */
    const val BASE_PRICE_UNITS: Int = 2_050_000

    /** Monday's trading day, as a plain date, for assertions. */
    val MONDAY_TRADING_DATE: java.time.LocalDate = java.time.LocalDate.of(2024, 1, 8)

    fun at(minuteIndex: Int): Long = MONDAY_OPEN_UTC_MS + minuteIndex * MINUTE_MS

    /**
     * One minute with the given OHLC, at [minuteIndex] into Monday's session.
     *
     * Validated shape: high is the maximum and low the minimum, so
     * [CandleValidator] invariants hold without the test having to think about
     * it.
     */
    fun candle(
        minuteIndex: Int,
        open: Int,
        close: Int,
        high: Int = maxOf(open, close),
        low: Int = minOf(open, close),
        volume: Int = 100,
    ): HistoricalCandle = HistoricalCandle(
        timestampUtcMs = at(minuteIndex),
        openUnits = open,
        highUnits = high,
        lowUnits = low,
        closeUnits = close,
        tickVolume = volume,
    )

    /** A rising series of [count] minutes, one unit per minute. */
    fun rising(count: Int, startUnits: Int = BASE_PRICE_UNITS): List<HistoricalCandle> =
        (0 until count).map { i ->
            val open = startUnits + i
            candle(i, open = open, close = open + 1, high = open + 2, low = open - 1)
        }

    /**
     * A flat series of [count] minutes at one price.
     *
     * Exercises the zero-span branch of the price axis, where a naive
     * implementation divides by zero.
     */
    fun flat(count: Int, price: Int = BASE_PRICE_UNITS): List<HistoricalCandle> =
        (0 until count).map { i -> candle(i, open = price, close = price, volume = 10) }
}
