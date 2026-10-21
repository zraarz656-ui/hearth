package com.tradequest.app.core.trading

import com.tradequest.app.core.accounting.MoneyScale

/** Direction of an order or position. */
enum class OrderSide { BUY, SELL }

/** How an order reaches the market (Step 5.2). */
enum class OrderType {
    /** Fills at the current executable price. */
    MARKET,

    /** Rests at a price better than the market: buy below, sell above. */
    LIMIT,

    /** Rests at a price worse than the market: buy above, sell below. */
    STOP,
    ;

    /** True for order types that rest until a level is reached. */
    val isPending: Boolean get() = this != MARKET

    /** True for the order types that trigger on a level being crossed adversely. */
    val isStopLike: Boolean get() = this == STOP
}

enum class OrderStatus {
    /** Resting, waiting for its trigger level. */
    PENDING,

    /** Fully filled. */
    FILLED,

    /** Cancelled by the user before filling. */
    CANCELLED,

    /** Refused at submission. */
    REJECTED,
}

enum class PositionStatus { OPEN, CLOSED }

/** Why a position closed (Step 5.3). Mirrors `ClosedTradeEntity.closeReason`. */
enum class CloseReason {
    STOP_LOSS,
    TAKE_PROFIT,
    /** Closed by the user, in full or in part. */
    MANUAL,
    /** Closed by the broker's stop-out rule (Step 6.8). */
    STOP_OUT,
    /** Closed because the daily floor was hit and the account was flattened (Step 6.9). */
    DAILY_LOSS_LIMIT,
    /** Closed because the challenge failed (Step 6.10). */
    CHALLENGE_FAILED,
    /** Closed because the season ended or the dataset ran out. */
    SEASON_END,
}

/**
 * A trailing stop (Step 5.13).
 *
 * # The single rule this phase uses
 *
 * - **Activation.** The trail does not move until price has moved
 *   [activationPips] in the position's favour, measured from the entry price.
 *   `0` means the trail is live from the open.
 * - **Distance.** Once active, the stop sits [distancePips] behind the *best*
 *   exit-side price seen so far — the highest bid for a long, the lowest ask
 *   for a short.
 * - **Update frequency.** Continuously: the best price is tracked along the
 *   path, not sampled at a fixed rate.
 * - **Application.** Immediately. A new best price moves the stop at once, in
 *   the same evaluation pass, rather than at the next segment boundary. A
 *   ratchet that only applied later would let price pass through a level the
 *   trail had already reached.
 *
 * The trail only ever tightens. It never moves away from price, because a stop
 * that could loosen would be a way to avoid being stopped out.
 */
data class TrailingStopSpec(
    val distancePips: Int,
    val activationPips: Int = 0,
) {
    init {
        require(distancePips > 0) { "trailing distance must be positive" }
        require(activationPips >= 0) { "activation distance cannot be negative" }
    }
}

/**
 * A move-to-breakeven rule (Step 5.3).
 *
 * Once price has moved [triggerPips] in the position's favour, the stop moves to
 * the entry price plus [lockPips] of profit. Applied once, and only if it
 * tightens the stop.
 */
data class BreakevenSpec(
    val triggerPips: Int,
    val lockPips: Int = 0,
) {
    init {
        require(triggerPips > 0) { "breakeven trigger must be positive" }
        require(lockPips >= 0) { "breakeven lock cannot be negative" }
    }
}

/**
 * An order in any state (Step 5.1).
 *
 * Domain type, deliberately distinct from `TradeOrderEntity`
 * (`docs/architecture.md` rule 5).
 */
data class TradeOrder(
    val orderId: String,
    val seasonId: String,
    val placedAtSimUtcMs: Long,
    val type: OrderType,
    val side: OrderSide,
    /** Size in hundredths of a lot. */
    val volumeHundredths: Int,
    /** Trigger price for a pending order; null for a market order. */
    val requestedPriceUnits: Int?,
    val stopLossPips: Int?,
    val takeProfitPips: Int?,
    val trailingStop: TrailingStopSpec?,
    val breakeven: BreakevenSpec?,
    val status: OrderStatus,
    val resolvedAtSimUtcMs: Long?,
    val rejectionReason: String?,
) {
    val isPending: Boolean get() = status == OrderStatus.PENDING
}

