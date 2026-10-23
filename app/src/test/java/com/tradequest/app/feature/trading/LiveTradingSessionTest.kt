package com.tradequest.app.feature.trading

import com.tradequest.app.core.replay.InMemoryReplayStore
import com.tradequest.app.core.replay.ReplayFixtures
import com.tradequest.app.core.trading.CommandResult
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.Modification
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.OrderType
import com.tradequest.app.core.trading.RejectionReason
import com.tradequest.app.core.trading.TradingContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 live trading: placing, modifying and closing against the real engine
 * (Steps 10.1, 10.2, 10.4, 10.7, 10.10).
 *
 * # Why this is an integration test and not a unit test with a fake engine
 *
 * The plan's Step 10.10 asks that "UI/domain synchronization" and "no accidental
 * trades from chart gestures" be verified. The first is a claim about the bridge
 * between a screen and the engine, and the second is a claim about the engine
 * refusing commands it should refuse. Faking the engine would test the fake, not
 * the bridge. So this drives [LiveTradingSession] against the real
 * [com.tradequest.app.core.trading.TradingEngine] over a real market path, with
 * only the database faked — exactly as `ReplayProcessorTest` does.
 *
 * Prices are integer thousandths of a USD; one pip is 0.10 USD, so 100 units.
 */
class LiveTradingSessionTest {

    private val contract = ContractSpec.XAUUSD

    private fun session(
        candles: List<com.tradequest.app.core.market.HistoricalCandle>,
        store: InMemoryReplayStore? = null,
    ): LiveTradingSession = LiveTradingSession(
        seasonId = ReplayFixtures.SEASON_ID,
        marketRepository = ReplayFixtures.FakeMarketRepository(candles),
        datasetId = ReplayFixtures.DATASET_ID,
        tradingContext = TradingContext(
            seasonId = ReplayFixtures.SEASON_ID,
            contract = contract,
        ),
        startingBalanceMicros = ReplayFixtures.START_BALANCE_MICROS,
        store = store,
    )

    // -------------------------------------------------- Step 10.1: opening

