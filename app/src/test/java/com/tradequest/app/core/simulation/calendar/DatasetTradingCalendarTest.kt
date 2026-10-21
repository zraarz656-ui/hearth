package com.tradequest.app.core.simulation.calendar

import com.tradequest.app.core.market.DatasetClosure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 3.6 tests: stored closures are respected, and rule-based hours and
 * observed closures are combined without either one masking the other.
 */
class DatasetTradingCalendarTest {

    private fun utcMs(text: String): Long =
        java.time.LocalDateTime.parse(text)
            .atZone(java.time.ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()

    private fun closure(start: String, end: String, reason: String = "HOLIDAY") =
        DatasetClosure(
            datasetId = "ds",
            startUtcMs = utcMs(start),
            endUtcMs = utcMs(end),
            reason = reason,
            missingMinuteCount = 0,
        )

    // Wednesday 2024-01-17, 14:00 UTC is inside London/NY overlap.
    private val openMinute = utcMs("2024-01-17T14:00:00")

    @Test
    fun `an open minute with no closures is tradable`() {
        val calendar = DatasetTradingCalendar(emptyList())
        assertTrue(calendar.isTradableAt(openMinute))
        assertFalse(calendar.isClosedAt(openMinute))
    }

    @Test
    fun `a stored closure makes an otherwise-open minute closed`() {
        val calendar = DatasetTradingCalendar(
            listOf(closure("2024-01-17T13:00:00", "2024-01-17T15:00:00")),
        )

        assertTrue(calendar.isClosedAt(openMinute))
        assertFalse(calendar.isTradableAt(openMinute))
    }

    @Test
    fun `the closure end is exclusive`() {
        val calendar = DatasetTradingCalendar(
            listOf(closure("2024-01-17T13:00:00", "2024-01-17T14:00:00")),
        )

        assertTrue(calendar.isClosedAt(utcMs("2024-01-17T13:59:00")))
        assertFalse("the end minute is the first open minute", calendar.isClosedAt(openMinute))
    }

    @Test
    fun `a closure does not make a weekend minute tradable`() {
        // Saturday is never tradable, closure or not.
        val calendar = DatasetTradingCalendar(emptyList())
        assertFalse(calendar.isTradableAt(utcMs("2024-01-20T12:00:00")))
    }

    @Test
    fun `closureAt returns the covering closure`() {
        val holiday = closure("2024-01-17T13:00:00", "2024-01-17T15:00:00", "HOLIDAY")
        val calendar = DatasetTradingCalendar(listOf(holiday))

        assertEquals(holiday, calendar.closureAt(openMinute))
        assertNull(calendar.closureAt(utcMs("2024-01-18T14:00:00")))
    }

    @Test
    fun `nextTradable skips a closure`() {
        val calendar = DatasetTradingCalendar(
            listOf(closure("2024-01-17T13:00:00", "2024-01-17T15:00:00")),
        )

        assertEquals(
            utcMs("2024-01-17T15:00:00"),
            calendar.nextTradableUtcMs(openMinute),
        )
    }

    @Test
    fun `nextTradable returns the input when already tradable`() {
        val calendar = DatasetTradingCalendar(emptyList())
        assertEquals(openMinute, calendar.nextTradableUtcMs(openMinute))
    }

    @Test
    fun `nextTradable crosses a weekend`() {
        val calendar = DatasetTradingCalendar(emptyList())
        // Friday 2024-01-19 22:00 UTC is after the close; the next tradable
        // instant is the Sunday 22:00 UTC open.
        assertEquals(
            utcMs("2024-01-21T22:00:00"),
            calendar.nextTradableUtcMs(utcMs("2024-01-19T22:00:00")),
        )
    }

    @Test
    fun `unsorted closures are still searched correctly`() {
        val calendar = DatasetTradingCalendar(
            listOf(
                closure("2024-02-01T00:00:00", "2024-02-01T01:00:00"),
                closure("2024-01-17T13:00:00", "2024-01-17T15:00:00"),
                closure("2024-01-01T00:00:00", "2024-01-01T01:00:00"),
            ),
        )

        assertTrue(calendar.isClosedAt(openMinute))
        assertFalse(calendar.isClosedAt(utcMs("2024-01-18T14:00:00")))
    }

    @Test
    fun `a gap before the first closure is not closed`() {
        val calendar = DatasetTradingCalendar(
            listOf(closure("2024-06-01T00:00:00", "2024-06-01T01:00:00")),
        )
        assertFalse(calendar.isClosedAt(openMinute))
    }

    @Test
    fun `nextTradable gives up rather than scanning forever`() {
        // A closure covering everything for a year, with a tiny scan budget.
        val calendar = DatasetTradingCalendar(
            listOf(closure("2024-01-01T00:00:00", "2025-01-01T00:00:00")),
        )
        assertNull(calendar.nextTradableUtcMs(openMinute, maxScanMinutes = 60))
    }
}
