package com.tradequest.app.core.chart.renderer

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
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
 * Steps 8.5, 8.6 and 8.9 tests: the renderer actually executes.
 *
 * # Why this runs the real draw path
 *
 * The renderer indexes primitive arrays with a window derived from the viewport,
 * and the forming candle occupies a slot no array holds. Together those two facts
 * are an out-of-bounds waiting to happen — a forming candle with no revealed bars
 * is the obvious case — and reasoning alone will not show whether the arithmetic
 * is right. So these tests run the real `draw` against a real Compose canvas under
 * Robolectric. A wrong index throws rather than silently drawing.
 *
 * Robolectric is used rather than an emulator so this runs in the normal JVM test
 * task. It supplies the Android runtime and a real text measurer, but the drawing
 * is software; a frame-rate claim would still need a device, and none is made here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartRendererTest {

    private val density = Density(density = 1f, fontScale = 1f)

    private val style = ChartStyle(
        bullish = Color(0xFF26A69A),
        bearish = Color(0xFFEF5350),
        doji = Color(0xFF9E9E9E),
        background = Color(0xFF101418),
        gridLine = Color(0xFF2A3038),
        axisLine = Color(0xFF3A4149),
        axisText = Color(0xFFB0B8C0),
        crosshair = Color(0xFFCCCCCC),
        currentPriceLine = Color(0xFFD4A017),
        asiaSession = Color(0x2226A69A),
        londonSession = Color(0x223D7DCA),
        newYorkSession = Color(0x22D4A017),
        weekendBand = Color(0x33FFFFFF),
        wickWidthPx = 1f,
        gridWidthPx = 1f,
        axisTextSizePx = 10f,
        axisLabelPaddingPx = 4f,
        minBodyHeightPx = 1f,
        bodyWidthFraction = 0.7f,
        sessionBandAlpha = 0.15f,
    )

    private fun measurer(): TextMeasurer {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = density,
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    /** Runs one real draw pass. A bad index throws rather than drawing wrong. */
    private fun drawOnce(
        state: ChartRenderState,
        plotWidthPx: Float = 800f,
        plotHeightPx: Float = 400f,
        priceAxisWidthPx: Float = 56f,
        timeAxisHeightPx: Float = 20f,
    ) {
        val bitmap = ImageBitmap(width = 1024, height = 512)
        val scope = CanvasDrawScope()
        val renderer = ChartRenderer(measurer())

        scope.draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(1024f, 512f),
        ) {
            // `draw` is a member extension on ChartRenderer, so both receivers
            // must be in scope: the renderer via `with`, the DrawScope via `this`.
            with(renderer) {
                draw(
                    state = state,
                    plotWidthPx = plotWidthPx,
                    plotHeightPx = plotHeightPx,
                    priceAxisWidthPx = priceAxisWidthPx,
                    timeAxisHeightPx = timeAxisHeightPx,
                )
            }
        }
    }

    private fun stateFor(
        buffer: CandleBuffer,
        forming: FormingCandleView? = null,
        barsVisible: Float = 120f,
        firstVisibleIndex: Float = 0f,
        timeframe: Timeframe = Timeframe.M1,
    ): ChartRenderState {
        val lastIndex = (buffer.size - 1).coerceAtLeast(0)
        val low = if (buffer.isEmpty) 2_049_000f else buffer.lowestLow(0, lastIndex)!!
        val high = if (buffer.isEmpty) 2_051_000f else buffer.highestHigh(0, lastIndex)!!
        return ChartRenderState(
            buffer = buffer,
            timeframe = timeframe,
            viewport = ChartViewport(barsVisible, firstVisibleIndex, 5f),
            priceRange = PriceAxisRange.of(low, high),
            style = style,
            formingCandle = forming,
            simulatedUtcMs = ChartFixtures.MONDAY_OPEN_UTC_MS,
        )
    }

    // ---------- The cases that exercise index arithmetic ----------

    @Test
    fun `drawing a non-empty buffer with a forming candle does not throw`() {
        // The case that exposes the slot-versus-index distinction: the visible
        // window counts the forming candle's slot, which is one past the end of
        // the buffer's arrays. Every pass that indexes the buffer must use the
        // clamped window, or it reads one past the end here.
        drawOnce(
            stateFor(
                buffer = CandleBuffer.of(ChartFixtures.rising(5)),
                forming = FormingCandleView(
                    timestampUtcMs = ChartFixtures.at(5),
                    openUnits = 2_050_005,
                    highUnits = 2_050_010,
                    lowUnits = 2_050_000,
                    closeUnits = 2_050_008,
                ),
            ),
        )
    }

    @Test
    fun `drawing a non-empty buffer with a forming candle at the live edge does not throw`() {
        // Same case, scrolled so the forming slot is the last visible one and the
        // buffer's last index is the second-to-last slot.
        drawOnce(
            stateFor(
                buffer = CandleBuffer.of(ChartFixtures.rising(300)),
                forming = FormingCandleView(
                    timestampUtcMs = ChartFixtures.at(300),
                    openUnits = 2_050_300,
                    highUnits = 2_050_320,
                    lowUnits = 2_050_290,
                    closeUnits = 2_050_310,
                ),
                barsVisible = 50f,
                firstVisibleIndex = 260f,
            ),
        )
    }

    @Test
    fun `drawing an empty buffer does not throw`() {
        drawOnce(stateFor(CandleBuffer.EMPTY))
    }

    @Test
    fun `drawing an empty buffer with a forming candle does not throw`() {
        // The case that motivated this test: the forming candle occupies slot 0
        // while the buffer is empty, so any code indexing the buffer with the
        // forming candle's slot reads past the end.
        drawOnce(
            stateFor(
                buffer = CandleBuffer.EMPTY,
                forming = FormingCandleView(
                    timestampUtcMs = ChartFixtures.MONDAY_OPEN_UTC_MS,
                    openUnits = 2_050_000,
                    highUnits = 2_050_500,
                    lowUnits = 2_049_500,
                    closeUnits = 2_050_200,
                ),
            ),
        )
    }

    @Test
    fun `drawing a single candle does not throw`() {
        drawOnce(stateFor(CandleBuffer.of(ChartFixtures.rising(1))))
    }

    @Test
    fun `drawing a full window does not throw`() {
        drawOnce(stateFor(CandleBuffer.of(ChartFixtures.rising(1_000))))
    }

    @Test
    fun `drawing a window scrolled past the end does not throw`() {
        drawOnce(
            stateFor(
                buffer = CandleBuffer.of(ChartFixtures.rising(100)),
                firstVisibleIndex = 5_000f,
            ),
        )
    }

    @Test
    fun `drawing a window scrolled before the start does not throw`() {
        drawOnce(
            stateFor(
                buffer = CandleBuffer.of(ChartFixtures.rising(100)),
                firstVisibleIndex = -5_000f,
            ),
        )
    }

    @Test
    fun `drawing a flat series does not throw`() {
        drawOnce(stateFor(CandleBuffer.of(ChartFixtures.flat(200))))
    }

    @Test
    fun `drawing a doji-heavy series does not throw`() {
        val dojis = (0 until 100).map { ChartFixtures.candle(it, open = 2_050_000, close = 2_050_000) }
        drawOnce(stateFor(CandleBuffer.of(dojis)))
    }

    @Test
    fun `drawing every timeframe does not throw`() {
        for (frame in Timeframe.entries) {
            drawOnce(stateFor(CandleBuffer.of(ChartFixtures.rising(300)), timeframe = frame))
        }
    }

    @Test
    fun `drawing a zero size plot does not throw`() {
        drawOnce(
            stateFor(CandleBuffer.of(ChartFixtures.rising(50))),
            plotWidthPx = 0f,
            plotHeightPx = 0f,
        )
    }

    @Test
    fun `drawing with no axis gutters does not throw`() {
        drawOnce(
            stateFor(CandleBuffer.of(ChartFixtures.rising(50))),
            priceAxisWidthPx = 0f,
            timeAxisHeightPx = 0f,
        )
    }

    @Test
    fun `drawing a single bar visible does not throw`() {
        drawOnce(stateFor(CandleBuffer.of(ChartFixtures.rising(10)), barsVisible = 1f))
    }

    @Test
    fun `drawing a very wide zoom does not throw`() {
        drawOnce(stateFor(CandleBuffer.of(ChartFixtures.rising(10)), barsVisible = 10_000f))
    }

    @Test
    fun `drawing a large dataset completes without drawing the whole series`() {
        // Not a frame-rate measurement — that needs a device. What is asserted is
        // that a pass over a 90,000-candle buffer stays fast, which it would not
        // if the renderer iterated all 90,000 instead of the visible window.
        val big = CandleBuffer.of(ChartFixtures.rising(90_000))
        val started = System.nanoTime()
        drawOnce(stateFor(big, barsVisible = 120f, firstVisibleIndex = 40_000f))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // A generous ceiling. It catches an accidental full-series loop, which on
        // 90,000 candles with per-candle allocation takes far longer, without
        // asserting a frame time this environment cannot measure.
        assertTrue(
            "a single draw pass took ${elapsedMs}ms, which suggests the whole series was drawn",
            elapsedMs < 3_000,
        )
    }

    @Test
    fun `the visible window for a 90k dataset is bounded by the bars on screen`() {
        // The direct claim behind the timing test, asserted without a clock.
        val viewport = ChartViewport(barsVisible = 120f, firstVisibleIndex = 40_000f, rightPaddingBars = 5f)
        val window = viewport.visibleWindow(dataSize = 90_000)
        assertTrue("window was ${window.first}..${window.last}", window.count() <= 125)
    }
}
