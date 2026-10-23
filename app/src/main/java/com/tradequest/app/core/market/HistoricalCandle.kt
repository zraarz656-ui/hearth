package com.tradequest.app.core.market

/**
 * One minute of historical price data.
 *
 * Price fields are integer **thousandths of a USD** ([PriceScale.UNITS_PER_USD]),
 * not cents. See `docs/adr-0001-price-scale.md`.
 *
 * Invariants, enforced by [CandleValidator]:
 *  - `highUnits >= openUnits`, `highUnits >= closeUnits`
 *  - `lowUnits <= openUnits`, `lowUnits <= closeUnits`
 *  - `highUnits >= lowUnits`
 *  - all prices positive
 *  - `tickVolume >= 0`
 */
data class HistoricalCandle(
    /** UTC epoch milliseconds at the start of the minute. */
    val timestampUtcMs: Long,
    val openUnits: Int,
    val highUnits: Int,
    val lowUnits: Int,
    val closeUnits: Int,
    val tickVolume: Int,
) {
    val isBullish: Boolean get() = closeUnits > openUnits
    val isBearish: Boolean get() = closeUnits < openUnits
    val isDoji: Boolean get() = closeUnits == openUnits

    /** Range in price units. Always nonnegative for a valid candle. */
    val rangeUnits: Int get() = highUnits - lowUnits

    /** Body size in price units, ignoring direction. */
    val bodyUnits: Int get() = kotlin.math.abs(closeUnits - openUnits)

    /**
     * True when the candle carries no real trading activity.
     *
     * Dukascopy pads closed periods with flat, zero-volume rows. Such rows are
     * fabricated, not observed, and must be excluded on import.
     */
    val isFlatAndEmpty: Boolean
        get() = tickVolume == 0 &&
            openUnits == highUnits &&
            highUnits == lowUnits &&
            lowUnits == closeUnits

    /** Direction as a sign: +1 bullish, -1 bearish, 0 flat. */
    val direction: Int
        get() = when {
            closeUnits > openUnits -> 1
            closeUnits < openUnits -> -1
            else -> 0
        }
}
