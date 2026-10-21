package com.tradequest.app.core.chart.timeframe

import com.tradequest.app.core.chart.ChartFixtures
import com.tradequest.app.core.market.HistoricalCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 8.11 tests: aggregation, and the future-information rule from
 * `AGENTS.md` section 8.
 */
class TimeframeAggregatorTest {

    // ---------- Completed aggregation ----------

    @Test
    fun `aggregating one minute returns the input unchanged`() {
        val candles = ChartFixtures.rising(10)
        assertEquals(candles, TimeframeAggregator.aggregate(candles, Timeframe.M1))
    }

    @Test
    fun `aggregating an empty series returns an empty series`() {
        assertTrue(TimeframeAggregator.aggregate(emptyList(), Timeframe.H1).isEmpty())
    }

    @Test
    fun `an hourly candle takes the first open, the last close and the extremes`() {
        // 60 minutes from the trading-day open, so exactly one 1h bucket.
        val candles = listOf(
            ChartFixtures.candle(0, open = 100, close = 110, high = 120, low = 90, volume = 5),
            ChartFixtures.candle(30, open = 110, close = 105, high = 130, low = 100, volume = 7),
            ChartFixtures.candle(59, open = 105, close = 115, high = 118, low = 95, volume = 3),
        )

        val result = TimeframeAggregator.aggregate(candles, Timeframe.H1)

        assertEquals(1, result.size)
        val hour = result.single()
        assertEquals(100, hour.openUnits) // first open
        assertEquals(115, hour.closeUnits) // last close
        assertEquals(130, hour.highUnits) // max high
        assertEquals(90, hour.lowUnits) // min low
        assertEquals(15, hour.tickVolume) // sum
        assertEquals(ChartFixtures.MONDAY_OPEN_UTC_MS, hour.timestampUtcMs)
    }

    @Test
    fun `minutes are split into separate buckets at the boundary`() {
        // Minute 59 is in the first hour; minute 60 starts the second.
        val candles = ChartFixtures.rising(120)
        val result = TimeframeAggregator.aggregate(candles, Timeframe.H1)

        assertEquals(2, result.size)
        assertEquals(ChartFixtures.at(0), result[0].timestampUtcMs)
        assertEquals(ChartFixtures.at(60), result[1].timestampUtcMs)
    }

    @Test
    fun `every input minute is accounted for exactly once`() {
        val candles = ChartFixtures.rising(500)
        val result = TimeframeAggregator.aggregate(candles, Timeframe.M15)

        val totalVolume = result.sumOf { it.tickVolume }
        assertEquals(candles.sumOf { it.tickVolume }, totalVolume)
    }

    @Test
    fun `a bucket with no minutes produces no candle`() {
        // Minutes 0 and 600: the hours between them have no data and must not
        // appear as fabricated flat candles.
        val candles = listOf(
            ChartFixtures.candle(0, open = 100, close = 101),
            ChartFixtures.candle(600, open = 200, close = 201),
        )

        val result = TimeframeAggregator.aggregate(candles, Timeframe.H1)

        assertEquals(2, result.size)
        assertEquals(ChartFixtures.at(0), result[0].timestampUtcMs)
        assertEquals(ChartFixtures.at(600), result[1].timestampUtcMs)
    }

    @Test
    fun `aggregating a flat series keeps a zero-height candle`() {
        val result = TimeframeAggregator.aggregate(ChartFixtures.flat(60), Timeframe.H1)
        assertEquals(1, result.size)
        val hour = result.single()
        assertEquals(hour.openUnits, hour.highUnits)
        assertEquals(hour.lowUnits, hour.closeUnits)
    }

    // ---------- Forming bucket: the future-information rule ----------

    @Test
    fun `a forming candle uses only the minutes it was given`() {
        // Three minutes revealed of a one-hour bucket. The bucket's high so far
        // must be the max of those three, not of the hour.
        val revealed = listOf(
            ChartFixtures.candle(0, open = 100, close = 110, high = 115, low = 95),
            ChartFixtures.candle(1, open = 110, close = 120, high = 125, low = 105),
            ChartFixtures.candle(2, open = 120, close = 118, high = 122, low = 112),
        )

        val forming = TimeframeAggregator.formingFrom(revealed, Timeframe.H1)

        assertNotNull(forming)
        forming!!
        assertEquals(ChartFixtures.MONDAY_OPEN_UTC_MS, forming.timestampUtcMs)
        assertEquals(100, forming.openUnits)
        assertEquals(118, forming.closeUnits)
        assertEquals(125, forming.highUnits) // max of the three, not of the hour
        assertEquals(95, forming.lowUnits)
    }

