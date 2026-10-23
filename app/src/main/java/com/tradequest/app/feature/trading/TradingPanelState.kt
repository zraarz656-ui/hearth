package com.tradequest.app.feature.trading

import com.tradequest.app.core.risk.RiskLockBps
import com.tradequest.app.core.trading.AccountState
import com.tradequest.app.core.trading.BracketEconomics
import com.tradequest.app.core.trading.ClosedTrade
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.OrderType
import com.tradequest.app.core.trading.Position
import com.tradequest.app.core.trading.TradeOrder

/** Which trading surface is showing over the chart. */
enum class TradingSheet {
    NONE,

    /** The order ticket (Step 10.2). */
    TICKET,

    /** The open positions and pending orders list (Steps 10.7, 10.9). */
    POSITIONS,
}

/**
 * The order ticket's fields (Step 10.2).
 *
 * # Why the lot size is stored as hundredths and not a formatted string
 *
 * The engine takes integer hundredths of a lot, and every intermediate — the risk
 * preview, the sizer's output, the submitted command — is in that unit. Keeping
 * the ticket in the domain unit means there is exactly one place a human-facing
 * "0.20" becomes 20, at the text field's parse, rather than a conversion that
 * could be applied twice or skipped.
 */
data class OrderTicket(
    val side: OrderSide,
    val type: OrderType = OrderType.MARKET,
    val volumeHundredths: Int = 10,
    /** Only meaningful for a LIMIT or STOP order; null for a market order. */
    val priceUnits: Int? = null,
    val stopLossPips: Int? = null,
    val takeProfitPips: Int? = null,
    /**
     * Whether the lot size is derived from the risk lock rather than typed.
     *
     * When true the size field is read-only and follows the lock and the stop. When
     * false the trader types a size and the lock is only a preview.
     */
    val useRiskLock: Boolean = false,
)

/**
 * The live trading state the Trading screen shows (Phase 10).
 *
 * # Why everything is in one object
 *
 * These values change together: an account refresh follows a fill, which changes
 * the position list, the marks and the overlays. Publishing them as one object
 * means Compose sees a single consistent frame rather than a position list from
 * before a fill paired with a balance from after it.
 *
 * [isLive] is false when no season is running. The chart still draws, but the
 * trade controls are hidden rather than shown disabled, because a disabled Buy
 * button on a historical chart invites the question "why can't I buy?" — the
 * answer being that there is no clock to trade against.
 */
data class TradingPanelState(
    val isLive: Boolean = false,
    val riskLock: RiskLockBps = RiskLockBps.ONE,
    val account: AccountState? = null,
    val positions: List<Position> = emptyList(),
    val pendingOrders: List<TradeOrder> = emptyList(),
    val closedTrades: List<ClosedTrade> = emptyList(),
    /** The executable bid per open position, for marking it to market. */
    val markPrices: Map<String, Int> = emptyMap(),
    /** The sizer's bracket preview, when the ticket is open with a stop (Step 10.5). */
    val bracketPreview: BracketEconomics? = null,
    /** The size the risk lock implies for the current stop, or null when unknown. */
    val riskLockLotUnits: Int? = null,
    /** A one-line result of the last command, shown as a transient message. */
    val lastMessage: String? = null,
) {
    val openPositionCount: Int get() = positions.size
    val pendingOrderCount: Int get() = pendingOrders.size
    val hasExposure: Boolean get() = positions.isNotEmpty() || pendingOrders.isNotEmpty()
}
