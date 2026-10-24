package com.tradequest.app.core.challenge

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The relative clock shown in Blind Mode (Step 12.5).
 *
 * # What Blind Mode is defending against
 *
 * The plan's rule is that a challenge must not reveal *when* it is. A date is
 * not neutral information: an experienced trader who sees "January 2024" knows
 * a rate decision is imminent, that a specific NFP print is coming, and roughly
 * which way the market went afterwards. The dataset name is worse still — it
 * encodes the instrument and the span. Masking only a label but leaving the year
 * on the axis would defeat the exercise.
 *
 * So the clock is expressed in *relative* coordinates: a week ordinal, a day
 * ordinal within the week, and the time of day. The weekday is kept because
 * session behaviour depends on it and a trader would know it in real life. The
 * absolute date is not.
 *
 * # Why the origin is the season, not the dataset
 *
 * Anchoring week 1 to the season's start means "W1 D1" is always the first day
 * the player traded, which reveals nothing about the data. Anchoring to the
 * dataset's first candle would leak how much of the dataset has been consumed:
 * a player who knows the dataset holds 20 days and sees "D18" has learned
 * something they were not meant to have.
 *
 * # Why trading days, not calendar days
 *
 * A Friday followed by a Monday must read D5 then D6. Counting calendar days
 * would produce D5 then D8, and the two missing ordinals would announce the
 * weekend — which, combined with a known session time, narrows the date enough
 * to matter.
 */
object BlindClock {

    /** Days in a blind week. Seven, so the day ordinal stays a weekday. */
    const val DAYS_PER_WEEK = 7

    /**
     * A hard bound on the day walk in [tradingDayIndex].
     *
     * Two years is far beyond any season this app creates, so hitting it means
     * the caller passed a mismatched instant — a bug worth surfacing as a wrong
     * answer than a hang.
     */
    private const val MAX_SCAN_DAYS = 2 * 366

    /**
     * Formats [utcMs] as `W2 D3 14:30`.
     *
     * [tradingDayIndex] is the count of *trading* days from the season start to
     * [utcMs], supplied by the caller because the trading calendar knows it and
     * this formatter deliberately does not. The week ordinal and the day ordinal
     * are both 1-based, so the first trading day is `W1 D1`.
     */
    fun format(utcMs: Long, tradingDayIndex: Int): String {
        val dayIndex = tradingDayIndex.coerceAtLeast(0)
        val week = dayIndex / DAYS_PER_WEEK + 1
        val day = dayIndex % DAYS_PER_WEEK + 1
        val time = Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC)
        val hh = time.hour.toString().padStart(2, '0')
        val mm = time.minute.toString().padStart(2, '0')
        return "W$week D$day $hh:$mm"
    }

    /**
     * Counts trading days in `(startUtcMs, atUtcMs]`.
     *
     * [isTradingDay] is asked about each UTC midnight in the interval; the
     * calendar that knows the closures supplies it. Returns 0 when [atUtcMs] is
     * at or before [startUtcMs], so the first day of a season reads D1 and the
     * instant of the start itself reads D0 (the caller formats the first candle
     * with index 0).
     */
    fun tradingDayIndex(
        startUtcMs: Long,
        atUtcMs: Long,
        isTradingDay: (Long) -> Boolean,
    ): Int {
        if (atUtcMs <= startUtcMs) return 0
        var day = startOfUtcDay(startUtcMs)
        val end = startOfUtcDay(atUtcMs)
        val oneDayMs = ChronoUnit.DAYS.duration.toMillis()
        var index = 0
        var guard = 0
        while (day < end) {
            day += oneDayMs
            guard++
            if (guard > MAX_SCAN_DAYS) return index
            if (isTradingDay(day)) index++
        }
        return index
    }

    /** UTC midnight of the day containing [utcMs]. */
    private fun startOfUtcDay(utcMs: Long): Long =
        Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS)
            .toInstant().toEpochMilli()
}

/**
 * Masks the identifying parts of a dataset name for Blind Mode (Step 12.5).
 *
 * # Why this is separate from [BlindClock]
 *
 * The clock hides *when*; this hides *what*. A dataset named
 * `XAUUSD_2024-01-02_2024-01-22` leaks the instrument, the year and the exact
 * span in one string, and it appears in the Market Library, the challenge
 * dashboard and the app bar. Each of those is a place the name could leak from,
 * so the masking is a tested function rather than a string edit at each site.
 */
object BlindLabel {

    /**
     * A stable, non-identifying label for a dataset, e.g. `Dataset 3F2A`.
     *
     * Derived from a hash of the dataset id, so the same dataset keeps the same
     * label across screens and sessions. A label that changed between screens
     * would be its own leak, because the user could correlate the two.
     *
     * `String.hashCode` rather than a cryptographic digest: this is not a
     * security boundary. The dataset id is already in the database and readable
     * by anyone with the device; the goal is only to avoid *displaying* it, so a
     * stable, collision-tolerant label is the right tool.
     */
    fun forDataset(datasetId: String): String {
        val hash = datasetId.hashCode().toLong() and 0xFFFFFFFFL
        val suffix = hash.toString(16).uppercase().padStart(8, '0').takeLast(4)
        return "Dataset $suffix"
    }
}
