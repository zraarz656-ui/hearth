package com.tradequest.app.core.simulation.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The New York trading calendar (Step 3.6).
 *
 * # Trading-day convention
 *
 * The logical trading day rolls over at **17:00 America/New_York**, and a
 * trading day is labelled by the date it **closes** on. So:
 *
 * ```
 * trading day "Tuesday" = [Monday 17:00 ET, Tuesday 17:00 ET)
 * ```
 *
 * This is the standard spot-metals convention, and it has a property worth
 * stating explicitly because it surprises people: the week's first session
 * opens around Sunday 17:00 ET and belongs to **Monday's** trading day. A
 * trader who opens a position on Sunday evening has made a Monday trade, which
 * is what the prop-firm rules in Step 11.4 count.
 *
 * The alternative convention — labelling the day by its opening date — would
 * make Sunday evening its own "Sunday" trading day, and would count the week's
 * opening session as a day on which no bank is open. This one is used instead.
 *
 * # Why 17:00 is safe
 *
 * US daylight-saving transitions occur at 02:00 local. 17:00 therefore never
 * falls in a DST gap or a DST overlap, so every trading-day boundary exists and
 * is unambiguous in *all* cases. This is the reason the calendar is anchored on
 * 17:00 rather than on a midnight boundary, which would be ambiguous or
 * non-existent on transition days.
 */
object NewYorkTradingCalendar {

    val ZONE: ZoneId = ZoneId.of("America/New_York")

    /** Hour of the daily rollover, in New York local time. */
    const val ROLLOVER_HOUR: Int = 17

    /** The first trading day of the week, by convention. */
    private val FIRST_TRADING_WEEKDAY = DayOfWeek.MONDAY

    private val LAST_TRADING_WEEKDAY = DayOfWeek.FRIDAY

    /**
     * The trading day an instant belongs to.
     *
     * At or after 17:00 local the next day has already begun, so 18:00 Monday
     * belongs to Tuesday's trading day while 10:00 Tuesday also belongs to
     * Tuesday's.
     */
    fun tradingDateOf(utcMs: Long): LocalDate {
        val local = Instant.ofEpochMilli(utcMs).atZone(ZONE)
        return if (local.hour >= ROLLOVER_HOUR) {
            local.toLocalDate().plusDays(1)
        } else {
            local.toLocalDate()
        }
    }

    /** The instant a trading day begins: 17:00 ET on the preceding calendar day. */
    fun tradingDayStartUtcMs(tradingDate: LocalDate): Long =
        ZonedDateTime.of(tradingDate.minusDays(1), LocalTime.of(ROLLOVER_HOUR, 0), ZONE)
            .toInstant()
            .toEpochMilli()

    /** The instant a trading day ends: its own 17:00 ET. */
    fun tradingDayEndUtcMs(tradingDate: LocalDate): Long =
        ZonedDateTime.of(tradingDate, LocalTime.of(ROLLOVER_HOUR, 0), ZONE)
            .toInstant()
            .toEpochMilli()

    /**
     * True when [tradingDate] is a weekday and therefore a trading day.
     *
     * Saturday is never a trading day. Sunday is never a trading day either:
     * the Sunday-evening open belongs to Monday under the convention above, so
     * no trading day is ever labelled Sunday.
     */
    fun isTradingDay(tradingDate: LocalDate): Boolean =
        tradingDate.dayOfWeek >= FIRST_TRADING_WEEKDAY &&
            tradingDate.dayOfWeek <= LAST_TRADING_WEEKDAY

    /** The UTC offset in force in New York at [utcMs], for diagnostics and tests. */
    fun offsetSecondsAt(utcMs: Long): Int =
        Instant.ofEpochMilli(utcMs).atZone(ZONE).offset.totalSeconds

    /** True when New York is observing daylight saving at [utcMs]. */
    fun isDaylightSavingAt(utcMs: Long): Boolean =
        ZONE.rules.isDaylightSavings(Instant.ofEpochMilli(utcMs))

    /**
     * True when the market is in its normal weekly session.
     *
     * The spot-metals week runs from the Sunday-evening open to the Friday
     * 17:00 ET close. Expressed in trading-day terms that is: every weekday,
     * plus the Sunday evening that belongs to Monday.
     *
     * Holidays are **not** handled here. `MarketClosureDetector` in
     * `core.market` owns holiday classification, and it deliberately labels
     * only closures it has *observed* in real data rather than assuming a
     * holiday is empty. Duplicating a holiday table here would give the
     * simulation a second, conflicting source of truth.
     */
    fun isMarketOpen(utcMs: Long): Boolean {
        val local = Instant.ofEpochMilli(utcMs).atZone(ZONE)
        return when (local.dayOfWeek) {
            DayOfWeek.SATURDAY -> false
            DayOfWeek.SUNDAY -> local.hour >= ROLLOVER_HOUR
            DayOfWeek.FRIDAY -> local.hour < ROLLOVER_HOUR
            else -> true
        }
    }

