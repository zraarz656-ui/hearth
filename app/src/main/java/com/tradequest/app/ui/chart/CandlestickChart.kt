package com.tradequest.app.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tradequest.app.core.chart.gestures.ChartGestureController
import com.tradequest.app.core.chart.renderer.ChartRenderState
import com.tradequest.app.core.chart.renderer.ChartRenderer
import com.tradequest.app.core.chart.viewport.ChartViewport

/**
 * The interactive candlestick chart (Steps 8.1, 8.3, 8.5).
 *
 * # What this composable does, and what it deliberately does not
 *
 * It turns pointer events into calls on [ChartGestureController] and hands the
 * result to [ChartRenderer]. All the arithmetic that can be wrong — zoom
 * anchoring, pan clamping, culling — lives in the core package, where it is tested
 * without a Compose runtime. What remains here is plumbing.
 *
 * # Why the viewport is the only Compose state
 *
 * `AGENTS.md` forbids per-candle Compose state. Candle data arrives as one
 * [ChartRenderState] value and the viewport is the single observed piece of
 * mutable state, so a pan invalidates one object rather than thousands.
 *
 * # Why `detectTransformGestures`
 *
 * A chart needs pan and pinch together, with the pinch anchored on the centroid
 * of the two fingers. `detectTransformGestures` supplies both in one callback and
 * gives that centroid, which is exactly the anchor [ChartGestureController.zoomBy]
 * expects. Layering a scroll modifier and a separate pinch detector produces two
 * gesture loops fighting over the same pointer stream.
 */
@Composable
fun CandlestickChart(
    state: ChartRenderState,
    modifier: Modifier = Modifier,
    priceAxisWidth: Dp = 56.dp,
    timeAxisHeight: Dp = 22.dp,
    /**
     * Called after a gesture moves the viewport.
     *
     * The chart owns the viewport while the user is interacting with it, so the
     * caller cannot see it otherwise. Without this, a clock tick would recompute
     * the price axis from a stale viewport and the axis would jump while the view
     * stayed put.
     */
    onViewportChange: (ChartViewport) -> Unit = {},
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val renderer = remember(textMeasurer) { ChartRenderer(textMeasurer) }
    val controller = remember { ChartGestureController() }

    // Seeded from the caller's viewport, then owned here. Keyed on the timeframe,
    // because a viewport fitted to one timeframe's bar count is meaningless on
    // another: keeping it would leave the chart scrolled past the end of a much
    // shorter series. Everything else about the state — a new forming candle, a
    // new price range — must NOT reset the view, or a clock tick would yank the
    // chart away from wherever the trader had scrolled to.
    var viewport by remember(state.timeframe) { mutableStateOf(state.viewport) }

    // The latest state, read inside the gesture lambda. Without this the lambda
    // would capture the first composition's state, and every gesture would act on
    // a stale data size and a stale price range.
    val latestState by rememberUpdatedState(state)
    val dataSize = state.totalBars

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(dataSize) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val priceAxisPx = with(density) { priceAxisWidth.toPx() }
                        val plotWidthPx = size.width - priceAxisPx
                        if (plotWidthPx <= 0f) return@detectTransformGestures

                        val current = viewport
                        val candleWidth = plotWidthPx / current.barsVisible
                        if (candleWidth <= 0f) return@detectTransformGestures

                        var next = current

                        if (zoom != 1f) {
                            // The centroid as a fraction of the plot width, which is
                            // what zoomBy expects as its anchor.
                            val anchorFraction = (centroid.x / plotWidthPx).coerceIn(0f, 1f)
                            next = controller.zoomBy(next, zoom, anchorFraction, dataSize)
                        }

                        if (pan.x != 0f) {
                            // A leftward drag (negative x) moves forward in time, so
                            // the sign is flipped.
                            next = controller.panBy(next, -pan.x / candleWidth, dataSize)
                        }

                        viewport = next
                        onViewportChange(next)
                    }
                },
        ) {
            val priceAxisPx = priceAxisWidth.toPx()
            val timeAxisPx = timeAxisHeight.toPx()
            val plotWidthPx = size.width - priceAxisPx
            val plotHeightPx = size.height - timeAxisPx

            // Draw the plot in its own coordinate space by translating past the
            // price gutter, so the renderer works from the plot's top-left corner.
            translate(left = priceAxisPx, top = 0f) {
                with(renderer) {
                    draw(
                        state = latestState.copy(viewport = viewport),
                        plotWidthPx = plotWidthPx,
                        plotHeightPx = plotHeightPx,
                        priceAxisWidthPx = priceAxisPx,
                        timeAxisHeightPx = timeAxisPx,
                    )
                }
            }
        }
    }
}
