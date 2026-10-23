package com.tradequest.app.core.market

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Converts decimal price text to integer price units without floating point.
 *
 * Parsing through `Double` would introduce binary rounding error before the
 * scaling multiply, so every price goes through [BigDecimal]. A value with 3
 * or fewer decimals converts exactly; anything finer is rounded half-up away
 * from zero and the reduction is reported, never silent.
 */
object PriceParser {

    /** Default scale of Dukascopy XAUUSD data. */
    const val SOURCE_DECIMALS = 3

    /**
     * Parses decimal price text into price units.
     *
     * @throws NumberFormatException if [text] is not a valid decimal number.
     */
    fun parseToUnits(text: String): Int = parseToUnitsDetailed(text).units

    /**
     * Parses decimal price text, reporting whether precision was reduced.
     *
     * @param sourceDecimals decimals the source claims to carry. Values with
     *   more decimals than this are rounded and flagged.
     */
    fun parseToUnitsDetailed(
        text: String,
        sourceDecimals: Int = SOURCE_DECIMALS,
    ): ParsedPrice {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "empty price value" }

        val decimal = BigDecimal(trimmed)
        val scaled = decimal.movePointRight(SOURCE_DECIMALS)
        val rounded = scaled.setScale(0, RoundingMode.HALF_UP)
        val units = rounded.intValueExact()

        val actualDecimals = maxOf(decimal.stripTrailingZeros().scale(), 0)
        val reduced = actualDecimals > sourceDecimals

        return ParsedPrice(units = units, precisionReduced = reduced, decimals = actualDecimals)
    }

    /**
     * Converts price units back to a canonical 3-decimal string.
     *
     * Used for hashing and CSV output, where a stable textual form matters.
     */
    fun unitsToCanonicalString(units: Int): String =
        BigDecimal(units).movePointLeft(SOURCE_DECIMALS)
            .setScale(SOURCE_DECIMALS, RoundingMode.UNNECESSARY)
            .toPlainString()

    /** Result of a price parse, including whether precision was lost. */
    data class ParsedPrice(
        val units: Int,
        val precisionReduced: Boolean,
        val decimals: Int,
    )
}
