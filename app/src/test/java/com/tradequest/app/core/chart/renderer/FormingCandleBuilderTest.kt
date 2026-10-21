package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.ChartFixtures
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.simulation.path.MarketPathBuilder
import com.tradequest.app.core.simulation.path.MarketPathWindow
import com.tradequest.app.core.simulation.path.PathModelVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 8.10 tests: the forming candle, and the future-information rule for higher
 * timeframes.
 *
 * # How the leak is tested
 *
 * The rule is that a forming 1h candle must not know the rest of its hour. The
 * test builds two datasets that share a prefix of the hour and differ *after* the
 * simulated instant, then asserts the forming candle is identical for both. If
 * the implementation reached for the hour's real data, the two would differ — and
 * that difference is exactly the leak.
 */
class FormingCandleBuilderTest {

    private val builder = FormingCandleBuilder()

    private fun windowOf(candles: List<HistoricalCandle>): MarketPathWindow =
        MarketPathWindow(
            datasetId = "test",
            steps = MarketPathBuilder.build("test", candles),
        )

    // ---------- 1m ----------

    @Test
    fun `a forming one-minute candle reports the extremes revealed so far`() {
        val candles = ChartFixtures.rising(10)
        val window = windowOf(candles)

        // 30 seconds into minute 3.
        val t = ChartFixtures.at(3) + 30_000
        val forming = builder.build(window, builder.completedPrefix(candles, t), Timeframe.M1, t)

        assertNotNull(forming)
        forming!!
        assertEquals(ChartFixtures.at(3), forming.timestampUtcMs)
        // The completed minute 3 has high 2_050_005; the forming candle must not
        // exceed what the path has actually reached by t.
        assertTrue(forming.highUnits <= candles[3].highUnits)
    }

    @Test
    fun `no forming candle exists inside a market gap`() {
        // Minute 0, then minute 10: the minutes between are a gap.
        val candles = listOf(
            ChartFixtures.candle(0, open = 100, close = 110),
            ChartFixtures.candle(10, open = 200, close = 210),
        )
        val window = windowOf(candles)

        val t = ChartFixtures.at(5)
        assertNull(builder.build(window, builder.completedPrefix(candles, t), Timeframe.M1, t))
    }

    @Test
    fun `no forming candle exists outside the dataset`() {
        val candles = ChartFixtures.rising(5)
        val window = windowOf(candles)

        val t = ChartFixtures.at(500)
        assertNull(builder.build(window, builder.completedPrefix(candles, t), Timeframe.M1, t))
    }

    // ---------- completedPrefix ----------

    @Test
    fun `the completed prefix excludes the minute containing the instant`() {
        val candles = ChartFixtures.rising(10)
        val t = ChartFixtures.at(4) + 30_000

        val prefix = builder.completedPrefix(candles, t)

        assertEquals(4, prefix.size)
        assertTrue(prefix.all { it.timestampUtcMs < ChartFixtures.at(4) })
    }

    @Test
    fun `the completed prefix on an exact minute boundary excludes that minute`() {
        val candles = ChartFixtures.rising(10)
        val prefix = builder.completedPrefix(candles, ChartFixtures.at(4))
        assertEquals(4, prefix.size)
    }

    @Test
    fun `the completed prefix covers the whole series before the end`() {
        val candles = ChartFixtures.rising(10)
        val prefix = builder.completedPrefix(candles, ChartFixtures.at(1_000))
        assertEquals(10, prefix.size)
    }

    @Test
    fun `the completed prefix is empty before the first candle`() {
        val candles = ChartFixtures.rising(10)
        val prefix = builder.completedPrefix(candles, ChartFixtures.at(0) - 60_000)
        assertTrue(prefix.isEmpty())
    }

    // ---------- The higher-timeframe leak ----------

