package com.tradequest.app.core.simulation.path

import com.tradequest.app.core.market.HistoricalCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steps 4.6 and 4.8: gaps are explicit, and generation is repeatable with no
 * future leakage.
 */
class MarketPathBuilderTest {

    private val t0 = 1_705_276_800_000L
    private val minute = 60_000L

    private fun candle(
        minuteIndex: Int,
        open: Int,
        high: Int,
        low: Int,
        close: Int,
        volume: Int = 100,
    ) = HistoricalCandle(
        timestampUtcMs = t0 + minuteIndex * minute,
        openUnits = open,
        highUnits = high,
        lowUnits = low,
        closeUnits = close,
        tickVolume = volume,
    )

    private fun rising(minuteIndex: Int) = candle(
        minuteIndex,
        open = 2_000_000 + minuteIndex * 1_000,
        high = 2_005_000 + minuteIndex * 1_000,
        low = 1_999_000 + minuteIndex * 1_000,
        close = 2_004_000 + minuteIndex * 1_000,
    )

    // ---------- Step 4.6 gap handling ----------

    @Test
    fun `contiguous candles produce no gaps`() {
        val steps = MarketPathBuilder.build("ds_1", listOf(rising(0), rising(1), rising(2)))
        assertTrue(steps.all { it.precedingGap == null })
    }

    @Test
    fun `a missing minute produces one explicit gap`() {
        val steps = MarketPathBuilder.build("ds_1", listOf(rising(0), rising(2)))

        assertNull(steps[0].precedingGap)
        val gap = steps[1].precedingGap
        assertNotNull("the break must be explicit, not interpolated", gap)
        assertEquals(t0 + minute, gap!!.fromUtcMs)
        assertEquals(t0 + 2 * minute, gap.toUtcMs)
        assertEquals(minute, gap.durationMs)
    }

    @Test
    fun `a gap carries the price break across it`() {
        val steps = MarketPathBuilder.build("ds_1", listOf(rising(0), rising(5)))
        val gap = steps[1].precedingGap!!
        assertEquals(rising(0).closeUnits, gap.fromPriceUnits)
        assertEquals(rising(5).openUnits, gap.toPriceUnits)
        assertEquals(rising(5).openUnits - rising(0).closeUnits, gap.priceDeltaUnits)
    }

    @Test
    fun `a weekend gap is reported with the caller's reason`() {
        // Friday 21:00 to Sunday 22:00 would be many minutes; the reason comes
        // from the dataset's stored closures, not from this engine.
        val steps = MarketPathBuilder.build(
            datasetId = "ds_1",
            candles = listOf(rising(0), rising(2_000)),
            gapReasonAt = { GapReason.MARKET_CLOSURE },
        )
        assertEquals(GapReason.MARKET_CLOSURE, steps[1].precedingGap!!.reason)
    }

    @Test
    fun `the default gap reason is missing minutes, not a guessed closure`() {
        val steps = MarketPathBuilder.build("ds_1", listOf(rising(0), rising(2)))
        assertEquals(GapReason.MISSING_MINUTES, steps[1].precedingGap!!.reason)
    }

    @Test
    fun `a gap never spans a candle`() {
        // Every gap must lie strictly between two candle minutes, so no candle's
        // own minute is ever reported as missing.
        val candles = listOf(rising(0), rising(3), rising(7))
        val steps = MarketPathBuilder.build("ds_1", candles)

        for (index in steps.indices) {
            val gap = steps[index].precedingGap ?: continue
            val previous = candles[index - 1]
            val current = candles[index]
            assertTrue(gap.fromUtcMs >= previous.timestampUtcMs + minute)
            assertTrue(gap.toUtcMs <= current.timestampUtcMs)
        }
    }

    @Test
    fun `a one-minute jump is a gap even when prices match`() {
        // Time is what is missing. A matching price must not hide it.
        val flat = candle(0, 2_000_000, 2_000_000, 2_000_000, 2_000_000)
        val flatLater = candle(2, 2_000_000, 2_000_000, 2_000_000, 2_000_000)
        val steps = MarketPathBuilder.build("ds_1", listOf(flat, flatLater))

        assertNotNull(steps[1].precedingGap)
        assertEquals(0, steps[1].precedingGap!!.priceDeltaUnits)
    }

    @Test
    fun `an empty candle list yields no steps`() {
        assertTrue(MarketPathBuilder.build("ds_1", emptyList()).isEmpty())
    }

    @Test
    fun `a single candle has no gap`() {
        val steps = MarketPathBuilder.build("ds_1", listOf(rising(0)))
        assertEquals(1, steps.size)
        assertNull(steps[0].precedingGap)
    }

    @Test
    fun `out-of-order candles are rejected rather than sorted`() {
        val error = runCatching {
            MarketPathBuilder.build("ds_1", listOf(rising(1), rising(0)))
        }.exceptionOrNull()
        assertTrue("a reordered series must not be silently accepted", error is IllegalArgumentException)
    }

    @Test
    fun `duplicate timestamps are rejected`() {
        val error = runCatching {
            MarketPathBuilder.build("ds_1", listOf(rising(0), rising(0)))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    // ---------- Step 4.8 repeatability ----------

    @Test
    fun `the same input produces an identical path series`() {
        val candles = listOf(rising(0), rising(1), rising(3), rising(4))
        val first = MarketPathBuilder.build("ds_1", candles)
        val second = MarketPathBuilder.build("ds_1", candles)

        assertEquals(first.size, second.size)
        for (index in first.indices) {
            assertEquals(first[index].path, second[index].path)
            assertEquals(first[index].precedingGap, second[index].precedingGap)
            assertEquals(first[index].path.waypoints, second[index].path.waypoints)
        }
    }

    @Test
    fun `no path exposes a price outside its candle's range`() {
        val candles = listOf(rising(0), rising(1), rising(2))
        for (step in MarketPathBuilder.build("ds_1", candles)) {
            val path = step.path
            for (elapsed in 0..60_000 step 250) {
                val price = path.priceAt(elapsed)
                assertTrue(
                    "price $price at $elapsed outside [${path.lowUnits}, ${path.highUnits}]",
                    price in path.lowUnits..path.highUnits,
                )
            }
        }
    }

    @Test
    fun `a forming candle never reports an extreme from a later part of the minute`() {
        // Step 4.8 "no future leakage", checked exhaustively across the minute.
        val path = IntraminutePath.of("ds_1", rising(0))

        for (elapsed in 0..60_000 step 100) {
            val forming = path.formingCandleAt(elapsed)
            val expectedHigh = (0..elapsed).maxOf { path.priceAt(it) }
            val expectedLow = (0..elapsed).minOf { path.priceAt(it) }
            assertEquals("high at $elapsed", expectedHigh, forming.highUnits)
            assertEquals("low at $elapsed", expectedLow, forming.lowUnits)
        }
    }

    @Test
    fun `the path is unchanged by later candles`() {
        // Step 4.8 "no future leakage" across candles: candle N's path must not
        // depend on candle N+1.
        val first = MarketPathBuilder.build("ds_1", listOf(rising(0)))[0]
        val withMore = MarketPathBuilder.build("ds_1", listOf(rising(0), rising(1), rising(2)))[0]
        assertEquals(first.path, withMore.path)
        assertEquals(first.path.waypoints, withMore.path.waypoints)
    }
}
