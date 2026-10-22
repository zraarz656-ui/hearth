package com.tradequest.app.core.chart.renderer

import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.coordinates.PriceAxisRange
import com.tradequest.app.core.chart.crosshair.CrosshairPosition
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.chart.viewport.ChartViewport

/**
 * Everything one frame needs to draw (Step 8.1).
 *
 * # Why the chart state is not Compose state
 *
 * `AGENTS.md` warns against creating thousands of Compose state objects for
 * candles. This type is the boundary that prevents it: the buffer holds primitive
 * arrays, this object holds references to them, and Compose observes exactly one
 * value — the [ChartRenderState] instance itself. A frame change replaces one
 * reference rather than invalidating 90,000 snapshots.
 *
 * # Why the forming candle is separate from the buffer
 *
 * The buffer holds candles the dataset has fully revealed. The forming candle is
 * derived from the simulation clock and changes every frame within a minute. If
 * it were appended to the buffer, every clock tick would rebuild the arrays.
 * Keeping it beside the buffer means a tick costs one object, not a copy of
 * 90,000 elements.
 *
 * [formingCandleIndex] is the index the forming candle would occupy if appended,
 * which is the buffer's size, so the renderer can place it without a special
 * case in the x-axis arithmetic.
 */
data class ChartRenderState(
    /** Revealed candles, aggregated to [timeframe]. Drawing-only arrays. */
    val buffer: CandleBuffer,
    val timeframe: Timeframe,
    val viewport: ChartViewport,
    val priceRange: PriceAxisRange,
    val style: ChartStyle,
    /**
     * The in-progress candle, or null when the newest bucket is complete.
     *
     * Price fields only; the renderer draws it exactly as a completed candle.
     * It is never written into [buffer], so it cannot be mistaken for revealed
     * data by anything downstream.
     */
    val formingCandle: FormingCandleView?,
    /**
     * The simulated instant, in UTC epoch milliseconds, or null when the chart is
     * showing a purely historical view.
     *
     * Drives the session bands and the weekend separators.
     */
    val simulatedUtcMs: Long?,
    /**
     * The crosshair to draw, or null when it is not active (Step 9.5).
     *
     * Part of the render state rather than a separate parameter so the debug
     * observer sees exactly the frame that was drawn — a crosshair that is missing
     * from `ChartDiagnosticsHook` is a crosshair that cannot be debugged.
     */
    val crosshair: CrosshairPosition? = null,
) {
    /**
     * Total bars the x-axis must place: revealed candles plus the forming one.
     */
    val totalBars: Int get() = buffer.size + if (formingCandle != null) 1 else 0
}

/**
 * The forming candle, in the same shape the renderer draws a completed one.
 *
 * Built from `FormingCandle`, whose high and low are the extremes *so far*. This
 * type deliberately has no volume field: `tickVolume` is a whole-minute total,
 * so exposing it mid-minute would leak how much of the minute remains.
 */
data class FormingCandleView(
    val timestampUtcMs: Long,
    val openUnits: Int,
    val highUnits: Int,
    val lowUnits: Int,
    val closeUnits: Int,
) {
    /** True when the forming candle closed above its open. */
    val isBullish: Boolean get() = closeUnits > openUnits

    /** True when it closed below its open. */
    val isBearish: Boolean get() = closeUnits < openUnits
}
