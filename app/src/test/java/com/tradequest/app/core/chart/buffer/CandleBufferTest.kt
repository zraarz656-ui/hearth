package com.tradequest.app.core.chart.buffer

import com.tradequest.app.core.chart.ChartFixtures
import com.tradequest.app.core.market.HistoricalCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 8.2 tests: the primitive-array candle buffer.
 *
 * The central claim is that the `Int` to `Float` copy is lossless. That is
 * asserted directly on every field, not assumed, because a silent rounding here
 * would draw every candle at a slightly wrong height.
 */
class CandleBufferTest {

    @Test
    fun `an empty list produces an empty buffer`() {
        val buffer = CandleBuffer.of(emptyList())
        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)
        assertEquals(CandleBuffer.EMPTY.size, buffer.size)
    }

    @Test
    fun `the float copy is lossless for every price field`() {
        val candles = ChartFixtures.rising(200)
        val buffer = CandleBuffer.of(candles)

        assertEquals(candles.size, buffer.size)
        for (i in candles.indices) {
            assertEquals(candles[i].openUnits.toFloat(), buffer.opens[i], 0f)
            assertEquals(candles[i].highUnits.toFloat(), buffer.highs[i], 0f)
            assertEquals(candles[i].lowUnits.toFloat(), buffer.lows[i], 0f)
            assertEquals(candles[i].closeUnits.toFloat(), buffer.closes[i], 0f)
            assertEquals(candles[i].timestampUtcMs, buffer.timestamps[i])
            assertEquals(candles[i].tickVolume, buffer.volumes[i])
        }
    }

    @Test
    fun `a price at the exact float limit is accepted`() {
        val limit = CandleBuffer.MAX_EXACT_FLOAT_INT
        val candle = HistoricalCandle(
            timestampUtcMs = ChartFixtures.MONDAY_OPEN_UTC_MS,
            openUnits = limit,
            highUnits = limit,
            lowUnits = limit,
            closeUnits = limit,
            tickVolume = 1,
        )

        val buffer = CandleBuffer.of(listOf(candle))

        assertEquals(limit.toFloat(), buffer.opens[0], 0f)
        // The round trip back to Int must be exact, which is the real claim.
        assertEquals(limit, buffer.opens[0].toInt())
    }

    @Test
    fun `a price beyond the exact float limit is rejected rather than rounded`() {
        val tooBig = CandleBuffer.MAX_EXACT_FLOAT_INT + 1
        val candle = HistoricalCandle(
            timestampUtcMs = ChartFixtures.MONDAY_OPEN_UTC_MS,
            openUnits = tooBig,
            highUnits = tooBig,
            lowUnits = tooBig,
            closeUnits = tooBig,
            tickVolume = 1,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            CandleBuffer.of(listOf(candle))
        }
        assertTrue(
            "the message should name the offending value, was: ${error.message}",
            error.message!!.contains(tooBig.toString()),
        )
    }

    @Test
    fun `an unsorted input is ordered by timestamp`() {
        val candles = ChartFixtures.rising(5).reversed()
        val buffer = CandleBuffer.of(candles)

        for (i in 1 until buffer.size) {
            assertTrue(buffer.timestamps[i] > buffer.timestamps[i - 1])
        }
    }

    @Test
    fun `indexAtOrBefore finds the containing candle`() {
        val buffer = CandleBuffer.of(ChartFixtures.rising(10))

        assertEquals(0, buffer.indexAtOrBefore(ChartFixtures.at(0)))
        assertEquals(0, buffer.indexAtOrBefore(ChartFixtures.at(0) + 30_000))
        assertEquals(1, buffer.indexAtOrBefore(ChartFixtures.at(1)))
        assertEquals(9, buffer.indexAtOrBefore(ChartFixtures.at(9) + 59_999))
    }

    @Test
    fun `indexAtOrBefore returns minus one before the first candle`() {
        val buffer = CandleBuffer.of(ChartFixtures.rising(10))
        assertEquals(-1, buffer.indexAtOrBefore(ChartFixtures.at(0) - 1))
    }

    @Test
    fun `indexAtOrBefore clamps past the end to the last candle`() {
        val buffer = CandleBuffer.of(ChartFixtures.rising(10))
        assertEquals(9, buffer.indexAtOrBefore(ChartFixtures.at(1_000)))
    }

    @Test
    fun `indexAtOrAfter finds the next candle`() {
        val buffer = CandleBuffer.of(ChartFixtures.rising(10))

        assertEquals(0, buffer.indexAtOrAfter(ChartFixtures.at(0) - 1))
        assertEquals(1, buffer.indexAtOrAfter(ChartFixtures.at(0) + 1))
        assertEquals(9, buffer.indexAtOrAfter(ChartFixtures.at(9)))
    }

    @Test
    fun `indexAtOrAfter returns size past the end`() {
        val buffer = CandleBuffer.of(ChartFixtures.rising(10))
        assertEquals(10, buffer.indexAtOrAfter(ChartFixtures.at(100)))
    }

    @Test
    fun `binary searches agree with a linear scan on every probe`() {
        val buffer = CandleBuffer.of(ChartFixtures.rising(500))

        for (minute in -5..505) {
            val probe = ChartFixtures.at(minute)
            val linearBefore = buffer.timestamps.indexOfLast { it <= probe }
            val linearAfter = buffer.timestamps.indexOfFirst { it >= probe }
            assertEquals(linearBefore, buffer.indexAtOrBefore(probe))
            assertEquals(
                if (linearAfter == -1) buffer.size else linearAfter,
                buffer.indexAtOrAfter(probe),
            )
        }
    }

    @Test
    fun `bullish and bearish are read from close against open`() {
        val bullish = ChartFixtures.candle(0, open = 100, close = 110)
        val bearish = ChartFixtures.candle(1, open = 110, close = 100)
        val doji = ChartFixtures.candle(2, open = 100, close = 100)
        val buffer = CandleBuffer.of(listOf(bullish, bearish, doji))

        assertTrue(buffer.isBullish(0))
        assertFalse(buffer.isBearish(0))
        assertTrue(buffer.isBearish(1))
        assertFalse(buffer.isBullish(1))
        assertFalse(buffer.isBullish(2))
        assertFalse(buffer.isBearish(2))
    }

    @Test
    fun `highest and lowest span the requested range`() {
        val buffer = CandleBuffer.of(ChartFixtures.rising(10))

        val high = buffer.highestHigh(2, 4)!!
        val low = buffer.lowestLow(2, 4)!!

        assertEquals(buffer.highs[4], high, 0f)
        assertEquals(buffer.lows[2], low, 0f)
    }

    @Test
    fun `highest and lowest of an empty range are null`() {
        val buffer = CandleBuffer.of(ChartFixtures.rising(10))
        assertNull(buffer.highestHigh(5, 4))
        assertNull(buffer.lowestLow(5, 4))
    }

    @Test
    fun `a single-element range returns that element`() {
        val buffer = CandleBuffer.of(ChartFixtures.rising(10))
        assertEquals(buffer.highs[3], buffer.highestHigh(3, 3)!!, 0f)
        assertEquals(buffer.lows[3], buffer.lowestLow(3, 3)!!, 0f)
    }
}
