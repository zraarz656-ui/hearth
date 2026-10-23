package com.tradequest.app.core.simulation.calendar

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 3.9 tests: daily rollover, DST, weekend handling, and session
 * classification.
 *
 * # Where the expected values come from
 *
 * Every UTC millisecond constant below was computed independently from the IANA
 * timezone database, not read back out of this code. They are hard-coded rather
 * than derived so that a bug in the implementation cannot make the test agree
 * with it.
 *
 * The two DST rules that matter, for 2024:
 *
 *  - United States: DST began Sun 10 March, ended Sun 3 November.
 *  - United Kingdom: DST began Sun 31 March, ended Sun 27 October.
 *
 * So 13-27 March and 27 October-3 November 2024 are the periods when the two
 * are an hour out of step. Those weeks are tested explicitly, because they are
 * where a fixed-offset implementation would silently be wrong.
 */
class NewYorkTradingCalendarTest {

    private val ny = ZoneId.of("America/New_York")

    private fun utcMs(text: String): Long =
        java.time.LocalDateTime.parse(text)
            .atZone(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()

    // ---------- Trading-day labelling (Step 3.6) ----------

    @Test
    fun `mid-afternoon belongs to the same trading day`() {
        assertEquals(
            LocalDate.of(2024, 1, 15),
            NewYorkTradingCalendar.tradingDateOf(utcMs("2024-01-15T15:00:00")),
        )
    }

    @Test
    fun `one minute before rollover still belongs to the old trading day`() {
        // 16:59 ET in January is 21:59 UTC.
        assertEquals(
            LocalDate.of(2024, 1, 15),
            NewYorkTradingCalendar.tradingDateOf(utcMs("2024-01-15T21:59:00")),
        )
    }

    @Test
    fun `exactly at 1700 New York the next trading day begins`() {
        // 17:00 ET in January is 22:00 UTC.
        assertEquals(
            LocalDate.of(2024, 1, 16),
            NewYorkTradingCalendar.tradingDateOf(utcMs("2024-01-15T22:00:00")),
        )
    }

    @Test
    fun `the Sunday evening open belongs to Monday`() {
        // Sunday 2024-01-14 20:00 ET = 2024-01-15 01:00 UTC.
        assertEquals(
            LocalDate.of(2024, 1, 15),
            NewYorkTradingCalendar.tradingDateOf(utcMs("2024-01-15T01:00:00")),
        )
    }

    @Test
    fun `trading day start is 1700 the previous calendar day`() {
        // Trading day Monday 2024-01-15 starts Sunday 17:00 ET = 22:00 UTC.
        assertEquals(
            utcMs("2024-01-14T22:00:00"),
            NewYorkTradingCalendar.tradingDayStartUtcMs(LocalDate.of(2024, 1, 15)),
        )
    }

    @Test
    fun `trading day end is its own 1700`() {
        assertEquals(
            utcMs("2024-01-15T22:00:00"),
            NewYorkTradingCalendar.tradingDayEndUtcMs(LocalDate.of(2024, 1, 15)),
        )
    }

    @Test
    fun `a trading day is exactly 24 hours in winter`() {
        val start = NewYorkTradingCalendar.tradingDayStartUtcMs(LocalDate.of(2024, 1, 15))
        val end = NewYorkTradingCalendar.tradingDayEndUtcMs(LocalDate.of(2024, 1, 15))
        assertEquals(24L * 60 * 60 * 1000, end - start)
    }

    @Test
    fun `weekdays are trading days and the weekend is not`() {
        assertTrue(NewYorkTradingCalendar.isTradingDay(LocalDate.of(2024, 1, 15))) // Mon
        assertTrue(NewYorkTradingCalendar.isTradingDay(LocalDate.of(2024, 1, 19))) // Fri
        assertFalse(NewYorkTradingCalendar.isTradingDay(LocalDate.of(2024, 1, 20))) // Sat
        assertFalse(NewYorkTradingCalendar.isTradingDay(LocalDate.of(2024, 1, 21))) // Sun
    }

    // ---------- DST (Step 3.6) ----------

    @Test
    fun `New York is on standard time before the March transition`() {
        assertFalse(NewYorkTradingCalendar.isDaylightSavingAt(utcMs("2024-03-09T12:00:00")))
        assertEquals(-5 * 3600, NewYorkTradingCalendar.offsetSecondsAt(utcMs("2024-03-09T12:00:00")))
    }

    @Test
    fun `New York is on daylight time after the March transition`() {
        assertTrue(NewYorkTradingCalendar.isDaylightSavingAt(utcMs("2024-03-11T12:00:00")))
        assertEquals(-4 * 3600, NewYorkTradingCalendar.offsetSecondsAt(utcMs("2024-03-11T12:00:00")))
    }

    @Test
    fun `the 1700 rollover exists on the spring-forward day`() {
        // 2024-03-10 is the US spring-forward day. 02:00 local does not exist,
        // but 17:00 does, so the rollover is well defined.
        assertEquals(
            utcMs("2024-03-10T21:00:00"), // 17:00 EDT
            NewYorkTradingCalendar.tradingDayEndUtcMs(LocalDate.of(2024, 3, 10)),
        )
    }

    @Test
    fun `the 1700 rollover exists on the fall-back day`() {
        // 2024-11-03 is the US fall-back day. 01:30 local occurs twice, but
        // 17:00 occurs once.
        assertEquals(
            utcMs("2024-11-03T22:00:00"), // 17:00 EST
            NewYorkTradingCalendar.tradingDayEndUtcMs(LocalDate.of(2024, 11, 3)),
        )
    }

    @Test
    fun `a spring-forward trading day is 23 hours long`() {
        // The US transition happens at 02:00 on Sunday 2024-03-10, which falls
        // inside the trading day *labeled* Sunday 2024-03-10 (it runs from
        // Saturday 17:00 EST to Sunday 17:00 EDT). Verified independently: that
        // day is 23 hours long.
        val start = NewYorkTradingCalendar.tradingDayStartUtcMs(LocalDate.of(2024, 3, 10))
        val end = NewYorkTradingCalendar.tradingDayEndUtcMs(LocalDate.of(2024, 3, 10))
        assertEquals(23L * 60 * 60 * 1000, end - start)
    }

    @Test
    fun `a fall-back trading day is 25 hours long`() {
        // The US fall-back is 02:00 Sunday 2024-11-03, inside the trading day
        // labeled Sunday 2024-11-03. Verified independently: 25 hours.
        val start = NewYorkTradingCalendar.tradingDayStartUtcMs(LocalDate.of(2024, 11, 3))
        val end = NewYorkTradingCalendar.tradingDayEndUtcMs(LocalDate.of(2024, 11, 3))
        assertEquals(25L * 60 * 60 * 1000, end - start)
    }

    @Test
    fun `rollover instants are strictly increasing across the March transition`() {
        var previous = 0L
        var date = LocalDate.of(2024, 3, 8)
        repeat(6) {
            val end = NewYorkTradingCalendar.tradingDayEndUtcMs(date)
            assertTrue("rollover for $date must follow the previous one", end > previous)
            previous = end
            date = date.plusDays(1)
        }
    }

    // ---------- Market open window ----------

    @Test
    fun `Saturday is always closed`() {
        assertFalse(NewYorkTradingCalendar.isMarketOpen(utcMs("2024-01-20T12:00:00")))
    }

    @Test
    fun `Sunday before 1700 is closed and after 1700 is open`() {
        assertFalse(NewYorkTradingCalendar.isMarketOpen(utcMs("2024-01-14T20:00:00"))) // 15:00 ET
        assertTrue(NewYorkTradingCalendar.isMarketOpen(utcMs("2024-01-14T23:00:00"))) // 18:00 ET
    }

    @Test
    fun `Friday closes at 1700 New York`() {
        // Friday 2024-01-19. 16:59 ET = 21:59 UTC is open, 17:01 ET is closed.
        assertTrue(NewYorkTradingCalendar.isMarketOpen(utcMs("2024-01-19T21:59:00")))
        assertFalse(NewYorkTradingCalendar.isMarketOpen(utcMs("2024-01-19T22:01:00")))
    }

    @Test
    fun `next market open skips a weekend`() {
        // Saturday 2024-01-20 12:00 UTC -> Sunday 17:00 ET = Sunday 22:00 UTC.
        assertEquals(
            utcMs("2024-01-21T22:00:00"),
            NewYorkTradingCalendar.nextMarketOpenUtcMs(utcMs("2024-01-20T12:00:00")),
        )
    }

    @Test
    fun `next market open is a no-op when already open`() {
        val open = utcMs("2024-01-15T12:00:00")
        assertEquals(open, NewYorkTradingCalendar.nextMarketOpenUtcMs(open))
    }

    @Test
    fun `trading days between counts weekdays only`() {
        // Mon 2024-01-15 to Mon 2024-01-22 is five trading days.
        assertEquals(
            5,
            NewYorkTradingCalendar.tradingDaysBetween(
                LocalDate.of(2024, 1, 15),
                LocalDate.of(2024, 1, 22),
            ),
        )
    }

    // ---------- Session classification (Step 3.7) ----------

    @Test
    fun `the London and New York overlap exists in normal winter weeks`() {
        // 2024-01-17 is a Wednesday in a week where both zones are on standard
        // time. London 08:00-17:00 GMT and New York 08:00-17:00 EST overlap
        // 13:00-17:00 UTC, four hours.
        val overlapMinutes = countOverlapMinutes(LocalDate.of(2024, 1, 17))
        assertEquals(4 * 60, overlapMinutes)
    }

    @Test
    fun `the overlap widens to five hours when only the US has changed clocks`() {
        // 2024-03-13: the US is on EDT, the UK is still on GMT. New York
        // 08:00-17:00 EDT is 12:00-21:00 UTC; London is 08:00-17:00 UTC, so the
        // overlap is 12:00-17:00 UTC, five hours. A fixed-offset implementation
        // reports four here and is wrong.
        val overlapMinutes = countOverlapMinutes(LocalDate.of(2024, 3, 13))
        assertEquals(5 * 60, overlapMinutes)
    }

    @Test
    fun `the overlap returns to four hours once both have changed clocks`() {
        // 2024-04-10: both on summer time. New York 08:00-17:00 EDT is
        // 12:00-21:00 UTC; London 08:00-17:00 BST is 07:00-16:00 UTC, so the
        // overlap is 12:00-16:00 UTC, four hours.
        assertEquals(4 * 60, countOverlapMinutes(LocalDate.of(2024, 4, 10)))
    }

    @Test
    fun `the mismatch week overlap differs from a normal week`() {
        // The assertion that matters most: the two weeks are genuinely
        // different, so an implementation that ignores the split DST rules
        // cannot pass both.
        assertNotEquals(
            countOverlapMinutes(LocalDate.of(2024, 1, 17)),
            countOverlapMinutes(LocalDate.of(2024, 3, 13)),
        )
    }

    @Test
    fun `Asia session is classified during Tokyo hours`() {
        // 2024-01-17 03:00 UTC = 12:00 JST, inside Asia and outside London/NY.
        assertEquals(
            TradingSession.ASIA,
            SessionClassifier.classify(utcMs("2024-01-17T03:00:00")),
        )
    }

    @Test
    fun `the overlap is reported as the most specific session`() {
        // 2024-01-17 14:00 UTC is inside both London and New York.
        assertEquals(
            TradingSession.LONDON_NEW_YORK_OVERLAP,
            SessionClassifier.classify(utcMs("2024-01-17T14:00:00")),
        )
    }

    @Test
    fun `sessionsAt reports every open session, not just the label`() {
        val open = SessionClassifier.sessionsAt(utcMs("2024-01-17T14:00:00"))
        assertTrue(TradingSession.LONDON in open)
        assertTrue(TradingSession.NEW_YORK in open)
        assertTrue(TradingSession.LONDON_NEW_YORK_OVERLAP in open)
    }

    @Test
    fun `no session is open in the dead hours`() {
        // 2024-01-17 22:00 UTC = 17:00 ET (New York closed), 22:00 London
        // (London closed), 07:00 JST (Tokyo opens at 09:00). All three closed.
        assertEquals(
            TradingSession.CLOSED,
            SessionClassifier.classify(utcMs("2024-01-17T22:00:00")),
        )
    }

    @Test
    fun `a non-wrapping window never claims to wrap midnight`() {
        // 2024-01-17 20:00 UTC is 05:00 JST, outside the 09:00-18:00 window.
        // If the window were mis-implemented as wrapping midnight it would
        // match; it must not.
        val window = SessionClassifier.ASIA_WINDOW
        assertFalse(
            "a 09:00-18:00 window must not contain 05:00 local",
            window.contains(utcMs("2024-01-17T20:00:00")),
        )
    }

    /**
     * Counts minutes in a UTC day during which both London and New York are in
     * their 08:00-17:00 local window.
     *
     * Computed from the classifier rather than from a second copy of the
     * session rules, so the test exercises the implementation under test.
     */
    private fun countOverlapMinutes(day: LocalDate): Int {
        val startUtc = day.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        return (0 until 24 * 60).count { minute ->
            val at = startUtc + minute * 60_000L
            SessionClassifier.sessionsAt(at).contains(TradingSession.LONDON_NEW_YORK_OVERLAP)
        }
    }
}
