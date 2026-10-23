package com.tradequest.app.core.trading

/**
 * XAUUSD contract rules (Step 5.4).
 *
 * A value type rather than scattered constants, so the rules are inspectable
 * and a test can state what it is assuming. There is one instance, [XAUUSD];
 * the app trades nothing else (product rule 3.1).
 */
data class ContractSpec(
    /** Symbol, for display and for identifying a season's instrument. */
    val symbol: String,

    /** Troy ounces per lot. XAUUSD is 100. */
    val ouncesPerLot: Int,

    /** Minimum tradable size, in hundredths of a lot. `1` means 0.01 lots. */
    val minLotUnits: Int,

    /** Size increment, in hundredths of a lot. `1` means steps of 0.01 lots. */
    val lotStepUnits: Int,

    /** Maximum size in one order, in hundredths of a lot. */
    val maxLotUnits: Int,

    /** Leverage denominator. `100` means 1:100. */
    val leverage: Int,

    /** Price units per pip. One pip is 0.10 USD, so 100 units. */
    val unitsPerPip: Int,
) {

    /**
     * True when [lotUnits] is a size this contract accepts.
     *
     * Deliberately does not clamp. A caller that silently rounded a bad size
     * would fill an order the user did not place, so an invalid size is
     * rejected and the caller decides what to do.
     */
    fun isValidLotUnits(lotUnits: Int): Boolean =
        lotUnits >= minLotUnits &&
            lotUnits <= maxLotUnits &&
            lotUnits % lotStepUnits == 0

    /** Converts a pip distance to price units. */
    fun pipsToUnits(pips: Int): Int = pips * unitsPerPip

    /**
     * The smallest position that can be split in half and stay valid.
     *
     * A 50% partial close of `lotUnits` leaves `lotUnits - floor(lotUnits / 2)`
     * and `floor(lotUnits / 2)`. Both halves must satisfy [isValidLotUnits], so
     * this is the smallest size for which `floor(n / 2) >= minLotUnits` and
     * `floor(n / 2) % lotStepUnits == 0`. With 0.01 minimum and 0.01 step that
     * is 0.02 lots: a 0.01-lot position cannot be halved at all, which is a
     * real case the UI must handle (Step 5.12).
     */
    val minLotUnitsForHalfClose: Int
        get() {
            var candidate = minLotUnits * 2
            while (candidate % lotStepUnits != 0) candidate += minLotUnits
            return candidate
        }

    companion object {
        /**
         * XAUUSD as the plan specifies (Step 5.4).
         *
         * Maximum size is 100 lots. The plan does not state a maximum; one is
         * needed because the margin and notional arithmetic has to be bounded
         * somewhere, and 100 lots of gold is already far beyond any prop-firm
         * limit. Recorded here rather than hidden in a validation branch.
         */
        val XAUUSD: ContractSpec = ContractSpec(
            symbol = "XAUUSD",
            ouncesPerLot = 100,
            minLotUnits = 1,
            lotStepUnits = 1,
            maxLotUnits = 10_000,
            leverage = 100,
            unitsPerPip = 100,
        )
    }
}
