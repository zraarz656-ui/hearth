package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 8.7 auto-scale tests.
 *
 * The reason these exist: auto-scale has to follow the *visible* window, not the
 * dataset. A range computed over everything would pass a smoke test and still
 * make a zoomed-in chart flat, so the visible-window test is the one with teeth.
 */
class ChartAutoScaleTest {

    private val fallback = PriceAxisRange.around(2_000_000f)

    private fun range(
        low: Float?,
        high: Float?,
        formingLow: Float? = null,
        formingHigh: Float? = null,
    ) = ChartAutoScale.priceRange(
        visibleWindow = 0..9,
        lowOf = { low },
        highOf = { high },
        formingLow = formingLow,
        formingHigh = formingHigh,
        fallback = fallback,
    )

    @Test
    fun `the range covers the visible low and high`() {
        val range = range(low = 2_000_000f, high = 2_000_100f)
        assertTrue(range.minUnits <= 2_000_000f)
        assertTrue(range.maxUnits >= 2_000_100f)
    }

    @Test
    fun `the range is padded beyond the raw extremes`() {
        val range = range(low = 2_000_000f, high = 2_000_100f)
        assertTrue("no padding below", range.minUnits < 2_000_000f)
        assertTrue("no padding above", range.maxUnits > 2_000_100f)
    }

    @Test
    fun `a forming candle that sets a new high extends the range`() {
        val withoutForming = range(low = 2_000_000f, high = 2_000_100f)
        val withForming = range(low = 2_000_000f, high = 2_000_100f, formingHigh = 2_000_500f)
        assertTrue(withForming.maxUnits > withoutForming.maxUnits)
    }

    @Test
    fun `a forming candle that sets a new low extends the range`() {
        val withoutForming = range(low = 2_000_000f, high = 2_000_100f)
        val withForming = range(low = 2_000_000f, high = 2_000_100f, formingLow = 1_999_500f)
        assertTrue(withForming.minUnits < withoutForming.minUnits)
    }

    @Test
    fun `a forming candle inside the window does not change the range`() {
        val plain = range(low = 2_000_000f, high = 2_000_100f)
        val withForming = range(low = 2_000_000f, high = 2_000_100f, formingLow = 2_000_050f, formingHigh = 2_000_060f)
        assertEquals(plain.minUnits, withForming.minUnits, 0.001f)
        assertEquals(plain.maxUnits, withForming.maxUnits, 0.001f)
    }

    @Test
    fun `an empty window falls back`() {
        val range = range(low = null, high = null)
        assertEquals(fallback, range)
    }

    @Test
    fun `a forming candle alone is enough to scale`() {
        val range = range(low = null, high = null, formingLow = 2_050_000f, formingHigh = 2_050_200f)
        assertTrue(range.minUnits <= 2_050_000f)
        assertTrue(range.maxUnits >= 2_050_200f)
    }

    @Test
    fun `a flat window is widened rather than collapsing`() {
        val range = range(low = 2_000_000f, high = 2_000_000f)
        assertTrue(range.maxUnits - range.minUnits >= PriceAxisRange.MINIMUM_SPAN_UNITS)
    }

    @Test
    fun `the low and high are read from the visible window only`() {
        // The lambdas receive the window they are handed; the caller decides what
        // "visible" means. This asserts the window is passed through unchanged, so
        // a future refactor cannot silently widen it to the whole buffer.
        var seen: IntRange? = null
        ChartAutoScale.priceRange(
            visibleWindow = 3..7,
            lowOf = { seen = it; 1f },
            highOf = { 2f },
            formingLow = null,
            formingHigh = null,
            fallback = fallback,
        )
        assertEquals(3..7, seen)
    }
}
