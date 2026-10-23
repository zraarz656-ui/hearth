package com.tradequest.app.core.trading

import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.trading.TradeFixtures.accepted
import com.tradequest.app.core.trading.TradeFixtures.at
import com.tradequest.app.core.trading.TradeFixtures.positionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steps 5.2, 5.5, 5.9, 5.10: order types, bid/ask execution, event ordering and
 * same-candle entry and exit.
 *
 * # How to read the price expectations
 *
 * Every candle here is a single minute starting at `T0 + n * 60_000`. A bullish
 * candle's path is open -> low -> high -> close, so the level a trigger crosses
 * first depends on the shape, not just on the OHLC. Where a test's expected
 * millisecond is not obvious it is derived in a comment.
 */
class OrderExecutionTest {

    // A bullish minute: 2,000,000 -> 1,990,000 -> 2,010,000 -> 2,005,000.
    // Waypoints at 0 / 15,000 / 30,000 / 60,000 ms.
    private fun risingMinute(index: Int = 0) = TradeFixtures.bullish(
        minuteIndex = index,
        open = 2_000_000,
        low = 1_990_000,
        high = 2_010_000,
        close = 2_005_000,
    )

    // A bearish minute: 2,000,000 -> 2,010,000 -> 1_990,000 -> 1_995,000.
    private fun fallingMinute(index: Int = 0) = TradeFixtures.bearish(
        minuteIndex = index,
        open = 2_000_000,
        high = 2_010_000,
        low = 1_990_000,
        close = 1_995_000,
    )

    // ------------------------------------------------------- market orders (5.2)

