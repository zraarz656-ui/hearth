package com.tradequest.app.core.trading

import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.simulation.path.GapReason
import com.tradequest.app.core.trading.TradeFixtures.accepted
import com.tradequest.app.core.trading.TradeFixtures.at
import com.tradequest.app.core.trading.TradeFixtures.positionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 5.11: what happens when price jumps a closure.
 *
 * # The rule being pinned
 *
 * Between two candles there is no path. The plan forbids assuming a fill at a
 * price that was never available, so a trigger inside the jump is resolved by
 * [GapExecution]:
 *
 *  - a **stop-like** trigger (a stop order, or a position's stop-loss) fills at
 *    the first price actually available after the gap, which is worse than its
 *    own level;
 *  - a **limit-like** trigger (a limit order, or a take-profit) fills at its own
 *    level, because a resting order at that price was genuinely crossed.
 *
 * A gap fill never adds slippage: the gap price already contains the adverse move.
 *
 * # The fixtures
 *
 * [weekendGap] is a Friday close at 2,000,000 followed by a Sunday open at
 * 1,950,000 — a 500,000-unit fall across a break, the classic weekend gap. Any
 * trigger between those two prices was crossed at no observable instant.
 */
class GapExecutionTest {

    /**
     * Friday 21:59, then Sunday 22:00 — a two-day closure.
     *
     * The Friday minute is flat at [before], the Sunday minute flat at [after], so
     * the only movement is the gap itself.
     */
    private fun weekendGap(
        before: Int = 2_000_000,
        after: Int = 1_950_000,
    ): com.tradequest.app.core.simulation.path.MarketPathWindow {
        val friday = HistoricalCandle(
            timestampUtcMs = TradeFixtures.T0,
            openUnits = before, highUnits = before, lowUnits = before, closeUnits = before,
            tickVolume = 100,
        )
        // A 48-hour break: Friday minute, then the same minute two days later.
        val sunday = HistoricalCandle(
            timestampUtcMs = TradeFixtures.T0 + 2 * 24 * 60 * 60_000L,
            openUnits = after, highUnits = after, lowUnits = after, closeUnits = after,
            tickVolume = 100,
        )
        return com.tradequest.app.core.simulation.path.MarketPathBuilder.build(
            datasetId = "ds_gap",
            candles = listOf(friday, sunday),
            gapReasonAt = { GapReason.MARKET_CLOSURE },
        ).let { com.tradequest.app.core.simulation.path.MarketPathWindow("ds_gap", it) }
    }

    private fun gapStart() = TradeFixtures.T0 + 60_000L
    private fun gapEnd() = TradeFixtures.T0 + 2 * 24 * 60 * 60_000L

    // ------------------------------------------------------------- the gap itself

    @Test
    fun `the window reports no price inside the gap`() {
        val window = weekendGap()
        assertNotNull(window.gapContaining(gapStart()))
        assertTrue(window.priceAt(gapStart()) == null)
    }

    @Test
    fun `the window reports a price on both sides of the gap`() {
        val window = weekendGap()
        assertEquals(2_000_000, window.priceAt(TradeFixtures.T0))
        assertEquals(1_950_000, window.priceAt(gapEnd()))
    }

    // ------------------------------------------------------- stop-like across a gap

    @Test
    fun `a long stop inside a gap fills at the post-gap price, not the stop`() {
        // A long entered at 2,000,300 with a stop at 1,995,300. The market gaps
        // straight to 1,950,000, so the stop is crossed inside the break.
        val window = weekendGap()
        val engine = TradeFixtures.engine(window)
        accepted(
            engine.submit(
                TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = TradeFixtures.T0),
            ),
        )
        accepted(
            engine.submit(
                TradingCommand.ModifyPosition(
                    atSimUtcMs = TradeFixtures.T0,
                    positionId = "pos-m1",
                    stopLossPips = Modification.of(50),
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        val trade = engine.state.closedTrades.single()
        assertEquals(CloseReason.STOP_LOSS, trade.closeReason)
        // Filled at the bid available after the gap, 1,950,000, not at the stop's
        // 1,995,300. Filling at the stop would understate the loss by 4,530 units.
        assertEquals(1_950_000, trade.closePriceUnits)
        assertTrue("the loss must be worse than the stop level implies", trade.netPnlMicros < 0)
    }

    @Test
    fun `a gap fill is worse than the stop level would suggest`() {
        val window = weekendGap()
        val engine = TradeFixtures.engine(window)
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = TradeFixtures.T0, stopLossPips = 50,
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        val trade = engine.state.closedTrades.single()
        // The entry was at the ask, 2,000,300, and the exit at the post-gap bid,
        // 1,950,000: a fall of 50,300 units, which is the 50,000-unit gap plus the
        // 300-unit spread. On 0.10 lots: 50,300 * 0.10 * 100 / 1000 = $503.
        assertEquals(-503_000_000L, trade.grossPnlMicros)
    }

    @Test
    fun `a sell stop order inside a gap fills at the post-gap price`() {
        // A sell stop at 1,990,000. The gap jumps to 1,950,000, below the stop.
        val window = weekendGap()
        val engine = TradeFixtures.engine(window)
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "s1", OrderType.STOP, OrderSide.SELL,
                    priceUnits = 1_990_000, atSimUtcMs = TradeFixtures.T0,
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        val fill = engine.state.executions.single()
        // A sell stop exits at the bid, so the fill is the post-gap bid itself.
        assertEquals(1_950_000, fill.priceUnits)
    }

    // ------------------------------------------------------ limit-like across a gap

    @Test
    fun `a long take profit inside a gap fills at its own level`() {
        // A *downward* gap with a long position cannot reach a take-profit above the
        // entry, so this uses an upward gap: Friday 2,000,000, Sunday 2,050,000.
        val window = weekendGap(before = 2_000_000, after = 2_050_000)
        val engine = TradeFixtures.engine(window)
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = TradeFixtures.T0, takeProfitPips = 100,
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        val trade = engine.state.closedTrades.single()
        assertEquals(CloseReason.TAKE_PROFIT, trade.closeReason)
        // Entry ask 2,000,300, 100 pips = 10,000 units above, so the target is
        // 2,010,300. A resting target at that price was crossed, so it fills there —
        // not at the post-gap 2,050,000, which would hand the user an extra 3,970
        // units they never asked for.
        assertEquals(2_010_300, trade.closePriceUnits)
    }

    @Test
    fun `a buy limit order inside a gap fills at its own level`() {
        // A buy limit at 1,999,000, with the market gapping down to 1,950,000. The
        // limit was crossed, so it fills at its level rather than the post-gap price.
        val window = weekendGap()
        val engine = TradeFixtures.engine(window)
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.BUY,
                    priceUnits = 1_999_000, atSimUtcMs = TradeFixtures.T0,
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        val fill = engine.state.executions.single()
        // A buy limit fills at the ask, which is the requested price.
        assertEquals(1_999_000, fill.priceUnits)
    }

    @Test
    fun `a limit order is not filled better than its price by a gap`() {
        val window = weekendGap()
        val engine = TradeFixtures.engine(window)
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.BUY,
                    priceUnits = 1_999_000, atSimUtcMs = TradeFixtures.T0,
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        // The post-gap bid is 1,950,000, but the fill must be 1,999,000: the user
        // asked for that price and no better.
        assertTrue(engine.state.executions.single().priceUnits > 1_950_000)
    }

    // ------------------------------------------------------- no slippage on a gap

    @Test
    fun `a gap fill records no slippage`() {
        val window = weekendGap()
        val engine = TradeFixtures.engine(window)
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = TradeFixtures.T0, stopLossPips = 50,
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        // The gap price already contains the adverse move, so adding slippage would
        // charge twice for the same event.
        assertEquals(0, engine.state.executions.last().slippageUnits)
    }

    @Test
    fun `a gap fill during news still records no slippage`() {
        val release = TradeFixtures.T0
        val window = weekendGap()
        val engine = TradeFixtures.engine(window, newsReleaseTimesUtcMs = listOf(release))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = TradeFixtures.T0, stopLossPips = 50,
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        assertEquals(0, engine.state.executions.last().slippageUnits)
    }

    // --------------------------------------------------- order placed after a gap

    @Test
    fun `an order placed after a gap is not filled by that gap`() {
        // The order is placed on the Sunday, after the break, so the break is not
        // available to fill it. The limit sits *below* the post-gap market so that
        // it is a genuine resting order rather than one already through the price.
        val window = weekendGap()
        val engine = TradeFixtures.engine(window)
        accepted(
            engine.submit(
                TradeFixtures.pendingOrder(
                    "l1", OrderType.LIMIT, OrderSide.BUY,
                    priceUnits = 1_940_000, atSimUtcMs = gapEnd(),
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        // The Sunday minute is flat at 1,950,000, above the limit, so it never fills.
        assertTrue(engine.state.executions.isEmpty())
    }

    @Test
    fun `a market order inside a gap is refused`() {
        val window = weekendGap()
        val engine = TradeFixtures.engine(window)
        val result = engine.submit(
            TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = gapStart()),
        )
        assertEquals(RejectionReason.MARKET_CLOSED, (result as CommandResult.Rejected).reason)
    }

    // ------------------------------------------------------- both sides at once

    @Test
    fun `a downward gap resolves a short's target, not its stop`() {
        // A short profits when price falls. Its target sits below the entry and its
        // stop above, so a single downward gap can only reach the target.
        //
        // Entry at the bid, 2,000,000, with a 100-pip target (10,000 units) below:
        // 1,990,000 on the exit side, which for a short is the ask, so the bid
        // trigger is 1,990,000 - 300 = 1,989,700. The gap to 1,950,000 is below it.
        val window = weekendGap(before = 2_000_000, after = 1_950_000)
        val engine = TradeFixtures.engine(window)
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.SELL, atSimUtcMs = TradeFixtures.T0,
                    stopLossPips = 100, takeProfitPips = 100,
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        val trade = engine.state.closedTrades.single()
        assertEquals(CloseReason.TAKE_PROFIT, trade.closeReason)
        // A target is limit-like, so it fills at its own level, not the post-gap
        // price: the user asked for 100 pips and no more.
        assertEquals(1_990_000, trade.closePriceUnits)
    }

    @Test
    fun `a position survives a gap it does not touch`() {
        // The stop is 200 pips below the entry, and the gap moves 50,000 units
        // (500 pips), so it does touch. Use a much wider stop instead.
        val window = weekendGap()
        val engine = TradeFixtures.engine(window)
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = TradeFixtures.T0, stopLossPips = 2_000,
                ),
            ),
        )
        engine.advanceTo(gapEnd() + 60_000L)

        assertTrue("a 2,000-pip stop survives a 500-pip gap", engine.state.closedTrades.isEmpty())
    }

    // --------------------------------------------------------- gap execution rules

    @Test
    fun `stop-like trigger kinds fill at the gap price`() {
        assertTrue(GapExecution.isStopLike(TriggerKind.STOP_LOSS))
        assertTrue(GapExecution.isStopLike(TriggerKind.PENDING_ORDER_STOP))
    }

    @Test
    fun `limit-like trigger kinds fill at their own level`() {
        assertTrue(!GapExecution.isStopLike(TriggerKind.TAKE_PROFIT))
        assertTrue(!GapExecution.isStopLike(TriggerKind.PENDING_ORDER_LIMIT))
    }

    @Test
    fun `a stop-like fill uses the gap end price`() {
        assertEquals(
            1_950_000,
            GapExecution.fillBidUnits(TriggerKind.STOP_LOSS, 1_995_300, 1_950_000),
        )
    }

    @Test
    fun `a limit-like fill uses its own level`() {
        assertEquals(
            1_999_000,
            GapExecution.fillBidUnits(TriggerKind.PENDING_ORDER_LIMIT, 1_999_000, 1_950_000),
        )
    }
}
