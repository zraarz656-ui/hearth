package com.tradequest.app.core.chart.gestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 9.10/9.11 tests: the priority order a touch is resolved through.
 *
 * A wrong order does not crash or look broken — the user simply gets the wrong
 * gesture, which is the hardest kind of bug to notice and the easiest to assert.
 */
class ChartGesturePriorityTest {

    @Test
    fun `a touch on the price axis scales the axis`() {
        assertEquals(
            ChartGesture.PRICE_AXIS_SCALE,
            ChartGesturePriority.resolve(ChartHitTest(onPriceAxis = true)),
        )
    }

    @Test
    fun `the price axis outranks everything else`() {
        // Every other layer claims the touch as well; the axis is checked first
        // because it is the narrowest, most deliberate target.
        assertEquals(
            ChartGesture.PRICE_AXIS_SCALE,
            ChartGesturePriority.resolve(
                ChartHitTest(
                    onPriceAxis = true,
                    onOrderLine = true,
                    onDrawingHandle = true,
                    drawingToolArmed = true,
                    isMultiTouch = true,
                    longPressSatisfied = true,
                ),
            ),
        )
    }

    @Test
    fun `an order line outranks a drawing handle and the armed tool`() {
        assertEquals(
            ChartGesture.ORDER_LINE_DRAG,
            ChartGesturePriority.resolve(
                ChartHitTest(
                    onOrderLine = true,
                    onDrawingHandle = true,
                    drawingToolArmed = true,
                    isMultiTouch = true,
                    longPressSatisfied = true,
                ),
            ),
        )
    }

    @Test
    fun `a drawing handle outranks the armed tool`() {
        // Handles are small, so missing one by a pixel would otherwise start a new
        // drawing instead of moving the existing one.
        assertEquals(
            ChartGesture.DRAWING_HANDLE_DRAG,
            ChartGesturePriority.resolve(
                ChartHitTest(onDrawingHandle = true, drawingToolArmed = true, isMultiTouch = true),
            ),
        )
    }

    @Test
    fun `the armed drawing tool outranks a pinch and a crosshair`() {
        assertEquals(
            ChartGesture.DRAWING_TOOL,
            ChartGesturePriority.resolve(
                ChartHitTest(drawingToolArmed = true, isMultiTouch = true, longPressSatisfied = true),
            ),
        )
    }

    @Test
    fun `a second finger turns a pan into a pinch`() {
        assertEquals(
            ChartGesture.PINCH_ZOOM,
            ChartGesturePriority.resolve(ChartHitTest(isMultiTouch = true)),
        )
    }

    @Test
    fun `a second finger beats the long press`() {
        // Both are true: the user has held a finger down and added another. Zooming
        // is the more specific intent, and a crosshair that ignored the second finger
        // would leave them pinching with no effect.
        assertEquals(
            ChartGesture.PINCH_ZOOM,
            ChartGesturePriority.resolve(
                ChartHitTest(isMultiTouch = true, longPressSatisfied = true),
            ),
        )
    }

    @Test
    fun `a still held finger becomes a crosshair`() {
        assertEquals(
            ChartGesture.CROSSHAIR,
            ChartGesturePriority.resolve(ChartHitTest(longPressSatisfied = true)),
        )
    }

    @Test
    fun `a bare touch pans`() {
        assertEquals(ChartGesture.PAN, ChartGesturePriority.resolve(ChartHitTest()))
    }

    // ---------- Promotability ----------

    @Test
    fun `a pan may still become something else`() {
        assertTrue(ChartGesturePriority.isPromotable(ChartGesture.PAN))
    }

    @Test
    fun `a crosshair may still become a pinch`() {
        assertTrue(ChartGesturePriority.isPromotable(ChartGesture.CROSSHAIR))
    }

    @Test
    fun `a committed gesture is never re-evaluated`() {
        // An axis drag was aimed at a specific strip. Re-evaluating it on a stray
        // second finger would abandon the drag the user deliberately started.
        assertFalse(ChartGesturePriority.isPromotable(ChartGesture.PRICE_AXIS_SCALE))
        assertFalse(ChartGesturePriority.isPromotable(ChartGesture.ORDER_LINE_DRAG))
        assertFalse(ChartGesturePriority.isPromotable(ChartGesture.DRAWING_HANDLE_DRAG))
        // Abandoning a half-drawn line would leave the drawing in an edited state.
        assertFalse(ChartGesturePriority.isPromotable(ChartGesture.DRAWING_TOOL))
    }

    @Test
    fun `a pinch does not need to change into anything`() {
        assertTrue(ChartGesturePriority.isPromotable(ChartGesture.PINCH_ZOOM))
    }

    // ---------- Promotion ----------

    @Test
    fun `a crosshair stays a crosshair when the finger drags it`() {
        // The regression this guards: the finger has now moved past the touch slop,
        // so a fresh resolve would say PAN and snatch the reading away mid-drag.
        val promoted = ChartGesturePriority.promote(
            current = ChartGesture.CROSSHAIR,
            hit = ChartHitTest(longPressSatisfied = false),
        )
        assertEquals(ChartGesture.CROSSHAIR, promoted)
    }

