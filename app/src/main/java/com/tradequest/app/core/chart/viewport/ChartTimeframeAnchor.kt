package com.tradequest.app.core.chart.viewport

import com.tradequest.app.core.chart.buffer.CandleBuffer
import kotlin.math.floor

/**
 * Carries the chart's time position across a timeframe switch (Step 9.9).
 *
 * # The problem
 *
 * A viewport is a bar count: "show 120 bars from index 4,300". That means nothing
 * on a different timeframe. Five-minute bars and hourly bars index the same
 * moment completely differently, so handing the old viewport to a new timeframe
 * would land the user at an unrelated point in history — usually far in the past,
 * because the new series is shorter and the old index is large.
 *
 * The obvious fix, opening at the live edge, throws away where the user was
 * looking. A trader who has scrolled back to a specific session and switches from
 * M15 to H1 to see it in a wider frame expects to still be looking at that
 * session.
 *
 * # The fix
 *
 * Convert the position to what it actually means — a *time* — and convert back.
 * The centre of the old window is the moment to keep, because that is what the
 * eye is on; the left edge would drift the view by half a screen, and the right
 * edge is often empty gutter.
 *
 * # Why this is two functions
 *
 * The centre timestamp has to be read from the *old* buffer, which is gone by the
 * time the new one exists. Splitting the conversion lets a caller capture the
 * timestamp while the old series is still in hand and apply it once the new series
 * has been aggregated, without holding the old series across the switch.
 */
object ChartTimeframeAnchor {

    /**
     * The timestamp at the centre of [viewport]'s window, or null when the centre
     * falls outside [buffer].
     *
     * Null is a real case rather than an error: a viewport is allowed to sit in
     * empty space past either end of the data.
     *
     * # Why `floor` and not `round`
     *
     * Bar `i` occupies the half-open span `[i, i+1)`, so its centre is at `i + 0.5`.
     * Taking the floor of a continuous index therefore returns the bar *containing*
     * that point, and it is the exact inverse of the `+ 0.5` offset [viewportAt]
     * applies. Rounding instead would map `i + 0.5` to `i + 1` and every switch
     * would land one bar late, which compounds if the user switches back and forth.
     */
    fun centreTimestamp(viewport: ChartViewport, buffer: CandleBuffer): Long? {
        val centre = viewport.firstVisibleIndex + viewport.barsVisible / 2f
        if (!centre.isFinite()) return null
        val index = floor(centre).toInt()
        if (index < 0 || index >= buffer.size) return null
        return buffer.timestamps[index]
    }

    /**
     * The viewport on [nextBuffer] that puts [centreUtcMs] at the middle of the
     * window.
     *
     * [barsVisible] is the zoom to keep. It is a parameter rather than a copy of the
     * old viewport because the caller may want the same *time span* instead of the
     * same *bar count* — the same span is a different count on a different
     * timeframe. Keeping the count is what a terminal does, and it is the default.
     *
     * Returns null when [centreUtcMs] falls outside [nextBuffer], which happens when
     * the new series does not reach back that far.
     */
    fun viewportAt(
        centreUtcMs: Long,
        nextBuffer: CandleBuffer,
        barsVisible: Float,
        rightPaddingBars: Float = ChartViewport.DEFAULT_RIGHT_PADDING_BARS,
    ): ChartViewport? {
        if (nextBuffer.isEmpty) return null
        if (barsVisible <= 0f || !barsVisible.isFinite()) return null

        // The bar at or before the centre, so the window opens showing the moment
        // rather than the next bucket along.
        val index = nextBuffer.indexAtOrBefore(centreUtcMs)
        if (index < 0) return null

        return ChartViewport(
            barsVisible = barsVisible,
            firstVisibleIndex = index + 0.5f - barsVisible / 2f,
            rightPaddingBars = rightPaddingBars,
        ).clampedToData(nextBuffer.size)
    }

    /**
     * The whole conversion in one call: the viewport on [nextBuffer] that keeps
     * [previous]'s centre moment on screen.
     *
     * A convenience over [centreTimestamp] plus [viewportAt] for callers that hold
     * both buffers at once. Returns null when the position cannot be carried over,
     * so the caller can fall back to its own default rather than being handed a
     * viewport that points nowhere.
     */
    fun viewportPreservingCentre(
        previous: ChartViewport,
        previousBuffer: CandleBuffer,
        nextBuffer: CandleBuffer,
        nextBarsVisible: Float = previous.barsVisible,
    ): ChartViewport? {
        val centre = centreTimestamp(previous, previousBuffer) ?: return null
        return viewportAt(
            centreUtcMs = centre,
            nextBuffer = nextBuffer,
            barsVisible = nextBarsVisible,
            rightPaddingBars = previous.rightPaddingBars,
        )
    }
}
