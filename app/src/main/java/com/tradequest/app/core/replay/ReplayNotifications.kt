package com.tradequest.app.core.replay

import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.trading.CloseReason
import com.tradequest.app.core.trading.TradingEvent

/**
 * What a notification is about (Step 7.9).
 *
 * The plan lists fills, stop losses, take profits, margin warnings, daily locks
 * and challenge results. [STOP_OUT] is added because a broker liquidation is at
 * least as material as a stop loss, and the plan's list reads as examples rather
 * than an exhaustive set.
 */
enum class TradeNotificationKind {
    FILL,
    STOP_LOSS,
    TAKE_PROFIT,
    STOP_OUT,
    MARGIN_WARNING,
    DAILY_LOCK,
    CHALLENGE_RESULT,
}

/** One user-facing notification, already resolved to text (Step 7.9). */
data class TradeNotification(
    val seasonId: String,
    val kind: TradeNotificationKind,
    val atSimUtcMs: Long,
    val title: String,
    val body: String,
)

/**
 * Delivers notifications (Step 7.9).
 *
 * A port, so `core.replay` stays free of the Android notification framework and
 * the processor can be tested by counting deliveries.
 */
fun interface ReplayNotifier {
    suspend fun notify(notification: TradeNotification)
}

/** A [ReplayNotifier] that drops everything. The default when none is wired. */
object NoOpReplayNotifier : ReplayNotifier {
    override suspend fun notify(notification: TradeNotification) = Unit
}

/**
 * Turns engine events into notifications (Step 7.9).
 *
 * # Why only some events become notifications
 *
 * Every event is already persisted as history; a notification exists to reach a
 * user who is not looking at the app. So this maps the events a user would want
 * to be interrupted for — a fill, a close, a limit being hit — and deliberately
 * ignores the rest. `TradingDayRolled`, `StopMoved` and `PositionOpened` are
 * silent: a trailing stop creeping up would otherwise notify on every segment.
 *
 * `PositionOpened` is silent because its `OrderFilled` already notifies; emitting
 * both would double-notify one action.
 */
object ReplayNotifications {

    fun map(seasonId: String, event: TradingEvent): TradeNotification? = when (event) {
        is TradingEvent.OrderFilled -> TradeNotification(
            seasonId = seasonId,
            kind = TradeNotificationKind.FILL,
            atSimUtcMs = event.atSimUtcMs,
            title = "Order filled",
            body = "${event.order.side} ${lots(event.order.volumeHundredths)} lots at " +
                "${price(event.execution.priceUnits)}" +
                if (event.inGap) " (gap fill)" else "",
        )

        is TradingEvent.PositionClosed -> when (event.trade.closeReason) {
            CloseReason.STOP_LOSS -> close(event.atSimUtcMs, seasonId, "Stop loss hit", event.trade.netPnlMicros)
            CloseReason.TAKE_PROFIT -> close(event.atSimUtcMs, seasonId, "Take profit hit", event.trade.netPnlMicros)
            // A manual close or a season end is either something the user just
            // did or something they can see; neither needs an interruption.
            else -> null
        }

        is TradingEvent.StopOutExecuted -> TradeNotification(
            seasonId = seasonId,
            kind = TradeNotificationKind.STOP_OUT,
            atSimUtcMs = event.atSimUtcMs,
            title = "Position liquidated",
            body = "Margin level fell to " +
                "${event.marginLevelPercentAfter?.let { "$it%" } ?: "no open positions"}; " +
                "a position was closed at ${price(event.trade.closePriceUnits)}.",
        )

        is TradingEvent.MarginWarning -> TradeNotification(
            seasonId = seasonId,
            kind = TradeNotificationKind.MARGIN_WARNING,
            atSimUtcMs = event.atSimUtcMs,
            title = "Margin warning",
            body = "Margin level is ${event.marginLevelPercent}%, at or below the warning level.",
        )

        is TradingEvent.DailyLossLimitHit -> TradeNotification(
            seasonId = seasonId,
            kind = TradeNotificationKind.DAILY_LOCK,
            atSimUtcMs = event.atSimUtcMs,
            title = if (event.challengeFailed) "Challenge failed" else "Daily loss limit hit",
            body = "Equity ${money(event.equityMicros)} reached the day's floor of " +
                "${money(event.floorMicros)}. Strike ${event.dailyLossStrikes}. " +
                if (event.challengeFailed) {
                    "The challenge is over."
                } else {
                    "Trading resumes at the next 17:00 New York rollover."
                },
        )

        is TradingEvent.ChallengeFailed -> TradeNotification(
            seasonId = seasonId,
            kind = TradeNotificationKind.CHALLENGE_RESULT,
            atSimUtcMs = event.atSimUtcMs,
            title = "Challenge failed",
            body = "Equity ${money(event.equityMicros)}: ${reasonText(event.reason)}.",
        )

        is TradingEvent.PositionOpened -> null
        is TradingEvent.StopMoved -> null
        is TradingEvent.TradingDayRolled -> null
    }

    /**
     * Maps a whole batch, in order.
     *
     * The order matters: a fill followed by its stop-out notifies in that order,
     * so a user reading them sees the trade as it happened rather than reversed.
     */
    fun mapAll(seasonId: String, events: List<TradingEvent>): List<TradeNotification> =
        events.mapNotNull { map(seasonId, it) }

    private fun close(
        atSimUtcMs: Long,
        seasonId: String,
        title: String,
        netPnlMicros: Long,
    ) = TradeNotification(
        seasonId = seasonId,
        kind = if (title.startsWith("Stop")) {
            TradeNotificationKind.STOP_LOSS
        } else {
            TradeNotificationKind.TAKE_PROFIT
        },
        atSimUtcMs = atSimUtcMs,
        title = title,
        body = "Closed for ${money(netPnlMicros)}.",
    )

    private fun reasonText(reason: com.tradequest.app.core.trading.RiskFailureReason): String =
        when (reason) {
            com.tradequest.app.core.trading.RiskFailureReason.DAILY_LOSS_STRIKES_EXHAUSTED ->
                "the daily loss limit was hit too many times"
            com.tradequest.app.core.trading.RiskFailureReason.STATIC_FLOOR_BREACHED ->
                "the account reached its floor"
        }

    /** `0.20` for 20 lot units. */
    private fun lots(lotUnits: Int): String =
        "${lotUnits / 100}.${(lotUnits % 100).toString().padStart(2, '0')}"

    /** `2450.505` for 2_450_505 price units. */
    private fun price(priceUnits: Int): String =
        "${priceUnits / 1000}.${(priceUnits % 1000).toString().padStart(3, '0')}"

    private fun money(micros: Long): String {
        val sign = if (micros < 0) "-" else ""
        val abs = kotlin.math.abs(micros)
        val whole = abs / MoneyScale.MICROS_PER_USD
        val cents = (abs % MoneyScale.MICROS_PER_USD) / (MoneyScale.MICROS_PER_USD / 100)
        return "$sign\$$whole.${cents.toString().padStart(2, '0')}"
    }
}
