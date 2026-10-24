package com.tradequest.app.core.challenge

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 12.5 tests: the relative clock and the dataset label that Blind Mode shows.
 *
 * # What the mode must hide
 *
 * The year, the month, the dataset identity and any recognizable campaign name.
 * The two functions under test are the only places those are produced, so pinning
 * them here is what makes the hiding testable rather than a matter of reviewing
 * every screen.
 *
 * # Why the day count is checked across a weekend
 *
 * A Friday followed by a Monday must read D5 then D6. If the count stepped over
 * calendar days the gap would read D5 then D8, and the two missing ordinals would
 * announce the weekend — the leak `BlindClock`'s own documentation warns about.
 */
class BlindModeTest {

    private fun utcMs(text: String): Long =
        LocalDateTime.parse(text).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    /** The trading calendar the app uses, so the test asks the real one. */
    private val isTradingDay: (Long) -> Boolean = {
        com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar.isMarketOpen(it)
    }

    // ---------- BlindClock.format ----------

    @Test
    fun `the first trading day formats as W1 D1`() {
        val at = utcMs("2024-01-08T14:30:00")
        assertEquals("W1 D1 14:30", BlindClock.format(at, tradingDayIndex = 0))
    }

    @Test
    fun `the eighth trading day rolls into week two`() {
        val at = utcMs("2024-01-08T09:05:00")
        assertEquals("W2 D1 09:05", BlindClock.format(at, tradingDayIndex = 7))
    }

    @Test
    fun `the formatted coordinate carries no year or month`() {
        val at = utcMs("2024-03-19T22:00:00")
        val label = BlindClock.format(at, tradingDayIndex = 49)

        assertTrue("the year leaked into $label", !label.contains("2024"))
        assertTrue("the month leaked into $label", !label.contains("03"))
        assertTrue(label.startsWith("W"))
    }

    @Test
    fun `a negative day index is clamped to the first day rather than crashing`() {
        val at = utcMs("2024-01-08T00:00:00")
        assertEquals("W1 D1 00:00", BlindClock.format(at, tradingDayIndex = -5))
    }

    // ---------- BlindClock.tradingDayIndex ----------

    @Test
    fun `the instant of the season start is day zero`() {
        val start = utcMs("2024-01-08T00:00:00")
        assertEquals(0, BlindClock.tradingDayIndex(start, start, isTradingDay))
        // Anything before the start is also zero, so an out-of-order instant can
        // never produce a negative coordinate.
        assertEquals(0, BlindClock.tradingDayIndex(start, start - 60_000L, isTradingDay))
    }

    @Test
    fun `one trading day later is day one`() {
        val start = utcMs("2024-01-08T12:00:00")
        val nextDay = utcMs("2024-01-09T12:00:00")
        assertEquals(1, BlindClock.tradingDayIndex(start, nextDay, isTradingDay))
    }

    @Test
    fun `a weekend does not advance the trading day count`() {
        // Monday 2024-01-08 is a trading day; Saturday the 13th and Sunday the
        // 14th are not. The next trading day is Monday the 15th, which must read
        // as one trading day later, not five calendar days later.
        val start = utcMs("2024-01-08T12:00:00")
        val saturday = utcMs("2024-01-13T12:00:00")
        val monday = utcMs("2024-01-15T12:00:00")

        assertEquals("Saturday is not a trading day", 4, BlindClock.tradingDayIndex(start, saturday, isTradingDay))
        assertEquals("Monday is the fifth trading day", 5, BlindClock.tradingDayIndex(start, monday, isTradingDay))
    }

    @Test
    fun `the day walk is bounded rather than hanging on a mismatched instant`() {
        // Two years beyond the start: the guard fires and returns a wrong-but-
        // finite answer rather than looping forever.
        val start = utcMs("2024-01-08T00:00:00")
        val farFuture = utcMs("2034-01-08T00:00:00")
        val index = BlindClock.tradingDayIndex(start, farFuture, isTradingDay)
        assertTrue("expected a bounded, nonnegative index, got $index", index >= 0)
        assertTrue(index <= 2 * 366)
    }

    // ---------- BlindLabel ----------

    @Test
    fun `the dataset label hides the instrument, the year and the span`() {
        val label = BlindLabel.forDataset("XAUUSD_2024-01-02_2024-01-22")

        assertTrue("the instrument leaked into $label", !label.contains("XAUUSD"))
        assertTrue("the year leaked into $label", !label.contains("2024"))
        assertTrue("the span leaked into $label", !label.contains("01-22"))
    }

    @Test
    fun `the dataset label is stable for the same dataset`() {
        // A label that changed between screens would itself be a leak, because the
        // user could correlate two different names.
        assertEquals(
            BlindLabel.forDataset("XAUUSD_2024-01-02_2024-01-22"),
            BlindLabel.forDataset("XAUUSD_2024-01-02_2024-01-22"),
        )
    }

    @Test
    fun `different datasets generally get different labels`() {
        assertNotEquals(
            BlindLabel.forDataset("XAUUSD_2024-01-02_2024-01-22"),
            BlindLabel.forDataset("XAUUSD_2023-06-05_2023-06-30"),
        )
    }

    @Test
    fun `the label has a fixed non-identifying shape`() {
        val label = BlindLabel.forDataset("anything-at-all")
        assertTrue("unexpected label shape: $label", Regex("^Dataset [0-9A-F]{4}$").matches(label))
    }

    // ---------- a sanity check on the calendar the test relies on ----------

    @Test
    fun `the calendar agrees that a weekend is closed`() {
        val saturday = utcMs("2024-01-13T15:00:00")
        assertTrue(
            "the test's weekend assumption is wrong",
            !isTradingDay(saturday),
        )
        assertEquals(LocalDate.of(2024, 1, 13), java.time.Instant.ofEpochMilli(saturday).atZone(ZoneId.of("UTC")).toLocalDate())
    }
}
