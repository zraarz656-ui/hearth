package com.tradequest.app.core.trading

/**
 * Deterministic slippage (Step 5.8).
 *
 * # What the plan asks for
 *
 * Normal conditions: $0.00. During a high-impact news window: $0.20-$1.00
 * *adverse*, from "deterministic seeded behavior".
 *
 * # What "deterministic seeded" means here
 *
 * The value is a pure function of the order's identity and the instant it
 * filled — never of a random number generator, and never of wall-clock time.
 * The same replay produces the same slippage on every device, which is the
 * point: product rule 2.4 requires identical replay outcomes, and a `Random`
 * would break that the moment the seed differed.
 *
 * The hash is FNV-1a over the order id and fill instant, the same published
 * algorithm the path fingerprint uses. Its output is mapped onto
 * `[MIN_UNITS, MAX_UNITS]` inclusive.
 *
 * # Which executions receive slippage
 *
 * Only fills whose instant falls inside a high-impact news window, and only
 * **market-type fills**: a market order, and a stop or stop-loss that is
 * triggered. A *limit* order does not slip adversely by construction — it
 * cannot fill worse than its limit, because if price were worse it would not
 * have filled at all. [slippageUnitsFor] encodes that distinction rather than
 * leaving it to each caller to remember.
 *
 * # Gap fills do not slip
 *
 * A fill that happens across a gap already uses the gap's real open price
 * (Step 5.11), which is typically far worse than a slip would be. Adding
 * slippage on top would double-count the same adverse move, so gap fills take
 * the gap price and no slip.
 */
object SlippageModel {

    /** $0.20 in price units. */
    const val MIN_UNITS: Int = 200

    /** $1.00 in price units. */
    const val MAX_UNITS: Int = 1_000

    private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L
    private const val FNV_PRIME: Long = 1099511628211L

    /**
     * The adverse slippage, in price units, for a fill.
     *
     * Returns a non-negative magnitude; the caller applies the direction, which
     * depends on the side. Zero when the instant is not inside a news window, or
     * when the fill type does not slip.
     */
    fun slippageUnitsFor(
        simUtcMs: Long,
        orderId: String,
        type: OrderType,
        newsReleaseTimesUtcMs: Collection<Long>,
        inGap: Boolean = false,
    ): Int {
        if (inGap) return 0
        if (!SpreadModel.isInNewsWindow(simUtcMs, newsReleaseTimesUtcMs)) return 0

        // A limit order cannot fill worse than its limit. Treating it as
        // slipping would produce a fill price the user never agreed to.
        val slips = when (type) {
            OrderType.MARKET, OrderType.STOP -> true
            OrderType.LIMIT -> false
        }
        if (!slips) return 0

        val span = MAX_UNITS - MIN_UNITS
        val hash = hashOf(orderId, simUtcMs)
        // `ushr` keeps the value non-negative; Long.MIN_VALUE is handled because
        // the shift discards the sign bit.
        val bucket = (hash ushr 1) % (span + 1L)
        return MIN_UNITS + bucket.toInt()
    }

    /**
     * Applies [slippageUnits] adversely to [priceUnits] for [side].
     *
     * Adverse means the buyer pays more and the seller receives less. Direction
     * lives here, next to the magnitude, so the two cannot be paired wrongly.
     */
    fun applyAdverse(priceUnits: Int, slippageUnits: Int, side: OrderSide): Int =
        when (side) {
            OrderSide.BUY -> priceUnits + slippageUnits
            OrderSide.SELL -> priceUnits - slippageUnits
        }

    private fun hashOf(orderId: String, simUtcMs: Long): Long {
        var hash = FNV_OFFSET_BASIS
        for (byte in orderId.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toLong() and 0xFFL)) * FNV_PRIME
        }
        var remaining = simUtcMs
        repeat(Long.SIZE_BYTES) {
            hash = (hash xor (remaining and 0xFFL)) * FNV_PRIME
            remaining = remaining ushr 8
        }
        return hash
    }
}
