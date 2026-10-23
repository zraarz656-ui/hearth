package com.tradequest.app.core.trading

import com.tradequest.app.core.accounting.CheckedMath
import com.tradequest.app.core.accounting.MoneyScale

/**
 * Position-level money math: unrealised PnL, margin, and liquidation ordering
 * (Steps 6.3-6.6, 6.8).
 *
 * # Why this lives in `core.trading` and not `core.risk`
 *
 * It is needed on both sides of the risk boundary. The engine has to mark
 * positions and compute equity on every segment, and the risk policy has to do
 * the same to decide anything. `core.risk` already depends on `core.trading`, so
 * putting this here keeps that dependency one-way; putting it in `core.risk`
 * would force the engine to import upward and create a cycle.
 *
 * The split that results is clean: this object knows what a position is *worth*
 * and what it *reserves*, while `core.risk` knows when those figures have
 * crossed a rule's threshold. Arithmetic here, thresholds there.
 */
object MarginMath {

    /**
     * Unrealised PnL of one position at an exit-side price (Step 6.3).
     *
     * [exitPriceUnits] is the price a close would actually get — the bid for a
     * long, the ask for a short — so the spread is already inside the difference
     * and must not be added again.
     */
    fun unrealizedPnl(
        position: Position,
        exitPriceUnits: Int,
        contract: ContractSpec,
    ): Long {
        val delta = when (position.side) {
            OrderSide.BUY -> exitPriceUnits - position.openPriceUnits
            OrderSide.SELL -> position.openPriceUnits - exitPriceUnits
        }
        return MoneyScale.pnlMicrosForPriceDelta(
            deltaUnits = delta,
            lotUnits = position.remainingVolumeHundredths,
            ouncesPerLot = contract.ouncesPerLot,
        )
    }

    /** Total unrealised PnL across the open positions, at their marks. */
    fun totalUnrealizedPnl(
        positions: List<Position>,
        markOf: (Position) -> Int,
        contract: ContractSpec,
    ): Long {
        var total = 0L
        for (position in positions) {
            if (!position.isOpen) continue
            total = CheckedMath.add(total, unrealizedPnl(position, markOf(position), contract))
        }
        return total
    }

    /**
     * Margin reserved by one position (Step 6.5).
     *
     * ```
     * margin = executable price * ounces per lot * lots / leverage
     * ```
     *
     * The price is the position's **entry** price — the ask for a long, the bid
     * for a short — because that is what re-opening it would cost, and margin is
     * the broker's cost of carrying it. Using the exit side would understate a
     * long's margin by one spread.
     *
     * Only the **remaining** size is margined, so a half-closed position reserves
     * half the margin. That is why a partial close frees buying power.
     */
    fun marginMicrosFor(
        entryPriceUnits: Int,
        lotUnits: Int,
        ouncesPerLot: Int,
        leverage: Int,
    ): Long {
        require(leverage > 0) { "leverage must be positive" }
        require(lotUnits >= 0) { "lot units cannot be negative" }
        val numerator = CheckedMath.multiply(
            entryPriceUnits.toLong(),
            CheckedMath.multiply(ouncesPerLot.toLong(), lotUnits.toLong()),
        )
        val scaled = CheckedMath.multiply(numerator, MoneyScale.MICROS_PER_USD)
        val denominator = CheckedMath.multiply(
            MoneyScale.PRICE_UNITS_PER_USD,
            CheckedMath.multiply(MoneyScale.LOT_UNITS_PER_LOT, leverage.toLong()),
        )
        return scaled / denominator
    }

    /** Margin reserved by one position, at its own size and entry price. */
    fun marginMicrosFor(position: Position, contract: ContractSpec): Long =
        marginMicrosFor(
            entryPriceUnits = position.openPriceUnits,
            lotUnits = position.remainingVolumeHundredths,
            ouncesPerLot = contract.ouncesPerLot,
            leverage = contract.leverage,
        )

    /** Total margin reserved by a set of positions. */
    fun usedMarginMicros(positions: List<Position>, contract: ContractSpec): Long {
        var used = 0L
        for (position in positions) {
            if (!position.isOpen) continue
            used = CheckedMath.add(used, marginMicrosFor(position, contract))
        }
        return used
    }

    /**
     * Margin level: equity over used margin, times 100 (Step 6.6).
     *
     * Returns **null** for zero used margin rather than zero or infinity. With
     * nothing open there is no level, and returning one would either trip the
     * warning immediately (if 0) or never (if infinite). The plan asks for zero
     * used margin to be handled explicitly, and this is how.
     */
    fun marginLevelPercent(equityMicros: Long, usedMarginMicros: Long): Long? =
        if (usedMarginMicros == 0L) null else equityMicros * 100L / usedMarginMicros

    /**
     * The open positions worst unrealised loser first (Step 6.8).
     *
     * "Largest loser" is the most negative unrealised PnL, so a position at
     * `-$500` precedes one at `-$100`, and both precede a winner. Ties break by
     * position id so a replay liquidates in a stable order rather than following
     * list order.
     *
     * Winners are included: a stop-out repeats until the margin level is restored
     * and may have to close a profitable position to get there.
     */
    fun stopOutOrder(
        positions: List<Position>,
        markOf: (Position) -> Int,
        contract: ContractSpec,
    ): List<Position> = positions
        .filter { it.isOpen }
        .sortedWith(
            compareBy(
                { unrealizedPnl(it, markOf(it), contract) },
                { it.positionId },
            ),
        )
}
