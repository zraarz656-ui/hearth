package com.tradequest.app.core.chart.gestures

import com.tradequest.app.core.chart.viewport.ChartViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 9.4 tests: zoom limits expressed as candle width.
 *
 * The point of these is the unit. The old limits were in bars, and a bar limit
 * means different things on different screens; these assert the *candle width* the
 * user ends up looking at, which is the thing the spec bounds.
 */
class ChartZoomLimitsTest {

    /** A typical phone plot, in pixels at 2.75x density. */
    private val plotWidthPx = 1_000f

    private fun px(dp: Float) = dp * 2.75f

    private val limits = ChartZoomLimits.fromPlot(
        plotWidthPx = plotWidthPx,
        minCandleWidthPx = px(ChartZoomLimits.MIN_CANDLE_WIDTH_DP),
        maxCandleWidthPx = px(ChartZoomLimits.MAX_CANDLE_WIDTH_DP),
    )

    private fun candleWidthPx(barsVisible: Float) = plotWidthPx / barsVisible

    // ---------- The conversion ----------

    @Test
    fun `the widest allowed candle matches the maximum`() {
        val widest = candleWidthPx(limits.minBarsVisible)
        assertEquals(px(ChartZoomLimits.MAX_CANDLE_WIDTH_DP), widest, 0.01f)
    }

    @Test
    fun `the narrowest allowed candle matches the minimum`() {
        val narrowest = candleWidthPx(limits.maxBarsVisible)
        assertEquals(px(ChartZoomLimits.MIN_CANDLE_WIDTH_DP), narrowest, 0.01f)
    }

    @Test
    fun `a wider plot fits more bars at the same candle width`() {
        val narrowPlot = 500f
        val widePlot = 2_000f
        val narrow = ChartZoomLimits.fromPlot(narrowPlot, px(2f), px(40f))
        val wide = ChartZoomLimits.fromPlot(widePlot, px(2f), px(40f))

        // Same candle width, more room, so more bars — and the same *candle width*
        // at the limit, which is the invariant that matters.
        assertTrue("a wider plot must fit more bars", wide.maxBarsVisible > narrow.maxBarsVisible)
        assertEquals(
            "the narrowest candle must be the same width on both",
            px(ChartZoomLimits.MIN_CANDLE_WIDTH_DP),
            widePlot / wide.maxBarsVisible,
            0.01f,
        )
        assertEquals(
            "the narrowest candle must be the same width on both",
            px(ChartZoomLimits.MIN_CANDLE_WIDTH_DP),
            narrowPlot / narrow.maxBarsVisible,
            0.01f,
        )
    }

    @Test
    fun `the range is not degenerate`() {
        assertTrue("min must be below max", limits.minBarsVisible < limits.maxBarsVisible)
        assertTrue("min must be positive", limits.minBarsVisible > 0f)
    }

    // ---------- Falling back before layout ----------

    @Test
    fun `an unmeasured plot falls back rather than throwing`() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val result = ChartZoomLimits.fromPlot(bad, px(2f), px(40f))
            assertEquals("a $bad width must fall back", ChartZoomLimits.FALLBACK, result)
        }
    }

    @Test
    fun `an inverted candle range falls back`() {
        // max below min cannot describe a range; refusing to derive one is safer
        // than producing limits that are inverted.
        assertEquals(ChartZoomLimits.FALLBACK, ChartZoomLimits.fromPlot(plotWidthPx, px(40f), px(2f)))
    }

    @Test
    fun `a non-positive candle width falls back`() {
        assertEquals(ChartZoomLimits.FALLBACK, ChartZoomLimits.fromPlot(plotWidthPx, 0f, px(40f)))
        assertEquals(ChartZoomLimits.FALLBACK, ChartZoomLimits.fromPlot(plotWidthPx, px(2f), 0f))
    }

    @Test
    fun `a zero candle width is rejected by the type itself`() {
        // The data class is the last line of defence: a caller that constructs one
        // directly with nonsense must not get a silently unusable clamp.
        val thrown = try {
            ChartZoomLimits(minBarsVisible = 0f, maxBarsVisible = 10f)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("a zero minBarsVisible must be rejected", thrown)

        val inverted = try {
            ChartZoomLimits(minBarsVisible = 10f, maxBarsVisible = 1f)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("an inverted range must be rejected", inverted)
    }

    // ---------- Through the controller ----------

    @Test
    fun `zooming in stops at the maximum candle width`() {
        val controller = ChartGestureController()
        var viewport = ChartViewport(barsVisible = 120f, firstVisibleIndex = 500f, rightPaddingBars = 5f)

        // Pinch in hard, many times.
        repeat(200) { viewport = controller.zoomBy(viewport, 0.5f, 0.5f, 5_000, limits) }

        val candleWidth = candleWidthPx(viewport.barsVisible)
        assertEquals(
            "zoom must stop at the widest candle the spec allows",
            px(ChartZoomLimits.MAX_CANDLE_WIDTH_DP),
            candleWidth,
            0.01f,
        )
    }

    @Test
    fun `zooming out stops at the minimum candle width`() {
        val controller = ChartGestureController()
        var viewport = ChartViewport(barsVisible = 120f, firstVisibleIndex = 500f, rightPaddingBars = 5f)

        repeat(200) { viewport = controller.zoomBy(viewport, 2f, 0.5f, 5_000, limits) }

        val candleWidth = candleWidthPx(viewport.barsVisible)
        assertEquals(
            "zoom must stop at the narrowest candle the spec allows",
            px(ChartZoomLimits.MIN_CANDLE_WIDTH_DP),
            candleWidth,
            0.01f,
        )
    }

    @Test
    fun `the default limits keep the historic behaviour`() {
        // A caller that supplies no limits must not have its clamp changed by the
        // introduction of candle-width limits.
        val controller = ChartGestureController()
        assertEquals(
            ChartGestureController.MIN_BARS_VISIBLE,
            controller.defaultLimits.minBarsVisible,
            0f,
        )
        assertEquals(
            ChartGestureController.MAX_BARS_VISIBLE,
            controller.defaultLimits.maxBarsVisible,
            0f,
        )
    }
}
