package com.tradequest.app.core.simulation.path

import com.tradequest.app.core.market.HistoricalCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steps 4.4-4.6 through the window API: mid-minute lookup, no interpolation
 * across gaps, and no leakage past the loaded candles.
 */
class MarketPathWindowTest {

    private val t0 = 1_705_276_800_000L
    private val minute = 60_000L

    private fun candle(minuteIndex: Int, base: Int) = HistoricalCandle(
        timestampUtcMs = t0 + minuteIndex * minute,
        openUnits = base,
        highUnits = base + 10_000,
        lowUnits = base - 10_000,
        closeUnits = base + 5_000,
        tickVolume = 100,
    )

    /** Three contiguous minutes at 2,000,000, 2,001,000, 2,002,000. */
    private fun contiguous() = MarketPathWindow.of(
        "ds_1",
        listOf(candle(0, 2_000_000), candle(1, 2_001_000), candle(2, 2_002_000)),
    )

    // ---------- Mid-minute lookup ----------

    @Test
    fun `a mid-minute time resolves to the containing candle`() {
        val window = contiguous()
        val at = t0 + 30_000

        val step = window.stepAt(at)
        assertNotNull(step)
        assertEquals(t0, step!!.path.timestampUtcMs)
    }

    @Test
    fun `a mid-minute time reports the right elapsed offset`() {
        val window = contiguous()
        val position = window.positionAt(t0 + 22_500)
        assertEquals(22_500, position.elapsedMs)
        assertTrue(position.hasPrice)
    }

    @Test
    fun `the price at a mid-minute time matches the candle path`() {
        val window = contiguous()
        val at = t0 + 22_500
        assertEquals(
            window.stepAt(at)!!.path.priceAt(22_500),
            window.priceAt(at),
        )
    }

    @Test
    fun `a candle start resolves to its own open`() {
        val window = contiguous()
        assertEquals(2_001_000, window.priceAt(t0 + minute))
    }

    @Test
    fun `the last millisecond of a minute still belongs to that candle`() {
        val window = contiguous()
        val position = window.positionAt(t0 + minute - 1)
        assertEquals(t0, position.step!!.path.timestampUtcMs)
        assertEquals(59_999, position.elapsedMs)
    }

    @Test
    fun `the first millisecond of the next minute belongs to the next candle`() {
        val window = contiguous()
        val position = window.positionAt(t0 + minute)
        assertEquals(t0 + minute, position.step!!.path.timestampUtcMs)
        assertEquals(0, position.elapsedMs)
    }

    // ---------- Outside the window ----------

    @Test
    fun `a time before the first candle has no price`() {
        val window = contiguous()
        val position = window.positionAt(t0 - 1)
        assertNull(position.priceUnits)
        assertTrue(position.outsideWindow)
        assertNull(position.inGap)
    }

    @Test
    fun `a time after the last candle has no price`() {
        val window = contiguous()
        val position = window.positionAt(t0 + 3 * minute)
        assertNull(position.priceUnits)
        assertTrue(position.outsideWindow)
    }

    @Test
    fun `the window reports its own extent`() {
        val window = contiguous()
        assertEquals(t0, window.firstUtcMs)
        assertEquals(t0 + 2 * minute, window.lastUtcMs)
        assertEquals(t0 + 3 * minute, window.endExclusiveUtcMs)
    }

    // ---------- Step 4.6: gaps are never interpolated ----------

    @Test
    fun `a time inside a gap has no price`() {
        // Minutes 0 and 3 are present; 1 and 2 are missing.
        val window = MarketPathWindow.of("ds_1", listOf(candle(0, 2_000_000), candle(3, 2_000_000)))

        val insideGap = t0 + minute
        val position = window.positionAt(insideGap)

        assertNull("a gap must not be interpolated across", position.priceUnits)
        assertNotNull(position.inGap)
        assertFalse(position.outsideWindow)
    }

    @Test
    fun `the gap is reported with its bounds and reason`() {
        val window = MarketPathWindow.of(
            "ds_1",
            listOf(candle(0, 2_000_000), candle(3, 2_000_000)),
            gapReasonAt = { GapReason.MARKET_CLOSURE },
        )

        val gap = window.gapContaining(t0 + minute)!!
        assertEquals(t0 + minute, gap.fromUtcMs)
        assertEquals(t0 + 3 * minute, gap.toUtcMs)
        assertEquals(GapReason.MARKET_CLOSURE, gap.reason)
        assertEquals(2 * minute, gap.durationMs)
    }

    @Test
    fun `the last gap millisecond is still a gap and the next is a price`() {
        val window = MarketPathWindow.of("ds_1", listOf(candle(0, 2_000_000), candle(3, 2_000_000)))

        assertNotNull("one ms before the next candle is still the gap", window.gapContaining(t0 + 3 * minute - 1))
        assertNull(window.gapContaining(t0 + 3 * minute))
        assertNotNull(window.priceAt(t0 + 3 * minute))
    }

