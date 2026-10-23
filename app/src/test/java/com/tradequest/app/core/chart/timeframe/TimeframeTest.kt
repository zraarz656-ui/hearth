package com.tradequest.app.core.chart.timeframe

import com.tradequest.app.core.chart.ChartFixtures
import com.tradequest.app.core.chart.ChartFixtures.MINUTE_MS
import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 8.11 tests: timeframe bucket alignment.
 *
 * # What is being pinned
 *
 * That intraday buckets are offsets from the *trading day* start rather than UTC
 * midnight, and that day and week buckets follow the 17:00 ET rollover. The
 * expected UTC values are computed from the IANA database, not read out of the
 * code, so a change in the alignment rule fails rather than silently agreeing.
 */
class TimeframeTest {

    private val ny = ZoneId.of("America/New_York")

    private fun nyUtcMs(text: String): Long =
        java.time.LocalDateTime.parse(text)
            .atZone(ny)
            .toInstant()
            .toEpochMilli()

    // ---------- Alignment to the trading day (Step 8.11) ----------

    @Test
    fun `an hourly bucket starts at the trading day open, which is 17 ET`() {
        val start = ChartFixtures.MONDAY_OPEN_UTC_MS
        assertEquals(start, Timeframe.H1.bucketStartUtcMs(start))
        assertEquals(nyUtcMs("2024-01-07T17:00:00"), Timeframe.H1.bucketStartUtcMs(start))
    }

    @Test
    fun `an hourly bucket boundary lands on the hour in New York`() {
        // 20:30 ET is inside the 20:00 ET hourly bucket.
        val instant = nyUtcMs("2024-01-08T20:30:00")
        val bucket = Timeframe.H1.bucketStartUtcMs(instant)
        assertEquals(nyUtcMs("2024-01-08T20:00:00"), bucket)

        val local = java.time.Instant.ofEpochMilli(bucket).atZone(ny).toLocalTime()
        assertEquals(java.time.LocalTime.of(20, 0), local)
    }

    @Test
    fun `a four-hour bucket is aligned to the trading day, not to UTC midnight`() {
        // New York's offset is a whole number of hours in both EST and EDT, so a
        // 1h boundary is a UTC hour boundary either way and proves nothing about
        // the alignment rule. The 4h frame is where the choice becomes visible:
        // the trading day opens at 17:00 ET, which is 22:00 UTC, and 22:00 is not
        // a multiple of four hours from UTC midnight.
        val dayOpen = nyUtcMs("2024-01-07T17:00:00")
        assertNotEquals(
            "a trading-day-aligned 4h boundary must not coincide with UTC midnight",
            0L,
            dayOpen % (4L * 3_600_000L),
        )
        assertEquals(dayOpen, Timeframe.H4.bucketStartUtcMs(dayOpen))
    }

    @Test
    fun `a four-hour bucket starts at 21 ET for an instant just after it`() {
        // The 4h boundaries from the 17:00 ET day open are 17, 21, 01, 05, 09, 13.
        val instant = nyUtcMs("2024-01-08T23:30:00")
        assertEquals(nyUtcMs("2024-01-08T21:00:00"), Timeframe.H4.bucketStartUtcMs(instant))
    }

    @Test
    fun `a four-hour bucket crosses midnight into the next calendar date`() {
        // 02:30 ET on the 9th is in the 01:00 ET bucket, and the calendar date of
        // that bucket differs from the date of the trading day it belongs to.
        val instant = nyUtcMs("2024-01-09T02:30:00")
        val bucket = Timeframe.H4.bucketStartUtcMs(instant)
        assertEquals(nyUtcMs("2024-01-09T01:00:00"), bucket)

        // 01:00 ET is before the 17:00 rollover, so it is the 9th's own trading
        // day, which began at 17:00 on the 8th. The bucket's calendar date and
        // its trading date therefore differ, which is the property being pinned.
        val tradingDate = NewYorkTradingCalendar.tradingDateOf(bucket)
        assertEquals(java.time.LocalDate.of(2024, 1, 9), tradingDate)
        assertEquals(
            nyUtcMs("2024-01-08T17:00:00"),
            NewYorkTradingCalendar.tradingDayStartUtcMs(tradingDate),
        )
    }

    @Test
    fun `bucket start is idempotent on an exact boundary`() {
        for (frame in Timeframe.entries) {
            val boundary = frame.bucketStartUtcMs(nyUtcMs("2024-01-08T10:30:00"))
            assertEquals(
                "re-bucketing a $frame boundary must not move it",
                boundary,
                frame.bucketStartUtcMs(boundary),
            )
        }
    }