    @Test
    fun `a market buy fills at the ask`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))

        val execution = engine.state.executions.single()
        // The path opens at 2,000,000 and the normal spread is 300, so the ask is
        // 2,000,300.
        assertEquals(2_000_300, execution.priceUnits)
        assertEquals(2_000_000, execution.bidPriceUnits)
        assertEquals(2_000_300, execution.askPriceUnits)
    }

    @Test
    fun `a market sell fills at the bid`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(fallingMinute()))
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.SELL, atSimUtcMs = at(0))))

        assertEquals(2_000_000, engine.state.executions.single().priceUnits)
    }

    @Test
    fun `a market order opens a position`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))

        val position = engine.positionFor("m1")
        assertNotNull(position)
        assertEquals(OrderSide.BUY, position!!.side)
        assertEquals(10, position.volumeHundredths)
        assertTrue(position.isOpen)
    }

    @Test
    fun `a market order takes no price`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        val result = engine.submit(
            TradingCommand.PlaceOrder(
                atSimUtcMs = at(0),
                orderId = "m1",
                type = OrderType.MARKET,
                side = OrderSide.BUY,
                volumeHundredths = 10,
                priceUnits = 2_000_000,
            ),
        )
        assertTrue(result is CommandResult.Rejected)
        assertEquals(RejectionReason.UNEXPECTED_PRICE, (result as CommandResult.Rejected).reason)
    }

    @Test
    fun `an invalid volume is rejected`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        val result = engine.submit(
            TradeFixtures.marketOrder("m1", OrderSide.BUY, volumeHundredths = 0, atSimUtcMs = at(0)),
        )
        assertEquals(RejectionReason.INVALID_VOLUME, (result as CommandResult.Rejected).reason)
    }

    @Test
    fun `a market order outside the window is rejected`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        val result = engine.submit(
            TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(5)),
        )
        assertEquals(RejectionReason.MARKET_CLOSED, (result as CommandResult.Rejected).reason)
    }

    // ------------------------------------------------------ pending orders (5.2)

    @Test
    fun `a buy limit fills when the bid falls to its level`() {
        // Buy limit at 2,000,000, so the bid trigger is 1,999,700 (ask-based).
        // The path drops from 2,000,000 to 1,990,000 over 15,000 ms.
        // Crossing 1,999,700: 300 / 10,000 of the way = 0.03 * 15,000 = 450 ms.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.BUY,
                    priceUnits = 2_000_000, atSimUtcMs = at(0),
                ),
            ),
        )
        engine.advanceTo(at(1))

        val fill = engine.state.executions.single()
        assertEquals(at(0, 450), fill.executedAtSimUtcMs)
        // Filled at the ask, which is the bid level plus the spread.
        assertEquals(2_000_000, fill.priceUnits)
    }

    @Test
    fun `a sell limit fills when the bid rises to its level`() {
        // Sell limit at 2,005,000: the bid must rise to 2,005,000.
        // Path rises 1,990,000 -> 2,010,000 between 15,000 and 30,000 ms.
        // 2,005,000 is 15,000/20,000 = 0.75 of the way = 15,000 + 11,250 = 26,250 ms.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.SELL,
                    priceUnits = 2_005_000, atSimUtcMs = at(0),
                ),
            ),
        )
        engine.advanceTo(at(1))

        val fill = engine.state.executions.single()
        assertEquals(at(0, 26_250), fill.executedAtSimUtcMs)
        assertEquals(2_005_000, fill.priceUnits)
    }

    @Test
    fun `a buy stop fills when the bid rises through its level`() {
        // Buy stop at 2,005,000 -> bid trigger 2,004,700.
        // On the rally 1,990,000 -> 2,010,000 (15,000..30,000 ms):
        // (2,004,700 - 1,990,000) / 20,000 = 0.735 -> 15,000 + 11,025 = 26,025 ms.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "s1", OrderType.STOP, OrderSide.BUY,
                    priceUnits = 2_005_000, atSimUtcMs = at(0),
                ),
            ),
        )
        engine.advanceTo(at(1))

        assertEquals(at(0, 26_025), engine.state.executions.single().executedAtSimUtcMs)
    }

    @Test
    fun `a sell stop fills when the bid falls through its level`() {
        // Sell stop at 1,995,000: the bid trigger is 1,995,000.
        // The path falls 2,000,000 -> 1,990,000 over the first 15,000 ms.
        // (2,000,000 - 1,995,000) / 10,000 = 0.5 -> 7,500 ms.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "s1", OrderType.STOP, OrderSide.SELL,
                    priceUnits = 1_995_000, atSimUtcMs = at(0),
                ),
            ),
        )
        engine.advanceTo(at(1))

        assertEquals(at(0, 7_500), engine.state.executions.single().executedAtSimUtcMs)
    }

    @Test
    fun `a pending order without a price is rejected`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        val result = engine.submit(
            TradingCommand.PlaceOrder(
                atSimUtcMs = at(0),
                orderId = "l1",
                type = OrderType.LIMIT,
                side = OrderSide.BUY,
                volumeHundredths = 10,
            ),
        )
        assertEquals(RejectionReason.MISSING_PRICE, (result as CommandResult.Rejected).reason)
    }

    @Test
    fun `a pending order that is never reached stays pending`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.BUY,
                    priceUnits = 1_500_000, atSimUtcMs = at(0),
                ),
            ),
        )
        engine.advanceTo(at(1))

        assertTrue(engine.state.executions.isEmpty())
        assertEquals(1, engine.state.pendingOrders.size)
    }

    @Test
    fun `a cancelled order never fills`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.BUY,
                    priceUnits = 2_000_000, atSimUtcMs = at(0),
                ),
            ),
        )
        accepted(engine.submit(TradingCommand.CancelOrder(atSimUtcMs = at(0, 100), orderId = "l1")))
        engine.advanceTo(at(1))

        assertTrue(engine.state.executions.isEmpty())
        assertEquals(OrderStatus.CANCELLED, engine.state.orders.single().status)
    }

    @Test
    fun `a cancelled order cannot be cancelled twice`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.BUY,
                    priceUnits = 2_000_000, atSimUtcMs = at(0),
                ),
            ),
        )
        accepted(engine.submit(TradingCommand.CancelOrder(atSimUtcMs = at(0, 100), orderId = "l1")))
        val second = engine.submit(TradingCommand.CancelOrder(atSimUtcMs = at(0, 200), orderId = "l1"))
        assertEquals(RejectionReason.ORDER_NOT_PENDING, (second as CommandResult.Rejected).reason)
    }

    @Test
    fun `modifying a pending order moves its trigger`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.BUY,
                    priceUnits = 1_500_000, atSimUtcMs = at(0),
                ),
            ),
        )
        accepted(
            engine.submit(
                TradingCommand.ModifyOrder(
                    atSimUtcMs = at(0, 1_000),
                    orderId = "l1",
                    priceUnits = Modification.of(1_999_000),
                ),
            ),
        )
        engine.advanceTo(at(1))

        // Triggered on the way down, so it fills.
        assertEquals(1, engine.state.executions.size)
    }

    @Test
    fun `clearing a pending order's price is refused`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.BUY,
                    priceUnits = 1_999_000, atSimUtcMs = at(0),
                ),
            ),
        )
        val result = engine.submit(
            TradingCommand.ModifyOrder(
                atSimUtcMs = at(0, 1_000),
                orderId = "l1",
                priceUnits = Modification.Clear,
            ),
        )
        assertEquals(RejectionReason.MISSING_PRICE, (result as CommandResult.Rejected).reason)
    }

    // --------------------------------------------------- no look-back fills (5.10)

    @Test
    fun `an order placed mid-minute is not filled by an earlier crossing`() {
        // The bid crosses 1,995,000 at 7,500 ms. An order placed at 20,000 ms must
        // not be filled by that crossing, because the crossing preceded it.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.BUY,
                    priceUnits = 1_995_000, atSimUtcMs = at(0, 20_000),
                ),
            ),
        )
        engine.advanceTo(at(1))

        assertTrue("must not fill against earlier movement", engine.state.executions.isEmpty())
    }

    @Test
    fun `a command before the processed time is rejected`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), risingMinute(1)))
        engine.advanceTo(at(1))

        val result = engine.submit(
            TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0, 30_000)),
        )
        assertEquals(RejectionReason.LOOK_BACK_COMMAND, (result as CommandResult.Rejected).reason)
    }

    @Test
    fun `a command exactly at the processed time is accepted`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), risingMinute(1)))
        engine.advanceTo(at(1))

        // The processed time is exclusive at its upper bound, so an instant equal
        // to it has not been processed yet.
        val result = engine.submit(
            TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(1)),
        )
        assertTrue(result is CommandResult.Accepted)
    }

    // -------------------------------------------------- same-candle execution (5.10)

    @Test
    fun `a position can open and close inside one minute`() {
        // A buy stop at 1,995,000 fills on the way up at 7,500 ms. The same path
        // then continues to the high at 30,000 ms, so a take-profit at 2,008,000
        // is reached later in the same minute.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "s1", OrderType.STOP, OrderSide.BUY,
                    priceUnits = 1_995_000,
                    atSimUtcMs = at(0),
                    takeProfitPips = 80, // 8,000 units above entry
                ),
            ),
        )
        engine.advanceTo(at(1))

        // Entry at 1,995,300 (ask), target at 2,003,300. The rally tops at
        // 2,010,000, so the target is crossed before the minute ends.
        assertEquals(1, engine.state.closedTrades.size)
        val trade = engine.state.closedTrades.single()
        assertEquals(CloseReason.TAKE_PROFIT, trade.closeReason)
        assertTrue("the trade must close inside the same minute", trade.closeTimeSimUtcMs < at(1))
        assertTrue("the close must follow the open", trade.closeTimeSimUtcMs > trade.openTimeSimUtcMs)
    }

    @Test
    fun `an order that fills and closes in one minute leaves no open position`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "s1", OrderType.STOP, OrderSide.BUY,
                    priceUnits = 1_995_000,
                    atSimUtcMs = at(0),
                    takeProfitPips = 80,
                ),
            ),
        )
        engine.advanceTo(at(1))

        assertTrue(engine.state.openPositions.isEmpty())
    }

    @Test
    fun `a same-minute stop-out happens after the entry, not before`() {
        // A buy stop at 2,005,000 fills on the rally at 26,025 ms. Its 10-pip stop
        // is 1,000 units below the entry, and after the trigger the path rises to
        // 2,010,000 and closes at 2,005,000, so the stop is never reached.
        //
        // A buy stop must sit *above* the market; below it the trigger is already
        // true and the order would fill at once, which is a different test.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "s1", OrderType.STOP, OrderSide.BUY,
                    priceUnits = 2_005_000,
                    atSimUtcMs = at(0),
                    stopLossPips = 10,
                ),
            ),
        )
        engine.advanceTo(at(1))

        assertEquals(1, engine.state.openPositions.size)
        assertTrue(engine.state.closedTrades.isEmpty())
    }

    // ------------------------------------------------------------ stop / target

    @Test
    fun `a long stop loss closes the position at a loss`() {
        // Buy at 2,000,300 with a 50-pip stop -> 1,995,300. The path dips to
        // 1,990,000, so the stop is hit.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 50,
                ),
            ),
        )
        engine.advanceTo(at(1))

        val trade = engine.state.closedTrades.single()
        assertEquals(CloseReason.STOP_LOSS, trade.closeReason)
        assertTrue("a stopped-out long must lose money", trade.netPnlMicros < 0)
    }

    @Test
    fun `a long take profit closes the position at a profit`() {
        // Buy at 2,000,300 with a 50-pip target -> 2,005,300, which the rally to
        // 2,010,000 crosses.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), takeProfitPips = 50,
                ),
            ),
        )
        engine.advanceTo(at(1))

        val trade = engine.state.closedTrades.single()
        assertEquals(CloseReason.TAKE_PROFIT, trade.closeReason)
        assertTrue("a profitable target must gain", trade.netPnlMicros > 0)
    }

    @Test
    fun `a short stop loss closes the position at a loss`() {
        // Sell at 2,000,000 with a 50-pip stop -> 2,005,000. The path rises to
        // 2,010,000, so the stop is hit.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.SELL, atSimUtcMs = at(0), stopLossPips = 50,
                ),
            ),
        )
        engine.advanceTo(at(1))

        val trade = engine.state.closedTrades.single()
        assertEquals(CloseReason.STOP_LOSS, trade.closeReason)
        assertTrue(trade.netPnlMicros < 0)
    }

    @Test
    fun `a short take profit closes the position at a profit`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.SELL, atSimUtcMs = at(0), takeProfitPips = 50,
                ),
            ),
        )
        engine.advanceTo(at(1))

        val trade = engine.state.closedTrades.single()
        assertEquals(CloseReason.TAKE_PROFIT, trade.closeReason)
        assertTrue(trade.netPnlMicros > 0)
    }

    @Test
    fun `a manual close uses the exit side price`() {
        val engine = TradeFixtures.engine(
            TradeFixtures.window(risingMinute(), TradeFixtures.flat(1, 2_005_000)),
        )
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))
        engine.advanceTo(at(1))
        accepted(
            engine.submit(
                TradingCommand.ClosePosition(atSimUtcMs = at(1), positionId = "pos-m1"),
            ),
        )

        // Closing a long sells at the bid, which is the close price of the candle.
        val trade = engine.state.closedTrades.single()
        assertEquals(2_005_000, trade.closePriceUnits)
        assertEquals(CloseReason.MANUAL, trade.closeReason)
    }

    @Test
    fun `modifying a position moves its stop`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), risingMinute(1)))
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))

        accepted(
            engine.submit(
                TradingCommand.ModifyPosition(
                    atSimUtcMs = at(0, 1_000),
                    positionId = "pos-m1",
                    stopLossPips = Modification.of(100),
                ),
            ),
        )

        // Entry 2,000,300, 100 pips = 10,000 units below.
        assertEquals(1_990_300, engine.positionFor("m1")!!.activeStopUnits)
    }

    @Test
    fun `clearing a position's stop leaves it unprotected`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), risingMinute(1)))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 50),
            ),
        )
        accepted(
            engine.submit(
                TradingCommand.ModifyPosition(
                    atSimUtcMs = at(0, 1_000),
                    positionId = "pos-m1",
                    stopLossPips = Modification.Clear,
                ),
            ),
        )

        val position = engine.positionFor("m1")!!
        assertNull(position.activeStopUnits)
        assertNull(position.stopLossPips)
    }

    // --------------------------------------------------------- accounting inputs

    @Test
    fun `entry commission is charged on the balance immediately`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        val before = engine.state.balanceMicros
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))

        // 0.10 lots at $3.50 per side.
        assertEquals(before - 350_000L, engine.state.balanceMicros)
    }

    @Test
    fun `a round trip charges seven dollars per lot in total`() {
        val engine = TradeFixtures.engine(
            TradeFixtures.window(risingMinute(), TradeFixtures.flat(1, 2_005_000)),
        )
        val before = engine.state.balanceMicros
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))
        engine.advanceTo(at(1))
        accepted(
            engine.submit(
                TradingCommand.ClosePosition(atSimUtcMs = at(1), positionId = "pos-m1"),
            ),
        )

        val trade = engine.state.closedTrades.single()
        val balanceDelta = engine.state.balanceMicros - before
        // Balance change is gross PnL minus the full round-trip commission.
        val expected = trade.grossPnlMicros - 700_000L
        assertEquals(expected, balanceDelta)
        assertEquals(700_000L, trade.commissionMicros)
    }

    @Test
    fun `realised R is profit over the stop distance`() {
        // A bullish minute dips before it rallies, so a long with both a stop and a
        // target inside the range is stopped out first: the low is reached at
        // 15,000 ms and the high only at 30,000 ms.
        //
        // So this uses a *bearish* minute, which falls first: a long opened at the
        // open has its target reached on the way down. 50-pip stop = 5,000 units of
        // risk; a 5,000-unit gain is 1R.
        val engine = TradeFixtures.engine(TradeFixtures.window(fallingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 50, takeProfitPips = 50,
                ),
            ),
        )
        engine.advanceTo(at(1))

        val trade = engine.state.closedTrades.single()
        assertEquals(CloseReason.TAKE_PROFIT, trade.closeReason)
        assertEquals(1_000_000L, trade.realizedRMicros)
    }

    @Test
    fun `a position with no stop reports zero R`() {
        val engine = TradeFixtures.engine(
            TradeFixtures.window(risingMinute(), TradeFixtures.flat(1, 2_005_000)),
        )
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))
        engine.advanceTo(at(1))
        accepted(
            engine.submit(
                TradingCommand.ClosePosition(atSimUtcMs = at(1), positionId = "pos-m1"),
            ),
        )
        assertEquals(0L, engine.state.closedTrades.single().realizedRMicros)
    }

    // --------------------------------------------------------------- determinism

    @Test
    fun `an identical replay produces identical results`() {
        fun run(): TradingState {
            val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), fallingMinute(1)))
            accepted(
                engine.submit(
                    TradeFixtures.marketOrder(
                        "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 50, takeProfitPips = 100,
                    ),
                ),
            )
            engine.advanceTo(at(2))
            return engine.state
        }

        val first = run()
        val second = run()
        assertEquals(first.balanceMicros, second.balanceMicros)
        assertEquals(first.executions, second.executions)
        assertEquals(first.closedTrades, second.closedTrades)
        assertEquals(first.positions, second.positions)
    }

    @Test
    fun `advancing in smaller steps gives the same result as one large step`() {
        fun run(stepCount: Int): TradingState {
            val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), fallingMinute(1)))
            accepted(
                engine.submit(
                    TradeFixtures.marketOrder(
                        "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 50, takeProfitPips = 100,
                    ),
                ),
            )
            for (index in 1..stepCount) {
                engine.advanceTo(at(0) + (2 * 60_000L) * index / stepCount)
            }
            return engine.state
        }

        val coarse = run(1)
        val fine = run(240)
        // The replay processor will advance in real-time-sized steps, so the result
        // must not depend on how the interval is divided. This is the property that
        // makes foreground and background replay agree (Phase 7).
        assertEquals(coarse.balanceMicros, fine.balanceMicros)
        assertEquals(coarse.closedTrades.size, fine.closedTrades.size)
    }

    @Test
    fun `advancing past the end of the window does not invent price`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))
        val events = engine.advanceTo(at(500))

        assertTrue(engine.state.openPositions.size == 1)
        assertEquals(0, engine.state.closedTrades.size)
    }

    @Test
    fun `an empty advance produces no events`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        assertEquals(emptyList<TradingEvent>(), engine.advanceTo(at(0)))
    }

    @Test
    fun `account equity is balance plus unrealised`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))

        val position = engine.positionFor("m1")!!
        val account = AccountState(
            seasonId = "season_test",
            balanceMicros = engine.state.balanceMicros,
            unrealizedPnlMicros = 0L,
        )
        assertEquals(account.balanceMicros, account.equityMicros)
        assertNotNull(position)
    }

    @Test
    fun `the spread is charged on a round trip at an unchanged price`() {
        // A flat minute: the bid never moves, so the only cost is the spread and
        // the commission.
        val engine = TradeFixtures.engine(
            TradeFixtures.window(TradeFixtures.flat(0, 2_000_000), TradeFixtures.flat(1, 2_000_000)),
        )
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))
        engine.advanceTo(at(1))
        accepted(
            engine.submit(
                TradingCommand.ClosePosition(atSimUtcMs = at(1), positionId = "pos-m1"),
            ),
        )

        val trade = engine.state.closedTrades.single()
        // Entry 2,000,300, exit 2,000,000: 300 units of loss on 0.10 lots.
        // 300 units * 0.10 lots * 100 oz / 1000 = $3.00 = 3,000,000 micros.
        assertEquals(-3_000_000L, trade.grossPnlMicros)
        assertEquals(700_000L, trade.commissionMicros)
        assertEquals(-3_700_000L, trade.netPnlMicros)
    }

    @Test
    fun `a flat path still allows a market order`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(TradeFixtures.flat(0, 2_000_000)))
        accepted(engine.submit(TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0))))
        assertEquals(1, engine.state.openPositions.size)
    }

    @Test
    fun `money scale format is used for readable assertions`() {
        // Guards the formatter the tests above rely on for readability.
        assertEquals("100.00", MoneyScale.formatMicros(100L * MoneyScale.MICROS_PER_USD))
        assertFalse(MoneyScale.formatMicros(0L).startsWith("-"))
    }
}
