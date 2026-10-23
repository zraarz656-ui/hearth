package com.tradequest.app.core.accounting

/**
 * The money scale, and the one place price units become money.
 *
 * Account money is integer **micro-dollars** (`Long`), per Step 6.1 and
 * `docs/architecture.md`. Price is integer thousandths of a USD. Both are powers
 * of ten, so every conversion here is **exact**: there is no rounding anywhere in
 * this file, and there must never be. If a rounding step is ever needed in this
 * path, a scale is wrong.
 *
 * Phase 5 owns execution mechanics and therefore needs the *inputs* to
 * accounting: a fill's money value and its commission. Phase 6 owns the rest —
 * equity, margin, stop-out and daily-loss rules — and builds on this type.
 */
object MoneyScale {

    /** Micro-dollars per US dollar. `$10,000.00` is `10_000_000_000`. */
    const val MICROS_PER_USD: Long = 1_000_000L

    /** Price units per US dollar: thousandths. */
    const val PRICE_UNITS_PER_USD: Long = 1_000L

    /** Lot units per lot: hundredths. `0.20` lots is `20`. */
    const val LOT_UNITS_PER_LOT: Long = 100L

    /**
     * Money value of a price move on a position.
     *
     * ```
     * micros = deltaUnits * lotUnits * OUNCES_PER_LOT * MICROS_PER_USD
     *          / (PRICE_UNITS_PER_USD * LOT_UNITS_PER_LOT)
     * ```
     *
     * With XAUUSD's 100 oz per lot this reduces to
     * `deltaUnits * lotUnits * 1000`, but it is written in full so the
     * derivation stays visible and a different contract size cannot silently
     * reuse the shortcut.
     *
     * The multiply happens in `Long` **before** any division, per the plan's
     * overflow rule: at 1 lot a 30 USD move is 3e9 micro-dollars, which exceeds
     * Int32, and the overflow would occur during the multiply.
     */
    fun pnlMicrosForPriceDelta(
        deltaUnits: Int,
        lotUnits: Int,
        ouncesPerLot: Int,
    ): Long {
        val numerator = deltaUnits.toLong() * lotUnits.toLong() *
            ouncesPerLot.toLong() * MICROS_PER_USD
        val denominator = PRICE_UNITS_PER_USD * LOT_UNITS_PER_LOT
        return numerator / denominator
    }

    /** Exact conversion of a price to micro-dollars. No rounding: 1000 is a factor of 1e6. */
    fun priceUnitsToMicros(priceUnits: Int): Long =
        priceUnits.toLong() * (MICROS_PER_USD / PRICE_UNITS_PER_USD)

    /** Formats micro-dollars as a signed 2-decimal USD string. */
    fun formatMicros(micros: Long): String {
        val negative = micros < 0
        val abs = kotlin.math.abs(micros)
        val whole = abs / MICROS_PER_USD
        val frac = abs % MICROS_PER_USD
        val cents = frac / (MICROS_PER_USD / 100)
        val sign = if (negative) "-" else ""
        return "$sign$whole.${cents.toString().padStart(2, '0')}"
    }
}

/**
 * Commission, per Step 5.7.
 *
 * $7 per lot round trip, charged proportionally at entry and exit. Splitting it
 * in half at each end is what makes a partial close behave correctly: closing
 * half a position must refund nothing and charge only the half it closes.
 *
 * The schedule is a value rather than constants so a test can use a different
 * rate without touching production code, and so Phase 6 can price a different
 * instrument without editing this file.
 */
data class CommissionSchedule(
    /** Round-trip commission for one lot, in micro-dollars. */
    val roundTripMicrosPerLot: Long,
) {

    /** Commission charged on one side of a trade of [lotUnits] hundredths. */
    fun perSideMicros(lotUnits: Int): Long =
        roundTripMicrosPerLot / 2L * lotUnits.toLong() / MoneyScale.LOT_UNITS_PER_LOT

    /** Full round-trip commission for [lotUnits] hundredths. */
    fun roundTripMicros(lotUnits: Int): Long =
        roundTripMicrosPerLot * lotUnits.toLong() / MoneyScale.LOT_UNITS_PER_LOT

    companion object {
        /** The plan's rate: $7 per lot round trip. */
        val STANDARD: CommissionSchedule = CommissionSchedule(
            roundTripMicrosPerLot = 7L * MoneyScale.MICROS_PER_USD,
        )
    }
}
