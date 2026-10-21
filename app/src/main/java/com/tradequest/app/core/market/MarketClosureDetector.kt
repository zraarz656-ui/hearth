package com.tradequest.app.core.market

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Why a period had no data. */
enum class ClosureReason {
    /** Expected weekend closure. */
    WEEKEND,

    /** A known market holiday. */
    HOLIDAY,

    /** Absent data with no closure explanation. Needs investigation. */
    UNEXPECTED,
}

/** Classification of one UTC day. */
data class DayClassification(
    val date: LocalDate,
    val reason: ClosureReason?,
    val candleCount: Int,
    val missingMinuteCount: Int,
) {
    val isClosed: Boolean get() = reason == ClosureReason.WEEKEND || reason == ClosureReason.HOLIDAY

    val status: String
        get() = when {
            reason == ClosureReason.WEEKEND -> "weekend_closure"
            reason == ClosureReason.HOLIDAY -> "holiday_closure"
            candleCount == 0 -> "no_data (unexpected)"
            missingMinuteCount > 0 -> "active_with_gaps ($missingMinuteCount missing minutes)"
            else -> "complete"
        }
}

/** Result of classifying a whole dataset. */
data class ClosureReport(
    val days: List<DayClassification>,
    val weekendDays: Int,
    val holidayDays: Int,
    val unexpectedEmptyDays: Int,
    val totalMissingMinutes: Int,
) {
    val hasUnexpectedGaps: Boolean get() = unexpectedEmptyDays > 0
}

/**
 * Classifies missing periods as weekend, holiday, or unexpected.
 *
 * The plan requires that not every missing minute be treated as normal
 * closure. XAUUSD trades roughly 23 hours a day, Sunday evening through
 * Friday evening, so Saturday and most of Sunday are genuinely closed. Any
 * other empty day is reported as unexpected rather than silently excused.
 *
 * Holidays are deliberately *not* inferred. Measured on real data, Christmas
 * Day returned 7.9 KB of ticks rather than nothing, so an empty holiday is
 * not a safe assumption. The known-holiday list below is only used to label
 * a closure that has already been observed, never to excuse absent data.
 */
object MarketClosureDetector {

    /** UTC hour when the gold week opens (Sunday 22:00 UTC, roughly Sydney open). */
    private const val WEEK_OPEN_HOUR_UTC = 22

    /** UTC hour when the gold week closes (Friday 21:00 UTC). */
    private const val WEEK_CLOSE_HOUR_UTC = 21

    /**
     * Fixed-date holidays that close the metals market.
     *
     * Only used to label an observed closure. Absence of data on one of these
     * dates is not assumed.
     */
    private val FIXED_HOLIDAYS: Set<Pair<Month, Int>> = setOf(
        Month.JANUARY to 1,    // New Year's Day
        Month.DECEMBER to 25,  // Christmas Day
    )

    fun classify(
        candles: List<HistoricalCandle>,
        validation: ValidationReport,
        zone: ZoneId = ZoneId.of("UTC"),
    ): ClosureReport {
        val missingSet = validation.missingMinutes.toHashSet()

        val byDay = candles.groupBy { instantToDate(it.timestampUtcMs, zone) }
        val missingByDay = validation.missingMinutes.groupBy { instantToDate(it, zone) }

        val allDates = (byDay.keys + missingByDay.keys).sorted()
        if (allDates.isEmpty()) {
            return ClosureReport(emptyList(), 0, 0, 0, 0)
        }

        val days = allDates.map { date ->
            val count = byDay[date]?.size ?: 0
            val missing = missingByDay[date]?.size ?: 0
            DayClassification(
                date = date,
                reason = classifyReason(date, count, zone),
                candleCount = count,
                missingMinuteCount = missing,
            )
        }

        return ClosureReport(
            days = days,
            weekendDays = days.count { it.reason == ClosureReason.WEEKEND },
            holidayDays = days.count { it.reason == ClosureReason.HOLIDAY },
            unexpectedEmptyDays = days.count { it.reason == ClosureReason.UNEXPECTED },
            totalMissingMinutes = validation.missingMinutes.size,
        )
    }

    private fun classifyReason(date: LocalDate, candleCount: Int, zone: ZoneId): ClosureReason? {
        if (candleCount == 0 && isWeekendClosure(date)) return ClosureReason.WEEKEND
        if (candleCount == 0 && isKnownHoliday(date)) return ClosureReason.HOLIDAY
        if (candleCount == 0) return ClosureReason.UNEXPECTED
        return null
    }

    /**
     * True when a date is unambiguously part of the normal weekly closure.
     *
     * Deliberately conservative. Only Saturday and Sunday are treated as
     * weekend closure, and only when they carry no data at all. Friday is
     * *not* excused even though it closes early, because a fully empty
     * Friday is unusual and deserves to be reported rather than assumed
     * away. A weekend day that does carry candles is likewise not treated as
     * closed, so real data is never discarded on a calendar assumption.
     */
    private fun isWeekendClosure(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    private fun isKnownHoliday(date: LocalDate): Boolean =
        FIXED_HOLIDAYS.contains(date.month to date.dayOfMonth)

    private fun instantToDate(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /**
     * True when [timestampUtcMs] falls in the normal weekly trading window.
     *
     * Approximate by nature: the real open and close shift with US and
     * European daylight saving. The hours below are therefore used only to
     * decide whether a gap is *expected*, never to discard or fabricate
     * data. Callers should treat a boundary result as uncertain.
     */
    fun isWithinTradingWeek(timestampUtcMs: Long, zone: ZoneId = ZoneId.of("UTC")): Boolean {
        val zdt = Instant.ofEpochMilli(timestampUtcMs).atZone(zone)
        return when (zdt.dayOfWeek) {
            DayOfWeek.SATURDAY -> false
            DayOfWeek.SUNDAY -> zdt.hour >= WEEK_OPEN_HOUR_UTC
            DayOfWeek.FRIDAY -> zdt.hour < WEEK_CLOSE_HOUR_UTC
            else -> true
        }
    }

    /** Number of whole minutes between two UTC epoch millisecond values. */
    fun minutesBetween(startUtcMs: Long, endUtcMs: Long): Long =
        ChronoUnit.MINUTES.between(
            Instant.ofEpochMilli(startUtcMs),
            Instant.ofEpochMilli(endUtcMs),
        )
}
