package com.tradequest.app.core.chart.gestures

import com.tradequest.app.core.chart.viewport.ChartViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 8.3 tests: gesture arithmetic.
 *
 * The zoom-anchor test is the one that matters. A pinch that does not hold the
 * bar under the fingers is the most common chart-feel bug, and it is invisible in
 * a screenshot.
 */
class ChartGestureControllerTest {

    private val controller = ChartGestureController()

    private fun viewport(barsVisible: Float = 100f, firstVisibleIndex: Float = 500f) =
        ChartViewport(barsVisible = barsVisible, firstVisibleIndex = firstVisibleIndex, rightPaddingBars = 5f)

    // ---------- Zoom anchoring ----------

    @Test
    fun `zooming holds the bar under the anchor in place`() {
        val start = viewport()
        val anchor = 0.5f

        val zoomed = controller.zoomBy(start, factor = 0.5f, anchorFraction = anchor, dataSize = 5_000)

        val before = start.firstVisibleIndex + anchor * start.barsVisible
        val after = zoomed.firstVisibleIndex + anchor * zoomed.barsVisible
        assertEquals("the anchor bar moved", before, after, 0.01f)
    }

    @Test
    fun `zooming with a left anchor holds the left edge`() {
        val start = viewport()
        val zoomed = controller.zoomBy(start, factor = 0.5f, anchorFraction = 0f, dataSize = 5_000)
        assertEquals(start.firstVisibleIndex, zoomed.firstVisibleIndex, 0.01f)
    }

    @Test
    fun `zooming with a right anchor holds the right edge`() {
        val start = viewport()
        val zoomed = controller.zoomBy(start, factor = 0.5f, anchorFraction = 1f, dataSize = 5_000)

        val before = start.firstVisibleIndex + start.barsVisible
        val after = zoomed.firstVisibleIndex + zoomed.barsVisible
        assertEquals(before, after, 0.01f)
    }

    @Test
    fun `zooming out widens the window`() {
        val zoomed = controller.zoomBy(viewport(), factor = 2f, anchorFraction = 0.5f, dataSize = 5_000)
        assertEquals(200f, zoomed.barsVisible, 0.01f)
    }

    @Test
    fun `zooming in narrows the window`() {
        val zoomed = controller.zoomBy(viewport(), factor = 0.5f, anchorFraction = 0.5f, dataSize = 5_000)
        assertEquals(50f, zoomed.barsVisible, 0.01f)
    }

    @Test
    fun `zooming in cannot go below the minimum`() {
        val zoomed = controller.zoomBy(viewport(barsVisible = 4f), 0.1f, 0.5f, 5_000)
        assertEquals(ChartGestureController.MIN_BARS_VISIBLE, zoomed.barsVisible, 0.01f)
    }

    @Test
    fun `zooming out cannot exceed the maximum`() {
        val zoomed = controller.zoomBy(viewport(barsVisible = 15_000f), 10f, 0.5f, 500_000)
        assertEquals(ChartGestureController.MAX_BARS_VISIBLE, zoomed.barsVisible, 0.01f)
    }

    @Test
    fun `a non-positive or non-finite zoom factor is ignored`() {
        val start = viewport()
        assertEquals(start, controller.zoomBy(start, 0f, 0.5f, 5_000))
        assertEquals(start, controller.zoomBy(start, -1f, 0.5f, 5_000))
        assertEquals(start, controller.zoomBy(start, Float.NaN, 0.5f, 5_000))
        assertEquals(start, controller.zoomBy(start, Float.POSITIVE_INFINITY, 0.5f, 5_000))
    }

    @Test
    fun `a zoom that hits the clamp leaves the viewport unchanged`() {
        val start = viewport(barsVisible = ChartGestureController.MIN_BARS_VISIBLE)
        assertEquals(start, controller.zoomBy(start, 0.5f, 0.5f, 5_000))
    }

    // ---------- Panning ----------

    @Test
    fun `panning forward moves the window toward later bars`() {
        val panned = controller.panBy(viewport(), deltaBars = 10f, dataSize = 5_000)
        assertEquals(510f, panned.firstVisibleIndex, 0.01f)
    }

