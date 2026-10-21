package com.tradequest.app.core.trading

/**
 * Deterministic gap execution (Step 5.11).
 *
 * # The problem
 *
 * Between two candles there is no path. Price does not travel from the old close
 * to the new open; it simply appears there. So a trigger whose level sits
 * *inside* the jump was crossed at no observable instant, and the plan's rule is
 * explicit: **do not assume fills occur at unavailable prices.**
 *
 * # The rule this phase uses
 *
 * A trigger is split into two kinds, and they behave differently across a gap.
 * The distinction is *which side the user is protected on*:
 *
 * - **Stop-like** — a stop order, or a position's stop-loss. These exist to get
 *   the user *out* at a level, and the market has moved against them. The fill
 *   uses **the first price actually available after the gap**, which is the next
 *   candle's open. That is worse than the trigger level, and that is correct: it
 *   is what happens in a real weekend gap, and hiding it would flatter every
 *   backtest.
 * - **Limit-like** — a limit order, or a position's take-profit. These rest at a
 *   price the user chose and the market has moved *through* in their favour. The
 *   fill uses **the trigger level itself**, because a resting order at that
 *   price was genuinely available and the market crossed it. Filling at the
 *   worse post-gap price instead would penalise the user for a gap that helped
 *   them.
 *
 * A gap fill never takes slippage on top. The gap price already contains the
 * adverse move; adding a slip would charge twice for the same event.
 */
internal object GapExecution {

    /** True when a trigger of this kind fills at the post-gap price rather than its own level. */
    fun isStopLike(kind: TriggerKind): Boolean = when (kind) {
        TriggerKind.STOP_LOSS, TriggerKind.PENDING_ORDER_STOP -> true
        TriggerKind.TAKE_PROFIT, TriggerKind.PENDING_ORDER_LIMIT -> false
    }

    /**
     * The bid price a trigger fills at, given the gap it fired across.
     *
     * [triggerLevelUnits] is the bid threshold; [gapEndBidUnits] is the bid
     * available at the first instant after the gap.
     */
    fun fillBidUnits(
        kind: TriggerKind,
        triggerLevelUnits: Int,
        gapEndBidUnits: Int,
    ): Int = if (isStopLike(kind)) gapEndBidUnits else triggerLevelUnits
}
