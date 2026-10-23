package com.tradequest.app.core.replay

import com.tradequest.app.core.trading.CloseReason
import com.tradequest.app.core.trading.ClosedTrade
import com.tradequest.app.core.trading.Execution
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.OrderStatus
import com.tradequest.app.core.trading.OrderType
import com.tradequest.app.core.trading.RiskFailureReason
import com.tradequest.app.core.trading.TradeOrder
import com.tradequest.app.core.trading.TradingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 7.9: which engine events become notifications, and what they say.
 *
 * # Why the silent events are tested too
 *
 * The mapping's value is as much in what it suppresses as in what it emits. A
 * trailing stop that notified on every segment would make the feature unusable,
 * so the tests for `StopMoved`, `PositionOpened` and `TradingDayRolled` returning
 * null are asserting the design, not filling coverage.
 */
class ReplayNotificationsTest {

    private val t0 = ReplayFixtures.T0

    private fun trade(
        closeReason: CloseReason,
        netPnlMicros: Long = 125_000_000L,
    ) = ClosedTrade(
        tradeId = "t1",
        seasonId = ReplayFixtures.SEASON_ID,
        positionId = "pos-1",
        side = OrderSide.BUY,
        volumeHundredths = 20,
        openPriceUnits = 2_000_000,
        closePriceUnits = 2_005_000,
        openTimeSimUtcMs = t0,
        closeTimeSimUtcMs = t0 + 60_000L,
        grossPnlMicros = netPnlMicros + 50_000L,
        commissionMicros = 50_000L,
        swapMicros = 0L,
        netPnlMicros = netPnlMicros,
        closeReason = closeReason,
        realizedRMicros = 0L,
    )

    private fun order() = TradeOrder(
        orderId = "o1",
        seasonId = ReplayFixtures.SEASON_ID,
        placedAtSimUtcMs = t0,
        type = OrderType.MARKET,
        side = OrderSide.BUY,
        volumeHundredths = 20,
        requestedPriceUnits = null,
        stopLossPips = null,
        takeProfitPips = null,
        trailingStop = null,
        breakeven = null,
        status = OrderStatus.FILLED,
        resolvedAtSimUtcMs = t0,
        rejectionReason = null,
    )

    private fun execution() = Execution(
        executionId = "e1",
        seasonId = ReplayFixtures.SEASON_ID,
        orderId = "o1",
        positionId = "pos-1",
        executedAtSimUtcMs = t0,
        priceUnits = 2_000_500,
        volumeHundredths = 20,
        commissionMicros = 50_000L,
        slippageUnits = 0,
        bidPriceUnits = 2_000_000,
        askPriceUnits = 2_000_500,
    )

    // ------------------------------------------------------------------ emitted

    @Test
    fun `a fill notifies with side, size and price`() {
        val notification = ReplayNotifications.map(
            ReplayFixtures.SEASON_ID,
            TradingEvent.OrderFilled(t0, order(), execution(), inGap = false),
        )
        assertNotNull(notification)
        assertEquals(TradeNotificationKind.FILL, notification!!.kind)
        assertTrue(notification.body.contains("BUY"))
        assertTrue(notification.body.contains("0.20"))
        assertTrue(notification.body.contains("2000.500"))
    }

    @Test
    fun `a gap fill says so`() {
        val notification = ReplayNotifications.map(
            ReplayFixtures.SEASON_ID,
            TradingEvent.OrderFilled(t0, order(), execution(), inGap = true),
        )!!
        assertTrue(notification.body.contains("gap fill"))
    }

    @Test
    fun `a stop loss and a take profit notify with distinct kinds`() {
        val stop = ReplayNotifications.map(
            ReplayFixtures.SEASON_ID,
            TradingEvent.PositionClosed(t0, "pos-1", trade(CloseReason.STOP_LOSS)),
        )!!
        val target = ReplayNotifications.map(
            ReplayFixtures.SEASON_ID,
            TradingEvent.PositionClosed(t0, "pos-1", trade(CloseReason.TAKE_PROFIT)),
        )!!

        assertEquals(TradeNotificationKind.STOP_LOSS, stop.kind)
        assertEquals(TradeNotificationKind.TAKE_PROFIT, target.kind)
        assertTrue(stop.body.contains("$125.00"))
    }

    @Test
    fun `a margin warning notifies`() {
        val notification = ReplayNotifications.map(
            ReplayFixtures.SEASON_ID,
            TradingEvent.MarginWarning(t0, marginLevelPercent = 95L),
        )!!
        assertEquals(TradeNotificationKind.MARGIN_WARNING, notification.kind)
        assertTrue(notification.body.contains("95%"))
    }

    @Test
    fun `a stop-out notifies and names the margin level`() {
        val notification = ReplayNotifications.map(
            ReplayFixtures.SEASON_ID,
            TradingEvent.StopOutExecuted(t0, "pos-1", trade(CloseReason.STOP_OUT), 42L),
        )!!
        assertEquals(TradeNotificationKind.STOP_OUT, notification.kind)
        assertTrue(notification.body.contains("42%"))
    }

