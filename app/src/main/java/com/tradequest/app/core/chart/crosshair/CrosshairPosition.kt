package com.tradequest.app.core.chart.crosshair

/**
 * Where the crosshair sits (Step 9.5).
 *
 * # Why this is stored in data space, not pixels
 *
 * A crosshair held at a pixel would slide off its candle the moment the chart was
 * re-laid-out — a rotation, a resize, or the axis gutter changing width. Storing the
 * *bar index* and the *price* means the crosshair stays on the candle it was placed
 * on, and the renderer converts to pixels with the same transformer everything else
 * uses. It also means the horizontal line survives a manual price scale (Step 9.7),
 * because the price it points at is what was recorded, not the pixel that price
 * happened to occupy.
 *
 * [priceUnits] is a `Float` because it is a pointer position rather than an
 * authoritative price: the authoritative price of the candle under the crosshair is
 * the integer in the buffer, which [CrosshairReadout] reads.
 */
data class CrosshairPosition(
    /** The bar index the vertical line sits on. */
    val index: Int,
    /** The price the horizontal line sits at, in price units. */
    val priceUnits: Float,
)