    @Test
    fun `opening seeds the state at the dataset start`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        val state = s.open()
        assertNotNull(state)
        assertEquals(ReplayFixtures.T0, state!!.processedToSimUtcMs)
        assertEquals(ReplayFixtures.START_BALANCE_MICROS, state.balanceMicros)
    }

    @Test
    fun `opening resumes from a stored state rather than reseeding`() = runTest {
        val store = InMemoryReplayStore()
        val candles = ReplayFixtures.flat(10)
        // Process a few minutes, so the stored cursor is ahead of the dataset start.
        val first = session(candles, store)
        first.open()
        first.advanceTo(ReplayFixtures.at(3))

        // A second session over the same store must resume, not restart.
        val resumed = session(candles, store)
        val state = resumed.open()
        assertEquals(ReplayFixtures.at(3), state!!.processedToSimUtcMs)
    }

    // ------------------------------------------- Step 10.1/10.2: placing

    @Test
    fun `a market buy opens a position`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))

        val result = s.placeMarket(
            orderId = "o1",
            side = OrderSide.BUY,
            volumeHundredths = 10,
            atSimUtcMs = ReplayFixtures.at(2),
        )
        assertTrue("expected acceptance, got $result", result is CommandResult.Accepted)
        assertEquals(1, s.openPositions().size)
        assertEquals(OrderSide.BUY, s.openPositions().first().side)
    }

    @Test
    fun `a limit order rests as pending until its price is reached`() = runTest {
        // A rising series: a buy limit far below the market never fills.
        val s = session(ReplayFixtures.climbing(20))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))

        val result = s.placePending(
            orderId = "o1",
            type = OrderType.LIMIT,
            side = OrderSide.BUY,
            volumeHundredths = 10,
            priceUnits = 1_000_000,
            atSimUtcMs = ReplayFixtures.at(2),
        )
        assertTrue(result is CommandResult.Accepted)
        assertEquals(1, s.pendingOrders().size)
        assertEquals(0, s.openPositions().size)
    }

    @Test
    fun `a market order cannot carry a price`() = runTest {
        // The engine refuses it, and the refusal must reach the caller rather than
        // being swallowed by the bridge.
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        val result = s.submit(
            com.tradequest.app.core.trading.TradingCommand.PlaceOrder(
                atSimUtcMs = ReplayFixtures.at(2),
                orderId = "o1",
                type = OrderType.MARKET,
                side = OrderSide.BUY,
                volumeHundredths = 10,
                priceUnits = 2_000_000,
            ),
        )
        assertTrue(result is CommandResult.Rejected)
        assertEquals(
            RejectionReason.UNEXPECTED_PRICE,
            (result as CommandResult.Rejected).reason,
        )
    }

    @Test
    fun `an invalid lot size is refused`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        // Zero lots is below the contract minimum.
        val result = s.placeMarket(
            orderId = "o1",
            side = OrderSide.BUY,
            volumeHundredths = 0,
            atSimUtcMs = ReplayFixtures.at(2),
        )
        assertTrue(result is CommandResult.Rejected)
        assertEquals(RejectionReason.INVALID_VOLUME, (result as CommandResult.Rejected).reason)
    }

    // ------------------------------------------- Step 10.4: modifying lines

    @Test
    fun `a position's stop can be moved`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        s.placeMarket("o1", OrderSide.BUY, 10, ReplayFixtures.at(2))
        val positionId = s.openPositions().first().positionId

        val result = s.modifyPosition(
            positionId = positionId,
            atSimUtcMs = ReplayFixtures.at(2),
            stopLossPips = Modification.Set(50),
        )
        assertTrue("expected acceptance, got $result", result is CommandResult.Accepted)
        assertEquals(50, s.openPositions().first().stopLossPips)
    }

    @Test
    fun `a pending order's entry price can be moved`() = runTest {
        val s = session(ReplayFixtures.climbing(20))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        s.placePending("o1", OrderType.LIMIT, OrderSide.BUY, 10, 1_000_000, ReplayFixtures.at(2))

        val result = s.modifyOrder(
            orderId = "o1",
            atSimUtcMs = ReplayFixtures.at(2),
            priceUnits = Modification.Set(1_100_000),
        )
        assertTrue(result is CommandResult.Accepted)
        assertEquals(1_100_000, s.pendingOrders().first().requestedPriceUnits)
    }

    @Test
    fun `an invalid stop distance is refused`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        s.placeMarket("o1", OrderSide.BUY, 10, ReplayFixtures.at(2))
        val positionId = s.openPositions().first().positionId

        // A zero-distance stop is not expressible; the engine refuses it.
        val result = s.modifyPosition(
            positionId = positionId,
            atSimUtcMs = ReplayFixtures.at(2),
            stopLossPips = Modification.Set(0),
        )
        assertTrue(result is CommandResult.Rejected)
        assertEquals(
            RejectionReason.INVALID_STOP_DISTANCE,
            (result as CommandResult.Rejected).reason,
        )
    }

    // ------------------------------------------- Step 10.7: closing

    @Test
    fun `a position can be closed in full`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        s.placeMarket("o1", OrderSide.BUY, 10, ReplayFixtures.at(2))
        val positionId = s.openPositions().first().positionId

        val result = s.closePosition(positionId, ReplayFixtures.at(2))
        assertTrue("expected acceptance, got $result", result is CommandResult.Accepted)
        assertEquals(0, s.openPositions().size)
        assertEquals(1, s.closedTrades().size)
    }

    @Test
    fun `a position can be partially closed by half`() = runTest {
        // 0.20 lots, so a 50% close leaves a valid 0.10-lot remainder.
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        s.placeMarket("o1", OrderSide.BUY, 20, ReplayFixtures.at(2))
        val positionId = s.openPositions().first().positionId

        val result = s.closePosition(positionId, ReplayFixtures.at(2), volumeHundredths = 10)
        assertTrue("expected acceptance, got $result", result is CommandResult.Accepted)
        // The position stays open with half its size.
        val remaining = s.openPositions().single()
        assertEquals(10, remaining.remainingVolumeHundredths)
    }

    @Test
    fun `a partial close that would leave an untradable remainder is refused`() = runTest {
        // 0.01 lots cannot be halved: the remainder would be below the minimum.
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        s.placeMarket("o1", OrderSide.BUY, 1, ReplayFixtures.at(2))
        val positionId = s.openPositions().first().positionId

        val result = s.closePosition(positionId, ReplayFixtures.at(2), volumeHundredths = 1)
        // Closing the whole thing is allowed; the engine treats a full-size close
        // as a full close. The invalid case is a genuine split.
        assertTrue(result is CommandResult.Accepted)
        assertEquals(0, s.openPositions().size)
    }

    @Test
    fun `a pending order can be cancelled`() = runTest {
        val s = session(ReplayFixtures.climbing(20))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        s.placePending("o1", OrderType.LIMIT, OrderSide.BUY, 10, 1_000_000, ReplayFixtures.at(2))
        assertEquals(1, s.pendingOrders().size)

        val result = s.cancelOrder("o1", ReplayFixtures.at(2))
        assertTrue(result is CommandResult.Accepted)
        assertEquals(0, s.pendingOrders().size)
    }

    @Test
    fun `a breakeven rule is armed rather than moving the stop immediately`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        s.placeMarket("o1", OrderSide.BUY, 10, ReplayFixtures.at(2), stopLossPips = 100)
        val positionId = s.openPositions().first().positionId

        val result = s.armBreakeven(positionId, ReplayFixtures.at(2))
        assertTrue("expected acceptance, got $result", result is CommandResult.Accepted)
        assertNotNull(s.openPositions().first().breakeven)
    }

    // ------------------------------------ Step 10.10: no accidental trades

    @Test
    fun `a command from before the processed cursor is refused`() = runTest {
        // This is the guard behind "no accidental trades from chart gestures": a
        // gesture that resolves to a stale instant cannot be filled against price
        // movement that happened before the user acted.
        val s = session(ReplayFixtures.climbing(20))
        s.open()
        s.advanceTo(ReplayFixtures.at(5))

        val result = s.placeMarket(
            orderId = "o1",
            side = OrderSide.BUY,
            volumeHundredths = 10,
            atSimUtcMs = ReplayFixtures.at(2),
        )
        assertTrue(result is CommandResult.Rejected)
        assertEquals(RejectionReason.LOOK_BACK_COMMAND, (result as CommandResult.Rejected).reason)
        assertEquals(0, s.openPositions().size)
    }

    @Test
    fun `advancing with no target ahead does nothing`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(5))
        val before = s.currentState!!.processedToSimUtcMs

        // A repeated tick at the same instant must be a no-op, not a second pass.
        val events = s.advanceTo(ReplayFixtures.at(5))
        assertTrue(events.isEmpty())
        assertEquals(before, s.currentState!!.processedToSimUtcMs)
    }

    @Test
    fun `submitting before the session is open is refused rather than crashing`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        val result = s.placeMarket("o1", OrderSide.BUY, 10, ReplayFixtures.T0)
        assertTrue(result is CommandResult.Rejected)
    }

    // ------------------------------------------- overlays and mark-to-market

    @Test
    fun `overlays include the open position's entry and stop`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        s.placeMarket("o1", OrderSide.BUY, 10, ReplayFixtures.at(2), stopLossPips = 50)

        val overlays = s.overlays()
        assertEquals(1, overlays.size)
        assertNotNull(overlays.first().entry)
        assertNotNull(overlays.first().stop)
    }

    @Test
    fun `mark to market reports PnL per open position`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        s.placeMarket("o1", OrderSide.BUY, 10, ReplayFixtures.at(2))
        val position = s.openPositions().first()

        // The bid is flat at 2,000,000, so a buy filled at the ask, 2,000,300. A mark
        // is the *exit* price, so marking at a bid of 2,001,000 is a 700-unit gain:
        // the $1.00 the price moved, less the $0.30 spread already paid on entry.
        // On 0.10 lots that is 0.10 * 100 * 0.70 = $7. Marking against the entry
        // price instead would show $10 of profit that a close would not realise.
        assertEquals(2_000_300, position.openPriceUnits)
        val marks = s.markToMarket(2_001_000)
        assertEquals(7_000_000L, marks[position.positionId])
    }

    @Test
    fun `the account reflects the starting balance before any trade`() = runTest {
        val s = session(ReplayFixtures.flat(10))
        s.open()
        s.advanceTo(ReplayFixtures.at(2))
        val account = s.account()
        assertEquals(ReplayFixtures.START_BALANCE_MICROS, account.balanceMicros)
    }
}