/**
 * One fill (Step 5.1).
 *
 * [slippageUnits] is the signed difference from the requested price: negative is
 * adverse for a buy, positive is adverse for a sell. It is recorded even when
 * zero, so a fill that was expected to slip and did not is visible in history.
 */
data class Execution(
    val executionId: String,
    val seasonId: String,
    val orderId: String,
    val positionId: String?,
    val executedAtSimUtcMs: Long,
    val priceUnits: Int,
    val volumeHundredths: Int,
    val commissionMicros: Long,
    val slippageUnits: Int,
    /** The bid at the instant of the fill, so the spread actually paid is auditable. */
    val bidPriceUnits: Int,
    val askPriceUnits: Int,
)

/**
 * An open or closed position (Step 5.1).
 *
 * # Stop and target levels
 *
 * [stopLossPips] and [takeProfitPips] are distances from the entry price, per
 * the plan's rule that every distance is stored in pips, never in raw units.
 *
 * The *effective* stop is [activeStopUnits], an absolute exit-side price. It
 * starts as the level implied by [stopLossPips] and moves when a trailing stop
 * or a breakeven rule applies. Keeping the original distance and the live level
 * separate is what makes it possible to show the user what they asked for and
 * what the engine is actually enforcing.
 */
data class Position(
    val positionId: String,
    val seasonId: String,
    val openedAtSimUtcMs: Long,
    val side: OrderSide,
    val volumeHundredths: Int,
    /** The actual fill price: ask for a long, bid for a short. */
    val openPriceUnits: Int,
    val stopLossPips: Int?,
    val takeProfitPips: Int?,
    val trailingStop: TrailingStopSpec?,
    val breakeven: BreakevenSpec?,
    val status: PositionStatus,
    val closedAtSimUtcMs: Long?,
    val closePriceUnits: Int?,
    /** Absolute exit-side stop currently enforced, or null when there is none. */
    val activeStopUnits: Int?,
    /** Absolute exit-side target, or null. Never moves. */
    val targetUnits: Int?,
    /** Best exit-side price seen since the position opened. Drives the trail. */
    val favorableExtremeUnits: Int,
    val breakevenApplied: Boolean,
    /** Commission already charged on the open side, so a partial close can apportion it. */
    val entryCommissionMicros: Long,
    /** Realised money from partial closes already taken on this position. */
    val realizedPnlMicros: Long,
    val realizedVolumeHundredths: Int,
) {
    val isOpen: Boolean get() = status == PositionStatus.OPEN

    /** Size still open. */
    val remainingVolumeHundredths: Int
        get() = volumeHundredths - realizedVolumeHundredths
}

/**
 * A completed round trip, or the completed part of a partially closed one
 * (Step 5.1).
 *
 * `realizedRMicros` is R in millionths, so a 2.5R win is `2_500_000` and R stays
 * exact without a `Double`.
 */
data class ClosedTrade(
    val tradeId: String,
    val seasonId: String,
    val positionId: String,
    val side: OrderSide,
    val volumeHundredths: Int,
    val openPriceUnits: Int,
    val closePriceUnits: Int,
    val openTimeSimUtcMs: Long,
    val closeTimeSimUtcMs: Long,
    val grossPnlMicros: Long,
    val commissionMicros: Long,
    val swapMicros: Long,
    val netPnlMicros: Long,
    val closeReason: CloseReason,
    val realizedRMicros: Long,
)

/**
 * Account state at an instant (Step 5.1).
 *
 * [equityMicros] is `balance + unrealised`. Margin, margin level and the
 * stop-out rule belong to Phase 6 (Steps 6.5-6.8) and are deliberately absent
 * here: this phase produces the reproducible *inputs* to accounting, and adding
 * a half-implemented margin figure would invite a caller to trust it.
 */
