package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 9.7 tests: manual price scaling.
 *
 * The two invariants worth asserting are that the grabbed price does not move, and
 * that no drag can produce an undrawable window. Both are things a screenshot cannot
 * show and a user would only notice as "the chart feels wrong".
 */
class ChartPriceScaleTest {

    private val plotHeightPx = 800f

    /** A window around the gold levels the fixtures use. */
    private val range = PriceAxisRange(minUnits = 2_048_000f, maxUnits = 2_052_000f)

    private fun px(dp: Float) = dp

    // ---------- Drag factor ----------

    @Test
    fun `no drag leaves the span unchanged`() {
        assertEquals(1f, ChartPriceScale.factorForDrag(0f, plotHeightPx), 1e-4f)
    }

    @Test
    fun `dragging down widens the window`() {
        // Down shows more price, which is "zoom out".
        assertTrue(ChartPriceScale.factorForDrag(200f, plotHeightPx) > 1f)
    }

    @Test
    fun `dragging up narrows the window`() {
        assertTrue(ChartPriceScale.factorForDrag(-200f, plotHeightPx) < 1f)
    }

    @Test
    fun `a full plot height of drag multiplies the span by e`() {
        assertEquals(
            Math.E.toFloat(),
            ChartPriceScale.factorForDrag(plotHeightPx, plotHeightPx),
            1e-3f,
        )
    }

    @Test
    fun `opposite drags of the same size cancel exactly`() {
        // Exponential, so this is exact rather than approximate: a linear model
        // would not round-trip.
        val down = ChartPriceScale.factorForDrag(300f, plotHeightPx)
        val up = ChartPriceScale.factorForDrag(-300f, plotHeightPx)
        assertEquals(1f, down * up, 1e-4f)
    }

    @Test
    fun `a drag before layout is a no-op`() {
        assertEquals(1f, ChartPriceScale.factorForDrag(200f, 0f), 1e-4f)
        assertEquals(1f, ChartPriceScale.factorForDrag(200f, -10f), 1e-4f)
        assertEquals(1f, ChartPriceScale.factorForDrag(Float.NaN, plotHeightPx), 1e-4f)
    }

    // ---------- Scaling about an anchor ----------

    @Test
    fun `the anchor price stays at the same screen fraction`() {
        // Grabbed at 25% down the plot; it must still be 25% down afterwards.
        val anchorFraction = 0.25f
        val anchorPrice = range.maxUnits - anchorFraction * range.spanUnits

        val scaled = ChartPriceScale.scaled(range, factor = 1.8f, anchorPrice, anchorFraction)

        val newFraction = (scaled.maxUnits - anchorPrice) / scaled.spanUnits
        assertEquals("the grabbed price must not move", anchorFraction, newFraction, 1e-4f)
    }

    @Test
    fun `the anchor holds for a narrowing drag too`() {
        val anchorFraction = 0.8f
        val anchorPrice = range.maxUnits - anchorFraction * range.spanUnits

        val scaled = ChartPriceScale.scaled(range, factor = 0.4f, anchorPrice, anchorFraction)

        val newFraction = (scaled.maxUnits - anchorPrice) / scaled.spanUnits
        assertEquals(anchorFraction, newFraction, 1e-4f)
    }

    @Test
    fun `a factor of one returns the same window`() {
        // The anchor must be *consistent*: `minUnits` is the bottom of the window,
        // which is fraction 1.0, not 0.5. Passing 0.5 would legitimately move the
        // window so that price sat in the middle — correct behaviour, wrong test.
        val scaled = ChartPriceScale.scaled(
            range,
            factor = 1f,
            anchorPrice = range.minUnits,
            anchorFraction = 1f,
        )
        assertEquals(range.minUnits, scaled.minUnits, 1e-3f)
        assertEquals(range.maxUnits, scaled.maxUnits, 1e-3f)
    }

