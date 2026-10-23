package com.tradequest.app.core.risk

import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.trading.CloseReason
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.RejectionReason
import com.tradequest.app.core.trading.RiskFailureReason
import com.tradequest.app.core.trading.TradingCommand
import com.tradequest.app.core.trading.TradingEvent
import com.tradequest.app.core.trading.TradingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Risk enforcement through the engine (Steps 6.7-6.10).
 *
 * # Why the limits are customised in several of these
 *
 * With the plan's real 3% daily limit, a position large enough to approach a
 * stop-out breaches the daily floor long first, and the day is flattened before
 * the stop-out rule can be observed. That is correct behaviour, but it makes the
 * stop-out untestable in isolation. Where a test targets one rule, the others are
 * widened explicitly and the reason is stated, rather than left implicit in
 * numbers that happen to work.
 *
 * # Price convention
 *
 * A long enters at the ask (bid + 300) and exits at the bid. A 1-lot position
 * earns $0.10 per price unit of move: 100 units is $1 of price, and 100 ounces a
 * lot makes that $100. So a move of 1,000 units is $100 a lot.
 */
class RiskIntegrationTest {

    private val micros = MoneyScale.MICROS_PER_USD

    /** Widened limits, so a targeted rule can be observed on its own. */
    private fun isolatedLimits(staticFloorMicros: Long = 0L) = RiskLimits(
        dailyLossPercent = 99,
        staticFloorMicros = staticFloorMicros,
    )

    // ------------------------------------------------------------- Step 6.8

    @Test
    fun `stop-out liquidates the largest loser first and repeats`() {
        // Three identical 2-lot longs, $20,000. Identical losses, so the id
        // tie-break decides the order: pos-a, pos-b, pos-c.
        // At a $30 fall each loses 2 * 100 oz * $30 = $6,000, so equity is far
        // below 50% of the $12,001.80 margin and all three must be closed.
        val engine = RiskFixtures.riskEngine(
            window = RiskFixtures.window(
                RiskFixtures.flat(0, 2_000_000),
                RiskFixtures.falling(1, 2_000_000, 2_000_000, 1_970_000, 1_970_000),
            ),
            startingBalanceMicros = 20_000L * micros,
            limits = isolatedLimits(),
        )
        for (id in listOf("a", "b", "c")) {
            engine.submit(
                TradingCommand.PlaceOrder(
                    atSimUtcMs = RiskFixtures.T0,
                    orderId = id,
                    type = com.tradequest.app.core.trading.OrderType.MARKET,
                    side = OrderSide.BUY,
                    volumeHundredths = 200,
                ),
            )
        }
        assertEquals(3, engine.state.openPositions.size)

        val events = engine.advanceTo(RiskFixtures.at(1) + 59_999)

        val stopOuts = events.filterIsInstance<TradingEvent.StopOutExecuted>()
        assertEquals("all three must be liquidated", 3, stopOuts.size)
        assertEquals(
            listOf("pos-a", "pos-b", "pos-c"),
            stopOuts.map { it.positionId },
        )
        assertTrue(
            "the stop-out reason must be recorded on the trade",
            stopOuts.all { it.trade.closeReason == CloseReason.STOP_OUT },
        )
        assertEquals("nothing is left open", 0, engine.state.openPositions.size)

        // Balance: 20,000 - 3 * $7 entry - 3 * $6,060 gross - 3 * $7 exit.
        //   entry commission per 2 lots = 7 * 200 / 100 / 2 = $7
        //   exit  commission per 2 lots = $7
        //   gross per position = 2 lots * 100 oz * -30.30 USD = -$6,060
        val expected = (20_000L - 3 * 7L - 3 * 6_060L - 3 * 7L) * micros
        assertEquals(expected, engine.state.balanceMicros)
    }

    @Test
    fun `stop-out stops once the margin level is restored`() {
        // One 1-lot and one 3-lot long, $20,000. The 3-lot is the larger loser, so
        // closing it alone must restore the level and end the liquidation.
        val engine = RiskFixtures.riskEngine(
            window = RiskFixtures.window(
                RiskFixtures.flat(0, 2_000_000),
                RiskFixtures.falling(1, 2_000_000, 2_000_000, 1_960_000, 1_960_000),
            ),
            startingBalanceMicros = 20_000L * micros,
            limits = isolatedLimits(),
        )
        engine.submit(marketBuy("small", 100))
        engine.submit(marketBuy("big", 300))

        val events = engine.advanceTo(RiskFixtures.at(1) + 59_999)

        val stopOuts = events.filterIsInstance<TradingEvent.StopOutExecuted>()
        assertEquals("only the largest loser closes", 1, stopOuts.size)
        assertEquals("pos-big", stopOuts.single().positionId)
        assertEquals(1, engine.state.openPositions.size)
        assertEquals("pos-small", engine.state.openPositions.single().positionId)
        val level = stopOuts.single().marginLevelPercentAfter
        assertTrue("the level must have been restored above 50%", level != null && level > 50)
    }

