package com.tradequest.app.core.chart.gestures

/**
 * How far the chart may be zoomed, expressed as the candle widths it allows
 * (Step 9.4).
 *
 * # Why the limits are in candle width and not in bars
 *
 * `barsVisible` is not a zoom level a user would recognise: "60 bars" means a very
 * different thing on a phone and on a tablet, and it changes meaning when the
 * window is resized or the device rotates. Candle width in dp is the measurement
 * the eye actually reads — how fat a candle is — so it is the one that should be
 * clamped. The spec's range is 2dp to 40dp: below 2dp the bodies are sub-pixel and
 * the chart reads as a smear, above 40dp a single candle fills the screen and the
 * chart stops being a chart.
 *
 * # Why the conversion needs the plot width
 *
 * `barsVisible = plotWidthPx / candleWidthPx`, so the bar limits depend on how wide
 * the plot is — which the core layer cannot know. [fromPlot] takes the width in
 * pixels and the two candle widths in pixels (the caller converts dp, because dp is
 * a Compose concern and this package has no Android dependency), and returns the
 * bar limits to clamp against.
 *
 * A caller that does not know its width yet uses [FALLBACK], which are the historic
 * bar-based limits; that keeps a first frame before layout from clamping the zoom
 * to something absurd.
 */
data class ChartZoomLimits(
    /** Fewest bars on screen, which is the most zoomed-in state. */
    val minBarsVisible: Float,
    /** Most bars on screen, which is the most zoomed-out state. */
    val maxBarsVisible: Float,
) {

    init {
        require(minBarsVisible > 0f) { "minBarsVisible must be positive, was $minBarsVisible" }
        require(maxBarsVisible >= minBarsVisible) {
            "maxBarsVisible ($maxBarsVisible) must not be below minBarsVisible ($minBarsVisible)"
        }
    }

    companion object {
        /** Narrowest a candle may get, in dp. Below this the bodies are sub-pixel. */
        const val MIN_CANDLE_WIDTH_DP: Float = 2f

        /** Widest a candle may get, in dp. Above this one candle fills the screen. */
        const val MAX_CANDLE_WIDTH_DP: Float = 40f

        /**
         * The bar limits for a plot [plotWidthPx] wide, with candle widths in pixels.
         *
         * Returns [FALLBACK] when the inputs cannot produce a usable range — a plot
         * that has not been measured, or a non-positive candle width. Falling back
         * rather than throwing keeps the first frame before layout working, and the
         * clamp it applies is the same one the chart used before this existed.
         */
        fun fromPlot(
            plotWidthPx: Float,
            minCandleWidthPx: Float,
            maxCandleWidthPx: Float,
        ): ChartZoomLimits {
            if (!plotWidthPx.isFinite() || plotWidthPx <= 0f) return FALLBACK
            if (!minCandleWidthPx.isFinite() || minCandleWidthPx <= 0f) return FALLBACK
            if (!maxCandleWidthPx.isFinite() || maxCandleWidthPx <= 0f) return FALLBACK
            if (maxCandleWidthPx < minCandleWidthPx) return FALLBACK

            // A narrow candle fits more bars; a wide candle fits fewer.
            val maxBars = plotWidthPx / minCandleWidthPx
            val minBars = plotWidthPx / maxCandleWidthPx
            if (!minBars.isFinite() || !maxBars.isFinite()) return FALLBACK
            if (minBars <= 0f || maxBars < minBars) return FALLBACK

            return ChartZoomLimits(minBarsVisible = minBars, maxBarsVisible = maxBars)
        }

        /**
         * The limits used before a plot width is known.
         *
         * The historic bar-based bounds, kept so a pre-layout frame behaves exactly
         * as it did before Step 9.4 rather than clamping to a value derived from a
         * zero width.
         */
        val FALLBACK: ChartZoomLimits = ChartZoomLimits(
            minBarsVisible = ChartGestureController.MIN_BARS_VISIBLE,
            maxBarsVisible = ChartGestureController.MAX_BARS_VISIBLE,
        )
    }
}
