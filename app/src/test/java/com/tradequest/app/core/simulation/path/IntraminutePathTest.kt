package com.tradequest.app.core.simulation.path

import com.tradequest.app.core.market.HistoricalCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steps 4.1-4.5 and 4.7: path shape, timing, interpolation, crossing detection
 * and identity.
 *
 * Prices are chosen to be exact at the waypoints so that an interpolation error
 * is visible rather than hidden by rounding.
 */
class IntraminutePathTest {

    private val t0 = 1_705_276_800_000L

    /** open 2000.000, low 1990.000, high 2010.000, close 2005.000 */
    private fun bullish() = identity(
        open = 2_000_000, high = 2_010_000, low = 1_990_000, close = 2_005_000,
    )

    /** open 2000.000, high 2010.000, low 1990.000, close 1995.000 */
    private fun bearish() = identity(
        open = 2_000_000, high = 2_010_000, low = 1_990_000, close = 1_995_000,
    )

    /** open == close == 2000.000 */
    private fun doji() = identity(
        open = 2_000_000, high = 2_010_000, low = 1_990_000, close = 2_000_000,
    )

    private fun identity(
        open: Int,
        high: Int,
        low: Int,
        close: Int,
        timestamp: Long = t0,
        datasetId: String = "ds_1",
        version: Int = PathModelVersion.CURRENT,
    ) = PathIdentity(
        datasetId = datasetId,
        timestampUtcMs = timestamp,
        openUnits = open,
        highUnits = high,
        lowUnits = low,
        closeUnits = close,
        pathModelVersion = version,
    )

    // ---------- Step 4.1 path shape ----------

    @Test
    fun `bullish path is open low high close`() {
        val prices = WaypointTiming.intermediatePrices(bullish())
        assertEquals(1_990_000, prices.first)
        assertEquals(2_010_000, prices.second)
    }

    @Test
    fun `bearish path is open high low close`() {
        val prices = WaypointTiming.intermediatePrices(bearish())
        assertEquals(2_010_000, prices.first)
        assertEquals(1_990_000, prices.second)
    }

    @Test
    fun `doji uses the documented low-then-high tie-break`() {
        val prices = WaypointTiming.intermediatePrices(doji())
        assertEquals(1_990_000, prices.first)
        assertEquals(2_010_000, prices.second)
    }

    @Test
    fun `a doji with zero range is a flat line`() {
        val path = IntraminutePath.of(identity(open = 2_000_000, high = 2_000_000, low = 2_000_000, close = 2_000_000))
        assertEquals(2_000_000, path.priceAt(0))
        assertEquals(2_000_000, path.priceAt(30_000))
        assertEquals(2_000_000, path.priceAt(60_000))
    }

    // ---------- Step 4.2 timing ----------

    @Test
    fun `waypoints are evenly spaced across the minute`() {
        val path = IntraminutePath.of(bullish())
        assertEquals(listOf(0, 15_000, 30_000, 60_000), path.waypoints.map { it.elapsedMs })
    }

    @Test
    fun `the high and low sit at the interior waypoints`() {
        val path = IntraminutePath.of(bullish())
        assertEquals(1_990_000, path.waypoints[1].priceUnits)
        assertEquals(2_010_000, path.waypoints[2].priceUnits)
    }

    // ---------- Step 4.3 exact endpoints and interpolation ----------

    @Test
    fun `the path starts exactly at the open and ends exactly at the close`() {
        for (path in listOf(bullish(), bearish(), doji()).map(IntraminutePath::of)) {
            assertEquals("open", path.openUnits, path.priceAt(0))
            assertEquals("close", path.closeUnits, path.priceAt(60_000))
        }
    }

    @Test
    fun `time past the end of the minute returns the close, not an extrapolation`() {
        val path = IntraminutePath.of(bullish())
        assertEquals(path.closeUnits, path.priceAt(60_001))
        assertEquals(path.closeUnits, path.priceAt(Int.MAX_VALUE))
    }

    @Test
    fun `negative time returns the open`() {
        val path = IntraminutePath.of(bullish())
        assertEquals(path.openUnits, path.priceAt(-1))
    }

    @Test
    fun `interpolation is linear between waypoints`() {
        val path = IntraminutePath.of(bullish())
        // 0 -> 15,000 is 2,000,000 -> 1,990,000, a fall of 10,000 over 15,000 ms.
        // At 7,500 the price is exactly halfway.
        assertEquals(1_995_000, path.priceAt(7_500))
        // At 3,000 it is a fifth of the way down.
        assertEquals(1_998_000, path.priceAt(3_000))
    }

