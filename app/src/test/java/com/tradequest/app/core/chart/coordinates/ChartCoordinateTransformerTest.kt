package com.tradequest.app.core.chart.coordinates

import com.tradequest.app.core.chart.viewport.ChartViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 8.1 and 8.7 tests: coordinate transforms and the price axis window.
 *
 * The sign of the price transform is the property most worth pinning: price
 * rises and screen y falls, and a chart that gets it backwards is obviously
 * wrong but easy to write.
 */
class ChartCoordinateTransformerTest {

    private fun transformer(
        width: Float = 1_000f,
        height: Float = 500f,
        barsVisible: Float = 100f,
        firstVisibleIndex: Float = 0f,
        minUnits: Float = 2_000_000f,
        maxUnits: Float = 2_100_000f,
    ) = ChartCoordinateTransformer(
        plotWidthPx = width,
        plotHeightPx = height,
        viewport = ChartViewport(barsVisible, firstVisibleIndex, 0f),
        priceRange = PriceAxisRange(minUnits, maxUnits),
    )

    // ---------- Horizontal ----------

    @Test
    fun `candle width is the plot width divided by the visible bars`() {
        val t = transformer(width = 1_000f, barsVisible = 100f)
        assertEquals(10f, t.candleWidthPx, 0.001f)
    }

    @Test
    fun `the first visible bar is centred half a bar from the left edge`() {
        val t = transformer(width = 1_000f, barsVisible = 100f, firstVisibleIndex = 0f)
        assertEquals(5f, t.xOfIndex(0f), 0.001f)
    }

    @Test
    fun `bar x positions are evenly spaced`() {
        val t = transformer(width = 1_000f, barsVisible = 100f, firstVisibleIndex = 0f)
        val step = t.xOfIndex(1f) - t.xOfIndex(0f)
        for (i in 1..50) {
            assertEquals(step * i, t.xOfIndex(i.toFloat()) - t.xOfIndex(0f), 0.01f)
        }
    }

    @Test
    fun `panning by one bar shifts every x by one candle width`() {
        val a = transformer(firstVisibleIndex = 0f)
        val b = transformer(firstVisibleIndex = 1f)
        assertEquals(a.candleWidthPx, a.xOfIndex(10f) - b.xOfIndex(10f), 0.001f)
    }

    @Test
    fun `indexAtX inverts xOfIndex`() {
        val t = transformer()
        for (index in listOf(0f, 1f, 12.5f, 50f, 99f)) {
            assertEquals(index, t.indexAtX(t.xOfIndex(index)), 0.001f)
        }
    }

    // ---------- Vertical: the sign flip ----------

    @Test
    fun `a higher price maps to a smaller y`() {
        val t = transformer()
        assertTrue(
            "a higher price must be nearer the top of the screen",
            t.yOfPrice(2_050_000f) < t.yOfPrice(2_040_000f),
        )
    }

    @Test
    fun `the axis maximum is at the top and the minimum at the bottom`() {
        val t = transformer(height = 500f)
        assertEquals(0f, t.yOfPrice(2_100_000f), 0.001f)
        assertEquals(500f, t.yOfPrice(2_000_000f), 0.001f)
    }

    @Test
    fun `the middle price maps to the middle of the plot`() {
        val t = transformer(height = 500f)
        assertEquals(250f, t.yOfPrice(2_050_000f), 0.001f)
    }

    @Test
    fun `priceAtY inverts yOfPrice`() {
        val t = transformer()
        for (price in listOf(2_000_000f, 2_025_000f, 2_050_000f, 2_099_999f)) {
            assertEquals(price, t.priceAtY(t.yOfPrice(price)), 0.5f)
        }
    }

    // ---------- Visibility ----------

    @Test
    fun `bars inside the window are visible`() {
        val t = transformer(width = 1_000f, barsVisible = 100f, firstVisibleIndex = 0f)
        assertTrue(t.isBarVisible(0))
        assertTrue(t.isBarVisible(50))
        assertTrue(t.isBarVisible(99))
    }

