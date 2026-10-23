package com.tradequest.app.core.chart.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import com.tradequest.app.core.chart.coordinates.ChartCoordinateTransformer
import com.tradequest.app.core.chart.coordinates.PriceAxisTicks
import com.tradequest.app.core.chart.coordinates.TimeAxisTicks
import com.tradequest.app.core.chart.crosshair.CrosshairReadout
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
import com.tradequest.app.core.simulation.calendar.SessionClassifier
import com.tradequest.app.core.simulation.calendar.TradingSession
import java.time.DayOfWeek

/**
 * Draws candles, grid, axes and session bands (Steps 8.5, 8.6, 8.7, 8.8, 8.9).
 *
 * # Allocation discipline
 *
 * The draw lambda runs every frame, so nothing here allocates per candle. Colours
 * and `Offset`s are constructed outside the loops or reused; the visible window
 * is an `IntRange` computed once; the axis tick lists are small and bounded by
 * the target count. This is Step 8.12's "minimal allocation" in practice, and it
 * is why the class takes a [DrawScope] rather than being a composable.
 *
 * # The forming candle
 *
 * Drawn as though it were the bar after the last revealed one. Its high and low
 * are the extremes revealed so far, so the chart shows a candle that grows as
 * time advances without ever showing where the price has yet to go.
 */