    @Test
    fun `a daily loss strike notifies as a lock, and a failed challenge says so`() {
        val strike = ReplayNotifications.map(
            ReplayFixtures.SEASON_ID,
            TradingEvent.DailyLossLimitHit(
                atSimUtcMs = t0,
                equityMicros = 9_700_000_000L,
                dayStartEquityMicros = 10_000_000_000L,
                floorMicros = 9_700_000_000L,
                dailyLossStrikes = 1,
                challengeFailed = false,
            ),
        )!!
        assertEquals(TradeNotificationKind.DAILY_LOCK, strike.kind)
        assertEquals("Daily loss limit hit", strike.title)
        assertTrue(strike.body.contains("resumes"))

        val failed = ReplayNotifications.map(
            ReplayFixtures.SEASON_ID,
            TradingEvent.DailyLossLimitHit(
                atSimUtcMs = t0,
                equityMicros = 9_700_000_000L,
                dayStartEquityMicros = 10_000_000_000L,
                floorMicros = 9_700_000_000L,
                dailyLossStrikes = 3,
                challengeFailed = true,
            ),
        )!!
        assertEquals("Challenge failed", failed.title)
        assertTrue(failed.body.contains("over"))
    }

    @Test
    fun `a challenge failure notifies with the reason spelled out`() {
        val notification = ReplayNotifications.map(
            ReplayFixtures.SEASON_ID,
            TradingEvent.ChallengeFailed(t0, 8_000_000_000L, RiskFailureReason.STATIC_FLOOR_BREACHED),
        )!!
        assertEquals(TradeNotificationKind.CHALLENGE_RESULT, notification.kind)
        assertTrue(notification.body.contains("floor"))
    }

    // ------------------------------------------------------------------ silenced

    @Test
    fun `a manual close is not notified, because the user did it`() {
        assertNull(
            ReplayNotifications.map(
                ReplayFixtures.SEASON_ID,
                TradingEvent.PositionClosed(t0, "pos-1", trade(CloseReason.MANUAL)),
            ),
        )
    }

    @Test
    fun `a trailing stop move is not notified`() {
        assertNull(
            ReplayNotifications.map(
                ReplayFixtures.SEASON_ID,
                TradingEvent.StopMoved(
                    t0,
                    "pos-1",
                    fromUnits = null,
                    toUnits = 1_995_000,
                    cause = com.tradequest.app.core.trading.StopMoveCause.TRAILING,
                ),
            ),
        )
    }

    @Test
    fun `a position opening is not notified, because its fill already was`() {
        assertNull(
            ReplayNotifications.map(
                ReplayFixtures.SEASON_ID,
                TradingEvent.PositionOpened(
                    t0,
                    com.tradequest.app.core.trading.Position(
                        positionId = "pos-1",
                        seasonId = ReplayFixtures.SEASON_ID,
                        openedAtSimUtcMs = t0,
                        side = OrderSide.BUY,
                        volumeHundredths = 20,
                        openPriceUnits = 2_000_500,
                        stopLossPips = null,
                        takeProfitPips = null,
                        trailingStop = null,
                        breakeven = null,
                        status = com.tradequest.app.core.trading.PositionStatus.OPEN,
                        closedAtSimUtcMs = null,
                        closePriceUnits = null,
                        activeStopUnits = null,
                        targetUnits = null,
                        favorableExtremeUnits = 2_000_500,
                        breakevenApplied = false,
                        entryCommissionMicros = 50_000L,
                        realizedPnlMicros = 0L,
                        realizedVolumeHundredths = 0,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a day rollover is not notified`() {
        assertNull(
            ReplayNotifications.map(
                ReplayFixtures.SEASON_ID,
                TradingEvent.TradingDayRolled(t0, 10_000_000_000L, tradingResumed = true),
            ),
        )
    }

    // -------------------------------------------------------------------- order

    @Test
    fun `a batch maps in order, dropping the silent events`() {
        val notifications = ReplayNotifications.mapAll(
            ReplayFixtures.SEASON_ID,
            listOf(
                TradingEvent.OrderFilled(t0, order(), execution(), inGap = false),
                TradingEvent.PositionOpened(
                    t0,
                    com.tradequest.app.core.trading.Position(
                        positionId = "pos-1",
                        seasonId = ReplayFixtures.SEASON_ID,
                        openedAtSimUtcMs = t0,
                        side = OrderSide.BUY,
                        volumeHundredths = 20,
                        openPriceUnits = 2_000_500,
                        stopLossPips = null,
                        takeProfitPips = null,
                        trailingStop = null,
                        breakeven = null,
                        status = com.tradequest.app.core.trading.PositionStatus.OPEN,
                        closedAtSimUtcMs = null,
                        closePriceUnits = null,
                        activeStopUnits = null,
                        targetUnits = null,
                        favorableExtremeUnits = 2_000_500,
                        breakevenApplied = false,
                        entryCommissionMicros = 50_000L,
                        realizedPnlMicros = 0L,
                        realizedVolumeHundredths = 0,
                    ),
                ),
                TradingEvent.PositionClosed(t0 + 60_000L, "pos-1", trade(CloseReason.STOP_LOSS)),
            ),
        )

        assertEquals(2, notifications.size)
        assertEquals(TradeNotificationKind.FILL, notifications[0].kind)
        assertEquals(TradeNotificationKind.STOP_LOSS, notifications[1].kind)
        assertTrue(notifications[0].atSimUtcMs <= notifications[1].atSimUtcMs)
    }
}