    @Test
    fun `a bar well outside the window is not visible`() {
        val t = transformer(width = 1_000f, barsVisible = 100f, firstVisibleIndex = 0f)
        assertFalse(t.isBarVisible(500))
        assertFalse(t.isBarVisible(-500))
    }

    @Test
    fun `a bar whose body just reaches the edge is visible`() {
        // Bar 100's centre is exactly at the right edge, so its left half is on
        // screen. It must be drawn or there would be a gap at the edge.
        val t = transformer(width = 1_000f, barsVisible = 100f, firstVisibleIndex = 0f)
        assertTrue(t.isBarVisible(100))
    }

    @Test
    fun `a zero width plot has no area and no visible bars`() {
        val t = transformer(width = 0f, height = 0f)
        assertFalse(t.hasArea)
        assertEquals(0f, t.candleWidthPx, 0.001f)
    }

    @Test
    fun `indexAtX does not divide by zero on a zero width plot`() {
        val t = transformer(width = 0f)
        assertEquals(t.viewport.firstVisibleIndex, t.indexAtX(10f), 0.001f)
    }
}

/** Step 8.7 tests: the price-axis window. */
class PriceAxisRangeTest {

    @Test
    fun `a window must have positive height`() {
        assertThrows(IllegalArgumentException::class.java) {
            PriceAxisRange(minUnits = 100f, maxUnits = 100f)
        }
    }

    @Test
    fun `span is the height of the window`() {
        assertEquals(1_000f, PriceAxisRange(2_000f, 3_000f).spanUnits, 0.001f)
    }

    @Test
    fun `padding widens the window symmetrically`() {
        val range = PriceAxisRange(2_000f, 3_000f)
        val padded = range.padded(paddingFraction = 0.1f)

        // 10% of the 1,000-unit span added at each end.
        assertEquals(1_900f, padded.minUnits, 0.01f)
        assertEquals(3_100f, padded.maxUnits, 0.01f)
    }

    @Test
    fun `padding a flat series still yields a drawable window`() {
        // The zero-span case, where a naive implementation divides by zero.
        val padded = PriceAxisRange.of(2_050_000f, 2_050_000f)

        assertTrue(padded.maxUnits > padded.minUnits)
        assertTrue(padded.contains(2_050_000f))
    }

    @Test
    fun `of builds a window from the visible extremes`() {
        val range = PriceAxisRange.of(low = 2_100_000f, high = 2_200_000f, paddingFraction = 0f)
        assertEquals(2_100_000f, range.minUnits, 0.01f)
        assertEquals(2_200_000f, range.maxUnits, 0.01f)
    }

    @Test
    fun `of accepts bounds given the wrong way round`() {
        val range = PriceAxisRange.of(low = 2_200_000f, high = 2_100_000f, paddingFraction = 0f)
        assertEquals(2_100_000f, range.minUnits, 0.01f)
        assertEquals(2_200_000f, range.maxUnits, 0.01f)
    }

    @Test
    fun `a tightly zoomed window is not silently widened`() {
        // A 5-unit span is below the minimum, so it is widened only to the
        // minimum. An earlier version floored at ten pips (1,000 units) and
        // magnified the caller's intent by 200x.
        val range = PriceAxisRange.of(low = 2_050_000f, high = 2_050_005f, paddingFraction = 0f)
        assertEquals(PriceAxisRange.MINIMUM_SPAN_UNITS, range.spanUnits, 0.01f)
    }

    @Test
    fun `around centres the window on the price`() {
        val range = PriceAxisRange.around(2_050_000f)
        assertEquals(2_050_000f, (range.minUnits + range.maxUnits) / 2f, 0.01f)
    }

    @Test
    fun `contains includes both edges`() {
        val range = PriceAxisRange(2_000f, 3_000f)
        assertTrue(range.contains(2_000f))
        assertTrue(range.contains(3_000f))
        assertFalse(range.contains(3_001f))
    }
}