    @Test
    fun `every intraday bucket is exactly the frame length`() {
        val start = Timeframe.H4.bucketStartUtcMs(nyUtcMs("2024-01-08T10:30:00"))
        val next = Timeframe.H4.bucketStartUtcMs(start + 4 * 60 * MINUTE_MS)
        assertEquals(4 * 60 * MINUTE_MS, next - start)
    }

    // ---------- Daily and weekly (Step 8.11) ----------

    @Test
    fun `a daily bucket is the trading day, 17 ET to 17 ET`() {
        val instant = nyUtcMs("2024-01-08T10:00:00")
        val bucket = Timeframe.D1.bucketStartUtcMs(instant)
        assertEquals(nyUtcMs("2024-01-07T17:00:00"), bucket)
        assertEquals(
            nyUtcMs("2024-01-08T17:00:00"),
            NewYorkTradingCalendar.tradingDayEndUtcMs(NewYorkTradingCalendar.tradingDateOf(bucket)),
        )
    }

    @Test
    fun `the evening before belongs to the next trading day, not the calendar date`() {
        // 18:00 ET Sunday is already Monday's trading day.
        val instant = nyUtcMs("2024-01-07T18:00:00")
        assertEquals(
            nyUtcMs("2024-01-07T17:00:00"),
            Timeframe.D1.bucketStartUtcMs(instant),
        )
    }

    @Test
    fun `a weekly bucket starts at Monday's trading day open`() {
        val wednesday = nyUtcMs("2024-01-10T12:00:00")
        assertEquals(
            nyUtcMs("2024-01-07T17:00:00"),
            Timeframe.W1.bucketStartUtcMs(wednesday),
        )
    }

    @Test
    fun `every day of one week maps to the same weekly bucket`() {
        val expected = nyUtcMs("2024-01-07T17:00:00")
        val days = listOf(
            "2024-01-07T18:00:00", // Sunday evening open
            "2024-01-08T09:00:00", // Monday
            "2024-01-10T09:00:00", // Wednesday
            "2024-01-12T16:59:00", // Friday, last minute before the close
        )
        for (text in days) {
            assertEquals("$text belongs to the week starting $expected", expected, Timeframe.W1.bucketStartUtcMs(nyUtcMs(text)))
        }
    }

    @Test
    fun `a Saturday belongs to the week that just ended`() {
        val saturday = nyUtcMs("2024-01-13T12:00:00")
        assertEquals(
            nyUtcMs("2024-01-07T17:00:00"),
            Timeframe.W1.bucketStartUtcMs(saturday),
        )
    }

    @Test
    fun `the next week starts on the following Sunday evening`() {
        val nextSunday = nyUtcMs("2024-01-14T18:00:00")
        assertEquals(
            nyUtcMs("2024-01-14T17:00:00"),
            Timeframe.W1.bucketStartUtcMs(nextSunday),
        )
    }

    // ---------- DST (Step 8.11) ----------

    @Test
    fun `hourly alignment survives the spring DST change`() {
        // DST began Sun 2024-03-10 02:00 ET. The Monday after is still a 24-hour
        // trading day, so the hour offsets are unchanged.
        val mondayAfterDst = java.time.LocalDateTime.parse("2024-03-11T10:30:00")
            .atZone(ny)
            .toInstant()
            .toEpochMilli()

        val bucket = Timeframe.H1.bucketStartUtcMs(mondayAfterDst)
        val local = java.time.Instant.ofEpochMilli(bucket).atZone(ny).toLocalTime()
        assertEquals(java.time.LocalTime.of(10, 0), local)
    }

    @Test
    fun `daily alignment survives the autumn DST change`() {
        // DST ended Sun 2024-11-03 02:00 ET.
        val mondayAfterDst = java.time.LocalDateTime.parse("2024-11-04T10:00:00")
            .atZone(ny)
            .toInstant()
            .toEpochMilli()

        val bucket = Timeframe.D1.bucketStartUtcMs(mondayAfterDst)
        val local = java.time.Instant.ofEpochMilli(bucket).atZone(ny)
        assertEquals(java.time.LocalTime.of(17, 0), local.toLocalTime())
        assertEquals(java.time.LocalDate.of(2024, 11, 3), local.toLocalDate())
    }

    // ---------- Labels ----------

    @Test
    fun `every frame has a distinct label`() {
        val labels = Timeframe.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `only the calendar frames are non-intraday`() {
        assertTrue(Timeframe.D1.isIntraday.not())
        assertTrue(Timeframe.W1.isIntraday.not())
        assertTrue(Timeframe.M1.isIntraday)
        assertTrue(Timeframe.H4.isIntraday)
    }
}
