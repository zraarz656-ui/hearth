package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.ChartFixtures
import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.chart.viewport.ChartViewport
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.simulation.path.MarketPathBuilder
import com.tradequest.app.core.simulation.path.MarketPathWindow
import com.tradequest.app.ui.theme.ThemeMode
import com.tradequest.app.ui.theme.chartStyleFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame assembly for a live chart (Steps 8.1, 8.10).
 *
 * # What is worth asserting here
 *
 * Three things can go wrong that a "does not throw" test would miss:
 *
 *  1. the buffer contains a candle from the forming bucket — a future leak;
 *  2. the price axis ignores the forming candle, so the newest candle is drawn
 *     off-screen;
 *  3. the visible window addresses the forming slot, reading past the buffer's
 *     arrays.
 *
 * Each has its own test below.
 */
class ChartFrameAssemblerTest {

    private val fallback = PriceAxisRange.around(2_050_000f)

    private fun windowOf(candles: List<HistoricalCandle>): MarketPathWindow =
        MarketPathWindow(
            datasetId = "test",
            steps = MarketPathBuilder.build("test", candles),
        )

    private fun build(
        candles: List<HistoricalCandle>,
        timeframe: Timeframe = Timeframe.M1,
        simulatedUtcMs: Long? = null,
        viewport: ChartViewport = ChartViewport.DEFAULT,
    ) = ChartFrameAssembler.build(
        candles = candles,
        timeframe = timeframe,
        style = chartStyleFor(ThemeMode.DARK),
        window = windowOf(candles),
        simulatedUtcMs = simulatedUtcMs,
        viewport = viewport,
        fallback = fallback,
    )

    // ---------- Historical view: no clock ----------

    @Test
    fun `a historical view reveals the whole dataset and has no forming candle`() {
        val candles = ChartFixtures.rising(120)
        val state = build(candles, simulatedUtcMs = null)

        assertEquals(120, state.buffer.size)
        assertNull(state.formingCandle)
        assertNull(state.simulatedUtcMs)
    }

    // ---------- Live view ----------

    @Test
    fun `a live view reveals only completed minutes`() {
        val candles = ChartFixtures.rising(120)
        // 30 minutes in: minutes 0..29 are complete.
        val state = build(candles, simulatedUtcMs = ChartFixtures.at(30))

        assertEquals(30, state.buffer.size)
        assertNotNull(state.formingCandle)
        assertEquals(ChartFixtures.at(30), state.simulatedUtcMs)
    }

    @Test
    fun `the forming candle sits one past the last revealed candle`() {
        val candles = ChartFixtures.rising(120)
        val state = build(candles, simulatedUtcMs = ChartFixtures.at(30))

        val forming = state.formingCandle!!
        // Minutes 0..29 are revealed and minute 30 is forming, so the x-axis has
        // 31 slots and the forming candle occupies the last one.
        assertEquals(30, state.buffer.size)
        assertEquals(31, state.totalBars)
        assertEquals(ChartFixtures.at(30), forming.timestampUtcMs)
    }

    @Test
    fun `the price axis covers the forming candle`() {
        // A forming candle whose extreme is far above every revealed candle. If
        // the axis ignored it, the newest bar would be drawn off-screen.
        val revealed = ChartFixtures.flat(10, price = 2_050_000)
        val spike = ChartFixtures.candle(10, open = 2_050_000, close = 2_060_000)
        val candles = revealed + spike

        val state = build(candles, simulatedUtcMs = ChartFixtures.at(10) + 1_000L)

        val forming = state.formingCandle!!
        assertTrue(
            "the price axis top does not reach the forming high",
            state.priceRange.maxUnits >= forming.highUnits,
        )
    }

    @Test
    fun `an empty buffer with a forming candle still produces a usable axis`() {
        // Ten minutes in, so the buffer is empty and the only bar is the forming
        // one. The buffer accessors must not be called with the forming index.
        val candles = ChartFixtures.rising(10)
        val state = build(candles, simulatedUtcMs = ChartFixtures.at(0) + 30_000L)

        assertEquals(0, state.buffer.size)
        assertNotNull(state.formingCandle)
        assertTrue(state.priceRange.maxUnits > state.priceRange.minUnits)
    }

    @Test
    fun `an hourly live view keeps the forming hour out of the buffer`() {
        val candles = ChartFixtures.rising(180)
        val state = build(candles, timeframe = Timeframe.H1, simulatedUtcMs = ChartFixtures.at(30))

        assertEquals("the forming hour leaked into the buffer", 0, state.buffer.size)
        assertNotNull(state.formingCandle)
    }

    @Test
    fun `a viewport carried over from a larger buffer is clamped to the bar count`() {
        val candles = ChartFixtures.rising(40)
        val state = build(candles, simulatedUtcMs = ChartFixtures.at(10))

        // totalBars is 11 (minutes 0..9 plus the forming one), but the caller's
        // viewport was scrolled to the end of a 10,000-bar series. It must come
        // back inside the new range rather than addressing bars that do not exist.
        val oversized = ChartViewport.DEFAULT.scrolledToEnd(10_000)
        val clamped = ChartFrameAssembler.build(
            candles = candles,
            timeframe = Timeframe.M1,
            style = chartStyleFor(ThemeMode.DARK),
            window = windowOf(candles),
            simulatedUtcMs = ChartFixtures.at(10),
            viewport = oversized,
            fallback = fallback,
        ).viewport

        assertTrue(
            "the viewport still addresses bars past the end",
            clamped.firstVisibleIndex <= clamped.maxFirstVisibleIndex(state.totalBars),
        )
        assertEquals(11, state.totalBars)
    }

    @Test
    fun `an instant outside the dataset yields no forming candle and no crash`() {
        val candles = ChartFixtures.rising(20)
        val state = build(candles, simulatedUtcMs = ChartFixtures.at(-100))

        assertEquals(0, state.buffer.size)
        assertNull(state.formingCandle)
    }

    @Test
    fun `an empty dataset yields an empty chart rather than an error`() {
        val state = ChartFrameAssembler.build(
            candles = emptyList(),
            timeframe = Timeframe.M1,
            style = chartStyleFor(ThemeMode.DARK),
            window = null,
            simulatedUtcMs = null,
            viewport = ChartViewport.DEFAULT,
            fallback = fallback,
        )

        assertEquals(0, state.buffer.size)
        assertNull(state.formingCandle)
        assertEquals(0, state.totalBars)
    }
}
