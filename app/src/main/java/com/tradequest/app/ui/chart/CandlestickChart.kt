package com.tradequest.app.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.os.SystemClock
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tradequest.app.core.chart.coordinates.ChartCoordinateTransformer
import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import com.tradequest.app.core.chart.gestures.ChartGesture
import com.tradequest.app.core.chart.gestures.ChartGesturePriority
import com.tradequest.app.core.chart.gestures.ChartHitTest
import com.tradequest.app.core.chart.crosshair.CrosshairPosition
import com.tradequest.app.core.chart.crosshair.CrosshairReadout
import com.tradequest.app.core.chart.gestures.ChartFling
import com.tradequest.app.core.chart.gestures.ChartGestureController
import com.tradequest.app.core.chart.gestures.ChartZoomLimits
import com.tradequest.app.core.chart.renderer.ChartAutoScale
import com.tradequest.app.core.chart.renderer.ChartPriceScale
import com.tradequest.app.core.chart.renderer.ChartRenderState
import com.tradequest.app.core.chart.renderer.ChartRenderer
import com.tradequest.app.core.chart.renderer.NewsFlagHit
import com.tradequest.app.core.chart.renderer.NewsFlagHitTester
import com.tradequest.app.core.chart.viewport.ChartViewport
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * # Why the viewport is Compose state
 *
 * `AGENTS.md` forbids per-candle Compose state. Candle data arrives as one
 * [ChartRenderState] value and the viewport is the single observed piece of
 * mutable state, so a pan invalidates one object rather than thousands. The
 * crosshair is a second observed value, but it is one object, not one per candle.
 *
 * # Why not `detectTransformGestures`
 *
 * It supplies pan and pinch in one callback, but it does not expose the throw
 * velocity, and it cannot be interrupted to become a long-press crosshair. The
 * explicit `awaitEachGesture` loop below reproduces its arithmetic while keeping
 * the `VelocityTracker` of Step 9.2 and the long press of Step 9.5.
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
    /**
     * Called as the crosshair moves, with the reading under it, and with null when
     * it is dismissed (Step 9.5).
     *
     * The chart owns the crosshair while the finger is down, so a screen cannot see
     * it otherwise — the same reason [onViewportChange] exists. Passing the whole
     * [CrosshairReadout] rather than an index keeps the caller from re-deriving the
     * OHLC and disagreeing with what is drawn.
     */
    onCrosshairChange: (CrosshairReadout?) -> Unit = {},
    /**
     * Called when a touch lands on a draggable order line (Step 10.4).
     *
     * Returns true when a line was grabbed, which is what makes the touch an
     * [ChartGesture.ORDER_LINE_DRAG] rather than a pan. The chart supplies the tap's
     * price and a tolerance already converted into price units from its own scale,
     * because only the chart knows how many units a finger's width covers; which
     * line that selects is the caller's decision.
     *
     * # Why the callbacks are price-based rather than passing a line object
     *
     * `core/chart` draws geometry and must not know what an order is. Naming the
     * grabbed line in the chart would pull the trading domain into the renderer. So
     * the chart reports prices and the screen decides which line they mean — the
     * same split as [onViewportChange].
     */
    onOrderLineGrab: (tapPriceUnits: Int, toleranceUnits: Int) -> Boolean = { _, _ -> false },
    /** Called as a grabbed order line is dragged, with the price under the finger. */
    onOrderLineDrag: (priceUnits: Int) -> Unit = {},
    /** Called when a dragged order line is released. */
    onOrderLineRelease: () -> Unit = {},
    /**
     * Called when a tap lands on a news flag (Step 12.3).
     *
     * # Why a tap and not a long press
     *
     * The plan says the markers are tappable. A long press is already the crosshair
     * (Step 9.5), and giving it a second meaning on the same strip would make the
     * two compete. A tap on the strip is unambiguous: the strip is the top few
     * pixels of the plot, above every candle, so a tap there cannot have meant a bar.
     *
     * # Why the hit is a [NewsFlagHit] rather than an index
     *
     * The chart resolves the flag against the viewport it is drawing, so the caller
     * receives the headline that was actually on screen. Re-deriving it from an index
     * against a re-read schedule could name a different event than the one tapped.
     */
    onNewsFlagTap: (NewsFlagHit) -> Unit = {},
) {
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

    // The manually scaled price window, or null while the axis auto-fits
    // (Step 9.7). Held here rather than in the viewport because the viewport is the
    // horizontal window; the two are independent, and the viewport's `autoScale`
    // flag is what tells the draw pass which of the two is authoritative.
    var manualPriceRange by remember(state.timeframe) { mutableStateOf<PriceAxisRange?>(null) }

    // The latest state, read inside the gesture lambda. Without this the lambda
    // would capture the first composition's state, and every gesture would act on
    // a stale data size and a stale price range.
    val latestState by rememberUpdatedState(state)
    val dataSize = state.totalBars

    // The price window actually on screen for a given viewport. One function used by
    // both the draw pass and the gesture loop, because an axis drag has to scale the
    // window the user is *looking at*: deriving it twice is how the grabbed price and
    // the drawn price drift apart.
    //
    // With auto-scale on, the axis fits the visible bars. With it off, the chart's own
    // manual window wins, and a caller that supplied a manual range of its own — a
    // persisted scale, say — is honoured when the chart has not set one.
    fun drawnRangeFor(vp: ChartViewport): PriceAxisRange {
        if (vp.autoScale) {
            return ChartAutoScale.priceRangeForViewport(
                buffer = latestState.buffer,
                forming = latestState.formingCandle,
                viewport = vp,
                totalBars = latestState.totalBars,
                fallback = latestState.priceRange,
            )
        }
        return manualPriceRange ?: latestState.priceRange
    }

    // The latest callback, so the fling coroutine — which outlives the gesture that
    // started it — reports through the current lambda rather than the one captured
    // when the pointer loop began.
    val latestOnViewportChange by rememberUpdatedState(onViewportChange)
    val latestOnCrosshairChange by rememberUpdatedState(onCrosshairChange)
    val latestOnOrderLineGrab by rememberUpdatedState(onOrderLineGrab)
    val latestOnOrderLineDrag by rememberUpdatedState(onOrderLineDrag)
    val latestOnOrderLineRelease by rememberUpdatedState(onOrderLineRelease)
    val latestOnNewsFlagTap by rememberUpdatedState(onNewsFlagTap)

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    // Deliberately not Compose state: the fling job is written on every gesture and
    // never read during composition, so making it observable would invalidate the
    // chart for no reason.
    val fling = remember { FlingHandle() }

    // The crosshair is Compose state because the canvas must redraw when it moves.
    // It is one object, not one per candle, so this does not violate the
    // no-per-candle-state rule in `AGENTS.md`.
    var crosshair by remember(state.timeframe) { mutableStateOf<CrosshairPosition?>(null) }
    val haptic = LocalHapticFeedback.current

    // A fling belongs to the timeframe it was thrown on, and to the composable that
    // is running it. A switch resets the viewport (see the `remember` above), so a
    // fling left running would fight the reset; leaving the screen entirely must not
    // leave a frame loop alive.
    DisposableEffect(state.timeframe) {
        onDispose { fling.cancel() }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(dataSize) {
                    // `detectTransformGestures` is not used here because it does not
                    // expose the throw velocity, and a fling is exactly that. The
                    // loop below reproduces its pan and pinch arithmetic — centroid
                    // delta and spread ratio — while keeping the `VelocityTracker`
                    // that Step 9.2 needs, and adding the long-press crosshair of
                    // Step 9.5, the boundary haptics of Step 9.6 and the axis drag of
                    // Step 9.7.
                    //
                    // Double-tap state lives outside `awaitEachGesture`, so it
                    // survives between the two taps that make a double tap.
                    var lastTapUpMs = 0L
                    var lastTapPosition = Offset.Zero

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        // A new touch always stops the previous fling (Step 9.2).
                        fling.cancel()

                        val priceAxisPx = priceAxisWidth.toPx()
                        val timeAxisPx = timeAxisHeight.toPx()
                        val plotWidthPx = canvasSize.width - priceAxisPx
                        val plotHeightPx = canvasSize.height - timeAxisPx
                        if (plotWidthPx <= 0f || plotHeightPx <= 0f) return@awaitEachGesture

                        // Step 9.10: where the gesture *starts* decides what it is,
                        // resolved through the shared priority order rather than by
                        // ad-hoc checks here. A touch that begins in the price gutter
                        // scales the axis and can never become a pan or a crosshair,
                        // so the two can never fight over the same finger. Order
                        // lines, drawing handles and the armed drawing tool are
                        // reserved slots in the same rule; none exist yet, so all
                        // three are false.
                        // Step 9.10: where the gesture *starts* decides what it is.
                        // The price gutter is checked first, and the order-line hit
                        // test is only run when the touch is *not* in the gutter: a
                        // grab callback has the side effect of arming a drag, so it
                        // must not fire for a touch that was aimed at the axis.
                        val startedOnPriceAxis =
                            ChartPriceScale.isOnPriceAxis(down.position.x, plotWidthPx)
                        val startedOnOrderLine = if (startedOnPriceAxis) {
                            false
                        } else {
                            // Step 10.4: a touch on a draggable order line wins over a
                            // pan, so a trader can move a stop without the chart sliding
                            // sideways underneath it. The tolerance is a finger's width
                            // converted to price units through the range on screen,
                            // because the hit test works in prices and only the chart
                            // knows how many units a finger spans.
                            val range = drawnRangeFor(viewport)
                            val tolerance = ChartPriceScale.toleranceUnits(
                                plotHeightPx = plotHeightPx,
                                range = range,
                            ).toInt()
                            val tapPrice = ChartPriceScale.priceAt(
                                y = down.position.y,
                                plotHeightPx = plotHeightPx,
                                range = range,
                            ).toInt()
                            latestOnOrderLineGrab(tapPrice, tolerance)
                        }

                        var gesture = ChartGesturePriority.resolve(
                            ChartHitTest(
                                onPriceAxis = startedOnPriceAxis,
                                onOrderLine = startedOnOrderLine,
                            ),
                        )
                        val onPriceAxis = gesture == ChartGesture.PRICE_AXIS_SCALE

                        // Debug-only: brackets the gesture so a slow frame can be
                        // correlated with "a finger was down, and this is what it
                        // was doing". Called at each exit below as well as the
                        // fall-through, because a crosshair release and a fling
                        // both end the gesture without reaching the bottom.
                        ChartDiagnosticsHook.onGesture?.invoke(gesture.name, true)
                        val endGesture = {
                            ChartDiagnosticsHook.onGesture?.invoke(gesture.name, false)
                        }

                        // Step 9.4: the zoom bounds are candle widths (2dp..40dp), so
                        // they are derived from the plot the user is actually looking
                        // at. `PointerInputScope` is a `Density`, so the dp conversion
                        // needs no `LocalDensity`.
                        val limits = ChartZoomLimits.fromPlot(
                            plotWidthPx = plotWidthPx,
                            minCandleWidthPx = ChartZoomLimits.MIN_CANDLE_WIDTH_DP.dp.toPx(),
                            maxCandleWidthPx = ChartZoomLimits.MAX_CANDLE_WIDTH_DP.dp.toPx(),
                        )

                        // The window on screen when the axis drag began. Every frame of
                        // the drag scales *this* range by the cumulative distance, not
                        // the previous frame's result: compounding per-frame factors
                        // would drift, and a jittery finger would ratchet the axis.
                        val dragBaseRange = if (onPriceAxis) drawnRangeFor(viewport) else null
                        val dragAnchorPrice = dragBaseRange?.let {
                            it.maxUnits - (down.position.y / plotHeightPx) * it.spanUnits
                        }
                        val dragAnchorFraction = down.position.y / plotHeightPx

                        // True while the touch is still a candidate for the still-hold
                        // crosshair: one finger down, not yet moved. Cleared once it has
                        // moved or gained a partner, so the timer below is armed only
                        // when a long press is still possible.
                        //
                        // Armed only for a pan. A touch that began in the price gutter
                        // is committed and can never become a crosshair, so arming the
                        // timer for it would do nothing but fire a timeout 280 ms in.
                        var longPressPending = gesture == ChartGesture.PAN

                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)

                        var previousCentroid: Offset? = null
                        var previousSpread = 0f
                        var lastCrosshairIndex = Int.MIN_VALUE
                        // The event that ended the loop, kept so the lift position and
                        // time are available after the loop for the tap/double-tap
                        // decision (Step 9.8). A local declared inside the loop would
                        // be out of scope there.
                        var lastEvent: PointerEvent? = null

                        while (true) {
                            // Step 9.5: a finger that is held still sends no pointer
                            // events, so `awaitPointerEvent` alone would never fire the
                            // long press — the crosshair would only ever appear once the
                            // user moved, which is the opposite of a long press. The
                            // await is therefore bounded by the time left on the
                            // threshold: a timeout *is* the long-press signal, and any
                            // real event resets the wait.
                            val event = if (longPressPending) {
                                val remaining = (LONG_PRESS_MS - (SystemClock.uptimeMillis() - down.uptimeMillis))
                                    .coerceAtLeast(0L)
                                withTimeoutOrNull(remaining) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }

                            if (event == null) {
                                // Timed out with the finger down and unmoved: that is
                                // the long press. Keep awaiting normally.
                                longPressPending = false

                                // The long press goes through the same promotion rule
                                // as every other event, because `resolve` would ignore
                                // what the gesture already is. A touch that began in
                                // the price gutter is committed, and holding it still is
                                // still an axis drag: `resolve` would hand back
                                // CROSSHAIR and throw the drag away, which is a real
                                // defect a device log caught — every axis hold longer
                                // than the 280 ms threshold became a crosshair.
                                // `promote` returns a committed gesture unchanged.
                                val promoted = ChartGesturePriority.promote(
                                    current = gesture,
                                    hit = ChartHitTest(longPressSatisfied = true),
                                )

                                // A committed gesture has nothing to promote to. Return
                                // to the normal await so the drag the user started
                                // continues, rather than opening a crosshair over it.
                                if (promoted == gesture) continue

                                // Bracket the change so a `begin` still matches its
                                // `end`. Without this the release would log the *new*
                                // name, because `endGesture` reads `gesture`.
                                ChartDiagnosticsHook.onGesture?.invoke(gesture.name, false)
                                gesture = promoted
                                ChartDiagnosticsHook.onGesture?.invoke(promoted.name, true)
                                fling.cancel()

                                // Publish the readout now, from the down position.
                                // Without this the crosshair would be active but
                                // invisible until the finger moved a pixel, which
                                // reads as the long press having done nothing.
                                val at = viewport
                                val width = plotWidthPx / at.barsVisible
                                val index = CrosshairReadout.indexFor(
                                    if (width <= 0f) {
                                        at.firstVisibleIndex
                                    } else {
                                        down.position.x / width + at.firstVisibleIndex - 0.5f
                                    },
                                )
                                val price = drawnRangeFor(at).let {
                                    it.maxUnits - (down.position.y / plotHeightPx) * it.spanUnits
                                }
                                crosshair = CrosshairPosition(index = index, priceUnits = price)
                                latestOnCrosshairChange(
                                    CrosshairReadout.at(latestState.buffer, latestState.formingCandle, index),
                                )
                                lastCrosshairIndex = index
                                continue
                            }

                            lastEvent = event
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } /
                                pressed.size.toFloat()

                            // Step 9.5/9.10: re-resolve the gesture each event, but only
                            // while it may still change. A committed gesture — an axis
                            // drag — is never re-evaluated, so a stray second finger
                            // cannot turn it into a pinch. The long press is a duration
                            // rather than a position, so it is offered to the rule here
                            // and only wins if no more specific layer claims the touch.
                            if (ChartGesturePriority.isPromotable(gesture)) {
                                val heldMs = event.changes.first().uptimeMillis - down.uptimeMillis
                                val travelled = (centroid - down.position).getDistance()
                                val longPress = pressed.size == 1 &&
                                    heldMs >= LONG_PRESS_MS &&
                                    travelled <= viewConfiguration.touchSlop

                                // Promotion, not a fresh resolve: a crosshair that is
                                // being dragged to another candle must stay a crosshair
                                // even though the finger has now passed the touch slop.
                                val promoted = ChartGesturePriority.promote(
                                    current = gesture,
                                    hit = ChartHitTest(
                                        isMultiTouch = pressed.size >= 2,
                                        longPressSatisfied = longPress,
                                    ),
                                )

                                if (promoted != gesture) {
                                    // Close the gesture being replaced before
                                    // opening its successor, so every `begin` has
                                    // a matching `end` and a reader can trust the
                                    // pairing when measuring a gesture's duration.
                                    ChartDiagnosticsHook.onGesture?.invoke(gesture.name, false)
                                    gesture = promoted
                                    if (gesture == ChartGesture.CROSSHAIR) {
                                        // The fling must not resume underneath the
                                        // crosshair.
                                        fling.cancel()
                                    }
                                    ChartDiagnosticsHook.onGesture?.invoke(promoted.name, true)
                                }

                                // A finger that has moved or gained a partner is no
                                // longer a candidate for the still-hold timer.
                                longPressPending = gesture == ChartGesture.PAN &&
                                    pressed.size == 1 &&
                                    travelled <= viewConfiguration.touchSlop
                            }

                            val current = viewport
                            val candleWidth = plotWidthPx / current.barsVisible
                            var next = current

                            if (gesture == ChartGesture.PRICE_AXIS_SCALE) {
                                // Step 9.7: a vertical drag in the gutter rescales the
                                // price window about the price the finger grabbed.
                                if (dragBaseRange != null && dragAnchorPrice != null) {
                                    val factor = ChartPriceScale.factorForDrag(
                                        dragPx = centroid.y - down.position.y,
                                        plotHeightPx = plotHeightPx,
                                    )
                                    val scaled = ChartPriceScale.scaled(
                                        range = dragBaseRange,
                                        factor = factor,
                                        anchorPrice = dragAnchorPrice,
                                        anchorFraction = dragAnchorFraction,
                                    )
                                    manualPriceRange = scaled
                                    // The draw pass reads `autoScale` to decide which
                                    // range is authoritative; touching the axis is what
                                    // switches the chart to manual.
                                    if (current.autoScale) {
                                        next = current.copy(autoScale = false)
                                    }
                                }
                            } else if (gesture == ChartGesture.ORDER_LINE_DRAG) {
                                // Step 10.4: the grabbed line follows the finger's
                                // price. Nothing else moves — not the viewport, not the
                                // crosshair — because a trader adjusting a stop wants
                                // the chart still, so they can see the level against the
                                // candles.
                                val range = drawnRangeFor(current)
                                val price = ChartPriceScale.priceAt(
                                    y = centroid.y,
                                    plotHeightPx = plotHeightPx,
                                    range = range,
                                ).toInt()
                                latestOnOrderLineDrag(price)
                            } else if (gesture == ChartGesture.CROSSHAIR) {
                                // While the crosshair is up, the finger moves it and
                                // nothing else: panning at the same time would drag the
                                // candle out from under the reading.
                                val index = CrosshairReadout.indexFor(
                                    if (candleWidth <= 0f) {
                                        current.firstVisibleIndex
                                    } else {
                                        centroid.x / candleWidth + current.firstVisibleIndex - 0.5f
                                    },
                                )
                                val price = drawnRangeFor(current).let {
                                    it.maxUnits - (centroid.y / plotHeightPx) * it.spanUnits
                                }
                                crosshair = CrosshairPosition(index = index, priceUnits = price)
                                latestOnCrosshairChange(
                                    CrosshairReadout.at(latestState.buffer, latestState.formingCandle, index),
                                )

                                // Step 9.6: a tick as the crosshair crosses onto a new
                                // candle. Comparing the index rather than the pixels is
                                // what makes it fire once per candle instead of once per
                                // frame.
                                if (index != lastCrosshairIndex) {
                                    if (lastCrosshairIndex != Int.MIN_VALUE) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    lastCrosshairIndex = index
                                }
                            } else {
                                // Step 9.10: only a pan drags the time axis. A pinch
                                // must zoom strictly around the focal point (Step 9.3),
                                // so letting it also pan would slide the chart sideways
                                // as the fingers close.
                                if (ChartGesturePriority.pansHorizontally(gesture) &&
                                    previousCentroid != null &&
                                    candleWidth > 0f
                                ) {
                                    val pan = centroid - previousCentroid
                                    if (pan.x != 0f) {
                                        // A leftward drag (negative x) moves forward in
                                        // time, so the sign is flipped.
                                        next = controller.panBy(next, -pan.x / candleWidth, dataSize)
                                    }
                                }

                                if (gesture == ChartGesture.PINCH_ZOOM && pressed.size >= 2) {
                                    val spread = pressed.fold(0f) { acc, c -> acc + (c.position - centroid).getDistance() } /
                                        pressed.size.toFloat()
                                    if (previousSpread > 0f && spread > 0f) {
                                        val anchorFraction = (centroid.x / plotWidthPx).coerceIn(0f, 1f)
                                        next = controller.zoomBy(next, previousSpread / spread, anchorFraction, dataSize, limits)
                                    }
                                    previousSpread = spread
                                } else {
                                    previousSpread = 0f
                                }
                            }

                            if (next != current) {
                                viewport = next
                                latestOnViewportChange(next)
                            }

                            tracker.addPosition(event.changes.first().uptimeMillis, centroid)
                            previousCentroid = centroid
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }

                        // The finger lifted. A crosshair is dismissed on release, the
                        // way a terminal clears it, so a stale reading cannot sit over a
                        // chart that keeps moving.
                        if (gesture == ChartGesture.CROSSHAIR) {
                            crosshair = null
                            latestOnCrosshairChange(null)
                            endGesture()
                            return@awaitEachGesture
                        }

                        // A released order line commits its move (Step 10.4). The
                        // release is announced rather than the drag being submitted
                        // per frame: one command per gesture is what the engine's
                        // look-back guard expects, and a drag of a hundred frames
                        // would otherwise be a hundred commands.
                        if (gesture == ChartGesture.ORDER_LINE_DRAG) {
                            latestOnOrderLineRelease()
                            endGesture()
                            return@awaitEachGesture
                        }

                        // Step 9.8: a double tap on the plot restores the auto-fitted
                        // axis. A tap is a press that neither travelled nor became an
                        // axis drag, so a drag that happens to end slowly cannot be
                        // mistaken for one.
                        val up = lastEvent?.changes?.firstOrNull { !it.pressed }
                        val upMs = up?.uptimeMillis ?: down.uptimeMillis
                        val upPosition = up?.position ?: down.position
                        val travelled = (upPosition - down.position).getDistance()
                        val isTap = !onPriceAxis && travelled <= viewConfiguration.touchSlop

                        // Step 12.3: a tap on the news flag strip opens the release.
                        // Checked before the double-tap branch, because a flag is a
                        // deliberate target on a strip no candle occupies, and letting
                        // the second tap of a double-tap resolve the axis instead would
                        // make the flag feel unresponsive. The hit test itself rejects
                        // anything below the strip, so a tap on a candle still falls
                        // through to the axis restore below.
                        if (isTap && latestState.newsFlags.isNotEmpty()) {
                            val flagHit = newsFlagAt(upPosition, latestState, viewport, plotWidthPx)
                            if (flagHit != null) {
                                latestOnNewsFlagTap(flagHit)
                                endGesture()
                                return@awaitEachGesture
                            }
                        }

                        if (isTap) {
                            val sinceLastTap = upMs - lastTapUpMs
                            val nearLastTap = (upPosition - lastTapPosition).getDistance() <= DOUBLE_TAP_SLOP_PX
                            if (sinceLastTap in 1..DOUBLE_TAP_TIMEOUT_MS && nearLastTap) {
                                manualPriceRange = null
                                if (!viewport.autoScale) {
                                    val restored = viewport.copy(autoScale = true)
                                    viewport = restored
                                    latestOnViewportChange(restored)
                                }
                                lastTapUpMs = 0L
                                endGesture()
                                return@awaitEachGesture
                            }
                            lastTapUpMs = upMs
                            lastTapPosition = upPosition
                            endGesture()
                            return@awaitEachGesture
                        }

                        // A touch that scaled the axis does not also throw the chart:
                        // the two axes are independent, and a fling is horizontal.
                        if (onPriceAxis) {
                            endGesture()
                            return@awaitEachGesture
                        }

                        // Otherwise throw the chart if the drag ended fast enough to be
                        // a flick (Step 9.2).
                        val velocityX = tracker.calculateVelocity().x
                        if (ChartFling.isFling(velocityX)) {
                            // The finger is up but the chart is still moving, so this
                            // is the one gesture whose cost lands *after* the release.
                            // Naming it FLING separates it from the pan that preceded
                            // it, which is what a frame-attribution pass needs.
                            //
                            // The drag is closed *first*. `endGesture` reads the
                            // mutable `gesture`, and the fling's `begin` used to be
                            // emitted before it, so the two brackets nested and a
                            // reader saw `begin FLING` / `end PAN` — which looks like
                            // a mispairing rather than the two sequential gestures it
                            // actually is. The device log was full of these.
                            endGesture()
                            // The fling's own bracket is opened and closed by
                            // [FlingHandle], so a cancelled fling cannot leave a
                            // `begin FLING` behind.
                            fling.start(scope) {
                                runFling(
                                    velocityPxPerSec = velocityX,
                                    plotWidthPx = plotWidthPx,
                                    dataSize = dataSize,
                                    controller = controller,
                                    readViewport = { viewport },
                                    onViewport = { next ->
                                        viewport = next
                                        latestOnViewportChange(next)
                                    },
                                )
                            }
                        } else {
                            endGesture()
                        }
                    }
                },
        ) {
            val priceAxisPx = priceAxisWidth.toPx()
            val timeAxisPx = timeAxisHeight.toPx()
            val plotWidthPx = size.width - priceAxisPx
            val plotHeightPx = size.height - timeAxisPx

            // Nothing drawn outside the plot may reach the screen. The price axis
            // draws its labels in the right-hand gutter, past `plotWidthPx`, and
            // the time axis draws below `plotHeightPx`; both are deliberately
            // outside the plot rectangle, and both would otherwise paint over
            // whatever the parent laid out beneath the chart — the timeframe chips
            // above and the navigation bar below.
            //
            // Clipping to the whole canvas rather than to the plot is what keeps
            // the gutters visible while still bounding every mark to this
            // composable. The earlier layout had no clip at all, so a zoomed chart
            // drew its axis text straight over the chips and the bottom bar.
            clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                // Auto-scale fits the price axis to the bars actually on screen,
                // and "on screen" is the viewport being drawn right now — which
                // only this lambda knows. The range handed in was fitted to
                // whatever viewport the last published frame used, so after a
                // zoom it is stale, and a stale range maps visible prices to
                // y < 0 or y > plotHeight: candles drawn above and below the
                // plot, straight over the timeframe chips and the bottom bar.
                //
                // Re-fitting here is a read over the visible window — a hundred
                // or so bars — not a re-aggregation, so it costs nothing per
                // frame and cannot race with the state.
                //
                // Honours `autoScale`: a manually scaled axis (Step 9.7) keeps
                // the range it was given, and `drawnRangeFor` is the one place
                // that decides which of the two ranges is authoritative.
                val seeded = latestState.copy(
                    viewport = viewport,
                    crosshair = crosshair,
                    priceRange = drawnRangeFor(viewport),
                )
                val drawn = seeded
                // Debug-only observer; null in release. Reports the viewport as
                // drawn, which is what pan/zoom debugging needs.
                ChartDiagnosticsHook.onDraw?.invoke(drawn)
                with(renderer) {
                    draw(
                        state = drawn,
                        plotWidthPx = plotWidthPx,
                        plotHeightPx = plotHeightPx,
                        priceAxisWidthPx = priceAxisPx,
                        timeAxisHeightPx = timeAxisPx,
                    )
                }
            }
        }

        // Step 9.8: shown only when the newest candle has scrolled off the right
        // edge. A button that is always there would cover candles; one that appears
        // exactly when the user has lost the live edge is what tells them they have.
        val scrolledToEnd = viewport.scrolledToEnd(dataSize) == viewport
        if (!scrolledToEnd) {
            JumpToLatestButton(
                onClick = {
                    fling.cancel()
                    val next = viewport.scrolledToEnd(dataSize)
                    viewport = next
                    latestOnViewportChange(next)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = priceAxisWidth + 8.dp, bottom = timeAxisHeight + 8.dp),
            )
        }
    }
}

