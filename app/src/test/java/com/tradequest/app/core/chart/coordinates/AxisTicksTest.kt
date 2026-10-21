package com.tradequest.app.core.chart.coordinates

import com.tradequest.app.core.chart.ChartFixtures
import com.tradequest.app.core.chart.timeframe.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 8.7 tests: price-axis gridline selection.
 */
class PriceAxisTicksTest {

    @Test
    fun `nice steps are 1, 2 or 5 times a power of ten`() {
        val steps = listOf(1f, 7f, 12f, 23f, 47f, 88f, 130f, 460f, 900f, 1_100f)
        for (raw in steps) {
            val nice = PriceAxisTicks.niceStep(raw)
            assertTrue("step for $raw was $nice", nice >= raw)
        }
    }

    @Test
    fun `nice step never returns less than one pip`() {
        assertEquals(100f, PriceAxisTicks.niceStep(0f), 0.001f)
        assertEquals(100f, PriceAxisTicks.niceStep(-5f), 0.001f)
        assertEquals(100f, PriceAxisTicks.niceStep(1f), 0.001f)
    }

    @Test
    fun `a 1000 unit span with ten labels gives a 100 unit step`() {
        assertEquals(100f, PriceAxisTicks.niceStep(100f), 0.001f)
    }

    @Test
    fun `a 2500 unit span with ten labels rounds up to a nice step`() {
        val step = PriceAxisTicks.niceStep(250f)
        assertTrue(step >= 250f)
        assertEquals(500f, step, 0.001f)
    }

    @Test
    fun `ticks land on round multiples of the step`() {
        val range = PriceAxisRange(2_047_123f, 2_057_891f)
        val ticks = PriceAxisTicks.ticks(range, targetCount = 6)
        val step = PriceAxisTicks.stepFor(range, 6)

        assertTrue(ticks.isNotEmpty())
        for (tick in ticks) {
            val remainder = tick % step
            assertTrue(
                "tick $tick is not a multiple of step $step",
                remainder < 0.01f || step - remainder < 0.01f,
            )
        }
    }

    @Test
    fun `every tick lies inside the range`() {
        val range = PriceAxisRange(2_047_123f, 2_057_891f)
        for (tick in PriceAxisTicks.ticks(range, targetCount = 8)) {
            assertTrue(range.contains(tick))
        }
    }

    @Test
    fun `ticks ascend`() {
        val ticks = PriceAxisTicks.ticks(PriceAxisRange(2_000_000f, 2_100_000f), targetCount = 10)
        for (i in 1 until ticks.size) {
            assertTrue(ticks[i] > ticks[i - 1])
        }
    }

    @Test
    fun `the tick count is near the target, not wildly over`() {
        val range = PriceAxisRange(2_000_000f, 2_100_000f)
        val ticks = PriceAxisTicks.ticks(range, targetCount = 10)
        assertTrue("got ${ticks.size} ticks for a target of 10", ticks.size in 4..16)
    }

    @Test
    fun `a zero target count returns no ticks`() {
        assertTrue(PriceAxisTicks.ticks(PriceAxisRange(2_000_000f, 2_100_000f), 0).isEmpty())
    }

    @Test
    fun `a tiny span still yields at least one line`() {
        val range = PriceAxisRange.around(2_050_000f)
        assertTrue(PriceAxisTicks.ticks(range, targetCount = 5).isNotEmpty())
    }

    @Test
    fun `step pips converts to pips`() {
        assertEquals(1f, PriceAxisTicks.stepPips(100f), 0.001f)
        assertEquals(10f, PriceAxisTicks.stepPips(1_000f), 0.001f)
    }

    @Test
    fun `formatPrice uses three decimals`() {
        assertEquals("2050.000", PriceAxisTicks.formatPrice(2_050_000f))
        assertEquals("2050.505", PriceAxisTicks.formatPrice(2_050_505f))
    }
}

