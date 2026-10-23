package com.tradequest.app.core.chart.crosshair

import com.tradequest.app.core.chart.ChartFixtures
import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.renderer.FormingCandleView
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.market.PriceScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 9.5 tests: the crosshair's reading.
 *
 * The two that matter are the boundary between the last closed bar and the forming
 * candle — off by one there reads the wrong candle, which a screenshot cannot show —
 * and the clamping, because the crosshair can be dragged into the right gutter where
 * there is no candle at all.
 */
class CrosshairReadoutTest {

    private val buffer = CandleBuffer.of(ChartFixtures.rising(10))

    private fun forming(
        open: Int = 2_100_000,
        high: Int = 2_100_500,
        low: Int = 2_099_500,
        close: Int = 2_100_200,
    ) = FormingCandleView(
        timestampUtcMs = ChartFixtures.at(10),
        openUnits = open,
        highUnits = high,
        lowUnits = low,
        closeUnits = close,
    )

    // ---------- Reading a closed candle ----------

    @Test
    fun `reads the candle at the given index`() {
        val readout = CrosshairReadout.at(buffer, forming = null, index = 3)!!
        assertEquals(3, readout.index)
        assertFalse(readout.isForming)
        assertEquals(buffer.timestamps[3], readout.timestampUtcMs)
        assertEquals(buffer.opens[3].toInt(), readout.openUnits)
        assertEquals(buffer.highs[3].toInt(), readout.highUnits)
        assertEquals(buffer.lows[3].toInt(), readout.lowUnits)
        assertEquals(buffer.closes[3].toInt(), readout.closeUnits)
    }

    @Test
    fun `reads the first candle`() {
        val readout = CrosshairReadout.at(buffer, forming = null, index = 0)!!
        assertEquals(0, readout.index)
        assertEquals(buffer.opens[0].toInt(), readout.openUnits)
    }

    @Test
    fun `reads the last closed candle`() {
        val readout = CrosshairReadout.at(buffer, forming = null, index = buffer.size - 1)!!
        assertEquals(buffer.size - 1, readout.index)
        assertFalse(readout.isForming)
        assertEquals(buffer.closes[buffer.size - 1].toInt(), readout.closeUnits)
    }

    // ---------- The closed/forming boundary ----------

    @Test
    fun `the forming candle occupies the slot past the last closed bar`() {
        val readout = CrosshairReadout.at(buffer, forming = forming(), index = buffer.size)!!
        assertTrue("index ${buffer.size} must be the forming candle", readout.isForming)
        assertEquals(buffer.size, readout.index)
        assertEquals(2_100_200, readout.closeUnits)
    }

    @Test
    fun `the last closed bar is not the forming candle`() {
        // The off-by-one that matters: index size-1 is data, index size is forming.
        val readout = CrosshairReadout.at(buffer, forming = forming(), index = buffer.size - 1)!!
        assertFalse("the last closed bar must not read as forming", readout.isForming)
        assertEquals(buffer.closes[buffer.size - 1].toInt(), readout.closeUnits)
    }

    // ---------- Clamping ----------

    @Test
    fun `an index past the end reads the newest candle rather than nothing`() {
        // Dragging into the right gutter must not make the crosshair blink off.
        val withForming = CrosshairReadout.at(buffer, forming = forming(), index = 9_999)!!
        assertTrue(withForming.isForming)

        val withoutForming = CrosshairReadout.at(buffer, forming = null, index = 9_999)!!
        assertEquals("with no forming candle the newest bar is the last", buffer.size - 1, withoutForming.index)
    }

    @Test
    fun `a negative index reads the oldest candle`() {
        val readout = CrosshairReadout.at(buffer, forming = forming(), index = -50)!!
        assertEquals(0, readout.index)
        assertEquals(buffer.opens[0].toInt(), readout.openUnits)
    }

    // ---------- Degenerate inputs ----------

    @Test
    fun `an empty buffer with no forming candle reads nothing`() {
        assertNull(CrosshairReadout.at(CandleBuffer.EMPTY, forming = null, index = 0))
    }