    @Test
    fun `a long weekend gap is one gap, not many`() {
        // Friday minute 0 to the following Wednesday minute 0. The gap begins at
        // the first minute after Friday's candle, so it spans 7,199 minutes.
        val window = MarketPathWindow.of("ds_1", listOf(candle(0, 2_000_000), candle(7_200, 2_000_000)))
        val gap = window.gapContaining(t0 + 3_000 * minute)!!
        assertEquals((7_200 - 1) * minute, gap.durationMs)
        assertNull(window.priceAt(t0 + 3_000 * minute))
    }

    @Test
    fun `a forming candle inside a gap is absent`() {
        val window = MarketPathWindow.of("ds_1", listOf(candle(0, 2_000_000), candle(3, 2_000_000)))
        assertNull(window.formingCandleAt(t0 + minute))
    }

    @Test
    fun `contiguous candles report no gap anywhere`() {
        val window = contiguous()
        for (offset in 0 until 3 * minute step 1_000) {
            assertNull("offset $offset", window.gapContaining(t0 + offset))
        }
    }

    // ---------- Step 4.5 through the window ----------

    @Test
    fun `a crossing in a later candle is found`() {
        val window = contiguous()
        // Minute 1 spans 2,001,000 open -> low 1,991,000 -> high 2,011,000 -> close 2,006,000.
        // The high is at 30,000 ms into that candle.
        val at = window.firstTouchAtOrAfter(t0, 2_011_000)
        assertEquals(t0 + minute + 30_000, at)
    }

    @Test
    fun `a crossing search does not look before the requested time`() {
        val window = contiguous()
        // Minute 0's low is 1,990,000 at 15,000 ms. Searching from 20,000 ms
        // must not return that earlier crossing.
        val at = window.firstTouchAtOrAfter(t0 + 20_000, 1_990_000)
        assertTrue("must not find a crossing before the search start", at == null || at!! >= t0 + 20_000)
    }

    @Test
    fun `a level only reachable in a later candle is found there`() {
        val window = contiguous()
        val at = window.firstTouchAtOrAfter(t0, 2_002_000 + 10_000)
        assertNotNull(at)
        assertTrue(at!! >= t0 + 2 * minute)
    }

    @Test
    fun `a level never reached returns null`() {
        val window = contiguous()
        assertNull(window.firstTouchAtOrAfter(t0, 9_999_999))
    }

    @Test
    fun `crossing search does not jump a gap`() {
        // Minute 0 closes at 2,005,000; minute 5 opens at 2,500,000. A level
        // between them is reached only at minute 5's own path, never by
        // interpolating the jump.
        val window = MarketPathWindow.of(
            "ds_1",
            listOf(
                HistoricalCandle(t0, 2_000_000, 2_010_000, 1_990_000, 2_005_000, 100),
                HistoricalCandle(t0 + 5 * minute, 2_500_000, 2_510_000, 2_490_000, 2_505_000, 100),
            ),
        )

        // 2,400,000 is above minute 0's range and below minute 5's, so it is
        // never reached at all, despite the jump passing "through" it.
        assertNull("the gap must not be treated as movement", window.firstTouchAtOrAfter(t0, 2_400_000))
    }

    // ---------- Step 4.4: no future leakage ----------

    @Test
    fun `a forming candle never sees the rest of its own minute`() {
        val window = contiguous()
        val at = t0 + 5_000
        val forming = window.formingCandleAt(at)!!

        // Minute 0's real high is 2,010,000, reached at 30,000 ms.
        assertEquals(2_000_000, forming.openUnits)
        assertTrue("high so far must be below the minute's high", forming.highUnits < 2_010_000)
        assertTrue("low so far must be above the minute's low", forming.lowUnits > 1_990_000)
    }

    @Test
    fun `a forming candle never sees a later candle`() {
        val window = contiguous()
        val forming = window.formingCandleAt(t0 + 5_000)!!

        // Minute 1's prices must not appear in minute 0's forming candle.
        assertTrue(forming.highUnits <= 2_010_000)
        assertTrue(forming.lowUnits >= 1_990_000)
        assertTrue(forming.highUnits < 2_011_000)
    }

    @Test
    fun `an empty window has no extent and no prices`() {
        val window = MarketPathWindow.of("ds_1", emptyList())
        assertNull(window.firstUtcMs)
        assertNull(window.lastUtcMs)
        assertNull(window.priceAt(t0))
        assertNull(window.stepAt(t0))
        assertTrue(window.positionAt(t0).outsideWindow)
    }

    @Test
    fun `a window is repeatable`() {
        val a = contiguous()
        val b = contiguous()
        for (offset in 0 until 3 * minute step 500) {
            assertEquals(a.priceAt(t0 + offset), b.priceAt(t0 + offset))
        }
    }
}