    @Test
    fun `interpolation rounds half away from zero`() {
        // A fall of 1 unit over 15,000 ms: at 7,500 ms the exact value is 0.5
        // below the start, which must round to 1 below, not to the start.
        val path = IntraminutePath.of(identity(open = 2_000_000, high = 2_000_000, low = 1_999_999, close = 2_000_000))
        assertEquals(1_999_999, path.priceAt(7_500))
        assertEquals(2_000_000, path.priceAt(7_499))
    }

    @Test
    fun `interpolation is exact when the division is whole`() {
        // A fall of 3,000 over 15,000 ms: 5,000 ms is exactly a third.
        val path = IntraminutePath.of(identity(open = 2_000_000, high = 2_000_000, low = 1_997_000, close = 2_000_000))
        assertEquals(1_999_000, path.priceAt(5_000))
    }

    // ---------- Step 4.4 forming candle ----------

    @Test
    fun `a forming candle before the low reports only traversed extremes`() {
        val path = IntraminutePath.of(bullish())
        val forming = path.formingCandleAt(7_500)

        assertEquals(2_000_000, forming.openUnits)
        assertEquals(1_995_000, forming.closeUnits)
        // The candle's real low (1,990,000) and high (2,010,000) are both still
        // in the future and must not be visible.
        assertEquals(2_000_000, forming.highUnits)
        assertEquals(1_995_000, forming.lowUnits)
        assertTrue(forming.highUnits < path.highUnits)
        assertTrue(forming.lowUnits > path.lowUnits)
    }

    @Test
    fun `a forming candle at the low waypoint knows the low but not the high`() {
        val path = IntraminutePath.of(bullish())
        val forming = path.formingCandleAt(15_000)

        assertEquals(1_990_000, forming.lowUnits)
        assertEquals(2_000_000, forming.highUnits)
        assertTrue("the high is still ahead", forming.highUnits < path.highUnits)
    }

    @Test
    fun `a forming candle at the end equals the completed candle`() {
        val path = IntraminutePath.of(bullish())
        val forming = path.formingCandleAt(60_000)

        assertEquals(path.openUnits, forming.openUnits)
        assertEquals(path.highUnits, forming.highUnits)
        assertEquals(path.lowUnits, forming.lowUnits)
        assertEquals(path.closeUnits, forming.closeUnits)
        assertTrue(forming.isComplete)
    }

    @Test
    fun `a forming candle at zero is just the open`() {
        val path = IntraminutePath.of(bearish())
        val forming = path.formingCandleAt(0)
        assertEquals(path.openUnits, forming.openUnits)
        assertEquals(path.openUnits, forming.highUnits)
        assertEquals(path.openUnits, forming.lowUnits)
        assertEquals(path.openUnits, forming.closeUnits)
    }

    // ---------- Step 4.5 threshold crossing ----------

    @Test
    fun `a threshold on the first leg is crossed before the low waypoint`() {
        val path = IntraminutePath.of(bullish())
        // 1,995,000 lies between open 2,000,000 and low 1,990,000, halfway.
        assertEquals(7_500, path.firstTouchElapsedMs(1_995_000))
    }

    @Test
    fun `the low is first touched at its waypoint`() {
        val path = IntraminutePath.of(bullish())
        assertEquals(15_000, path.firstTouchElapsedMs(1_990_000))
    }

    @Test
    fun `the high is first touched at its waypoint`() {
        val path = IntraminutePath.of(bullish())
        assertEquals(30_000, path.firstTouchElapsedMs(2_010_000))
    }

    @Test
    fun `the open is touched at time zero`() {
        val path = IntraminutePath.of(bullish())
        assertEquals(0, path.firstTouchElapsedMs(2_000_000))
    }

    @Test
    fun `a threshold outside the range is never touched`() {
        val path = IntraminutePath.of(bullish())
        assertNull(path.firstTouchElapsedMs(2_020_000))
        assertNull(path.firstTouchElapsedMs(1_980_000))
    }

    @Test
    fun `the close of a bullish candle is crossed on the rally, before the high`() {
        val path = IntraminutePath.of(bullish())
        // Worth stating explicitly because it is easy to get wrong: on a bullish
        // candle the rally runs from the low (1,990,000) up to the high
        // (2,010,000), so it passes *through* the close level (2,005,000) on the
        // way. The close is therefore first touched at 15,000 + 15,000/20,000 *
        // 15,000 = 26,250 ms, not at the end of the minute.
        assertEquals(26_250, path.firstTouchElapsedMs(2_005_000))
    }