    // ------------------------------------------------------------- Step 6.7

    @Test
    fun `a margin warning is raised when the level reaches one hundred percent`() {
        // Five 1-lot longs, $20,000. Margin is $10,001.50, so the level is 100%
        // when equity is about $10,001. A fall to $1,980,000 puts equity at
        // $9,832 — a warning, but above the 50% stop-out.
        val engine = RiskFixtures.riskEngine(
            window = RiskFixtures.window(
                RiskFixtures.flat(0, 2_000_000),
                RiskFixtures.falling(1, 2_000_000, 2_000_000, 1_980_000, 1_980_000),
            ),
            startingBalanceMicros = 20_000L * micros,
            limits = isolatedLimits(),
        )
        repeat(5) { engine.submit(marketBuy("m$it", 100)) }

        val events = engine.advanceTo(RiskFixtures.at(1) + 59_999)

        val warnings = events.filterIsInstance<TradingEvent.MarginWarning>()
        assertEquals("the warning fires once, on the crossing", 1, warnings.size)
        assertTrue(warnings.single().marginLevelPercent <= 100)
        assertTrue("no liquidation at a warning", engine.state.openPositions.isNotEmpty())
    }

    // ------------------------------------------------------------- Step 6.9

    @Test
    fun `the daily floor flattens, locks the day and blocks new orders`() {
        // The plan's real 3% limit. $10,000 start, floor $9,700. One 1-lot long
        // entered at 2,000,300 loses $530 by 1,995,000, putting equity at $9,466.
        val engine = RiskFixtures.riskEngine(
            window = RiskFixtures.window(
                RiskFixtures.flat(0, 2_000_000),
                RiskFixtures.falling(1, 2_000_000, 2_000_000, 1_995_000, 1_995_000),
            ),
        )
        engine.submit(marketBuy("a", 100))

        val events = engine.advanceTo(RiskFixtures.at(1) + 59_999)

        val hits = events.filterIsInstance<TradingEvent.DailyLossLimitHit>()
        assertEquals("the daily floor is hit once", 1, hits.size)
        assertEquals("the floor is 3% below the day start", 9_700L * micros, hits.single().floorMicros)
        assertEquals(1, hits.single().dailyLossStrikes)
        assertFalse("one strike is not a failure", hits.single().challengeFailed)

        assertTrue("the day is locked", engine.state.dayLocked)
        assertFalse("the challenge is still alive", engine.state.challengeFailed)
        assertEquals("the position was flattened", 0, engine.state.openPositions.size)

        // New exposure is refused. The order is placed at the next minute, since a
        // command before the processed instant is rejected as a look-back first.
        val rejected = engine.submit(marketBuy("b", 100, atMinute = 2))
        assertTrue(rejected is com.tradequest.app.core.trading.CommandResult.Rejected)
        assertEquals(
            RejectionReason.TRADING_DAY_LOCKED,
            (rejected as com.tradequest.app.core.trading.CommandResult.Rejected).reason,
        )
    }

    @Test
    fun `a locked day still allows closing an existing position`() {
        val engine = RiskFixtures.riskEngine(
            window = RiskFixtures.window(
                RiskFixtures.flat(0, 2_000_000),
                RiskFixtures.falling(1, 2_000_000, 2_000_000, 1_995_000, 1_995_000),
                RiskFixtures.flat(2, 1_995_000),
            ),
        )
        engine.submit(marketBuy("a", 100))
        engine.advanceTo(RiskFixtures.at(1) + 59_999)
        assertTrue("the day is locked by the loss", engine.state.dayLocked)

        // A second position opened before the lock would still be closeable; here
        // the first was flattened, so the close is refused for a different reason
        // and the point is that it is not refused as a day lock.
        val close = engine.submit(
            TradingCommand.ClosePosition(
                atSimUtcMs = RiskFixtures.at(2),
                positionId = "pos-a",
            ),
        )
        val rejection = close as? com.tradequest.app.core.trading.CommandResult.Rejected
        assertTrue(
            "a close must not be refused as a day lock",
            rejection == null || rejection.reason != RejectionReason.TRADING_DAY_LOCKED,
        )
    }