    @Test
    fun `a factor of one about the middle keeps the window`() {
        val mid = (range.minUnits + range.maxUnits) / 2f
        val scaled = ChartPriceScale.scaled(range, factor = 1f, anchorPrice = mid, anchorFraction = 0.5f)
        assertEquals(range.minUnits, scaled.minUnits, 1e-3f)
        assertEquals(range.maxUnits, scaled.maxUnits, 1e-3f)
    }

    @Test
    fun `scaling keeps the span proportional`() {
        val scaled = ChartPriceScale.scaled(range, factor = 2f, anchorPrice = range.minUnits, anchorFraction = 1f)
        assertEquals(range.spanUnits * 2f, scaled.spanUnits, 1e-2f)
    }

    // ---------- Limits ----------

    @Test
    fun `zooming in is floored at the minimum span`() {
        var current = range
        // Many hard narrowing drags; the floor must hold.
        repeat(50) {
            current = ChartPriceScale.scaled(current, factor = 0.5f, anchorPrice = current.minUnits, anchorFraction = 0f)
        }
        assertEquals(ChartPriceScale.MIN_SPAN_UNITS, current.spanUnits, 1e-2f)
    }

    @Test
    fun `zooming out is capped at the maximum span`() {
        var current = range
        repeat(50) {
            current = ChartPriceScale.scaled(current, factor = 2f, anchorPrice = current.minUnits, anchorFraction = 0f)
        }
        assertEquals(ChartPriceScale.MAX_SPAN_UNITS, current.spanUnits, 1e-1f)
    }

    @Test
    fun `the result is always a drawable range`() {
        // A degenerate range makes the price transform divide by zero, so no input
        // may produce one.
        for (factor in listOf(0.001f, 0.5f, 1f, 3f, 1_000f)) {
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val scaled = ChartPriceScale.scaled(range, factor, anchorPrice = 2_050_000f, anchorFraction = fraction)
                assertTrue("span must be positive for factor $factor", scaled.spanUnits > 0f)
                assertTrue("range must be finite for factor $factor", scaled.minUnits.isFinite() && scaled.maxUnits.isFinite())
            }
        }
    }

    @Test
    fun `a non-positive or non-finite factor is ignored`() {
        // Returning the input unchanged is the safe answer: a zero factor would
        // collapse the window to nothing.
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertSame(range, ChartPriceScale.scaled(range, bad, anchorPrice = 2_050_000f, anchorFraction = 0.5f))
        }
    }

    @Test
    fun `a non-finite anchor is ignored`() {
        assertSame(range, ChartPriceScale.scaled(range, 2f, anchorPrice = Float.NaN, anchorFraction = 0.5f))
    }

    @Test
    fun `the anchor fraction is clamped`() {
        // A pointer below the plot would otherwise put the anchor outside the
        // window, which reads as the chart jumping.
        val above = ChartPriceScale.scaled(range, 2f, anchorPrice = range.minUnits, anchorFraction = -5f)
        val atTop = ChartPriceScale.scaled(range, 2f, anchorPrice = range.minUnits, anchorFraction = 0f)
        assertEquals(atTop.minUnits, above.minUnits, 1e-3f)
    }

    // ---------- Hit testing ----------

    @Test
    fun `a pointer past the plot width is on the price axis`() {
        assertTrue(ChartPriceScale.isOnPriceAxis(901f, plotWidthPx = 900f))
        assertTrue(ChartPriceScale.isOnPriceAxis(900f, plotWidthPx = 900f))
    }

    @Test
    fun `a pointer inside the plot is not on the price axis`() {
        assertFalse(ChartPriceScale.isOnPriceAxis(899f, plotWidthPx = 900f))
        assertFalse(ChartPriceScale.isOnPriceAxis(0f, plotWidthPx = 900f))
    }

    @Test
    fun `an unmeasured plot has no price axis to hit`() {
        // Before layout every x would otherwise test as "on the axis", and the first
        // touch would scale instead of pan.
        assertFalse(ChartPriceScale.isOnPriceAxis(10f, plotWidthPx = 0f))
    }
}
