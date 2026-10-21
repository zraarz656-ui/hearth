package com.tradequest.app.core.chart.coordinates

import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Chooses time-axis labels (Step 8.8).
 *
 * # Real candle times, not a synthetic clock
 *
 * A label is only ever placed on the timestamp of a candle that actually exists
 * in the buffer. The axis does not step at a fixed interval from the window's
 * edge, because on a real dataset that would put labels in the middle of the
 * weekend closure or on a public holiday, labelling time at which nothing
 * traded. Anchoring to stored candle times means every label is a moment the
 * market was genuinely open.
 *
 * # Zone
 *
 * Labels are rendered in New York time, matching
 * [NewYorkTradingCalendar.ZONE] and every other time decision in the app. A
 * chart labelled in UTC while the risk engine rolls the day at 17:00 ET would
 * disagree with itself about where the day boundary is.
 */
object TimeAxisTicks {

    private val ZONE: ZoneId = NewYorkTradingCalendar.ZONE

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM")
    private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")
    private val YEAR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy")

    /**
     * A chosen label placement.
     *
     * [major] marks a boundary a reader should notice — a new day at intraday
     * zoom, a new month at daily zoom — and is drawn with a stronger line and a
     * fuller label than the minor ticks between.
     */
    data class Tick(
        val utcMs: Long,
        val label: String,
        val major: Boolean,
    )

    /**
     * Picks at most [targetCount] ticks from the candle times in
     * `[fromIndex, toIndex]` of [timestamps].
     *
     * # Why this strides rather than filtering
     *
     * The simplest correct implementation would test every visible candle for a
     * boundary crossing and label the ones that match. At 90,000 candles zoomed
     * out that is 90,000 formatter calls per frame. Instead the stride is
     * computed from the count and the target, and only the sampled candles are
     * formatted — at most [targetCount] of them, whatever the zoom.
     *
     * The stride is a whole number of bars, so a tick always lands on a candle
     * that exists. Labels may therefore be slightly unevenly spaced when the
     * dataset has gaps; that is the honest outcome, and the alternative —
     * even spacing — would place labels on minutes that were never recorded.
     */
    fun ticks(
        timestamps: LongArray,
        fromIndex: Int,
        toIndex: Int,
        timeframe: Timeframe,
        targetCount: Int,
    ): List<Tick> {
        if (fromIndex < 0 || toIndex >= timestamps.size || fromIndex > toIndex || targetCount <= 0) {
            return emptyList()
        }

        val count = toIndex - fromIndex + 1
        val stride = maxOf(1, count / targetCount)

        val out = ArrayList<Tick>(targetCount + 1)
        var previousDate: LocalDate? = null
        var previousMonth: Int? = null
        var previousYear: Int? = null

        // Start at the last index so the newest label is always placed, then walk
        // backwards by the stride. Anchoring at the right edge keeps the most
        // recent time labelled as the window scrolls.
        var index = toIndex
        while (index >= fromIndex) {
            val utcMs = timestamps[index]
            val local = Instant.ofEpochMilli(utcMs).atZone(ZONE)
            val date = local.toLocalDate()
            val month = date.monthValue
            val year = date.year

            val newDay = date != previousDate
            val newMonth = month != previousMonth
            val newYear = year != previousYear

            val major = when {
                timeframe == Timeframe.D1 || timeframe == Timeframe.W1 -> newMonth || newYear
                else -> newDay
            }

            val label = when {
                newYear -> date.format(YEAR_FORMAT)
                timeframe == Timeframe.D1 || timeframe == Timeframe.W1 -> date.format(MONTH_FORMAT)
                newDay -> date.format(DAY_FORMAT)
                else -> local.format(TIME_FORMAT)
            }

            out += Tick(utcMs = utcMs, label = label, major = major)

            previousDate = date
            previousMonth = month
            previousYear = year
            index -= stride
        }

        // Built right-to-left so the newest label is anchored; reverse for a
        // conventional left-to-right draw order.
        out.reverse()
        return out
    }

    /**
     * The axis label for the very latest candle, shown at the right edge.
     *
     * Separate from [ticks] because it is always drawn, whatever the stride,
     * and it is the one label a user checks against the live price.
     */
    fun latestLabel(utcMs: Long, timeframe: Timeframe): String {
        val local = Instant.ofEpochMilli(utcMs).atZone(ZONE)
        return when (timeframe) {
            Timeframe.D1, Timeframe.W1 -> local.toLocalDate().format(DAY_FORMAT)
            else -> local.format(TIME_FORMAT)
        }
    }
}
