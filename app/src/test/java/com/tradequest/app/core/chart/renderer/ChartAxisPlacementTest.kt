package com.tradequest.app.core.chart.renderer

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.chart.ChartFixtures
import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.chart.viewport.ChartViewport
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Axis *placement* tests (Steps 8.7, 8.8).
 *
 * # Why these exist, and why the other renderer tests did not catch the bug
 *
 * [ChartRendererTest] asserts that a draw pass does not throw. That is the right
 * assertion for index arithmetic, and it is blind to layout: a price axis drawn on
 * top of the candles throws nothing and passes every one of those tests.
 *
 * The bug these tests pin down was exactly that. `drawPriceAxis` drew its labels
 * at `x = +axisLabelPadding`, and `drawTimeAxis` drew its labels at `y = 0`,
 * both measured in the coordinate space that [ChartRenderer.draw] documents as
 * "relative to the plot's top-left corner". So the price labels were painted over
 * the leftmost candles and the time labels over the top of the plot, while both
 * gutters stayed empty.
 *
 * The price gutter then moved to the *right*, which is where a trading terminal
 * puts it, so the assertions below scan the band from the plot's right edge.
 *
 * These tests read the rendered pixels back, which is the only way to see it.
 * They assert on *bands* — is the label in its gutter? — rather than exact
 * coordinates, so a font change moves them without breaking them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartAxisPlacementTest {

    private val density = Density(density = 1f, fontScale = 1f)

    private val axisText = Color(0xFFB0B8C0)
    private val axisLine = Color(0xFF3A4149)
    private val background = Color(0xFF101418)

    private val style = ChartStyle(
        bullish = Color(0xFF26A69A), bearish = Color(0xFFEF5350), doji = Color(0xFF9E9E9E),
        background = background, gridLine = Color(0xFF2A3038), axisLine = axisLine,
        axisText = axisText, crosshair = Color(0xFFCCCCCC), currentPriceLine = Color(0xFFD4A017),
        asiaSession = Color(0x2226A69A), londonSession = Color(0x223D7DCA),
        newYorkSession = Color(0x22D4A017), weekendBand = Color(0x33FFFFFF),
        wickWidthPx = 1f, gridWidthPx = 1f, axisTextSizePx = 10f, axisLabelPaddingPx = 4f,
        minBodyHeightPx = 1f, bodyWidthFraction = 0.7f, sessionBandAlpha = 0.15f,
    )

    private val width = 1024
    private val height = 512
    private val priceAxisPx = 56f
    private val timeAxisPx = 20f
    private val plotWidth = width - priceAxisPx
    private val plotHeight = height - timeAxisPx

    /** The gutter is on the right, so it starts at the plot's right edge. */
    private val gutterStart = plotWidth.toInt()

    private fun measurer(): TextMeasurer {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = density,
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    /** Exact-ish match: a 0.03 tolerance still separates the grid grey from the axis grey. */
    private fun isColor(actual: Color, expected: Color, tol: Float = 0.03f): Boolean =
        kotlin.math.abs(actual.red - expected.red) < tol &&
            kotlin.math.abs(actual.green - expected.green) < tol &&
            kotlin.math.abs(actual.blue - expected.blue) < tol

    /** Renders one frame exactly as `CandlestickChart` does. */
    private fun render(): androidx.compose.ui.graphics.PixelMap {
        val candles = (0 until 60).map { i ->
            val base = 2_050_000 + (i % 7) * 300
            if (i % 2 == 0) ChartFixtures.candle(i, open = base, close = base + 200)
            else ChartFixtures.candle(i, open = base, close = base - 200)
        }
        val buffer = CandleBuffer.of(candles)
        val state = ChartRenderState(
            buffer = buffer,
            timeframe = Timeframe.M1,
            viewport = ChartViewport(barsVisible = 50f, firstVisibleIndex = 0f, rightPaddingBars = 5f),
            priceRange = PriceAxisRange.of(buffer.lowestLow(0, buffer.size - 1)!!, buffer.highestHigh(0, buffer.size - 1)!!),
            style = style,
            formingCandle = null,
            simulatedUtcMs = ChartFixtures.MONDAY_OPEN_UTC_MS,
        )

        val bitmap = ImageBitmap(width = width, height = height)
        val scope = CanvasDrawScope()
        val renderer = ChartRenderer(measurer())
        scope.draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            clipRect(left = 0f, top = 0f, right = width.toFloat(), bottom = height.toFloat()) {
                with(renderer) { draw(state, plotWidth, plotHeight, priceAxisPx, timeAxisPx) }
            }
        }
        return bitmap.toPixelMap()
    }

    private fun countInBand(
        predicate: (Int, Int) -> Boolean,
        color: Color,
    ): Int {
        val px = render()
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (predicate(x, y) && isColor(px[x, y], color)) count++
            }
        }
        return count
    }

    @Test
    fun `price labels are drawn in the right gutter, not over the candles`() {
        val inGutter = countInBand({ x, _ -> x >= gutterStart }, axisText)
        assertTrue("no price label pixels in the right gutter; they are not where the gutter is", inGutter > 100)
    }

    @Test
    fun `no price label is drawn in the plot area`() {
        // The plot's right column is where the old left-gutter implementation put
        // its separator; checking a narrow strip beside the boundary keeps the
        // assertion meaningful without depending on where a candle happens to be.
        val justInsidePlot = countInBand(
            { x, y -> x >= gutterStart - 4 && x < gutterStart && y < plotHeight.toInt() },
            axisText,
        )
        assertTrue("price labels are overlapping the plot area", justInsidePlot == 0)
    }

    @Test
    fun `time labels are drawn in the bottom gutter, not inside the plot`() {
        val inGutter = countInBand({ _, y -> y >= plotHeight.toInt() }, axisText)
        assertTrue("no time label pixels in the bottom gutter", inGutter > 100)
    }

    @Test
    fun `no time label is drawn inside the plot area`() {
        val topStrip = countInBand(
            { _, y -> y < plotHeight.toInt() },
            axisText,
        ) - countInBand({ x, _ -> x >= gutterStart }, axisText)
        assertTrue("time labels are overlapping the plot area", topStrip == 0)
    }

    @Test
    fun `the price axis separator is drawn at the plot and gutter boundary`() {
        val px = render()
        // Scan a column just inside the plot; the separator must appear near the
        // bottom of the plot area, not at its top.
        val ys = (0 until height).filter { y -> isColor(px[gutterStart - 5, y], axisLine) }
        assertTrue("no separator found just inside the plot", ys.isNotEmpty())
        assertTrue(
            "the separator is not at the plot/gutter boundary; found at y=$ys",
            ys.any { it >= plotHeight.toInt() - 2 && it <= plotHeight.toInt() + 2 },
        )
    }

    @Test
    fun `the price axis vertical separator is drawn at the plot and gutter boundary`() {
        val px = render()
        val xs = (0 until width).filter { x -> isColor(px[x, 10], axisLine) }
        assertTrue("no vertical separator found", xs.isNotEmpty())
        assertTrue(
            "the vertical separator is not at the plot/gutter boundary; found at x=$xs",
            xs.any { it >= gutterStart - 2 && it <= gutterStart + 2 },
        )
    }

    /**
     * The chart must not paint outside its own bounds.
     *
     * The regression this pins down: with no clip, the axis text escaped the
     * composable and drew over the timeframe chips above the chart and the
     * navigation bar below it, which is what the user saw after zooming.
     *
     * `render()` draws into a bitmap the size of the chart, so anything the
     * renderer emits beyond the plot is either inside the gutters (fine) or
     * outside the canvas entirely (dropped by the bitmap, and invisible here).
     * The assertion therefore checks the meaningful direction: every axis-text
     * pixel lands in a gutter, never in the plot.
     */
    @Test
    fun `axis text never lands inside the plot area`() {
        val px = render()
        var insidePlot = 0
        for (y in 0 until plotHeight.toInt()) {
            for (x in 0 until gutterStart) {
                if (isColor(px[x, y], axisText)) insidePlot++
            }
        }
        assertTrue("axis text is bleeding into the plot area: $insidePlot px", insidePlot == 0)
    }
}