data class AccountState(
    val seasonId: String,
    val balanceMicros: Long,
    val unrealizedPnlMicros: Long,
) {
    val equityMicros: Long get() = balanceMicros + unrealizedPnlMicros
}

/** A user action (Step 5.1). */
sealed interface TradingCommand {

    /** The instant the command was issued, in simulated time. */
    val atSimUtcMs: Long

    data class PlaceOrder(
        override val atSimUtcMs: Long,
        val orderId: String,
        val type: OrderType,
        val side: OrderSide,
        val volumeHundredths: Int,
        val priceUnits: Int? = null,
        val stopLossPips: Int? = null,
        val takeProfitPips: Int? = null,
        val trailingStop: TrailingStopSpec? = null,
        val breakeven: BreakevenSpec? = null,
    ) : TradingCommand

    data class ModifyOrder(
        override val atSimUtcMs: Long,
        val orderId: String,
        val priceUnits: Modification<Int> = Modification.Keep,
        val stopLossPips: Modification<Int> = Modification.Keep,
        val takeProfitPips: Modification<Int> = Modification.Keep,
    ) : TradingCommand

    data class CancelOrder(
        override val atSimUtcMs: Long,
        val orderId: String,
    ) : TradingCommand

    data class ModifyPosition(
        override val atSimUtcMs: Long,
        val positionId: String,
        val stopLossPips: Modification<Int> = Modification.Keep,
        val takeProfitPips: Modification<Int> = Modification.Keep,
        val trailingStop: Modification<TrailingStopSpec> = Modification.Keep,
        val breakeven: Modification<BreakevenSpec> = Modification.Keep,
    ) : TradingCommand

    /**
     * Closes [volumeHundredths] of a position, or all of it when null.
     *
     * A partial close must leave both halves valid under the contract's lot
     * rules; an invalid split is rejected rather than rounded (Step 5.12).
     */
    data class ClosePosition(
        override val atSimUtcMs: Long,
        val positionId: String,
        val volumeHundredths: Int? = null,
    ) : TradingCommand
}

/** Why a command was refused. */
enum class RejectionReason {
    ORDER_NOT_FOUND,
    POSITION_NOT_FOUND,
    INVALID_VOLUME,
    MISSING_PRICE,
    UNEXPECTED_PRICE,
    INVALID_STOP_DISTANCE,
    INVALID_TARGET_DISTANCE,
    /** A close that would leave a remainder too small to trade. */
    INVALID_PARTIAL_CLOSE,
    /** The command was issued before the engine had advanced to that instant. */
    LOOK_BACK_COMMAND,
    /** The command was issued at a time the market was closed. */
    MARKET_CLOSED,
    /** The command names an order that is no longer pending. */
    ORDER_NOT_PENDING,
    /** The day's loss limit was hit; trading resumes at the next rollover (Step 6.9). */
    TRADING_DAY_LOCKED,
    /** The challenge has failed and no further trading is allowed (Steps 6.9-6.10). */
    CHALLENGE_FAILED,
    /** The account has no margin headroom for the requested size (Step 6.5). */
    INSUFFICIENT_MARGIN,
}

/** The outcome of a [TradingCommand]. */
sealed interface CommandResult {
    data class Accepted(val execution: Execution? = null, val position: Position? = null) : CommandResult

    data class Rejected(val reason: RejectionReason, val detail: String) : CommandResult
}

/** What the engine did, in order. Produced by an advance. */
sealed interface TradingEvent {
    val atSimUtcMs: Long

    /** A pending order's level was reached and it filled. */
    data class OrderFilled(
        override val atSimUtcMs: Long,
        val order: TradeOrder,
        val execution: Execution,
        /** True when the level was jumped rather than traded through (Step 5.11). */
        val inGap: Boolean,
    ) : TradingEvent

    data class PositionOpened(
        override val atSimUtcMs: Long,
        val position: Position,
    ) : TradingEvent

    data class PositionClosed(
        override val atSimUtcMs: Long,
        val positionId: String,
        val trade: ClosedTrade,
    ) : TradingEvent