    @Test
    fun `panning backward moves the window toward earlier bars`() {
        val panned = controller.panBy(viewport(), deltaBars = -10f, dataSize = 5_000)
        assertEquals(490f, panned.firstVisibleIndex, 0.01f)
    }

    @Test
    fun `panning cannot scroll before the first bar beyond the overscroll allowance`() {
        val panned = controller.panBy(viewport(firstVisibleIndex = 2f), deltaBars = -100f, dataSize = 5_000)
        // A bounded overscroll is allowed so the edge is felt, but the user cannot
        // be flung into unbounded emptiness.
        assertEquals(-100f * ChartViewport.OVERSCROLL_FRACTION, panned.firstVisibleIndex, 0.01f)
        assertTrue(panned.firstVisibleIndex > -100f)
    }

    @Test
    fun `panning cannot scroll past the newest bar plus the gutter`() {
        val panned = controller.panBy(viewport(firstVisibleIndex = 4_900f), deltaBars = 1_000f, dataSize = 5_000)
        // The right gutter stays open rather than scrolling into empty space.
        assertTrue(panned.lastVisibleIndex <= 5_000f + 5f + 0.01f)
    }

    @Test
    fun `a zero or non-finite pan is ignored`() {
        val start = viewport()
        assertEquals(start, controller.panBy(start, 0f, 5_000))
        assertEquals(start, controller.panBy(start, Float.NaN, 5_000))
        assertEquals(start, controller.panBy(start, Float.POSITIVE_INFINITY, 5_000))
    }

    // ---------- Latest / follow ----------

    @Test
    fun `scroll to latest puts the newest bar on screen with the gutter open`() {
        val latest = controller.scrollToLatest(viewport(), dataSize = 5_000)
        assertTrue(latest.firstVisibleIndex < 5_000f)
        assertTrue(latest.lastVisibleIndex >= 5_000f)
    }

    @Test
    fun `a viewport scrolled to the end counts as following the latest`() {
        val latest = controller.scrollToLatest(viewport(), dataSize = 5_000)
        assertTrue(controller.isAtLatest(latest, dataSize = 5_000))
    }

    @Test
    fun `a viewport scrolled well back does not count as following the latest`() {
        assertFalse(controller.isAtLatest(viewport(firstVisibleIndex = 100f), dataSize = 5_000))
    }

    @Test
    fun `an empty dataset always counts as following the latest`() {
        assertTrue(controller.isAtLatest(viewport(), dataSize = 0))
    }

    // ---------- Discrete zoom ----------

    @Test
    fun `a negative zoom step zooms in`() {
        val zoomed = controller.zoomStep(viewport(), steps = -1, dataSize = 5_000)
        assertTrue(zoomed.barsVisible < 100f)
    }

    @Test
    fun `a positive zoom step zooms out`() {
        val zoomed = controller.zoomStep(viewport(), steps = 1, dataSize = 5_000)
        assertTrue(zoomed.barsVisible > 100f)
    }

    @Test
    fun `zoom steps are reversible`() {
        val start = viewport()
        val there = controller.zoomStep(start, steps = 3, dataSize = 5_000)
        val back = controller.zoomStep(there, steps = -3, dataSize = 5_000)
        assertEquals(start.barsVisible, back.barsVisible, 0.5f)
    }

    @Test
    fun `a zero zoom step is a no-op`() {
        val start = viewport()
        assertEquals(start, controller.zoomStep(start, steps = 0, dataSize = 5_000))
    }

    @Test
    fun `discrete zoom anchors on the middle`() {
        val start = viewport()
        val zoomed = controller.zoomStep(start, steps = 2, dataSize = 5_000)

        val before = start.firstVisibleIndex + 0.5f * start.barsVisible
        val after = zoomed.firstVisibleIndex + 0.5f * zoomed.barsVisible
        assertEquals(before, after, 0.01f)
    }

    @Test
    fun `bars visible rounds for display`() {
        assertEquals(100, controller.barsVisibleRounded(viewport()))
        assertEquals(101, controller.barsVisibleRounded(viewport(barsVisible = 100.6f)))
    }
}
