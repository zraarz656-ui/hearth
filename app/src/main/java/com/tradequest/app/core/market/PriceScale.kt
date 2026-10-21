package com.tradequest.app.core.market

/**
 * Price scale constants for the whole application.
 *
 * Authoritative prices are integer **thousandths of a US dollar**, not cents.
 * XAUUSD quotes to three decimals, and 20.3% of real quotes are not
 * representable as whole cents. See `docs/adr-0001-price-scale.md`.
 *
 * Do not convert these values to cents, divide by 100, or introduce a
 * `*Cents` field. One scale, one constant, everywhere.
 */
object PriceScale {

    /** Integer price units per US dollar. A price of 2047.204 is stored as 2047204. */
    const val UNITS_PER_USD: Int = 1000

    /** Integer price units per pip. One pip = 0.10 USD. */
    const val UNITS_PER_PIP: Int = 100

    /** Integer price units per cent. Retained only for display conversion. */
    const val UNITS_PER_CENT: Int = 10

    /**
     * Converts a USD amount to price units, rounding half-up away from zero.
     *
     * Only use this for values that are not already exact at 3 decimals.
     * Values read from a 3-decimal source convert exactly.
     */
    fun usdToUnits(usd: Double): Int {
        val scaled = usd * UNITS_PER_USD
        return if (scaled >= 0) {
            kotlin.math.floor(scaled + 0.5).toInt()
        } else {
            kotlin.math.ceil(scaled - 0.5).toInt()
        }
    }

    /** Converts price units back to a USD value. Exact at 3 decimals. */
    fun unitsToUsd(units: Int): Double = units.toDouble() / UNITS_PER_USD

    /** Converts pips to price units. */
    fun pipsToUnits(pips: Int): Int = pips * UNITS_PER_PIP

    /** Converts price units to pips. */
    fun unitsToPips(units: Int): Int = units / UNITS_PER_PIP

    /** Formats price units as a 3-decimal USD string. */
    fun format(units: Int): String {
        val negative = units < 0
        val abs = kotlin.math.abs(units.toLong())
        val whole = abs / UNITS_PER_USD
        val frac = abs % UNITS_PER_USD
        val sign = if (negative) "-" else ""
        return "$sign$whole.${frac.toString().padStart(3, '0')}"
    }
}