    @Test
    fun `an empty buffer with a forming candle reads the forming candle`() {
        val readout = CrosshairReadout.at(CandleBuffer.EMPTY, forming = forming(), index = 0)!!
        assertTrue(readout.isForming)
        assertEquals(0, readout.index)
        assertEquals(2_100_200, readout.closeUnits)
    }

    @Test
    fun `an empty buffer at a negative index still reads the forming candle`() {
        // There is only one candle, so any index resolves to it rather than null.
        val readout = CrosshairReadout.at(CandleBuffer.EMPTY, forming = forming(), index = -5)!!
        assertTrue(readout.isForming)
    }

    // ---------- Derived display fields ----------

    @Test
    fun `the change is close minus open`() {
        val up = CrosshairReadout.at(buffer, forming = null, index = 3)!!
        assertEquals(up.closeUnits - up.openUnits, up.changeUnits)
        assertTrue("a rising fixture must read as up", up.changeUnits > 0)
    }

    @Test
    fun `the change label carries an explicit sign`() {
        val readout = CrosshairReadout.at(buffer, forming = null, index = 3)!!
        assertTrue("a gain must be signed positive, was ${readout.changeLabel}", readout.changeLabel.startsWith("+"))
    }

    @Test
    fun `a falling candle has a negative change label`() {
        val falling = CrosshairReadout(
            index = 0, isForming = false, timestampUtcMs = ChartFixtures.at(0),
            openUnits = 2_050_000, highUnits = 2_050_100, lowUnits = 2_049_000, closeUnits = 2_049_500,
        )
        assertEquals(-500, falling.changeUnits)
        assertTrue("a loss must be signed negative, was ${falling.changeLabel}", falling.changeLabel.startsWith("-"))
    }

    @Test
    fun `the change label is formatted on the app price scale`() {
        val readout = CrosshairReadout(
            index = 0, isForming = false, timestampUtcMs = ChartFixtures.at(0),
            openUnits = 2_050_000, highUnits = 2_051_000, lowUnits = 2_049_000, closeUnits = 2_051_230,
        )
        assertEquals("+" + PriceScale.format(1_230), readout.changeLabel)
    }

    @Test
    fun `a flat candle has a positive signed change`() {
        // Zero is rendered as +0 rather than -0, which would read as a loss.
        val flat = CrosshairReadout(
            index = 0, isForming = false, timestampUtcMs = ChartFixtures.at(0),
            openUnits = 2_050_000, highUnits = 2_050_000, lowUnits = 2_050_000, closeUnits = 2_050_000,
        )
        assertEquals(0, flat.changeUnits)
        assertTrue("a flat candle must not read as negative", flat.changeLabel.startsWith("+"))
    }

    @Test
    fun `the ohlc labels are the four prices in order`() {
        val readout = CrosshairReadout.at(buffer, forming = null, index = 2)!!
        assertEquals(
            listOf(readout.openUnits, readout.highUnits, readout.lowUnits, readout.closeUnits)
                .map { PriceScale.format(it) },
            readout.ohlcLabels,
        )
    }

    @Test
    fun `the timestamp label is formatted in New York time`() {
        val readout = CrosshairReadout.at(buffer, forming = null, index = 0)!!
        assertNotNull(readout.timestampLabel)
        // The fixture starts at Monday 17:00 ET, the trading day open.
        assertTrue(
            "expected a 17:00 open, was ${readout.timestampLabel}",
            readout.timestampLabel.endsWith("17:00"),
        )
    }

    // ---------- Index resolution ----------

    @Test
    fun `an index is rounded rather than truncated`() {
        // Truncation biases left, so 3.6 would read bar 3 instead of bar 4.
        assertEquals(4, CrosshairReadout.indexFor(3.6f))
        assertEquals(3, CrosshairReadout.indexFor(3.4f))
    }

    @Test
    fun `intraday timeframes do not need a date`() {
        assertFalse(CrosshairReadout.needsDate(Timeframe.M1))
        assertFalse(CrosshairReadout.needsDate(Timeframe.H1))
    }

    @Test
    fun `daily and above timeframes need a date`() {
        assertTrue(CrosshairReadout.needsDate(Timeframe.D1))
    }
}