    /**
     * The next instant the market opens, at or after [utcMs].
     *
     * Returns [utcMs] unchanged when the market is already open. Used by Time
     * Warp (Phase 12) to skip a weekend; it is here rather than in Phase 12
     * because it is a property of the calendar, not of the warp.
     */
    fun nextMarketOpenUtcMs(utcMs: Long): Long {
        if (isMarketOpen(utcMs)) return utcMs
        var cursor = Instant.ofEpochMilli(utcMs).atZone(ZONE)
        // At most a week of hours; bounded so a malformed input cannot spin.
        repeat(MAX_HOURS_TO_SCAN) {
            if (isMarketOpen(cursor.toInstant().toEpochMilli())) {
                return cursor.toInstant().toEpochMilli()
            }
            cursor = cursor.plusHours(1)
        }
        return cursor.toInstant().toEpochMilli()
    }

    /** The number of whole days between two trading dates. */
    fun tradingDaysBetween(from: LocalDate, to: LocalDate): Int {
        var count = 0
        var cursor = from
        while (cursor.isBefore(to)) {
            if (isTradingDay(cursor)) count++
            cursor = cursor.plusDays(1)
        }
        return count
    }

    private const val MAX_HOURS_TO_SCAN = 24 * 8
}

/** A named trading session (Step 3.7). */
enum class TradingSession {
    /** Tokyo hours. */
    ASIA,

    /** London hours. */
    LONDON,

    /** New York hours, outside the London overlap. */
    NEW_YORK,

    /** London and New York both open. The most liquid period of the day. */
    LONDON_NEW_YORK_OVERLAP,

    /** No session is open. */
    CLOSED,
}

/**
 * Session classification (Step 3.7).
 *
 * # Why sessions are defined in their own timezones
 *
 * Each session is defined by the business hours of the market it belongs to, in
 * *that market's* timezone. This is not a stylistic choice; it is the only way
 * to get the overlap right.
 *
 * The US and UK change clocks on **different dates**: the US on the second
 * Sunday in March and the first Sunday in November, the UK on the last Sunday
 * in March and the last Sunday in October. For roughly two weeks each spring
 * and each autumn the two are an hour out of step, and the London/New York
 * overlap genuinely widens or narrows by an hour.
 *
 * A fixed offset from UTC, or a single ET-based table, would silently produce
 * the wrong overlap for those weeks every year. Defining each session in its
 * own zone lets `java.time` apply the correct rule, and there is a test that
 * pins the mismatch week specifically.
 *
 * # Priority
 *
 * Sessions overlap, so a single label is chosen by specificity:
 * overlap, then New York, then London, then Asia. The full set of open sessions
 * is available from [sessionsAt].
 */
object SessionClassifier {

    /** Tokyo business hours, in Tokyo local time. */
    val ASIA_WINDOW: SessionWindow = SessionWindow(
        zoneId = ZoneId.of("Asia/Tokyo"),
        start = LocalTime.of(9, 0),
        end = LocalTime.of(18, 0),
    )

    /** London business hours, in London local time. */
    val LONDON_WINDOW: SessionWindow = SessionWindow(
        zoneId = ZoneId.of("Europe/London"),
        start = LocalTime.of(8, 0),
        end = LocalTime.of(17, 0),
    )

    /** New York business hours, in New York local time. */
    val NEW_YORK_WINDOW: SessionWindow = SessionWindow(
        zoneId = NewYorkTradingCalendar.ZONE,
        start = LocalTime.of(8, 0),
        end = LocalTime.of(17, 0),
    )

    /** Every session open at [utcMs]. Empty when the market is closed. */
    fun sessionsAt(utcMs: Long): Set<TradingSession> {
        val open = mutableSetOf<TradingSession>()
        if (ASIA_WINDOW.contains(utcMs)) open += TradingSession.ASIA
        val london = LONDON_WINDOW.contains(utcMs)
        val newYork = NEW_YORK_WINDOW.contains(utcMs)
        if (london) open += TradingSession.LONDON
        if (newYork) open += TradingSession.NEW_YORK
        if (london && newYork) open += TradingSession.LONDON_NEW_YORK_OVERLAP
        return open
    }

    /** The single most specific session label for [utcMs]. */
    fun classify(utcMs: Long): TradingSession {
        val open = sessionsAt(utcMs)
        return when {
            TradingSession.LONDON_NEW_YORK_OVERLAP in open -> TradingSession.LONDON_NEW_YORK_OVERLAP
            TradingSession.NEW_YORK in open -> TradingSession.NEW_YORK
            TradingSession.LONDON in open -> TradingSession.LONDON
            TradingSession.ASIA in open -> TradingSession.ASIA
            else -> TradingSession.CLOSED
        }
    }

    /** A session's daily open window, expressed in its own timezone. */
    data class SessionWindow(
        val zoneId: ZoneId,
        val start: LocalTime,
        val end: LocalTime,
    ) {
        /**
         * True when [utcMs] falls inside the window.
         *
         * A window whose [end] is not after its [start] would wrap midnight; no
         * window here does, and rather than guess a policy for a case that does
         * not occur, wrapping is treated as always-closed and asserted against
         * in test.
         */
        fun contains(utcMs: Long): Boolean {
            if (!end.isAfter(start)) return false
            val local = Instant.ofEpochMilli(utcMs).atZone(zoneId).toLocalTime()
            return !local.isBefore(start) && local.isBefore(end)
        }
    }
}
