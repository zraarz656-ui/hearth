package com.tradequest.app.core.trading

import com.tradequest.app.core.accounting.MoneyScale
import kotlin.math.abs

/**
 * Which line of a trade's bracket a chart is drawing (Steps 10.3, 10.4).
 *
 * The three are separate kinds rather than a generic "level" because a drag has
 * to know which field of the command it will write: entry moves the order's
 * price, while the other two move a pip distance from an entry that does not
 * move. Collapsing them would put that decision in the composable.
 */
enum class TradeLineKind {
    ENTRY,
    STOP_LOSS,
    TAKE_PROFIT,
}

/**
 * Which side of a trade's entry a level is expected to sit on.
 *
 * A stop is adverse to the position and a target is favourable, so the two legs of
 * one bracket lie on opposite sides of the entry. Naming the expectation lets the
 * distance helper return a positive number for a correctly-placed level and a
 * negative one for a level on the wrong side.
 */
enum class TradeDirection {
    /** The side the trade profits on: above entry for a buy, below for a sell. */
    FAVOURABLE,

    /** The side the trade loses on: below entry for a buy, above for a sell. */
    ADVERSE,
}

/**
 * One draggable line of a trade's bracket, in price units.
 *
 * [isDraggable] is carried rather than inferred. A market position's entry is
 * history — the fill already happened — so it cannot be dragged, while a pending
 * order's entry is a request that can. The UI reads this flag instead of
 * re-deriving the rule from the order type.
 */
data class TradeLine(
    val kind: TradeLineKind,
    val priceUnits: Int,
    val isDraggable: Boolean,
)

/**
 * The lines a chart draws for one open position or one pending order.
 *
 * # Why this is a domain type and not a renderer type
 *
 * The lines are decided by trading rules — which levels exist, whether the entry
 * is draggable — not by pixels. Keeping the decision here means the drag logic
 * and the drawing code read the same list, so a line that is drawn is a line that
 * can be dragged and vice versa. A renderer that built its own list would be able
 * to draw a handle nothing could grab.
 */
