package com.tradequest.app.core.accounting

/**
 * Thrown when a monetary computation would overflow `Long`.
 *
 * # Why this is not silently clamped
 *
 * Clamping a balance produces a plausible-looking number that is wrong, and the
 * error then propagates into equity, margin and every risk decision made from
 * them. A stop-out computed from a clamped balance is worse than a crash: it
 * looks like it worked. So an overflow is a hard failure, and it is a bug rather
 * than a market condition.
 *
 * The plan's own example — a $30 move at one lot, 3e9 micro-dollars — already
 * exceeds `Int32`, which is why every intermediate here is `Long`. A `Long`
 * overflow needs about 9.2e12 USD, so this is reachable only through a defect:
 * an absurd lot size, a price far outside any real range, or a sign error that
 * turns a difference into a sum. All three are worth failing loudly on.
 */
class MoneyOverflowException(
    val operation: String,
    val left: Long,
    val right: Long,
) : ArithmeticException(
    "money overflow in $operation: $left and $right",
)

/**
 * Checked monetary arithmetic (Step 6.1).
 *
 * Every operation the accounting layer performs goes through here, so an
 * overflow is caught at the point it happens rather than appearing later as a
 * negative balance. The wrappers are deliberately thin: they exist to make the
 * check impossible to forget, not to add behaviour.
 *
 * Nothing in this file rounds. Price units and micro-dollars are both powers of
 * ten apart, so conversion is exact; if a caller needs rounding it belongs in
 * [PositionSizing] where the rule is explicit.
 */
object CheckedMath {

    /** `left + right`, failing on overflow. */
    fun add(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (e: ArithmeticException) {
        throw MoneyOverflowException("add", left, right)
    }

    /** `left - right`, failing on overflow. */
    fun subtract(left: Long, right: Long): Long = try {
        Math.subtractExact(left, right)
    } catch (e: ArithmeticException) {
        throw MoneyOverflowException("subtract", left, right)
    }

    /** `left * right`, failing on overflow. */
    fun multiply(left: Long, right: Long): Long = try {
        Math.multiplyExact(left, right)
    } catch (e: ArithmeticException) {
        throw MoneyOverflowException("multiply", left, right)
    }

    /**
     * `value * multiplier / divisor`, with the multiply widened before the
     * divide so no intermediate is computed at a narrower type.
     *
     * The plan requires this shape for money: dividing first would truncate, and
     * multiplying in `Int` would overflow. Both mistakes are silent.
     */
    fun multiplyDivide(value: Long, multiplier: Long, divisor: Long): Long {
        require(divisor != 0L) { "divisor cannot be zero" }
        return multiply(value, multiplier) / divisor
    }
}

/**
 * Account arithmetic for Phase 6: realised and unrealised PnL, equity, and the
 * definitions the risk rules are built from (Steps 6.3-6.4).
 *
 * # The one definition of equity
 *
 * ```
 * equity = balance + unrealised PnL
 * ```
 *
 * This is stated once here and used everywhere. Margin level, the daily-loss
 * floor and the static floor are all functions of equity, so a second definition
 * anywhere would make the risk rules disagree with each other about whether the
 * account was in trouble.
 *
 * # Entry commission and equity
 *
 * The plan asks explicitly that entry commissions not be double charged (Step
 * 6.4). The convention this codebase uses:
 *
 *  - The **entry** commission leaves the balance when the position opens. It is
 *    money already paid, so it is not "unrealised" and must not sit in equity as
 *    if it could come back.
 *  - The **exit** commission is a cost the position will incur. It is charged to
 *    the balance when the position actually closes.
 *
 * So equity is `balance + unrealised`, where the balance has already absorbed
 * the entry charge and will absorb the exit charge on close. At no point is a
 * commission counted twice, and at no point is one counted zero times.
 *
 * The consequence worth stating: a position opened and held at the entry price
 * shows equity `$7` per lot below the starting balance, because the entry half
 * of the round trip has genuinely been paid. That is correct, not a rounding
 * artefact.
 */
object AccountMath {

    /**
     * Realised PnL in micro-dollars for a closed quantity.
     *
     * [entryPriceUnits] and [exitPriceUnits] are both **executable** prices on the
     * correct sides, so the spread is already inside the difference. Adding a
     * separate spread term here would charge it twice — once in the prices, once
     * again in the formula. That is why there is no `spread` parameter.
     *
     * Slippage is likewise already reflected in the fill prices. It is carried on
     * the [com.tradequest.app.core.trading.Execution] for audit, not re-applied
     * here.
     */
    fun realizedPnlMicros(
        side: com.tradequest.app.core.trading.OrderSide,
        entryPriceUnits: Int,
        exitPriceUnits: Int,
        lotUnits: Int,
        ouncesPerLot: Int,
    ): Long {
        val delta = when (side) {
            com.tradequest.app.core.trading.OrderSide.BUY -> exitPriceUnits - entryPriceUnits
            com.tradequest.app.core.trading.OrderSide.SELL -> entryPriceUnits - exitPriceUnits
        }
        return MoneyScale.pnlMicrosForPriceDelta(delta, lotUnits, ouncesPerLot)
    }

    /**
     * Equity from a balance and an already-computed unrealised figure.
     *
     * Overflow is checked: equity feeds every risk decision, so a wrapped value
     * here would silently disable the stop-out.
     */
    fun equityMicros(balanceMicros: Long, unrealizedPnlMicros: Long): Long =
        CheckedMath.add(balanceMicros, unrealizedPnlMicros)

    /**
     * Net realised PnL: gross, less both halves of the commission.
     *
     * Used for reporting a closed trade, where the reader wants the true cost of
     * the round trip. It is **not** the figure to add to the balance — the entry
     * half is already gone by then. See `TradingEngine.closePartially`.
     */
    fun netPnlMicros(grossMicros: Long, commissionMicros: Long): Long =
        CheckedMath.subtract(grossMicros, commissionMicros)

    /**
     * True when [candidate] is at or below [floor], the form every loss limit in
     * this phase takes.
     *
     * "At or below" rather than "below": a floor of $9,000 is breached by an
     * equity of exactly $9,000. Treating the boundary as safe would let a
     * challenge sit one cent above failure indefinitely.
     */
    fun atOrBelow(candidate: Long, floor: Long): Boolean = candidate <= floor
}