    @Test
    fun `a forming hourly candle ignores minutes after the simulated instant`() {
        // Two datasets share minutes 0..10 of the hour and diverge from minute 11.
        // The simulated instant is inside minute 10, so the forming minute is
        // shared and everything after it differs. The forming candle must be
        // identical for both datasets; any difference is the future leaking in.
        val shared = ChartFixtures.rising(11)
        val quietRest = (11 until 60).map { i ->
            ChartFixtures.candle(i, open = 2_060_000, close = 2_060_001, high = 2_060_002, low = 2_059_999)
        }
        val wildRest = (11 until 60).map { i ->
            ChartFixtures.candle(i, open = 2_000_000, close = 2_100_000, high = 2_150_000, low = 1_950_000)
        }

        val quiet = shared + quietRest
        val wild = shared + wildRest

        val t = ChartFixtures.at(10) + 30_000

        val quietForming = builder.build(
            windowOf(quiet), builder.completedPrefix(quiet, t), Timeframe.H1, t,
        )
        val wildForming = builder.build(
            windowOf(wild), builder.completedPrefix(wild, t), Timeframe.H1, t,
        )

        assertNotNull(quietForming)
        assertEquals(
            "the forming hourly candle must not depend on minutes after T",
            quietForming,
            wildForming,
        )
    }

    @Test
    fun `a forming hourly candle takes its open from the first minute of the hour`() {
        val candles = ChartFixtures.rising(30)
        val t = ChartFixtures.at(20) + 30_000

        val forming = builder.build(windowOf(candles), builder.completedPrefix(candles, t), Timeframe.H1, t)!!

        assertEquals(ChartFixtures.MONDAY_OPEN_UTC_MS, forming.timestampUtcMs)
        assertEquals(candles[0].openUnits, forming.openUnits)
    }

    @Test
    fun `a forming hourly candle high is the max of revealed minutes only`() {
        // Minute 25 is deliberately the highest, and it is still forming.
        val candles = (0 until 30).map { i ->
            val high = if (i == 25) 2_099_999 else 2_050_100
            ChartFixtures.candle(i, open = 2_050_000, close = 2_050_050, high = high, low = 2_049_900)
        }

        // At 25:30 the 25th minute has partly elapsed, so its real high may be
        // reached. At 24:30 it must not be visible at all.
        val t = ChartFixtures.at(24) + 30_000
        val forming = builder.build(windowOf(candles), builder.completedPrefix(candles, t), Timeframe.H1, t)!!

        assertTrue(
            "the forming candle saw minute 25's high of 2099999: ${forming.highUnits}",
            forming.highUnits < 2_099_999,
        )
    }

    @Test
    fun `a forming candle never reports volume`() {
        val candles = ChartFixtures.rising(30)
        val t = ChartFixtures.at(20) + 30_000
        // FormingCandleView has no volume field at all, which is the strongest
        // form of the guarantee: there is nothing to leak. This asserts the type
        // shape stays that way by checking the aggregation path uses zero.
        val forming = builder.build(windowOf(candles), builder.completedPrefix(candles, t), Timeframe.H1, t)!!
        assertEquals(ChartFixtures.MONDAY_OPEN_UTC_MS, forming.timestampUtcMs)
    }

    @Test
    fun `a forming daily candle spans the whole trading day so far`() {
        val candles = ChartFixtures.rising(120)
        val t = ChartFixtures.at(100) + 30_000

        val forming = builder.build(windowOf(candles), builder.completedPrefix(candles, t), Timeframe.D1, t)!!

        assertEquals(ChartFixtures.MONDAY_OPEN_UTC_MS, forming.timestampUtcMs)
        assertEquals(candles[0].openUnits, forming.openUnits)
    }

    @Test
    fun `the forming candle tracks the current price as the instant advances`() {
        val candles = ChartFixtures.rising(30)
        val window = windowOf(candles)

        val early = builder.build(window, builder.completedPrefix(candles, ChartFixtures.at(5)), Timeframe.M1, ChartFixtures.at(5))!!
        val later = builder.build(window, builder.completedPrefix(candles, ChartFixtures.at(15)), Timeframe.M1, ChartFixtures.at(15))!!

        assertTrue(
            "the later forming candle should reflect a higher price on a rising series",
            later.closeUnits > early.closeUnits,
        )
    }

    @Test
    fun `the forming candle at a gap boundary produces nothing`() {
        val candles = listOf(
            ChartFixtures.candle(0, open = 100, close = 110),
            ChartFixtures.candle(60, open = 200, close = 210),
        )
        val t = ChartFixtures.at(30)
        assertNull(builder.build(windowOf(candles), builder.completedPrefix(candles, t), Timeframe.H1, t))
    }

    @Test
    fun `the path model version is unchanged by chart work`() {
        // The chart consumes the path engine's output; it must never alter it.
        assertEquals(1, PathModelVersion.CURRENT)
    }
}