data class TradeOverlay(
    /** The position or order this overlay belongs to. */
    val id: String,
    val side: OrderSide,
    /** True for a pending order, false for an open position. */
    val isPending: Boolean,
    val lines: List<TradeLine>,
) {

    /** The entry line, if this overlay has one. */
    val entry: TradeLine? get() = lines.firstOrNull { it.kind == TradeLineKind.ENTRY }

    /** The stop line, if set. */
    val stop: TradeLine? get() = lines.firstOrNull { it.kind == TradeLineKind.STOP_LOSS }

    /** The target line, if set. */
    val target: TradeLine? get() = lines.firstOrNull { it.kind == TradeLineKind.TAKE_PROFIT }

    companion object {

        /**
         * The overlay for an open [position].
         *
         * The entry is not draggable: the fill is committed history, and letting a
         * drag rewrite it would change what the trader actually did. The stop and
         * target are draggable because both are forward-looking instructions.
         *
         * The stop and target prices come from [Position.activeStopUnits] and
         * [Position.targetUnits] rather than being recomputed from the pip
         * distances, because a trailing stop or a breakeven move changes the
         * active level without changing the pip distance the position was opened
         * with. Reading the active level is what makes the drawn line the level the
         * engine will actually act on.
         */
        fun of(position: Position): TradeOverlay {
            val lines = buildList {
                add(TradeLine(TradeLineKind.ENTRY, position.openPriceUnits, isDraggable = false))
                position.activeStopUnits?.let {
                    add(TradeLine(TradeLineKind.STOP_LOSS, it, isDraggable = true))
                }
                position.targetUnits?.let {
                    add(TradeLine(TradeLineKind.TAKE_PROFIT, it, isDraggable = true))
                }
            }
            return TradeOverlay(
                id = position.positionId,
                side = position.side,
                isPending = false,
                lines = lines,
            )
        }

        /**
         * The overlay for a pending [order].
         *
         * The entry is draggable here — the order has not filled, so its price is
         * still a request. The stop and target are placed relative to the requested
         * price, which is why they cannot be built until one exists: a market order
         * has no entry level to draw and returns no overlay at all.
         */
        fun of(order: TradeOrder, contract: ContractSpec): TradeOverlay? {
            val entryPrice = order.requestedPriceUnits ?: return null
            val lines = buildList {
                add(TradeLine(TradeLineKind.ENTRY, entryPrice, isDraggable = true))
                order.stopLossPips?.let { pips ->
                    add(
                        TradeLine(
                            kind = TradeLineKind.STOP_LOSS,
                            priceUnits = stopPriceFor(
                                side = order.side,
                                entryPriceUnits = entryPrice,
                                stopDistancePips = pips,
                                contract = contract,
                            ),
                            isDraggable = true,
                        ),
                    )
                }
                order.takeProfitPips?.let { pips ->
                    add(
                        TradeLine(
                            kind = TradeLineKind.TAKE_PROFIT,
                            priceUnits = targetPriceFor(
                                side = order.side,
                                entryPriceUnits = entryPrice,
                                targetDistancePips = pips,
                                contract = contract,
                            ),
                            isDraggable = true,
                        ),
                    )
                }
            }
            return TradeOverlay(
                id = order.orderId,
                side = order.side,
                isPending = true,
                lines = lines,
            )
        }

        /**
         * The stop price for a trade at [entryPriceUnits] (Step 10.4).
         *
         * A buy is stopped *below* its entry and a sell above, so the sign is a
         * function of the side rather than a caller's choice. Getting this backwards
         * would place the stop on the profitable side, which the engine would fill
         * instantly — a bug that looks like a windfall.
         */
        fun stopPriceFor(
            side: OrderSide,
            entryPriceUnits: Int,
            stopDistancePips: Int,
            contract: ContractSpec,
        ): Int {
            val distance = contract.pipsToUnits(stopDistancePips)
            return if (side == OrderSide.BUY) entryPriceUnits - distance
            else entryPriceUnits + distance
        }

        /** The target price for a trade at [entryPriceUnits] (Step 10.4). */
        fun targetPriceFor(
            side: OrderSide,
            entryPriceUnits: Int,
            targetDistancePips: Int,
            contract: ContractSpec,
        ): Int {
            val distance = contract.pipsToUnits(targetDistancePips)
            return if (side == OrderSide.BUY) entryPriceUnits + distance
            else entryPriceUnits - distance
        }

        /**
         * The pip distance from [entryPriceUnits] to [levelPriceUnits], signed by
         * [direction].
         *
         * # Why the direction is a parameter and not implied
         *
         * A buy's stop is below its entry while its target is above, so the two legs
         * of one bracket lie on *opposite* sides of the entry. A single "distance
         * from entry" therefore cannot serve both: it would read one leg as negative
         * and the caller would have to know which one. Naming the direction makes the
         * call site say what it means, and an earlier version of this function that
         * assumed the stop side silently dropped every target.
         *
         * The result is positive when the level is on the requested side and
         * negative when it is on the other, so a caller can reject a stop that has
         * been dragged through the entry rather than take its magnitude.
         *
         * Rounds to the nearest pip: a drag lands on an arbitrary pixel, so the
         * resulting price is almost never an exact pip multiple, and the engine stores
         * whole pips.
         */
        fun pipDistanceFor(
            side: OrderSide,
            entryPriceUnits: Int,
            levelPriceUnits: Int,
            contract: ContractSpec,
            direction: TradeDirection,
        ): Int {
            val delta = levelPriceUnits - entryPriceUnits
            // In the trade's favour means price moved the way the position profits:
            // up for a buy, down for a sell.
            val favourable = if (side == OrderSide.BUY) delta else -delta
            val signed = when (direction) {
                TradeDirection.FAVOURABLE -> favourable
                TradeDirection.ADVERSE -> -favourable
            }
            if (contract.unitsPerPip <= 0) return 0
            val magnitude = abs(signed)
            val whole = magnitude / contract.unitsPerPip
            val remainder = magnitude % contract.unitsPerPip
            // Round to nearest rather than truncate, so a drag one unit short of a pip
            // does not read as a pip smaller than it looks.
            val rounded = if (remainder * 2 >= contract.unitsPerPip) whole + 1 else whole
            return if (signed < 0) -rounded else rounded
        }

        /**
         * The money a position is currently up or down, in micro-dollars.
         *
         * Uses the exit-side price, because that is the price a close would
         * actually get. Marking at the mid would show a profit that disappears on
         * the way out.
         */
        fun unrealizedPnlMicros(
            position: Position,
            markPriceUnits: Int,
            contract: ContractSpec,
        ): Long {
            val delta = if (position.side == OrderSide.BUY) {
                markPriceUnits - position.openPriceUnits
            } else {
                position.openPriceUnits - markPriceUnits
            }
            return MoneyScale.pnlMicrosForPriceDelta(
                deltaUnits = delta,
                lotUnits = position.remainingVolumeHundredths,
                ouncesPerLot = contract.ouncesPerLot,
            )
        }
    }
}