/**
 * The "back to the live edge" control (Step 9.8).
 *
 * A small pill rather than a floating action button, because it sits over the chart
 * and a full-size FAB would hide the newest candles — the very thing the user is
 * trying to get back to.
 */
@Composable
private fun JumpToLatestButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(text = "Latest", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Holds the running fling's job so a new gesture can stop it.
 *
 * A plain holder rather than Compose state: it is written on every gesture and read
 * only by the gesture loop and `onDispose`, never during composition, so making it
 * observable would invalidate the chart for no reason. Keeping it out of the
 * composable's state is also what lets [cancel] be called from `onDispose` without
 * a write during teardown.
 */
private class FlingHandle {
    private var job: Job? = null

    /**
     * Identifies the fling that currently owns the `FLING` bracket.
     *
     * Incremented per [start]. A cancelled fling's `finally` still runs, and it
     * must not close a bracket that a *newer* fling has since opened — so the
     * cleanup carries the generation it belongs to and gives up if it is stale.
     */
    private var generation = 0

    /** True between the `begin FLING` and its matching `end FLING`. */
    private var open = false

    /** Starts [block], replacing any fling already running. */
    fun start(scope: kotlinx.coroutines.CoroutineScope, block: suspend () -> Unit) {
        // Close the previous fling's bracket before opening this one, or the two
        // would nest and a reader could not pair them.
        closeBracket()
        val mine = ++generation
        ChartDiagnosticsHook.onGesture?.invoke("FLING", true)
        open = true
        job = scope.launch {
            try {
                block()
            } finally {
                // Runs when the fling comes to rest *and* when it is cancelled.
                // Owning the bracket here rather than inside [block] is the point:
                // a cancelled fling never reached the end of [block], so it left a
                // `begin FLING` with no `end` — visible in a device log.
                if (mine == generation) closeBracket()
            }
        }
    }

    /** Stops the running fling, if any. Safe to call repeatedly. */
    fun cancel() {
        job?.cancel()
        job = null
        // The `finally` above also runs on cancellation, but it is asynchronous.
        // Closing here guarantees the bracket is shut before a new gesture opens
        // its own, which is what keeps the log pairing correct.
        closeBracket()
    }

    private fun closeBracket() {
        if (!open) return
        open = false
        ChartDiagnosticsHook.onGesture?.invoke("FLING", false)
    }
}

/**
 * The news flag under a touch, or null when the touch is not on the strip
 * (Step 12.3).
 *
 * # Why the transformer is rebuilt here rather than shared with the draw pass
 *
 * The draw pass builds its transformer inside the `Canvas` lambda, which the
 * gesture loop cannot see, and the viewport it uses is the one being drawn — which
 * during a gesture is the local `viewport` Compose state, the same value passed in
 * here. Rebuilding from that value is therefore exact, not an approximation, and it
 * avoids threading a second piece of state through the composable that could fall
 * out of step with the one the user is looking at.
 */
private fun newsFlagAt(
    touch: Offset,
    state: ChartRenderState,
    viewport: ChartViewport,
    plotWidthPx: Float,
): NewsFlagHit? {
    if (state.newsFlags.isEmpty() || plotWidthPx <= 0f) return null
    val transformer = ChartCoordinateTransformer(
        plotWidthPx = plotWidthPx,
        plotHeightPx = 1f,
        viewport = viewport,
        priceRange = state.priceRange,
    )
    return NewsFlagHitTester.hitTestAt(
        touchX = touch.x,
        touchY = touch.y,
        flags = state.newsFlags,
        flagX = { transformer.xOfIndex(it.index.toFloat()) },
        tolerancePx = NEWS_FLAG_TOUCH_TOLERANCE_PX,
    )
}

/**
 * How far from a flag's staff a tap still counts, in pixels.
 *
 * Wider than the flag itself so a fingertip can hit a 1.5px staff at all. Two flags
 * one bar apart can both fall inside this, which is why [NewsFlagHitTester] returns
 * the nearest rather than the first.
 */
private const val NEWS_FLAG_TOUCH_TOLERANCE_PX: Float = 18f

/**
 * How long a finger must be held still before it becomes a crosshair rather than a
 * pan (Step 9.5).
 *
 * 280ms is the platform long-press threshold the rest of Android uses, so the chart
 * does not feel different from every other surface on the device. A shorter
 * threshold turns a deliberately slow pan into a crosshair mid-drag.
 */
private const val LONG_PRESS_MS: Long = 280L

/**
 * Longest gap between two taps that still counts as a double tap (Step 9.8).
 *
 * Matches the platform's double-tap timeout. Longer and two deliberate taps at
 * different prices would reset the axis, which is surprising.
 */
private const val DOUBLE_TAP_TIMEOUT_MS: Long = 300L

/**
 * How close together two taps must land to count as a double tap, in pixels.
 *
 * Generous, because a double tap is a quick gesture and the finger moves; too tight
 * and the second tap lands a few pixels off and nothing happens.
 */
private const val DOUBLE_TAP_SLOP_PX: Float = 48f

/**
 * Runs one fling to a stop (Step 9.2).
 *
 * # Why this is a frame loop and not an `Animatable`
 *
 * The fling has to stop the moment the viewport reaches the data edge. The edge is
 * only known after the pan is clamped, so the decision is per frame, and it is made
 * by comparing the viewport before and after. `Animatable` animates a single scalar
 * toward a target and would need the target up front — but the target depends on
 * where the clamp lands, which is exactly what we do not know in advance.
 *
 * # Why the distance is converted to bars per frame
 *
 * [ChartFling] works in pixels, because a fling should travel the same physical
 * distance on screen whatever the zoom. The viewport is in bars, and the candle
 * width is the conversion — recomputed each frame, so a fling that crosses a zoom
 * change still moves at the right screen speed.
 *
 * # Stopping
 *
 * Three ways, all of which leave the viewport clamped and the chart still:
 * the fling decays below its floor; a frame interval arrives that is not usable;
 * or the pan stops moving the viewport because the clamp has caught it. The last is
 * what makes a fling into the edge stop there rather than grinding against it.
 */
private suspend fun runFling(
    velocityPxPerSec: Float,
    plotWidthPx: Float,
    dataSize: Int,
    controller: ChartGestureController,
    readViewport: () -> ChartViewport,
    onViewport: (ChartViewport) -> Unit,
) {
    val fling = ChartFling(initialVelocityPxPerSec = velocityPxPerSec)
    var lastFrameNanos = 0L

    while (fling.isActive) {
        val frameNanos = withFrameNanos { it }
        if (lastFrameNanos == 0L) {
            lastFrameNanos = frameNanos
            continue
        }
        val dtSeconds = (frameNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameNanos

        val deltaPx = fling.step(dtSeconds)
        if (deltaPx == 0f) break

        val current = readViewport()
        val candleWidth = plotWidthPx / current.barsVisible
        if (candleWidth <= 0f) break

        // A rightward throw (positive velocity) reveals older candles, so the sign
        // is flipped to match the drag convention: negative pixel delta moves forward.
        val next = controller.panBy(current, -deltaPx / candleWidth, dataSize)
        if (next.firstVisibleIndex == current.firstVisibleIndex) break

        onViewport(next)
    }
}
