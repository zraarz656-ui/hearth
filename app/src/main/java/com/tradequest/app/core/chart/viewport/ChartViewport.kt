package com.tradequest.app.core.chart.viewport

/**
 * The horizontal window onto a candle series (Step 8.3).
 *
 * # What "right padding" is for
 *
 * A chart that pins its last candle to the right edge leaves no room to see
 * where the price is going, and no room to place an order line in front of the
 * market. Real terminals leave a gutter of empty chart to the right of the last
 * candle. [rightPaddingBars] is that gutter, measured in bars so it scales with
 * zoom instead of changing meaning when the user pinches.
 *
 * # Why the values are `Float` and not money
 *
 * [barsVisible] is a count, [candleWidthPx] is a pixel measurement. Neither is
 * money, price, lot size or PnL, so `docs/architecture.md` permits `Float` here.
 * Prices stay integers in [CandleBuffer]; this class never holds one.
 */
data class ChartViewport(
    /** How many bars fit across the plot area. Smaller means more zoomed in. */
    val barsVisible: Float,
    /**
     * Index of the leftmost visible bar, as a fraction.
     *
     * Fractional because a smooth pan moves by pixels, not whole bars. The value
     * may fall outside the data: the chart shows empty space rather than refusing
     * to scroll, which is what a real terminal does when the user drags past the
     * edge. The amount is bounded by [OVERSCROLL_FRACTION] through
     * [clampedToData], so a fling cannot leave the user staring at a blank screen
     * with no way back.
     */
    val firstVisibleIndex: Float,
    /** Empty bars held open to the right of the newest candle. */
    val rightPaddingBars: Float,
    /**
     * Whether the price axis follows the visible extremes.
     *
     * Part of this class rather than a separate one because it is part of "what
     * the chart is showing", and a viewport that could disagree with its own
     * scale mode would be a bug waiting to happen.
     */
    val autoScale: Boolean = true,
) {

    /**
     * The rightmost visible index, exclusive.
     *
     * A `Float`, so it is a boundary rather than an index; callers take the
     * integer window from [visibleWindow].
     */
    val lastVisibleIndex: Float get() = firstVisibleIndex + barsVisible

    /**
     * The integer bar window to draw, padded by [bufferBars] on each side.
     *
     * # Why the result is clamped to the data
     *
     * The visible window is allowed to run past either end of the data, because
     * the user can scroll into empty space. The *drawn* window must not, because
     * the renderer indexes arrays with it. Clamping here means the renderer
     * never has to check bounds per candle.
     *
     * Returns an empty range when the data is empty or the window misses it
     * entirely, which the renderer treats as "draw nothing".
     */
    fun visibleWindow(dataSize: Int, bufferBars: Int = DEFAULT_BUFFER_BARS): IntRange {
        if (dataSize <= 0) return IntRange.EMPTY

        val from = kotlin.math.floor(firstVisibleIndex).toInt() - bufferBars
        // lastVisibleIndex is exclusive, so the last bar actually on screen is one
        // less than its ceiling. Adding the buffer to the exclusive boundary
        // directly would draw one bar more than the buffer promises.
        val lastOnScreen = kotlin.math.ceil(lastVisibleIndex).toInt() - 1
        val to = lastOnScreen + bufferBars

        val clampedFrom = from.coerceAtLeast(0)
        val clampedTo = to.coerceAtMost(dataSize - 1)

        return if (clampedFrom > clampedTo) IntRange.EMPTY else clampedFrom..clampedTo
    }

    /**
     * The viewport showing the newest [dataSize] candles, with the gutter open.
     *
     * Used when a chart opens and whenever the user asks to jump to the live
     * edge, which is the common case in a replay.
     */
    fun scrolledToEnd(dataSize: Int): ChartViewport {
        if (dataSize <= 0) return this
        return copy(firstVisibleIndex = maxFirstVisibleIndex(dataSize))
    }

    /**
     * The largest [firstVisibleIndex] that still leaves the newest candle in view.
     *
     * Exposed so gesture code does not re-derive the formula; an earlier version
     * of the controller had its own copy and drifted out of step with this class.
     */
    fun maxFirstVisibleIndex(dataSize: Int): Float {
        if (dataSize <= 0) return firstVisibleIndex
        return (dataSize - 1).toFloat() + rightPaddingBars - barsVisible + 1f
    }

    /**
     * The smallest [firstVisibleIndex] a pan may reach.
     *
     * Negative, so the user can drag a little past the first candle — which is how
     * a chart tells them they have reached the start — but not into unbounded
     * emptiness. The allowance is [OVERSCROLL_FRACTION] of the visible width, so
     * it scales with zoom: at any zoom the user can overscroll by the same
     * fraction of a screenful.
     */
    fun minFirstVisibleIndex(): Float = -barsVisible * OVERSCROLL_FRACTION

    /**
     * Clamps [firstVisibleIndex] into the scrollable range.
     *
     * # Why both ends are clamped
     *
     * Without the right clamp a fling past the data leaves an empty screen. Without
     * the left clamp it does the same in the other direction, which is just as
     * confusing. Clamping both, with a bounded overscroll so the edge is still
     * felt, means a user can always drag back to data.
     */
    fun clampedToData(dataSize: Int): ChartViewport {
        if (dataSize <= 0) return this

        val min = minFirstVisibleIndex()
        val max = maxFirstVisibleIndex(dataSize)

        // A series narrower than the visible window inverts the two bounds: the
        // index that puts the newest candle at the right edge lies *left* of the
        // smallest a pan may reach, so the range is empty and `coerceIn` throws.
        // That is the normal state at the start of a season, when a handful of
        // candles exist and the view still shows a full screenful. There is
        // nothing to scroll, so pin to the right bound: the newest candle stays
        // in view with the empty space to its left, which is what the live edge
        // of a chart looks like.
        val clamped = if (max <= min) max else firstVisibleIndex.coerceIn(min, max)
        return if (clamped == firstVisibleIndex) this else copy(firstVisibleIndex = clamped)
    }

    /**
     * The viewport a chart opens with for a series of [dataSize] bars.
     *
     * # Why the default window is not always right
     *
     * [DEFAULT_BARS_VISIBLE] is a good opening window for a long series, but a series
     * *shorter* than it has nothing to fill the window with. A 4-bar weekly series
     * opened at `first=-111.00` — exactly the right-hand clamp — which on a phone puts
     * four candles in the last 3% of the plot with the rest of the screen empty, and a
     * five-bar gutter wider than the entire dataset. Reported from a device on W1, D1
     * and H4 (4, 16 and 91 bars), each exactly on the clamp.
     *
     * Fitting first and scrolling second means a short series opens showing all of
     * itself, still at its live edge.
     */
    fun openingFor(dataSize: Int): ChartViewport = fittedToData(dataSize).scrolledToEnd(dataSize)

    /**
     * The same viewport with its window narrowed when the series is too short to fill
     * it.
     *
     * # Why this is not [clampedToData]
     *
     * [clampedToData] only moves the window horizontally; it cannot make the candles
     * wider. A 120-bar window over 4 bars is not a position problem, it is a zoom
     * problem, so the zoom is what has to give.
     *
     * # Why the gutter shrinks too
     *
     * [rightPaddingBars] exists to read the current price against the axis, and five
     * bars is right on a full screen. On a 4-bar series it is larger than the data, so
     * the gutter alone would be more than half the chart. It is capped at a quarter of
     * the series, keeping a proportional margin instead of a screenful of nothing.
     *
     * # Why the caller chooses when to apply it
     *
     * Fitting on every frame would fight the user: a pinch-out would be undone by the
     * next frame, so zooming out would simply stop working. It is applied where a
     * series is *introduced* — an opening view, or a timeframe switch — and never to a
     * viewport the user has since adjusted.
     */
    fun fittedToData(dataSize: Int): ChartViewport {
        if (dataSize <= 0) return this

        val padding = minOf(rightPaddingBars, maxOf(1f, dataSize / 4f))
        val needed = dataSize + padding
        if (barsVisible <= needed) {
            // Wide enough already; only the gutter may still need shrinking.
            return if (padding == rightPaddingBars) this else copy(rightPaddingBars = padding)
        }
        return copy(barsVisible = maxOf(needed, MIN_BARS_VISIBLE), rightPaddingBars = padding)
    }

    companion object {
        /**
         * Extra bars drawn outside the visible window.
         *
         * Two, so a partially-visible candle at each edge is complete. Any more
         * is work the user cannot see; any fewer leaves a gap when a candle's
         * body straddles the edge.
         */
        const val DEFAULT_BUFFER_BARS: Int = 2

        /** Narrowest a candle is allowed to get, so bodies never vanish entirely. */
        const val MIN_CANDLE_WIDTH_PX: Float = 0.5f

        /** Widest a candle is allowed to get, so one candle never fills the screen. */
        const val MAX_CANDLE_WIDTH_PX: Float = 64f

        /**
         * How far past the data a pan may reach, as a fraction of the visible
         * width. A third of a screenful: enough to feel the edge, not enough to
         * lose the chart.
         */
        const val OVERSCROLL_FRACTION: Float = 1f / 3f

        /** Fewest bars that can be shown, which is the most zoomed-in state. */
        const val MIN_BARS_VISIBLE: Float = 5f

        /** A sensible opening view: about two hours of one-minute candles. */
        const val DEFAULT_BARS_VISIBLE: Float = 120f

        /** Five bars of gutter, enough to read the current price against the axis. */
        const val DEFAULT_RIGHT_PADDING_BARS: Float = 5f

        val DEFAULT: ChartViewport = ChartViewport(
            barsVisible = DEFAULT_BARS_VISIBLE,
            firstVisibleIndex = 0f,
            rightPaddingBars = DEFAULT_RIGHT_PADDING_BARS,
        )
    }
}