    @Test
    fun `a forming candle never reports volume`() {
        // tickVolume is a whole-minute total, so any figure here would leak how
        // much of the minute remains. The forming candle reports zero.
        val revealed = listOf(
            ChartFixtures.candle(0, open = 100, close = 110, volume = 9_999),
        )

        val forming = TimeframeAggregator.formingFrom(revealed, Timeframe.H1)

        assertEquals(0, forming!!.tickVolume)
    }

    @Test
    fun `the forming candle is unchanged when later minutes are absent`() {
        // The point of the rule: the forming candle is a function of the revealed
        // minutes only. Passing the same revealed prefix twice gives the same
        // answer, whatever the rest of the hour contains.
        val revealedPrefix = ChartFixtures.rising(10).map { it.copy(tickVolume = 0) }
        val fullHour = ChartFixtures.rising(60).map { it.copy(tickVolume = 0) }

        val fromPrefix = TimeframeAggregator.formingFrom(revealedPrefix, Timeframe.H1)
        val fromFull = TimeframeAggregator.formingFrom(
            TimeframeAggregator.currentBucketMinutes(fullHour, Timeframe.H1).take(10),
            Timeframe.H1,
        )

        assertEquals(fromPrefix, fromFull)
    }

    @Test
    fun `forming a candle from an empty prefix returns null`() {
        assertNull(TimeframeAggregator.formingFrom(emptyList(), Timeframe.H1))
    }

    // ---------- Current-bucket extraction ----------

    @Test
    fun `current bucket minutes returns only the tail of the series`() {
        // 120 minutes of data; the last minute (119) is in the second hour, which
        // began at minute 60.
        val candles = ChartFixtures.rising(120)
        val current = TimeframeAggregator.currentBucketMinutes(candles, Timeframe.H1)

        assertEquals(60, current.size)
        assertEquals(ChartFixtures.at(60), current.first().timestampUtcMs)
        assertEquals(ChartFixtures.at(119), current.last().timestampUtcMs)
    }

    @Test
    fun `current bucket minutes of an empty series is empty`() {
        assertTrue(TimeframeAggregator.currentBucketMinutes(emptyList(), Timeframe.H1).isEmpty())
    }

    @Test
    fun `current bucket minutes for a daily frame spans the whole trading day`() {
        val candles = ChartFixtures.rising(120)
        val current = TimeframeAggregator.currentBucketMinutes(candles, Timeframe.D1)

        // All 120 minutes are inside one trading day, so all are returned.
        assertEquals(120, current.size)
    }

    // ---------- Cross-check: aggregate and formingFrom agree on a complete bucket ----------

    @Test
    fun `forming a complete bucket matches the aggregated candle on price`() {
        val candles = ChartFixtures.rising(60)
        val aggregated = TimeframeAggregator.aggregate(candles, Timeframe.H1).single()
        val forming = TimeframeAggregator.formingFrom(candles, Timeframe.H1)!!

        assertEquals(aggregated.openUnits, forming.openUnits)
        assertEquals(aggregated.highUnits, forming.highUnits)
        assertEquals(aggregated.lowUnits, forming.lowUnits)
        assertEquals(aggregated.closeUnits, forming.closeUnits)
        // Volume deliberately differs: the forming form never reports it.
        assertEquals(0, forming.tickVolume)
    }

    // ---------- Ordering ----------

    @Test
    fun `aggregated buckets ascend by time`() {
        val result = TimeframeAggregator.aggregate(ChartFixtures.rising(600), Timeframe.M15)
        for (i in 1 until result.size) {
            assertTrue(
                "bucket $i (${result[i].timestampUtcMs}) must be after bucket ${i - 1}",
                result[i].timestampUtcMs > result[i - 1].timestampUtcMs,
            )
        }
    }

    @Test
    fun `aggregating preserves candle validity so the result passes validation`() {
        val result = TimeframeAggregator.aggregate(ChartFixtures.rising(240), Timeframe.H4)
        for (candle in result) {
            assertTrue(candle.highUnits >= candle.openUnits)
            assertTrue(candle.highUnits >= candle.closeUnits)
            assertTrue(candle.lowUnits <= candle.openUnits)
            assertTrue(candle.lowUnits <= candle.closeUnits)
            assertTrue(candle.highUnits >= candle.lowUnits)
            assertTrue(candle.tickVolume >= 0)
        }
    }

    @Test
    fun `an unvalidated input candle does not silently pass aggregation`() {
        // A deliberately broken candle: low above high. Aggregation must not
        // invent a correction, and the result must still reflect the input.
        val broken: HistoricalCandle = ChartFixtures.candle(
            minuteIndex = 0,
            open = 100,
            close = 100,
            high = 100,
            low = 200,
        )
        val result = TimeframeAggregator.aggregate(listOf(broken), Timeframe.H1)
        assertEquals(200, result.single().lowUnits)
    }
}