    data class StopMoved(
        override val atSimUtcMs: Long,
        val positionId: String,
        val fromUnits: Int?,
        val toUnits: Int,
        val cause: StopMoveCause,
    ) : TradingEvent

    /** A margin warning became due (Step 6.7). Emitted once per crossing, not per segment. */
    data class MarginWarning(
        override val atSimUtcMs: Long,
        val marginLevelPercent: Long,
    ) : TradingEvent

    /** A position was liquidated by the stop-out rule (Step 6.8). */
    data class StopOutExecuted(
        override val atSimUtcMs: Long,
        val positionId: String,
        val trade: ClosedTrade,
        /** Margin level after the liquidation, or null when nothing is left open. */
        val marginLevelPercentAfter: Long?,
    ) : TradingEvent

    /** The daily floor was reached; positions were flattened and trading locked (Step 6.9). */
    data class DailyLossLimitHit(
        override val atSimUtcMs: Long,
        val equityMicros: Long,
        val dayStartEquityMicros: Long,
        val floorMicros: Long,
        /** Strikes accumulated so far, including this one. */
        val dailyLossStrikes: Int,
        /** True when this strike exhausted the allowance and failed the challenge. */
        val challengeFailed: Boolean,
    ) : TradingEvent

    /** The challenge failed at the static floor (Step 6.10). */
    data class ChallengeFailed(
        override val atSimUtcMs: Long,
        val equityMicros: Long,
        val reason: RiskFailureReason,
    ) : TradingEvent

    /** The 17:00 New York rollover reset the daily budget (Step 6.9). */
    data class TradingDayRolled(
        override val atSimUtcMs: Long,
        val dayStartEquityMicros: Long,
        /** False when the challenge had already failed, so the day is not tradeable. */
        val tradingResumed: Boolean,
    ) : TradingEvent
}

/** Why an effective stop moved (Step 5.13). */
enum class StopMoveCause { TRAILING, BREAKEVEN }

/** The engine's whole mutable state, as an immutable value. */
data class TradingState(
    val seasonId: String,
    val balanceMicros: Long,
    val orders: List<TradeOrder>,
    val positions: List<Position>,
    val executions: List<Execution>,
    val closedTrades: List<ClosedTrade>,
    /** Simulated time the engine has processed up to, exclusive. */
    val processedToSimUtcMs: Long,
    /** Equity at the current trading day's 17:00 New York rollover (Step 6.9). */
    val dayStartEquityMicros: Long = balanceMicros,
    /** Epoch day of the trading date the current daily budget belongs to. */
    val dayEpochDay: Long = 0L,
    /** Daily-floor breaches accumulated across the challenge (Step 6.9). */
    val dailyLossStrikes: Int = 0,
    /** True when the current day's loss limit was hit; clears at the next rollover. */
    val dayLocked: Boolean = false,
    /** True when the challenge has failed; never clears (Steps 6.9-6.10). */
    val challengeFailed: Boolean = false,
    /** Why the challenge failed, when it has. */
    val failureReason: RiskFailureReason? = null,
    /** True while a margin warning is showing, so it is emitted once per crossing. */
    val marginWarningActive: Boolean = false,
) {
    val openPositions: List<Position> get() = positions.filter { it.isOpen }
    val pendingOrders: List<TradeOrder> get() = orders.filter { it.isPending }

    /** True when no further trading is allowed, for any reason. */
    val tradingBlocked: Boolean get() = challengeFailed || dayLocked
}

/** Unrealised PnL of one position at a given exit-side price. */
internal fun Position.unrealizedPnlMicrosAt(
    exitPriceUnits: Int,
    contract: ContractSpec,
): Long {
    val delta = when (side) {
        OrderSide.BUY -> exitPriceUnits - openPriceUnits
        OrderSide.SELL -> openPriceUnits - exitPriceUnits
    }
    return MoneyScale.pnlMicrosForPriceDelta(
        deltaUnits = delta,
        lotUnits = remainingVolumeHundredths,
        ouncesPerLot = contract.ouncesPerLot,
    )
}