class ChartRenderer(
    private val textMeasurer: TextMeasurer,
) {

    /**
     * Draws one frame.
     *
     * [plotWidthPx] and [plotHeightPx] exclude the axis gutters. Every coordinate
     * here is measured from the *plot's* top-left corner: `x = 0` is the plot's
     * left edge and `x = plotWidthPx` its right edge, which is where the price
     * gutter begins. The caller does not translate; the renderer draws the
     * gutters itself, so it needs the plot width to place them.
     */
    fun DrawScope.draw(
        state: ChartRenderState,
        plotWidthPx: Float,
        plotHeightPx: Float,
        priceAxisWidthPx: Float,
        timeAxisHeightPx: Float,
    ) {
        if (plotWidthPx <= 0f || plotHeightPx <= 0f) return

        val transformer = ChartCoordinateTransformer(
            plotWidthPx = plotWidthPx,
            plotHeightPx = plotHeightPx,
            viewport = state.viewport,
            priceRange = state.priceRange,
        )

        // The visible slot range counts the forming candle, because it occupies a
        // slot. Everything that indexes the buffer must use the narrower range
        // below, or it would read one past the end when a forming candle exists
        // and no revealed bar does.
        val visibleSlots = state.viewport.visibleWindow(dataSize = state.totalBars)
        val bufferWindow = clampToBuffer(visibleSlots, state.buffer.size)

        if (visibleSlots.isEmpty()) {
            drawPriceAxis(state, transformer, plotWidthPx, priceAxisWidthPx)
            return
        }

        if (!bufferWindow.isEmpty()) {
            drawSessionBands(state, transformer, plotHeightPx, bufferWindow)
            drawWeekendSeparators(state, transformer, plotHeightPx, bufferWindow)
            drawGrid(state, transformer, plotWidthPx, plotHeightPx, bufferWindow)
        }
        drawCandles(state, transformer, bufferWindow, visibleSlots)
        // Overlays sit above the candles: a stop line hidden behind a wick would be
        // invisible exactly when price is near it, which is when it matters.
        drawTradeOverlays(state, transformer, plotWidthPx)
        drawTradeMarkers(state, transformer, bufferWindow)
        drawPriceAxis(state, transformer, plotWidthPx, priceAxisWidthPx)
        drawTimeAxis(state, transformer, plotWidthPx, timeAxisHeightPx, bufferWindow)
        drawCurrentPriceLine(state, transformer, plotWidthPx, priceAxisWidthPx)
        // Last, so the crosshair sits over everything it is being used to read.
        drawCrosshair(state, transformer, plotWidthPx, plotHeightPx, priceAxisWidthPx)
    }

    /** [window] restricted to indices that exist in a buffer of [bufferSize]. */
    private fun clampToBuffer(window: IntRange, bufferSize: Int): IntRange {
        if (window.isEmpty() || bufferSize <= 0) return IntRange.EMPTY
        val from = window.first.coerceAtLeast(0)
        val to = window.last.coerceAtMost(bufferSize - 1)
        return if (from > to) IntRange.EMPTY else from..to
    }

    // ---------- Step 8.5: candles ----------

    private fun DrawScope.drawCandles(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        bufferWindow: IntRange,
        visibleSlots: IntRange,
    ) {
        val buffer = state.buffer
        val style = state.style
        val candleWidth = transformer.candleWidthPx
        if (candleWidth <= 0f) return

        val bodyWidth = (candleWidth * style.bodyWidthFraction)
            .coerceAtLeast(MIN_BODY_WIDTH_PX)
        val halfBody = bodyWidth / 2f

        for (index in bufferWindow) {
            val centreX = transformer.xOfIndex(index.toFloat())
            // Cheap rejection before any per-candle arithmetic. The window is
            // already tight, so this only trims the two buffer bars at each end.
            if (centreX + halfBody < 0f || centreX - halfBody > transformer.plotWidthPx) continue

            val open = buffer.opens[index]
            val close = buffer.closes[index]
            val high = buffer.highs[index]
            val low = buffer.lows[index]

            val colour = when {
                close > open -> style.bullish
                close < open -> style.bearish
                else -> style.doji
            }

            drawCandle(
                centreX = centreX,
                openY = transformer.yOfPrice(open),
                closeY = transformer.yOfPrice(close),
                highY = transformer.yOfPrice(high),
                lowY = transformer.yOfPrice(low),
                bodyWidth = bodyWidth,
                colour = colour,
                style = style,
            )
        }

        // The forming candle, placed one slot past the last revealed bar. It is
        // drawn only when its own slot is visible, so a chart panned away from the
        // live edge does not show a candle in empty space.
        val forming = state.formingCandle ?: return
        val formingIndex = buffer.size
        if (formingIndex !in visibleSlots) return

        val centreX = transformer.xOfIndex(formingIndex.toFloat())
        val colour = when {
            forming.isBullish -> style.bullish
            forming.isBearish -> style.bearish
            else -> style.doji
        }
        drawCandle(
            centreX = centreX,
            openY = transformer.yOfPrice(forming.openUnits.toFloat()),
            closeY = transformer.yOfPrice(forming.closeUnits.toFloat()),
            highY = transformer.yOfPrice(forming.highUnits.toFloat()),
            lowY = transformer.yOfPrice(forming.lowUnits.toFloat()),
            bodyWidth = bodyWidth,
            colour = colour,
            style = style,
        )
    }

    /**
     * One candle: a one-pixel wick plus a body.
     *
     * The wick is drawn as a hairline at the body's centre. [wickWidthPx] of 1f
     * is deliberate — a thicker wick at wide zoom looks clumsy, and at narrow
     * zoom a 1px line is the crispest a rasteriser will produce.
     */
    private fun DrawScope.drawCandle(
        centreX: Float,
        openY: Float,
        closeY: Float,
        highY: Float,
        lowY: Float,
        bodyWidth: Float,
        colour: Color,
        style: ChartStyle,
    ) {
        drawLine(
            color = colour,
            start = Offset(centreX, highY),
            end = Offset(centreX, lowY),
            strokeWidth = style.wickWidthPx,
        )

        val bodyTop = minOf(openY, closeY)
        val rawHeight = kotlin.math.abs(closeY - openY)
        // A doji, or a candle whose body rounds to nothing, is drawn as a flat
        // line rather than a zero-height rectangle, which would be invisible.
        val bodyHeight = if (rawHeight < style.minBodyHeightPx) {
            style.minBodyHeightPx
        } else {
            rawHeight
        }

        drawRect(
            color = colour,
            topLeft = Offset(centreX - bodyWidth / 2f, bodyTop),
            size = Size(bodyWidth, bodyHeight),
        )
    }

    // ---------- Step 8.6: grid ----------

    private fun DrawScope.drawGrid(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        plotWidthPx: Float,
        plotHeightPx: Float,
        bufferWindow: IntRange,
    ) {
        val style = state.style

        // Horizontal lines at the price ticks, so a gridline always has a label.
        val priceTicks = PriceAxisTicks.ticks(state.priceRange, targetCount = priceTickTarget(plotHeightPx))
        for (price in priceTicks) {
            val y = transformer.yOfPrice(price)
            drawLine(
                color = style.gridLine,
                start = Offset(0f, y),
                end = Offset(plotWidthPx, y),
                strokeWidth = style.gridWidthPx,
            )
        }

        // Vertical lines at the time ticks, drawn from the same tick list as the
        // labels so the two can never disagree about where a boundary is.
        for (tick in timeTicks(state, bufferWindow)) {
            val index = state.buffer.indexAtOrBefore(tick.utcMs)
            if (index < 0) continue
            val x = transformer.xOfIndex(index.toFloat())
            if (x < 0f || x > plotWidthPx) continue
            drawLine(
                color = style.gridLine,
                start = Offset(x, 0f),
                end = Offset(x, plotHeightPx),
                strokeWidth = style.gridWidthPx,
            )
        }
    }

    // ---------- Step 8.9: sessions and weekends ----------

    /**
     * Session shading, as vertical bands behind the candles.
     *
     * # Why the bands are built from candle times
     *
     * A band must cover exactly the minutes the session was open. The obvious
     * implementation steps a clock forward and asks [SessionClassifier] at each
     * step, which would shade minutes the dataset does not contain — labelling
     * time that did not trade. Instead the visible candles are walked once and a
     * band is opened and closed as the classification changes, so a band's edges
     * are always real candle positions.
     */
    private fun DrawScope.drawSessionBands(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        plotHeightPx: Float,
        window: IntRange,
    ) {
        val buffer = state.buffer
        if (buffer.isEmpty || window.isEmpty()) return

        var bandStartIndex = window.first
        var bandSession = sessionOf(buffer.timestamps[window.first])

        for (index in (window.first + 1)..window.last) {
            val session = sessionOf(buffer.timestamps[index])
            if (session != bandSession) {
                drawSessionBand(state, transformer, plotHeightPx, bandStartIndex, index - 1, bandSession)
                bandStartIndex = index
                bandSession = session
            }
        }
        drawSessionBand(state, transformer, plotHeightPx, bandStartIndex, window.last, bandSession)
    }

    private fun DrawScope.drawSessionBand(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        plotHeightPx: Float,
        fromIndex: Int,
        toIndex: Int,
        session: TradingSession?,
    ) {
        val colour = when (session) {
            TradingSession.ASIA -> state.style.asiaSession
            TradingSession.LONDON -> state.style.londonSession
            TradingSession.NEW_YORK -> state.style.newYorkSession
            TradingSession.LONDON_NEW_YORK_OVERLAP -> state.style.newYorkSession
            // No shading for closed periods; the weekend separator handles the
            // weekend, and shading every overnight gap would tint the whole chart.
            TradingSession.CLOSED, null -> return
        }

        // Extend the band to cover the full slot of its first and last candle, so
        // adjacent bands meet without a hairline gap between them.
        val left = transformer.xOfIndex(fromIndex.toFloat()) - transformer.candleWidthPx / 2f
        val right = transformer.xOfIndex(toIndex.toFloat()) + transformer.candleWidthPx / 2f
        if (right < 0f || left > transformer.plotWidthPx) return

        drawRect(
            color = colour.copy(alpha = state.style.sessionBandAlpha),
            topLeft = Offset(left, 0f),
            size = Size(right - left, plotHeightPx),
        )
    }

    /**
     * Weekend separators: a stronger line at each gap between Friday's close and
     * Sunday's open.
     *
     * Detected from the calendar rather than from a fixed 24-hour rule, so a
     * holiday closure that happens to fall on a weekday is not mistaken for a
     * weekend, and a dataset that skips a weekend entirely gets no line drawn in
     * empty space.
     */
    private fun DrawScope.drawWeekendSeparators(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        plotHeightPx: Float,
        window: IntRange,
    ) {
        val buffer = state.buffer
        if (buffer.size < 2 || window.isEmpty()) return

        for (index in maxOf(window.first, 1)..window.last) {
            val previous = NewYorkTradingCalendar.tradingDateOf(buffer.timestamps[index - 1])
            val current = NewYorkTradingCalendar.tradingDateOf(buffer.timestamps[index])
            if (current == previous) continue

            // A new trading week starts on Monday. Friday-to-Monday is the
            // weekend; a weekday change is an ordinary daily rollover.
            val crossesWeekend = previous.dayOfWeek == DayOfWeek.FRIDAY &&
                current.dayOfWeek == DayOfWeek.MONDAY
            if (!crossesWeekend) continue

            val x = transformer.xOfIndex(index.toFloat()) - transformer.candleWidthPx / 2f
            if (x < 0f || x > transformer.plotWidthPx) continue
            drawLine(
                color = state.style.weekendBand,
                start = Offset(x, 0f),
                end = Offset(x, plotHeightPx),
                strokeWidth = WEEKEND_SEPARATOR_WIDTH_PX,
            )
        }
    }

    // ---------- Step 8.7: price axis ----------

    private fun DrawScope.drawPriceAxis(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        plotWidthPx: Float,
        priceAxisWidthPx: Float,
    ) {
        if (priceAxisWidthPx <= 0f) return
        val style = state.style
        val textStyle = TextStyle(color = style.axisText, fontSize = axisFontSize(style))

        // The vertical separator sits on the plot/gutter boundary, which is
        // x = plotWidthPx in this coordinate space. The price gutter is on the
        // right, which is where every trading terminal puts it: the newest
        // candles are on the right, and the eye reads the latest price against a
        // scale it does not have to cross the chart to find.
        drawLine(
            color = style.axisLine,
            start = Offset(plotWidthPx, 0f),
            end = Offset(plotWidthPx, transformer.plotHeightPx),
            strokeWidth = style.gridWidthPx,
        )

        val target = priceTickTarget(transformer.plotHeightPx)
        for (price in PriceAxisTicks.ticks(state.priceRange, target)) {
            val y = transformer.yOfPrice(price)
            val label = PriceAxisTicks.formatPrice(price)
            val measured = textMeasurer.measure(
                text = label,
                style = textStyle,
                constraints = Constraints(maxWidth = (priceAxisWidthPx - style.axisLabelPaddingPx).toInt().coerceAtLeast(1)),
            )
            // Labels live in the gutter to the *right* of the plot. Drawing them
            // at the plot edge instead would put them on top of the newest
            // candles, which are exactly the ones a trader is reading.
            val top = y - measured.size.height / 2f
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(plotWidthPx + style.axisLabelPaddingPx, top),
            )
        }
    }

    // ---------- Step 8.8: time axis ----------

    private fun DrawScope.drawTimeAxis(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        plotWidthPx: Float,
        timeAxisHeightPx: Float,
        bufferWindow: IntRange,
    ) {
        if (timeAxisHeightPx <= 0f) return
        val style = state.style
        val textStyle = TextStyle(color = style.axisText, fontSize = axisFontSize(style))

        // The horizontal separator sits on the plot/gutter boundary, which is
        // y = plotHeightPx in this translated space — not y = 0, which is the top
        // of the chart.
        drawLine(
            color = style.axisLine,
            start = Offset(0f, transformer.plotHeightPx),
            end = Offset(plotWidthPx, transformer.plotHeightPx),
            strokeWidth = style.gridWidthPx,
        )

        for (tick in timeTicks(state, bufferWindow)) {
            val index = state.buffer.indexAtOrBefore(tick.utcMs)
            if (index < 0) continue
            val x = transformer.xOfIndex(index.toFloat())
            if (x < 0f || x > plotWidthPx) continue

            val measured = textMeasurer.measure(text = tick.label, style = textStyle)
            // Centre the label under its gridline, then clamp so the first and
            // last labels are not half off the axis.
            val left = (x - measured.size.width / 2f)
                .coerceIn(0f, (plotWidthPx - measured.size.width).coerceAtLeast(0f))
            // Labels go *below* the plot, in the gutter. At y = 0 they would sit
            // inside the plot, over the top of the candles.
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(left, transformer.plotHeightPx + style.axisLabelPaddingPx),
            )
        }
    }

    // ---------- Current price ----------

    /**
     * A horizontal line at the newest known price, with its price tagged in the
     * right-hand gutter.
     *
     * Uses the forming candle's close when one exists, because that is the price
     * the trader is watching, and the last revealed close otherwise.
     */
    private fun DrawScope.drawCurrentPriceLine(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        plotWidthPx: Float,
        priceAxisWidthPx: Float,
    ) {
        val price = state.formingCandle?.closeUnits?.toFloat()
            ?: state.buffer.takeIf { !it.isEmpty }?.closes?.get(state.buffer.size - 1)
            ?: return

        if (!state.priceRange.contains(price)) return

        val y = transformer.yOfPrice(price)
        val style = state.style
        drawLine(
            color = style.currentPriceLine,
            start = Offset(0f, y),
            end = Offset(plotWidthPx, y),
            strokeWidth = CURRENT_PRICE_LINE_WIDTH_PX,
        )

        // The tag goes in the gutter beside the line. Without it the line says
        // "here" but not "at what price", and the axis ticks are too sparse to
        // read the current price off one of them.
        if (priceAxisWidthPx <= 0f) return
        val label = PriceAxisTicks.formatPrice(price)
        val measured = textMeasurer.measure(
            text = label,
            style = TextStyle(color = style.currentPriceLine, fontSize = axisFontSize(style)),
            constraints = Constraints(maxWidth = (priceAxisWidthPx - style.axisLabelPaddingPx).toInt().coerceAtLeast(1)),
        )
        val top = (y - measured.size.height / 2f)
            .coerceIn(0f, (transformer.plotHeightPx - measured.size.height).coerceAtLeast(0f))
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(plotWidthPx + style.axisLabelPaddingPx, top),
        )
    }

    // ---------- Step 9.5: crosshair ----------

    /**
     * Dashed lines through the crosshair position, with its price tagged in the
     * right gutter and its time in the bottom one.
     *
     * # Why the tags are drawn here and not by the axes
     *
     * The axis ticks are placed on round prices and round times; the crosshair is
     * wherever the finger is. Its tags therefore have to be drawn by whoever knows
     * that position, and they must be drawn *over* the axis labels, because a
     * crosshair tag that hides behind a tick is unreadable exactly when the user
     * is reading it.
     */
    private fun DrawScope.drawCrosshair(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        plotWidthPx: Float,
        plotHeightPx: Float,
        priceAxisWidthPx: Float,
    ) {
        val crosshair = state.crosshair ?: return
        val style = state.style

        // The vertical line follows the bar the crosshair is on, so it stays on
        // that candle when the chart is panned or zoomed.
        val x = transformer.xOfIndex(crosshair.index.toFloat())
        if (x < 0f || x > plotWidthPx) return

        // The horizontal line is at the recorded price. A price outside the window
        // has no line to draw, but the vertical line and the time tag are still
        // meaningful, so this is not an early return.
        val priceInRange = state.priceRange.contains(crosshair.priceUnits)
        val y = if (priceInRange) transformer.yOfPrice(crosshair.priceUnits) else null

        drawLine(
            color = style.crosshair,
            start = Offset(x, 0f),
            end = Offset(x, plotHeightPx),
            strokeWidth = CROSSHAIR_WIDTH_PX,
        )
        if (y != null) {
            drawLine(
                color = style.crosshair,
                start = Offset(0f, y),
                end = Offset(plotWidthPx, y),
                strokeWidth = CROSSHAIR_WIDTH_PX,
            )
        }

        drawCrosshairPriceTag(state, plotWidthPx, plotHeightPx, priceAxisWidthPx, y)
        drawCrosshairTimeTag(state, transformer, x, plotHeightPx)
    }

    /** The crosshair's price, tagged in the right gutter. */
    private fun DrawScope.drawCrosshairPriceTag(
        state: ChartRenderState,
        plotWidthPx: Float,
        plotHeightPx: Float,
        priceAxisWidthPx: Float,
        y: Float?,
    ) {
        if (y == null || priceAxisWidthPx <= 0f) return
        val crosshair = state.crosshair ?: return
        val style = state.style

        val measured = textMeasurer.measure(
            text = PriceAxisTicks.formatPrice(crosshair.priceUnits),
            style = TextStyle(color = style.background, fontSize = axisFontSize(style)),
            constraints = Constraints(
                maxWidth = (priceAxisWidthPx - style.axisLabelPaddingPx).toInt().coerceAtLeast(1),
            ),
        )
        // Kept inside the plot so a tag at either extreme is fully visible.
        val top = (y - measured.size.height / 2f)
            .coerceIn(0f, (plotHeightPx - measured.size.height).coerceAtLeast(0f))

        // A filled block behind the text, so the label stays readable over the
        // axis ticks and the current-price line it may be sitting on.
        drawRect(
            color = style.crosshair,
            topLeft = Offset(plotWidthPx, top),
            size = Size(priceAxisWidthPx, measured.size.height.toFloat()),
        )
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(plotWidthPx + style.axisLabelPaddingPx, top),
        )
    }

    /** The crosshair's bar time, tagged in the bottom gutter. */
    private fun DrawScope.drawCrosshairTimeTag(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        x: Float,
        plotHeightPx: Float,
    ) {
        val crosshair = state.crosshair ?: return
        val style = state.style
        val readout = CrosshairReadout.at(state.buffer, state.formingCandle, crosshair.index) ?: return

        val label = if (CrosshairReadout.needsDate(state.timeframe)) {
            readout.timestampLabel
        } else {
            // Intraday: the date is on the axis already, so the tag shows the time.
            readout.timestampLabel.substringAfter(' ')
        }

        val measured = textMeasurer.measure(
            text = label,
            style = TextStyle(color = style.background, fontSize = axisFontSize(style)),
        )
        // Centre on the bar, then clamp so a tag at either edge is not half off.
        val left = (x - measured.size.width / 2f)
            .coerceIn(0f, (transformer.plotWidthPx - measured.size.width).coerceAtLeast(0f))

        drawRect(
            color = style.crosshair,
            topLeft = Offset(left, plotHeightPx),
            size = Size(measured.size.width.toFloat(), measured.size.height.toFloat()),
        )
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(left, plotHeightPx),
        )
    }

    // ---------- Shared helpers ----------

    private fun sessionOf(utcMs: Long): TradingSession? {
        // A minute inside a stored closure or outside market hours is not part of
        // any session band. SessionClassifier already answers CLOSED for both.
        val session = SessionClassifier.classify(utcMs)
        return if (session == TradingSession.CLOSED) null else session
    }

    private fun timeTicks(state: ChartRenderState, bufferWindow: IntRange): List<TimeAxisTicks.Tick> {
        if (bufferWindow.isEmpty()) return emptyList()
        return TimeAxisTicks.ticks(
            timestamps = state.buffer.timestamps,
            fromIndex = bufferWindow.first,
            toIndex = bufferWindow.last,
            timeframe = state.timeframe,
            targetCount = TIME_TICK_TARGET,
        )
    }

    /**
     * Roughly one price label per 48 pixels.
     *
     * Chosen so a phone-height plot gets five to eight labels, which is the
     * density that stays readable without crowding. The exact count does not
     * matter because [PriceAxisTicks] rounds the step to a nice number anyway.
     */
    private fun priceTickTarget(plotHeightPx: Float): Int =
        (plotHeightPx / PIXELS_PER_PRICE_LABEL).toInt().coerceIn(2, 16)

    private fun axisFontSize(style: ChartStyle) = androidx.compose.ui.unit.TextUnit(
        value = style.axisTextSizePx,
        type = androidx.compose.ui.unit.TextUnitType.Sp,
    )

    // ---------- Steps 10.3, 10.5, 10.8: trade overlays and markers ----------

    /**
     * Draws every trade bracket: its shaded zones, its lines, and its handles.
     *
     * # Order within a group
     *
     * Zones first, then lines, then handles. A zone is a fill behind the lines that
     * bound it, so drawing it last would tint the lines themselves; a handle is a
     * target drawn on the line, so it must come after the line or the line would
     * cover it.
     *
     * # Why the risk zone needs both a stop and an entry
     *
     * The zone spans entry to stop. With no stop there is no zone to shade — an
     * entry alone is a line, not an area — so a group missing either edge is drawn
     * as lines only. The same applies to the reward zone.
     */
    private fun DrawScope.drawTradeOverlays(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        plotWidthPx: Float,
    ) {
        if (state.overlays.isEmpty()) return
        val style = state.style
        val plotHeight = transformer.plotHeightPx

        for (group in state.overlays) {
            val entry = group.entry ?: continue

            // Shade risk and reward before the lines so the fills sit behind them.
            group.stop?.let { stop ->
                shadeZone(
                    transformer = transformer,
                    fromUnits = entry.priceUnits,
                    toUnits = stop.priceUnits,
                    plotWidthPx = plotWidthPx,
                    plotHeightPx = plotHeight,
                    color = style.stopLine,
                    alpha = style.zoneAlpha,
                )
            }
            group.target?.let { target ->
                shadeZone(
                    transformer = transformer,
                    fromUnits = entry.priceUnits,
                    toUnits = target.priceUnits,
                    plotWidthPx = plotWidthPx,
                    plotHeightPx = plotHeight,
                    color = style.targetLine,
                    alpha = style.zoneAlpha,
                )
            }

            for (line in group.lines) {
                val price = line.priceUnits.toFloat()
                if (!state.priceRange.contains(price)) continue
                val y = transformer.yOfPrice(price)
                if (y < 0f || y > plotHeight) continue

                val color = when (line.kind) {
                    OverlayLineKind.ENTRY -> style.entryLine
                    OverlayLineKind.STOP -> style.stopLine
                    OverlayLineKind.TARGET -> style.targetLine
                }
                drawLine(
                    color = color,
                    start = Offset(0f, y),
                    end = Offset(plotWidthPx, y),
                    strokeWidth = TRADE_LINE_WIDTH_PX,
                )

                // Only a line a drag can move gets a handle, so the affordance and
                // the behaviour agree.
                if (line.isDraggable) {
                    drawHandle(
                        y = y,
                        plotWidthPx = plotWidthPx,
                        radiusPx = style.handleRadiusPx,
                        color = color,
                    )
                }
            }
        }
    }

    /** Fills the vertical band between two price levels. */
    private fun DrawScope.shadeZone(
        transformer: ChartCoordinateTransformer,
        fromUnits: Int,
        toUnits: Int,
        plotWidthPx: Float,
        plotHeightPx: Float,
        color: Color,
        alpha: Float,
    ) {
        val y1 = transformer.yOfPrice(fromUnits.toFloat())
        val y2 = transformer.yOfPrice(toUnits.toFloat())
        val top = minOf(y1, y2).coerceIn(0f, plotHeightPx)
        val bottom = maxOf(y1, y2).coerceIn(0f, plotHeightPx)
        val height = bottom - top
        if (height <= 0f) return
        drawRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(0f, top),
            size = Size(plotWidthPx, height),
        )
    }

    /**
     * The grab target on a draggable line.
     *
     * Drawn as a filled circle at the left edge, where a thumb naturally rests and
     * where it does not cover the most recent candles on the right.
     */
    private fun DrawScope.drawHandle(
        y: Float,
        plotWidthPx: Float,
        radiusPx: Float,
        color: Color,
    ) {
        if (radiusPx <= 0f) return
        drawCircle(
            color = color,
            radius = radiusPx * HANDLE_FILL_FRACTION,
            center = Offset(radiusPx, y),
        )
    }

    /**
     * Draws entry and exit arrows (Step 10.8).
     *
     * An entry arrow points the way the trade expects price to go — up for a buy,
     * down for a sell — and an exit arrow points the opposite way, so the pair reads
     * as "in here, out there" without a legend.
     *
     * Only markers whose bar is in the drawn window are considered, and a marker
     * outside the price window is skipped rather than clamped to the edge, because a
     * clamped arrow would point at a price the trade never touched.
     */
    private fun DrawScope.drawTradeMarkers(
        state: ChartRenderState,
        transformer: ChartCoordinateTransformer,
        bufferWindow: IntRange,
    ) {
        if (state.markers.isEmpty()) return
        val style = state.style

        for (marker in state.markers) {
            if (bufferWindow.isEmpty() || marker.index !in bufferWindow) continue
            val price = marker.priceUnits.toFloat()
            if (!state.priceRange.contains(price)) continue

            val x = transformer.xOfIndex(marker.index.toFloat())
            if (x < 0f || x > transformer.plotWidthPx) continue
            val y = transformer.yOfPrice(price)

            // Entry points into the trade, exit points out of it.
            val pointsUp = if (marker.isEntry) marker.isBuy else !marker.isBuy
            val colour = if (marker.isEntry) style.markerEntry else style.markerExit
            drawMarkerArrow(x = x, y = y, pointsUp = pointsUp, color = colour)
        }
    }

    /** A small triangle at ([x], [y]) pointing up or down. */
    private fun DrawScope.drawMarkerArrow(
        x: Float,
        y: Float,
        pointsUp: Boolean,
        color: Color,
    ) {
        val size = MARKER_SIZE_PX
        // Offset the triangle so its point touches the price and its body sits
        // clear of the bar, rather than the bar sitting on the arrow's centre.
        val tipY = if (pointsUp) y - MARKER_GAP_PX else y + MARKER_GAP_PX
        val baseY = if (pointsUp) tipY + size else tipY - size
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(x, tipY)
            lineTo(x - size / 2f, baseY)
            lineTo(x + size / 2f, baseY)
            close()
        }
        drawPath(path = path, color = color)
    }

    private companion object {
        const val MIN_BODY_WIDTH_PX: Float = 1f
        const val WEEKEND_SEPARATOR_WIDTH_PX: Float = 1.5f
        const val CURRENT_PRICE_LINE_WIDTH_PX: Float = 1f
        const val CROSSHAIR_WIDTH_PX: Float = 1f
        const val TIME_TICK_TARGET: Int = 6
        const val PIXELS_PER_PRICE_LABEL: Float = 48f
        /** Stroke width of a trade bracket line, in pixels. */
        const val TRADE_LINE_WIDTH_PX: Float = 1.5f
        /** The filled part of a handle, as a fraction of its hit radius. */
        const val HANDLE_FILL_FRACTION: Float = 0.45f
        /** Edge length of a trade marker triangle, in pixels. */
        const val MARKER_SIZE_PX: Float = 12f
        /** Gap between the marker's point and the price level, in pixels. */
        const val MARKER_GAP_PX: Float = 3f
    }
}
