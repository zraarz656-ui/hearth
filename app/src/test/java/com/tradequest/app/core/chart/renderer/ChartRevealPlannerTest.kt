package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.ChartFixtures
import com.tradequest.app.core.chart.timeframe.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reveal boundary (Step 8.10).
 *
 * # What these tests are really guarding
 *
 * The dangerous failure is a **future leak**: the chart showing a bucket's real
 * high or low before the simulated clock has reached it. That looks completely
 * normal on screen, so it cannot be caught by a smoke test. The assertions below
 * therefore check the *boundary* — that no revealed minute is at or after the
 * forming bucket, and that no forming minute is at or after the current minute —
 * rather than merely that a list has the right length.
 */
class ChartRevealPlannerTest {

    /** Monday's open, plus [n] minutes. */
    private fun minute(n: Int) = ChartFixtures.at(n)

    /** 90 minutes of Monday, one candle per minute. */
    private fun ninetyMinutes() = (0 until 90).map { i ->
        ChartFixtures.candle(i, open = 2_050_000 + i, close = 2_050_100 + i)
    }

    // ---------- The one-minute case: bucket and minute coincide ----------

    @Test
    fun `a one-minute chart reveals the minutes before the current one`() {
        val candles = ninetyMinutes()
        val plan = ChartRevealPlanner.plan(candles, Timeframe.M1, minute(30))

        // Minutes 0..29 are complete; minute 30 is forming.
        assertEquals(30, plan.revealed.size)
        assertEquals(30, plan.formingMinutes.size)
    }

    @Test
    fun `no revealed minute is at or after the current minute`() {
        val plan = ChartRevealPlanner.plan(ninetyMinutes(), Timeframe.M1, minute(30))
        val currentMinuteStart = minute(30)

        assertTrue(
            "a revealed minute is at or past the forming instant",
            plan.revealed.all { it.timestampUtcMs < currentMinuteStart },
        )
    }

    @Test
    fun `on an exact minute boundary that minute is not yet revealed`() {
        // The instant is exactly 00:30:00.000. Minute 30 has just begun, so it
        // has no completed data and must not be in the revealed list.
        val plan = ChartRevealPlanner.plan(ninetyMinutes(), Timeframe.M1, minute(30))
        assertFalse(plan.revealed.any { it.timestampUtcMs == minute(30) })
    }

    @Test
    fun `mid-minute the current minute is still excluded`() {
        val midMinute = minute(30) + 30_000L
        val plan = ChartRevealPlanner.plan(ninetyMinutes(), Timeframe.M1, midMinute)

        assertEquals(30, plan.revealed.size)
        assertFalse(plan.revealed.any { it.timestampUtcMs == minute(30) })
    }

    // ---------- The higher-timeframe case: the leak-prone one ----------

    @Test
    fun `an hourly chart reveals only completed hours`() {
        val candles = ninetyMinutes()
        // 30 minutes into the first hour: the 1h bucket is still forming, so the
        // buffer must be empty and the forming candle covers the hour so far.
        val plan = ChartRevealPlanner.plan(candles, Timeframe.H1, minute(30))

        assertEquals("the forming hour leaked into the buffer", 0, plan.revealed.size)
        assertEquals("the hour's completed minutes are missing", 30, plan.formingMinutes.size)
    }

    @Test
    fun `the revealed list never contains a minute from the forming hour`() {
        val candles = (0 until 180).map { i ->
            ChartFixtures.candle(i, open = 2_050_000 + i, close = 2_050_100 + i)
        }
        val hourTwoStart = Timeframe.H1.bucketStartUtcMs(minute(90))
        val plan = ChartRevealPlanner.plan(candles, Timeframe.H1, minute(90))

        assertTrue(
            "a minute from the forming hour reached the buffer",
            plan.revealed.all { it.timestampUtcMs < hourTwoStart },
        )
        // Hour one completed in full, so it is revealed.
        assertEquals(60, plan.revealed.size)
    }

    @Test
    fun `forming minutes reach back into the current hour, not just the current minute`() {
        val candles = (0 until 180).map { i ->
            ChartFixtures.candle(i, open = 2_050_000 + i, close = 2_050_100 + i)
        }
        // 25 minutes into hour two: the forming 1h candle must be built from the
        // hour's 25 completed minutes, which is why formingMinutes runs back to
        // the hour's start rather than stopping at the previous minute.
        val plan = ChartRevealPlanner.plan(candles, Timeframe.H1, minute(85))

        assertEquals(85, plan.formingMinutes.size)
        val hourTwoStart = Timeframe.H1.bucketStartUtcMs(minute(85))
        assertTrue(
            "forming minutes do not reach back to the forming hour's start",
            plan.formingMinutes.any { it.timestampUtcMs == hourTwoStart },
        )
    }

    @Test
    fun `revealed and forming minutes differ on a higher timeframe`() {
        val plan = ChartRevealPlanner.plan(ninetyMinutes(), Timeframe.H1, minute(30))
        // This difference is the whole point of the two lists. If a refactor ever
        // collapses them into one, this test fails and says why.
        assertTrue(
            "revealed and formingMinutes collapsed; one of them is now wrong",
            plan.revealed.size < plan.formingMinutes.size,
        )
    }

    // ---------- Edges ----------

    @Test
    fun `an empty dataset yields nothing`() {
        val plan = ChartRevealPlanner.plan(emptyList(), Timeframe.M1, minute(10))
        assertTrue(plan.revealed.isEmpty())
        assertTrue(plan.formingMinutes.isEmpty())
    }

    @Test
    fun `an instant before the dataset reveals nothing and forms nothing`() {
        val plan = ChartRevealPlanner.plan(ninetyMinutes(), Timeframe.M1, minute(-5))
        assertTrue(plan.revealed.isEmpty())
        assertTrue(plan.formingMinutes.isEmpty())
    }

    @Test
    fun `an instant after the dataset reveals everything`() {
        val candles = ninetyMinutes()
        val plan = ChartRevealPlanner.plan(candles, Timeframe.M1, minute(1000))
        assertEquals(candles.size, plan.revealed.size)
        assertEquals(candles.size, plan.formingMinutes.size)
    }

    @Test
    fun `a daily chart reveals only completed trading days`() {
        // Two full trading days of minutes (1440 each) plus a partial third.
        val candles = (0 until 1440 * 2 + 10).map { i ->
            ChartFixtures.candle(i, open = 2_050_000, close = 2_050_100)
        }
        val dayThreeStart = Timeframe.D1.bucketStartUtcMs(minute(1440 * 2))
        val plan = ChartRevealPlanner.plan(candles, Timeframe.D1, minute(1440 * 2 + 5))

        assertEquals(1440 * 2, plan.revealed.size)
        assertTrue(plan.revealed.all { it.timestampUtcMs < dayThreeStart })
    }
}
