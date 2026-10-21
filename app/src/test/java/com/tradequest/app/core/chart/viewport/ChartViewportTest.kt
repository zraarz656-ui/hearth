package com.tradequest.app.core.chart.viewport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steps 8.3 and 8.4 tests: the viewport and visible-range culling.
 *
 * The culling claim is that the drawn window is bounded by the bars on screen,
 * not by the size of the dataset. That is what makes a 90,000-candle dataset
 * drawable, so it is asserted directly.
 */
class ChartViewportTest {

    @Test
    fun `the visible window is bounded by the bars on screen, not the dataset`() {
        val viewport = ChartViewport(
            barsVisible = 100f,
            firstVisibleIndex = 40_000f,
            rightPaddingBars = 5f,
        )

        val window = viewport.visibleWindow(dataSize = 90_000)

        // 100 bars plus two of buffer at each end, not 90,000.
        assertTrue("window was ${window.first}..${window.last}", window.count() <= 105)
        assertEquals(40_000 - ChartViewport.DEFAULT_BUFFER_BARS, window.first)
    }

    @Test
    fun `the window includes the buffer bars on each side`() {
        val viewport = ChartViewport(barsVisible = 10f, firstVisibleIndex = 50f, rightPaddingBars = 0f)
        val window = viewport.visibleWindow(dataSize = 1_000, bufferBars = 2)

        assertEquals(48, window.first)
        assertEquals(61, window.last)
    }

    @Test
    fun `the window is clamped at the start of the data`() {
        val viewport = ChartViewport(barsVisible = 50f, firstVisibleIndex = -20f, rightPaddingBars = 0f)
        val window = viewport.visibleWindow(dataSize = 1_000)

        assertEquals(0, window.first)
        assertTrue(window.last <= 31)
    }

    @Test
    fun `the window is clamped at the end of the data`() {
        val viewport = ChartViewport(barsVisible = 50f, firstVisibleIndex = 990f, rightPaddingBars = 0f)
        val window = viewport.visibleWindow(dataSize = 1_000)

        assertEquals(999, window.last)
    }

    @Test
    fun `a window entirely past the data is empty`() {
        val viewport = ChartViewport(barsVisible = 10f, firstVisibleIndex = 5_000f, rightPaddingBars = 0f)
        val window = viewport.visibleWindow(dataSize = 100)

        assertTrue(window.isEmpty())
    }

    @Test
    fun `an empty dataset yields an empty window`() {
        assertTrue(ChartViewport.DEFAULT.visibleWindow(dataSize = 0).isEmpty())
    }

    @Test
    fun `a window covering everything returns the whole range`() {
        val viewport = ChartViewport(barsVisible = 1_000f, firstVisibleIndex = 0f, rightPaddingBars = 0f)
        val window = viewport.visibleWindow(dataSize = 500)

        assertEquals(0, window.first)
        assertEquals(499, window.last)
    }

    // ---------- Scrolling and clamping ----------

    @Test
    fun `scrolling to the end leaves the right gutter open`() {
        val viewport = ChartViewport(barsVisible = 100f, firstVisibleIndex = 0f, rightPaddingBars = 5f)
        val scrolled = viewport.scrolledToEnd(dataSize = 1_000)

        // The last candle is visible, and there is empty space to its right.
        assertTrue(scrolled.lastVisibleIndex > 999f)
        assertTrue(scrolled.firstVisibleIndex < 999f)
    }

    @Test
    fun `clamping stops the newest candle being dragged left of the gutter`() {
        val viewport = ChartViewport(barsVisible = 100f, firstVisibleIndex = 99_999f, rightPaddingBars = 5f)
        val clamped = viewport.clampedToData(dataSize = 1_000)

        // The leftmost visible bar cannot exceed the gutter position.
        assertEquals(1_000f - 1f + 5f - 100f + 1f, clamped.firstVisibleIndex, 0.001f)
    }

    @Test
    fun `clamping leaves a viewport already inside the data unchanged`() {
        val viewport = ChartViewport(barsVisible = 100f, firstVisibleIndex = 500f, rightPaddingBars = 5f)
        assertEquals(viewport, viewport.clampedToData(dataSize = 1_000))
    }

    @Test
    fun `clamping an empty dataset leaves the viewport unchanged`() {
        val viewport = ChartViewport(barsVisible = 100f, firstVisibleIndex = 50f, rightPaddingBars = 5f)
        assertEquals(viewport, viewport.clampedToData(dataSize = 0))
    }

    @Test
    fun `last visible index is the first plus the count`() {
        val viewport = ChartViewport(barsVisible = 80f, firstVisibleIndex = 10f, rightPaddingBars = 0f)
        assertEquals(90f, viewport.lastVisibleIndex, 0.001f)
    }

    /**
     * Regression: a series narrower than the visible window must not throw.
     *
     * This is the state at the very start of a season — a few candles revealed,
     * the view still showing a full screenful — so it is the first thing a live
     * chart does, not an exotic edge. The two clamp bounds invert, and the
     * original `coerceIn` threw "Cannot coerce value to an empty range". The
     * newest candle must stay in view.
     */
    @Test
    fun `a series narrower than the window clamps instead of throwing`() {
        val viewport = ChartViewport.DEFAULT
        val clamped = viewport.clampedToData(dataSize = 3)

        assertTrue(
            "the newest candle fell out of view",
            clamped.firstVisibleIndex <= clamped.maxFirstVisibleIndex(3),
        )
    }

    @Test
    fun `a single candle clamps instead of throwing`() {
        val clamped = ChartViewport.DEFAULT.clampedToData(dataSize = 1)
        assertEquals(ChartViewport.DEFAULT.barsVisible, clamped.barsVisible, 0.001f)
    }

    @Test
    fun `auto scale defaults to on and survives a copy`() {
        val viewport = ChartViewport.DEFAULT
        assertTrue(viewport.autoScale)
        assertEquals(false, viewport.copy(autoScale = false).autoScale)
        assertEquals(true, viewport.copy(autoScale = false).copy(autoScale = true).autoScale)
    }

    @Test
    fun `the default viewport is zoomed to a sensible opening window`() {
        assertTrue(ChartViewport.DEFAULT.barsVisible >= 50f)
        assertTrue(ChartViewport.DEFAULT.barsVisible <= 500f)
        assertTrue(ChartViewport.DEFAULT.rightPaddingBars > 0f)
    }
}