/**
 * Step 8.8 tests: time-axis labels.
 *
 * Labels must land on candle timestamps that exist, and must never label an
 * instant the market was closed.
 */
class TimeAxisTicksTest {

    private fun timestamps(count: Int): LongArray =
        LongArray(count) { ChartFixtures.at(it) }

    @Test
    fun `no ticks are produced for an invalid window`() {
        val ts = timestamps(100)
        assertTrue(TimeAxisTicks.ticks(ts, -1, 99, Timeframe.M1, 10).isEmpty())
        assertTrue(TimeAxisTicks.ticks(ts, 0, 100, Timeframe.M1, 10).isEmpty())
        assertTrue(TimeAxisTicks.ticks(ts, 50, 10, Timeframe.M1, 10).isEmpty())
        assertTrue(TimeAxisTicks.ticks(ts, 0, 99, Timeframe.M1, 0).isEmpty())
    }

    @Test
    fun `every tick timestamp is a real candle time`() {
        val ts = timestamps(1_000)
        val allowed = ts.toSet()

        for (tick in TimeAxisTicks.ticks(ts, 0, 999, Timeframe.M1, 12)) {
            assertTrue("tick ${tick.utcMs} is not a stored candle time", tick.utcMs in allowed)
        }
    }

    @Test
    fun `the tick count does not exceed the target by more than one`() {
        val ts = timestamps(10_000)
        for (target in listOf(3, 5, 10, 20)) {
            val ticks = TimeAxisTicks.ticks(ts, 0, 9_999, Timeframe.M1, target)
            assertTrue(
                "target $target produced ${ticks.size} ticks",
                ticks.size <= target + 1,
            )
        }
    }

    @Test
    fun `ticks ascend in time`() {
        val ts = timestamps(5_000)
        val ticks = TimeAxisTicks.ticks(ts, 0, 4_999, Timeframe.M1, 15)
        for (i in 1 until ticks.size) {
            assertTrue(ticks[i].utcMs > ticks[i - 1].utcMs)
        }
    }

    @Test
    fun `the newest tick is the last candle in the window`() {
        val ts = timestamps(1_000)
        val ticks = TimeAxisTicks.ticks(ts, 0, 999, Timeframe.M1, 10)
        assertEquals(ts[999], ticks.last().utcMs)
    }

    @Test
    fun `intraday labels include a time`() {
        val ts = timestamps(300)
        val ticks = TimeAxisTicks.ticks(ts, 0, 299, Timeframe.M1, 10)
        // At least one label is a plain clock time, since the window is under a day.
        assertTrue(ticks.any { it.label.matches(Regex("\\d{2}:\\d{2}")) })
    }

    @Test
    fun `daily labels are dates, not clock times`() {
        // 20 days of minutes, so a daily window spans several days.
        val ts = timestamps(20 * 1_440)
        val ticks = TimeAxisTicks.ticks(ts, 0, ts.size - 1, Timeframe.D1, 6)
        assertTrue(ticks.isNotEmpty())
        assertTrue(
            "expected no clock-only labels, got ${ticks.map { it.label }}",
            ticks.none { it.label.matches(Regex("\\d{2}:\\d{2}")) },
        )
    }

    @Test
    fun `a new day is marked major at intraday zoom`() {
        // Two trading days of minutes; the crossing should be major.
        val ts = timestamps(2 * 1_440)
        val ticks = TimeAxisTicks.ticks(ts, 0, ts.size - 1, Timeframe.H1, 50)
        assertTrue(ticks.any { it.major })
    }

    @Test
    fun `latest label reflects the timeframe`() {
        val t = ChartFixtures.MONDAY_OPEN_UTC_MS + 3 * 3_600_000L
        assertEquals("20:00", TimeAxisTicks.latestLabel(t, Timeframe.M1))
        assertTrue(TimeAxisTicks.latestLabel(t, Timeframe.D1).contains("Jan"))
    }
}
