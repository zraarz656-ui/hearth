package com.tradequest.app.core.chart.timeframe

import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/**
 * A chart timeframe (Step 8.11).
 *
 * # Why buckets are aligned to the trading day, not to UTC midnight
 *
 * A 4-hour candle is a convention, and which instants it covers is part of that
 * convention. Two choices are defensible: align to UTC midnight, or align to the
 * trading day. This app already has one answer to "when does a day start" —
 * 17:00 New York ([NewYorkTradingCalendar.ROLLOVER_HOUR]) — and the clock, the
 * risk engine's daily floor and the challenge rules all use it.
 *
 * Aligning to UTC midnight would put 4-hour boundaries at 20:00 and 00:00 ET,
 * which straddle the daily rollover and split one trading day's activity across
 * buckets no other part of the app recognises. Aligning to the trading day gives
 * 17:00, 21:00, 01:00, 05:00, 09:00 and 13:00 ET — the standard FX alignment —
 * and keeps every bucket inside a single trading day.
 *
 * # DST
 *
 * [M15], [H1] and [H4] are offsets from the trading day's start, which is safe
 * because a trading day is exactly 24 hours of wall time. New York's clock
 * changes at 02:00 on a Sunday, inside the weekend closure, never inside a
 * trading day, so the offset in force cannot change mid-day.
 */
enum class Timeframe(
    /** Human label, for the timeframe selector. */
    val label: String,
    /** Bucket length in milliseconds, or null for calendar-based frames. */
    private val fixedDurationMs: Long?,
) {
    M1("1m", 60_000L),
    M15("15m", 15L * 60_000L),
    H1("1h", 60L * 60_000L),
    H4("4h", 4L * 60L * 60_000L),

    /** One trading day: 17:00 ET to 17:00 ET. */
    D1("1D", null),

    /** One trading week: Monday's trading day through Friday's. */
    W1("1W", null),
    ;

    /** True when this frame is a whole number of minutes. */
    val isIntraday: Boolean get() = fixedDurationMs != null

    /**
     * The start of the bucket containing [utcMs], in UTC epoch milliseconds.
     *
     * Every instant maps to exactly one bucket start, and an instant already on
     * a boundary is its own bucket start, so this is idempotent.
     */
    fun bucketStartUtcMs(utcMs: Long): Long {
        val duration = fixedDurationMs
        if (duration == null) {
            return if (this == W1) weekStartUtcMs(utcMs) else tradingDayStartUtcMs(utcMs)
        }
        val dayStart = tradingDayStartUtcMs(utcMs)
        // Distance into the trading day, floored to a whole number of buckets.
        // Non-negative and under 24h, because dayStart is at or before utcMs.
        val intoDay = utcMs - dayStart
        return dayStart + (intoDay / duration) * duration
    }

    /** The start of the trading day containing [utcMs]. */
    private fun tradingDayStartUtcMs(utcMs: Long): Long =
        NewYorkTradingCalendar.tradingDayStartUtcMs(NewYorkTradingCalendar.tradingDateOf(utcMs))

    /**
     * The start of the trading week containing [utcMs].
     *
     * The week begins at Monday's trading day start, which is Sunday 17:00 ET.
     * A Saturday belongs to the week that just ended, which is what
     * [TemporalAdjusters.previousOrSame] gives: Saturday adjusts back to the
     * Monday of its own calendar week.
     */
    private fun weekStartUtcMs(utcMs: Long): Long {
        val tradingDate = NewYorkTradingCalendar.tradingDateOf(utcMs)
        val monday = tradingDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return NewYorkTradingCalendar.tradingDayStartUtcMs(monday)
    }
}
