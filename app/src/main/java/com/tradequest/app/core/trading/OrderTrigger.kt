package com.tradequest.app.core.trading

/** Which way the bid must move to satisfy a trigger. */
internal enum class TriggerDirection { RISING, FALLING }

/**
 * A condition expressed as a bid level (Steps 5.5, 5.6).
 *
 * # Why everything becomes a bid threshold
 *
 * Orders and positions trigger on the *ask* or the *bid* depending on their
 * side, but the only price the dataset stores is the bid. Since
 * `ask = bid + spread`, an ask condition can be rewritten as a bid condition
 * whenever the spread is known — and it is, because the engine splits time at
 * every spread change ([SpreadTimeline]).
 *
 * Doing the conversion in exactly one place is what stops two subsystems from
 * disagreeing about whether a stop was hit. It also makes the spread-jump case
 * fall out for free: when the spread widens, the bid threshold moves, and a
 * trigger that was not satisfied a moment ago may be satisfied now even though
 * the bid never moved. That is a real fill, and an engine comparing only bids
 * would miss it.
 */
internal data class BidTrigger(
    val thresholdUnits: Int,
    val direction: TriggerDirection,
) {
    /** True when the bid at [bidPriceUnits] already satisfies this trigger. */
    fun isSatisfiedAt(bidPriceUnits: Int): Boolean = when (direction) {
        TriggerDirection.RISING -> bidPriceUnits >= thresholdUnits
        TriggerDirection.FALLING -> bidPriceUnits <= thresholdUnits
    }
}

/**
 * What kind of thing wants to fire. The integer is a documented stable
 * priority for the case where two triggers land on the same millisecond
 * (Step 5.9).
 *
 * The order follows the plan's event sequence: pending-order triggers are
 * handled before stop-losses, which are handled before take-profits. Where the
 * plan is silent, the tie-break is "the older thing wins", which is stable and
 * easy to explain.
 */
internal enum class TriggerKind(val priority: Int) {
    /** A resting limit order reaching its level. Fills at its own level across a gap. */
    PENDING_ORDER_LIMIT(0),

    /** A resting stop order reaching its level. Fills at the post-gap price. */
    PENDING_ORDER_STOP(0),

    /** A position's stop-loss, including a trailing or breakeven stop. */
    STOP_LOSS(1),

    /** A position's take-profit. */
    TAKE_PROFIT(2),
    ;

    /** True for the kinds that fill at the price available after a gap. */
    val isStopLike: Boolean get() = GapExecution.isStopLike(this)

    /** True for the kinds that are resting orders rather than position exits. */
    val isOrderKind: Boolean
        get() = this == PENDING_ORDER_LIMIT || this == PENDING_ORDER_STOP
}

/** A trigger that is about to fire. */
internal data class ResolvedTrigger(
    val kind: TriggerKind,
    val id: String,
    val atSimUtcMs: Long,
    /** The bid level used, kept so the fill is auditable. */
    val trigger: BidTrigger,
    /** The spread in force at that instant. */
    val spreadUnits: Int,
    /** True when the level was jumped across a closure rather than traded through. */
    val inGap: Boolean,
    /**
     * The bid the fill actually uses.
     *
     * Equal to the trigger level for a normal fill. Across a gap it follows
     * [GapExecution]: the post-gap price for a stop-like trigger, the trigger
     * level for a limit-like one.
     */
    val fillBidUnits: Int,
)

/** Derives bid triggers from orders and positions. */
internal object TriggerDerivation {

    /**
     * The bid trigger for a pending order, or null for a market order.
     *
     * [spreadUnits] must be the spread in force at the instant being evaluated.
     */
    fun forOrder(
        order: TradeOrder,
        spreadUnits: Int,
        contract: ContractSpec,
    ): BidTrigger? {
        val requested = order.requestedPriceUnits ?: return null
        return when (order.type) {
            OrderType.MARKET -> null

            // A buy triggers on the ask, so the bid level is the requested price
            // less the spread. A buy limit sits below the market and fires as the
            // bid falls; a buy stop sits above and fires as the bid rises.
            OrderType.LIMIT -> when (order.side) {
                OrderSide.BUY -> BidTrigger(requested - spreadUnits, TriggerDirection.FALLING)
                OrderSide.SELL -> BidTrigger(requested, TriggerDirection.RISING)
            }

            // A sell triggers on the bid directly, so its level is the requested
            // price with no spread adjustment.
            OrderType.STOP -> when (order.side) {
                OrderSide.BUY -> BidTrigger(requested - spreadUnits, TriggerDirection.RISING)
                OrderSide.SELL -> BidTrigger(requested, TriggerDirection.FALLING)
            }
        }
    }

    /**
     * The bid trigger for a position's effective stop, or null when it has none.
     *
     * A long exits at the bid, so its stop is already a bid level. A short exits
     * at the ask, so its stop level is shifted by the spread.
     */
    fun forStop(
        position: Position,
        spreadUnits: Int,
    ): BidTrigger? {
        val stop = position.activeStopUnits ?: return null
        return when (position.side) {
            OrderSide.BUY -> BidTrigger(stop, TriggerDirection.FALLING)
            OrderSide.SELL -> BidTrigger(stop - spreadUnits, TriggerDirection.RISING)
        }
    }

    /** The bid trigger for a position's target, or null when it has none. */
    fun forTarget(
        position: Position,
        spreadUnits: Int,
    ): BidTrigger? {
        val target = position.targetUnits ?: return null
        return when (position.side) {
            OrderSide.BUY -> BidTrigger(target, TriggerDirection.RISING)
            OrderSide.SELL -> BidTrigger(target - spreadUnits, TriggerDirection.FALLING)
        }
    }
}

/**
 * The price a position's stop sits at, **on its exit side**.
 *
 * Stop distances are stored in pips, per the plan's rule, and converted here.
 *
 * # Why the side matters
 *
 * A long exits at the bid, so a long's stop is already a bid price and needs no
 * adjustment. A short exits at the **ask**, so a short's stop of "50 pips above
 * the entry" is 50 pips above the entry on the ask, which is one spread higher
 * than the equivalent bid level. Storing the raw level and comparing it against a
 * bid would make a short's stop trigger one spread early — and its target one
 * spread late, in the user's favour. Both would be wrong, and the second would
 * flatter every short backtest.
 *
 * Storing the exit-side level here means [TriggerDerivation] shifts it by the
 * spread once, when it builds a bid trigger, and the stored value stays meaningful
 * on its own.
 */
internal fun stopLevelFor(
    side: OrderSide,
    entryPriceUnits: Int,
    stopLossPips: Int,
    contract: ContractSpec,
): Int {
    val distance = contract.pipsToUnits(stopLossPips)
    return when (side) {
        OrderSide.BUY -> entryPriceUnits - distance
        OrderSide.SELL -> entryPriceUnits + distance
    }
}

/** The price a position's target sits at, in exit-side terms. */
internal fun targetLevelFor(
    side: OrderSide,
    entryPriceUnits: Int,
    takeProfitPips: Int,
    contract: ContractSpec,
): Int {
    val distance = contract.pipsToUnits(takeProfitPips)
    return when (side) {
        OrderSide.BUY -> entryPriceUnits + distance
        OrderSide.SELL -> entryPriceUnits - distance
    }
}