    @Test
    fun `a crosshair becomes a pinch when a second finger lands`() {
        assertEquals(
            ChartGesture.PINCH_ZOOM,
            ChartGesturePriority.promote(
                current = ChartGesture.CROSSHAIR,
                hit = ChartHitTest(isMultiTouch = true),
            ),
        )
    }

    @Test
    fun `a pinch does not fall back to a pan when a finger lifts`() {
        // Half a pinch is not a drag: resuming a pan as one finger lifts would slide
        // the chart out from under the zoom the user just performed.
        assertEquals(
            ChartGesture.PINCH_ZOOM,
            ChartGesturePriority.promote(
                current = ChartGesture.PINCH_ZOOM,
                hit = ChartHitTest(isMultiTouch = false),
            ),
        )
    }

    @Test
    fun `a pan still becomes a crosshair when the threshold passes`() {
        assertEquals(
            ChartGesture.CROSSHAIR,
            ChartGesturePriority.promote(
                current = ChartGesture.PAN,
                hit = ChartHitTest(longPressSatisfied = true),
            ),
        )
    }

    @Test
    fun `a committed gesture is never promoted`() {
        // A stray second finger must not abandon an axis drag the user started.
        assertEquals(
            ChartGesture.PRICE_AXIS_SCALE,
            ChartGesturePriority.promote(
                current = ChartGesture.PRICE_AXIS_SCALE,
                hit = ChartHitTest(isMultiTouch = true, longPressSatisfied = true),
            ),
        )
        assertEquals(
            ChartGesture.DRAWING_TOOL,
            ChartGesturePriority.promote(
                current = ChartGesture.DRAWING_TOOL,
                hit = ChartHitTest(isMultiTouch = true),
            ),
        )
    }

    @Test
    fun `the long press must be promoted, not resolved`() {
        // Regression for a device-visible defect. The long-press timer used to call
        // `resolve(longPressSatisfied = true)` on timeout, and `resolve` ignores what
        // the gesture already is. Holding still in the price gutter therefore came
        // back as CROSSHAIR, throwing away an axis drag the user had deliberately
        // started — every axis hold longer than the 280 ms threshold was hijacked.
        // The device log showed it exactly: `begin PRICE_AXIS_SCALE` paired with
        // `end CROSSHAIR` for every hold over 280 ms, and a correct pairing for the
        // two holds under it.
        //
        // This asserts the two calls disagree, which is *why* the caller must use
        // `promote`. If a future change makes `resolve` respect commitment, this test
        // fails and should be deleted deliberately rather than silently.
        assertEquals(
            ChartGesture.CROSSHAIR,
            ChartGesturePriority.resolve(ChartHitTest(longPressSatisfied = true)),
        )
        assertEquals(
            ChartGesture.PRICE_AXIS_SCALE,
            ChartGesturePriority.promote(
                current = ChartGesture.PRICE_AXIS_SCALE,
                hit = ChartHitTest(longPressSatisfied = true),
            ),
        )
    }

    @Test
    fun `a held pan still becomes a crosshair`() {
        // The fix must not disable the long press for the gesture that wants it.
        assertEquals(
            ChartGesture.CROSSHAIR,
            ChartGesturePriority.promote(
                current = ChartGesture.PAN,
                hit = ChartHitTest(longPressSatisfied = true),
            ),
        )
    }

    @Test
    fun `promotion of a bare pan agrees with resolve`() {
        // Where nothing has happened yet, promote must be the same decision as
        // resolve, or the two would disagree on the first event of a touch.
        val hits = listOf(
            ChartHitTest(),
            ChartHitTest(isMultiTouch = true),
            ChartHitTest(longPressSatisfied = true),
        )
        hits.forEach { hit ->
            assertEquals(ChartGesturePriority.resolve(hit), ChartGesturePriority.promote(ChartGesture.PAN, hit))
        }
    }

    // ---------- Horizontal panning ----------

    @Test
    fun `only a pan drags the time axis`() {
        assertTrue(ChartGesturePriority.pansHorizontally(ChartGesture.PAN))
        // A pinch must zoom strictly around the focal point, so it must not also
        // slide the chart sideways as the fingers close.
        assertFalse(ChartGesturePriority.pansHorizontally(ChartGesture.PINCH_ZOOM))
        assertFalse(ChartGesturePriority.pansHorizontally(ChartGesture.CROSSHAIR))
        assertFalse(ChartGesturePriority.pansHorizontally(ChartGesture.PRICE_AXIS_SCALE))
    }

    @Test
    fun `every gesture is classified as panning or not`() {
        // Exhaustive on purpose: adding a gesture must force a decision about
        // whether it pans, rather than silently defaulting to not panning.
        ChartGesture.entries.forEach { ChartGesturePriority.pansHorizontally(it) }
        ChartGesture.entries.forEach { ChartGesturePriority.isPromotable(it) }
    }
}