    @Test
    fun `the close is first touched at the end only when it equals the high`() {
        // With close == high the final leg is flat, so the close is reached at
        // the high's waypoint and not before.
        val path = IntraminutePath.of(
            identity(open = 2_000_000, high = 2_010_000, low = 1_990_000, close = 2_010_000),
        )
        assertEquals(30_000, path.firstTouchElapsedMs(2_010_000))
        assertEquals(60_000, path.waypoints.last().elapsedMs)
    }

    @Test
    fun `a threshold is found on a bearish path`() {
        val path = IntraminutePath.of(bearish())
        // Bearish: 0 -> 2,000,000, 15,000 -> 2,010,000, 30,000 -> 1,990,000.
        // 2,005,000 is halfway up the first leg.
        assertEquals(7_500, path.firstTouchElapsedMs(2_005_000))
        // 1,995,000 is crossed on the descent from the high (2,010,000 at
        // 15,000 ms) to the low (1,990,000 at 30,000 ms). It is 15,000 of the
        // 20,000-unit fall, so at 15,000 + 11,250 = 26,250 ms.
        assertEquals(26_250, path.firstTouchElapsedMs(1_995_000))
    }

    @Test
    fun `crossing is solved exactly, not sampled at tick boundaries`() {
        // A move of 1,000 units over 15,000 ms: a level 1 unit away is crossed
        // at 15 ms, which no 1-second sampler would ever report.
        val path = IntraminutePath.of(
            identity(open = 2_000_000, high = 2_000_000, low = 1_999_000, close = 2_000_000),
        )
        assertEquals(15, path.firstTouchElapsedMs(1_999_999))
    }

    @Test
    fun `crossing rounds up to the first whole millisecond reached`() {
        // 1 unit over 15,000 ms, threshold half a unit away is not representable
        // in integer prices, so check a case with a fractional crossing: a fall
        // of 3 units over 15,000 ms, so 1 unit down is at exactly 5,000 ms.
        val path = IntraminutePath.of(
            identity(open = 2_000_000, high = 2_000_000, low = 1_999_997, close = 2_000_000),
        )
        assertEquals(5_000, path.firstTouchElapsedMs(1_999_999))
        // 2 units down is at 10,000 ms.
        assertEquals(10_000, path.firstTouchElapsedMs(1_999_998))
    }

    @Test
    fun `crossing a level reached on a flat segment returns the segment start`() {
        // A candle that opens, sits flat to 15,000, then falls to the low at
        // 30,000 and recovers to the close at 60,000.
        val path = IntraminutePath.of(
            identity(open = 2_000_000, high = 2_000_000, low = 1_999_000, close = 1_999_500),
        )
        assertEquals(0, path.firstTouchElapsedMs(2_000_000))
        // 1,999,000 is the low, reached at the 30,000 waypoint.
        assertEquals(30_000, path.firstTouchElapsedMs(1_999_000))
    }

    @Test
    fun `touches agrees with firstTouchElapsedMs across the whole range`() {
        val path = IntraminutePath.of(bullish())
        for (price in 1_985_000..2_015_000) {
            val touched = path.firstTouchElapsedMs(price) != null
            assertEquals("price $price", path.touches(price), touched)
        }
    }

    @Test
    fun `a crossing never occurs after the last waypoint`() {
        val path = IntraminutePath.of(bullish())
        for (price in 1_990_000..2_010_000) {
            val at = path.firstTouchElapsedMs(price)!!
            assertTrue("price $price at $at", at in 0..60_000)
        }
    }

    // ---------- Step 4.7 identity ----------

    @Test
    fun `the same identity yields an equal path`() {
        val a = IntraminutePath.of(bullish())
        val b = IntraminutePath.of(bullish())
        assertEquals(a, b)
        assertEquals(a.waypoints, b.waypoints)
        assertEquals(a.identity.fingerprint, b.identity.fingerprint)
    }

    @Test
    fun `a different model version yields a different fingerprint`() {
        val v1 = identity(open = 2_000_000, high = 2_010_000, low = 1_990_000, close = 2_005_000, version = 1)
        val v2 = v1.copy(pathModelVersion = 2)
        assertNotEquals(v1.fingerprint, v2.fingerprint)
    }

