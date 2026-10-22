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

    // ---------- Opening a short series (device-reported) ----------

    /**
     * A series shorter than the opening window must not open crammed into the
     * right edge.
     *
     * Reported from a HONOR BRP-NX1 running the Phase 9 debug build. Weekly has 4
     * bars and opened at `first=-111.00`, which is exactly the right-hand clamp
     * `(4-1) + 5 - 120 + 1`. On screen that is four candles in the last 3% of the
     * plot with 97% of the screen empty, and a five-bar gutter wider than the whole
     * dataset. Daily (16 bars, `first=-99.00`) and H4 (91 bars, `first=-24.00`) were
     * the same, each exactly on the clamp.
     */
    @Test
    fun `a short series does not open crammed into the right edge`() {
        // The three device cases, with the first index the log recorded.
        val cases = listOf(
            Triple("W1", 4, -111.00f),
            Triple("D1", 16, -99.00f),
            Triple("H4", 91, -24.00f),
        )

        cases.forEach { (name, bars, loggedFirst) ->
            val opening = ChartViewport.DEFAULT.openingFor(bars)

            // The reported bug: the old behaviour sat exactly on the clamp.
            assertEquals(
                "the fixture must reproduce the device value for $name",
                loggedFirst,
                ChartViewport.DEFAULT.scrolledToEnd(bars).firstVisibleIndex,
                0.01f,
            )

            // The candles must fill a useful share of the window, not a sliver.
            val share = bars / opening.barsVisible
            assertTrue(
                "$name shows $bars bars in a ${opening.barsVisible}-bar window " +
                    "(only ${"%.1f".format(share * 100)}% of the screen)",
                share >= 0.5f,
            )
        }
    }

    @Test
    fun `a long series still opens at the default window`() {
        // The fix must not change the common case. Hourly has 343 bars, well past
        // the default window, and the log shows it opening at first=228.00 with
        // visible=120.0.
        val opening = ChartViewport.DEFAULT.openingFor(343)

        assertEquals(ChartViewport.DEFAULT_BARS_VISIBLE, opening.barsVisible, 0.001f)
        assertEquals(228.00f, opening.firstVisibleIndex, 0.01f)
    }

    @Test
    fun `an opening view of a short series is still at the live edge`() {
        // Fitting the zoom must not scroll away from the newest candle: a chart
        // opens at the live edge whatever the series length.
        listOf(1, 2, 4, 16, 91, 120, 343).forEach { bars ->
            val opening = ChartViewport.DEFAULT.openingFor(bars)
            assertEquals(
                "a $bars-bar series did not open at the live edge",
                opening.maxFirstVisibleIndex(bars),
                opening.firstVisibleIndex,
                0.01f,
            )
        }
    }

    @Test
    fun `an opening view never zooms past the gesture limits`() {
        // A two-bar series must not request a window narrower than the most
        // zoomed-in state a pinch allows, or the chart would open beyond its own
        // zoom range and the first pinch would jump.
        listOf(1, 2, 4, 16).forEach { bars ->
            val opening = ChartViewport.DEFAULT.openingFor(bars)
            assertTrue(
                "a $bars-bar series opened at ${opening.barsVisible}, below the floor",
                opening.barsVisible >= ChartViewport.MIN_BARS_VISIBLE,
            )
        }
    }

    @Test
    fun `an opening view of an empty series is the default`() {
        // No data is not a reason to divide by zero or to invent a zoom.
        val opening = ChartViewport.DEFAULT.openingFor(0)
        assertEquals(ChartViewport.DEFAULT_BARS_VISIBLE, opening.barsVisible, 0.001f)
    }
}
