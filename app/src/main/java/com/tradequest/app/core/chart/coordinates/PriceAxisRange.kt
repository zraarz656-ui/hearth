package com.tradequest.app.core.chart.coordinates

/**
 * The vertical price window a chart is drawing (Step 8.7).
 *
 * `Float`, not `Int`, because these are pixel-geometry bounds rather than
 * authoritative prices. The authoritative price of any candle remains the
 * integer in [com.tradequest.app.core.market.HistoricalCandle]; this is a
 * window derived from them for drawing.
 *
 * [minUnits] is the price at the bottom of the plot and [maxUnits] at the top.
 * [maxUnits] is strictly greater than [minUnits], enforced at construction,
 * because a zero-height range would make the price-to-pixel transform a division
 * by zero.
 */
data class PriceAxisRange(
    val minUnits: Float,
    val maxUnits: Float,
) {
    /** Height of the window in price units. Always positive. */
    val spanUnits: Float get() = maxUnits - minUnits

    init {
        require(maxUnits > minUnits) {
            "price axis range must have positive height, got [$minUnits, $maxUnits]"
        }
        require(minUnits.isFinite() && maxUnits.isFinite()) {
            "price axis range must be finite, got [$minUnits, $maxUnits]"
        }
    }

    /** True when [priceUnits] lies inside the window, inclusive of both edges. */
    fun contains(priceUnits: Float): Boolean =
        priceUnits >= minUnits && priceUnits <= maxUnits

    /**
     * This window widened by [paddingFraction] of its own span at each end.
     *
     * Padding keeps an extreme candle's wick off the very edge of the plot,
     * where it is hard to see and where an axis label collides with the frame.
     */
    fun padded(paddingFraction: Float = DEFAULT_PADDING): PriceAxisRange {
        val pad = spanUnits * paddingFraction
        return PriceAxisRange(minUnits - pad, maxUnits + pad)
    }

    companion object {
        /** Padding added above and below the visible extremes, as a fraction of span. */
        const val DEFAULT_PADDING: Float = 0.08f

        /**
         * Smallest window height, in price units. A tenth of a pip.
         *
         * Only large enough to keep the transform off a division by zero. It is
         * deliberately *not* a round number of pips: an earlier version floored
         * the span at ten pips, which silently widened a tightly-zoomed chart by
         * a factor of ten and made padding behave in a way the caller could not
         * predict from the arguments.
         */
        const val MINIMUM_SPAN_UNITS: Float = 10f

        /**
         * A window covering `[low, high]` with [paddingFraction] of the span at
         * each end.
         *
         * A flat input — every candle at one price, or a single candle — has zero
         * span and cannot be padded into a drawable window, so it is widened to
         * [MINIMUM_SPAN_UNITS] around the price rather than collapsing to a
         * division by zero.
         */
        fun of(
            low: Float,
            high: Float,
            paddingFraction: Float = DEFAULT_PADDING,
        ): PriceAxisRange {
            require(low.isFinite() && high.isFinite()) {
                "price bounds must be finite, got low=$low high=$high"
            }
            val (lo, hi) = if (low <= high) low to high else high to low

            if (hi - lo < MINIMUM_SPAN_UNITS) {
                val centre = (lo + hi) / 2f
                return PriceAxisRange(
                    centre - MINIMUM_SPAN_UNITS / 2f,
                    centre + MINIMUM_SPAN_UNITS / 2f,
                )
            }

            val pad = (hi - lo) * paddingFraction
            return PriceAxisRange(lo - pad, hi + pad)
        }

        /** A window around a single price, for a chart with no visible candles. */
        fun around(priceUnits: Float): PriceAxisRange = PriceAxisRange(
            minUnits = priceUnits - MINIMUM_SPAN_UNITS / 2f,
            maxUnits = priceUnits + MINIMUM_SPAN_UNITS / 2f,
        )
    }
}