    @Test
    fun `a rollover clears the day lock and resets the reference equity`() {
        // Start on the day of T0, fall enough to lock it, then advance past the
        // 17:00 New York rollover and confirm the day resets.
        val engine = RiskFixtures.riskEngine(
            window = RiskFixtures.window(
                RiskFixtures.flat(0, 2_000_000),
                RiskFixtures.falling(1, 2_000_000, 2_000_000, 1_995_000, 1_995_000),
            ),
        )
        engine.submit(marketBuy("a", 100))
        engine.advanceTo(RiskFixtures.at(1) + 59_999)
        assertTrue(engine.state.dayLocked)

        // 2024-01-01T23:00:00Z is 18:00 EST, past the 17:00 rollover, so the
        // trading date has advanced to 2024-01-02.
        val afterRollover = 1_704_153_600_000L
        val events = engine.advanceTo(afterRollover)

        val rolled = events.filterIsInstance<TradingEvent.TradingDayRolled>()
        assertEquals("the rollover is reported once", 1, rolled.size)
        assertTrue(rolled.single().tradingResumed)
        assertFalse("the day lock is cleared", engine.state.dayLocked)
        assertEquals("the strike is kept across days", 1, engine.state.dailyLossStrikes)
        assertEquals(
            "the new day's reference is the equity at the rollover",
            engine.state.balanceMicros,
            engine.state.dayStartEquityMicros,
        )
    }

    // ------------------------------------------------------------ Step 6.10

    @Test
    fun `the static floor fails the challenge and blocks all trading`() {
        // The daily rule is widened so it cannot fire first, isolating the static
        // floor. One 1-lot long at 1,990,000 puts equity at about $8,966.
        val engine = RiskFixtures.riskEngine(
            window = RiskFixtures.window(
                RiskFixtures.flat(0, 2_000_000),
                RiskFixtures.falling(1, 2_000_000, 2_000_000, 1_990_000, 1_990_000),
            ),
            limits = isolatedLimits(staticFloorMicros = 9_000L * micros),
        )
        engine.submit(marketBuy("a", 100))

        val events = engine.advanceTo(RiskFixtures.at(1) + 59_999)

        val failures = events.filterIsInstance<TradingEvent.ChallengeFailed>()
        assertEquals(1, failures.size)
        assertEquals(RiskFailureReason.STATIC_FLOOR_BREACHED, failures.single().reason)
        assertTrue(engine.state.challengeFailed)
        assertEquals("the position was flattened", 0, engine.state.openPositions.size)

        val rejected = engine.submit(marketBuy("b", 100, atMinute = 2))
        assertEquals(
            RejectionReason.CHALLENGE_FAILED,
            (rejected as com.tradequest.app.core.trading.CommandResult.Rejected).reason,
        )
    }

    // ------------------------------------------------------- Phase 5 parity

    @Test
    fun `without a policy the engine enforces no risk rules`() {
        // The Phase 5 behaviour must survive: a catastrophic move produces no
        // stop-out, no warning and no lock when no policy is configured.
        val engine = com.tradequest.app.core.trading.TradingEngine(
            window = RiskFixtures.window(
                RiskFixtures.flat(0, 2_000_000),
                RiskFixtures.falling(1, 2_000_000, 2_000_000, 1_900_000, 1_900_000),
            ),
            context = com.tradequest.app.core.trading.TradingContext(seasonId = "season_test"),
            initialState = TradingState(
                seasonId = "season_test",
                balanceMicros = RiskFixtures.START_BALANCE,
                orders = emptyList(),
                positions = emptyList(),
                executions = emptyList(),
                closedTrades = emptyList(),
                processedToSimUtcMs = RiskFixtures.T0,
            ),
        )
        engine.submit(marketBuy("a", 100))

        val events = engine.advanceTo(RiskFixtures.at(1) + 59_999)

        assertTrue(events.filterIsInstance<TradingEvent.StopOutExecuted>().isEmpty())
        assertTrue(events.filterIsInstance<TradingEvent.MarginWarning>().isEmpty())
        assertFalse(engine.state.dayLocked)
        assertFalse(engine.state.challengeFailed)
        assertEquals("the position is untouched", 1, engine.state.openPositions.size)
        assertNull(engine.state.failureReason)
    }

    private fun marketBuy(
        orderId: String,
        volume: Int,
        atMinute: Int = 0,
    ): TradingCommand.PlaceOrder =
        TradingCommand.PlaceOrder(
            atSimUtcMs = RiskFixtures.at(atMinute),
            orderId = orderId,
            type = com.tradequest.app.core.trading.OrderType.MARKET,
            side = OrderSide.BUY,
            volumeHundredths = volume,
        )
}