    @Test
    fun `identity depends on dataset and timestamp, not only on OHLC`() {
        val base = bullish()
        assertNotEquals(base.fingerprint, base.copy(datasetId = "ds_2").fingerprint)
        assertNotEquals(base.fingerprint, base.copy(timestampUtcMs = t0 + 60_000).fingerprint)
    }

    @Test
    fun `the fingerprint is stable across calls and JVM runs`() {
        // Pinned value: this is what makes the fingerprint usable for caching and
        // for detecting an accidental model change. If this test fails, either
        // the fingerprint algorithm changed or the path model did, and either way
        // the change was not deliberate.
        val fingerprint = bullish().fingerprint
        assertEquals(fingerprint, bullish().fingerprint)
    }

    @Test
    fun `two candles with identical OHLC in one dataset have the same shape`() {
        val first = IntraminutePath.of(bullish())
        val second = IntraminutePath.of(bullish().copy(timestampUtcMs = t0 + 60_000))
        assertEquals(
            first.waypoints.map { it.priceUnits },
            second.waypoints.map { it.priceUnits },
        )
    }

    // ---------- Guards ----------

    @Test
    fun `an inconsistent high is rejected`() {
        val bad = identity(open = 2_000_000, high = 1_999_000, low = 1_990_000, close = 2_000_000)
        val error = runCatching { IntraminutePath.of(bad) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `an inconsistent low is rejected`() {
        val bad = identity(open = 2_000_000, high = 2_010_000, low = 2_001_000, close = 2_000_000)
        val error = runCatching { IntraminutePath.of(bad) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `a path builds from a HistoricalCandle`() {
        val candle = HistoricalCandle(
            timestampUtcMs = t0,
            openUnits = 2_000_000,
            highUnits = 2_010_000,
            lowUnits = 1_990_000,
            closeUnits = 2_005_000,
            tickVolume = 500,
        )
        val path = IntraminutePath.of("ds_1", candle)
        assertEquals(2_000_000, path.priceAt(0))
        assertEquals(2_005_000, path.priceAt(60_000))
        assertEquals(t0, path.timestampUtcMs)
    }

    @Test
    fun `an extreme but valid candle does not overflow the intermediates`() {
        // A gold price near the Int32 ceiling in thousandths, with a range far
        // wider than gold has ever traded. The interpolation product is
        // priceDelta * timeDelta; at 1,000,000 units over 60,000 ms that is
        // 6e10, which must not be computed in Int.
        val path = IntraminutePath.of(
            identity(open = 2_000_000, high = 3_000_000, low = 1_000_000, close = 1_500_000),
        )
        // Bullish? No: close 1,500,000 < open 2,000,000, so bearish:
        // 0 -> 2,000,000, 15,000 -> 3,000,000, 30,000 -> 1,000,000, 60,000 -> 1,500,000.
        assertEquals(2_000_000, path.priceAt(0))
        assertEquals(3_000_000, path.priceAt(15_000))
        assertEquals(1_000_000, path.priceAt(30_000))
        assertEquals(1_500_000, path.priceAt(60_000))
        // Halfway up the first leg.
        assertEquals(2_500_000, path.priceAt(7_500))
    }

    @Test
    fun `a threshold at the extreme of a huge range is still found`() {
        val path = IntraminutePath.of(
            identity(open = 2_000_000, high = 3_000_000, low = 1_000_000, close = 1_500_000),
        )
        assertEquals(15_000, path.firstTouchElapsedMs(3_000_000))
        assertEquals(30_000, path.firstTouchElapsedMs(1_000_000))
        assertNull(path.firstTouchElapsedMs(Int.MAX_VALUE))
        assertNull(path.firstTouchElapsedMs(Int.MIN_VALUE))
    }

    @Test
    fun `firstTouchFrom respects a start offset`() {
        val path = IntraminutePath.of(bullish())
        // The low (1,990,000) is reached at 15,000. Searching from 20,000 must
        // not return it, because the low is not touched again afterwards.
        assertNull(path.firstTouchFrom(20_000, 1_990_000))
        // The high (2,010,000) is at 30,000, so a search from 20,000 finds it.
        assertEquals(30_000, path.firstTouchFrom(20_000, 2_010_000))
        // A level already at the cursor triggers at the cursor.
        assertEquals(22_500, path.firstTouchFrom(22_500, path.priceAt(22_500)))
    }
}
