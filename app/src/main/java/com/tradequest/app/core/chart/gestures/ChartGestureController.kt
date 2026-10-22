package com.tradequest.app.core.chart.gestures

import com.tradequest.app.core.chart.viewport.ChartViewport
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Applies gestures to a [ChartViewport] (Step 8.3).
 *
 * # Why the arithmetic lives here and not in the Compose layer
 *
 * Pan, zoom and clamp are the parts of gesture handling that can be wrong in ways
 * a finger cannot easily show: a zoom that drifts, a pan that overshoots the data,
 * a pinch whose anchor slides. Keeping them as pure functions means they are
 * tested directly, and the composable is left with nothing but turning pointer
 * events into calls.
 *
 * # Zoom anchoring
 *
 * A pinch must keep the price under the fingers in place. That is the whole
 * reason this is a class rather than a one-line multiplication: the naive
 * `barsVisible *= factor` zooms about the left edge, which feels like the chart
 * sliding sideways. [zoomBy] solves for the `firstVisibleIndex` that leaves the
 * anchor bar where it was.
 */
class ChartGestureController(
    private val minBarsVisible: Float = MIN_BARS_VISIBLE,
    private val maxBarsVisible: Float = MAX_BARS_VISIBLE,
) {

    /** The bar limits this controller clamps to when a caller does not supply any. */
    val defaultLimits: ChartZoomLimits = ChartZoomLimits(minBarsVisible, maxBarsVisible)

    /**
     * [viewport] zoomed by [factor], keeping the bar at [anchorFraction] of the
     * plot width fixed.
     *
     * [factor] above 1 widens the window (zoom out); below 1 narrows it (zoom in).
     * [anchorFraction] is 0 at the left edge and 1 at the right edge.
     *
     * [limits] carries the candle-width bounds in bars (Step 9.4). It defaults to
     * this controller's own, so a caller that has not measured its plot yet — or a
     * test that does not care — behaves as it did before the limits were expressible
     * in candle width.
     */
    fun zoomBy(
        viewport: ChartViewport,
        factor: Float,
        anchorFraction: Float,
        dataSize: Int,
        limits: ChartZoomLimits = defaultLimits,
    ): ChartViewport {
        if (factor <= 0f || !factor.isFinite()) return viewport

        val clampedAnchor = anchorFraction.coerceIn(0f, 1f)
        val newBars = (viewport.barsVisible * factor)
            .coerceIn(limits.minBarsVisible, limits.maxBarsVisible)
        if (newBars == viewport.barsVisible) return viewport

        // The data index under the anchor before the zoom.
        val anchorIndex = viewport.firstVisibleIndex + clampedAnchor * viewport.barsVisible
        // The first index that keeps that same index under the same fraction.
        val newFirst = anchorIndex - clampedAnchor * newBars

        return viewport.copy(barsVisible = newBars, firstVisibleIndex = newFirst)
            .clampedToData(dataSize)
    }

    /**
     * [viewport] panned by [deltaBars], where a positive value moves the chart
     * forward in time — toward newer candles.
     *
     * The gesture layer derives this from a drag: a leftward finger movement has a
     * negative `x`, and that should move forward in time, so it negates the pixel
     * delta before dividing by the candle width.
     */
    fun panBy(viewport: ChartViewport, deltaBars: Float, dataSize: Int): ChartViewport {
        if (deltaBars == 0f || !deltaBars.isFinite()) return viewport
        return viewport.copy(firstVisibleIndex = viewport.firstVisibleIndex + deltaBars)
            .clampedToData(dataSize)
    }

    /**
     * [viewport] scrolled to the newest bar, leaving the right gutter open.
     *
     * Used when a replay is live: the chart follows the price until the user pans
     * away, at which point following stops.
     */
    fun scrollToLatest(viewport: ChartViewport, dataSize: Int): ChartViewport =
        viewport.scrolledToEnd(dataSize)

    /**
     * True when the viewport is close enough to the newest bar to be considered
     * "following" it.
     *
     * Compared against the same bound [ChartViewport.scrolledToEnd] produces, so
     * the two cannot disagree about what "at the latest" means.
     */
    fun isAtLatest(viewport: ChartViewport, dataSize: Int): Boolean {
        if (dataSize <= 0) return true
        return abs(viewport.firstVisibleIndex - viewport.maxFirstVisibleIndex(dataSize)) <= FOLLOW_TOLERANCE_BARS
    }

    /**
     * A discrete zoom, for a double-tap or a zoom button.
     *
     * [steps] uses the same sense as [zoomBy]: a positive value widens the window
     * (zooms out), a negative value narrows it (zooms in). The sign is stated
     * because an earlier version used the opposite convention and the two methods
     * then disagreed, which is the kind of thing a caller cannot see from the name.
     */
    fun zoomStep(
        viewport: ChartViewport,
        steps: Int,
        dataSize: Int,
        limits: ChartZoomLimits = defaultLimits,
    ): ChartViewport {
        if (steps == 0) return viewport
        val factor = Math.pow(ZOOM_STEP_FACTOR.toDouble(), steps.toDouble()).toFloat()
        return zoomBy(viewport, factor, anchorFraction = 0.5f, dataSize = dataSize, limits = limits)
    }

    /** The zoom level as a whole number of bars, for display. */
    fun barsVisibleRounded(viewport: ChartViewport): Int = viewport.barsVisible.roundToInt()

    companion object {
        /** Zoomed in far enough to see about three candles. */
        const val MIN_BARS_VISIBLE: Float = 3f

        /** Zoomed out far enough for years of daily candles to fit a phone. */
        const val MAX_BARS_VISIBLE: Float = 20_000f

        /** Multiplier applied per discrete zoom step. */
        const val ZOOM_STEP_FACTOR: Float = 1.25f

        /** How close to the live edge counts as following it, in bars. */
        const val FOLLOW_TOLERANCE_BARS: Float = 1f
    }
}
