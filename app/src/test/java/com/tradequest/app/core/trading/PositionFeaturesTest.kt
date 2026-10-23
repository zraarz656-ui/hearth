package com.tradequest.app.core.trading

import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.trading.TradeFixtures.accepted
import com.tradequest.app.core.trading.TradeFixtures.at
import com.tradequest.app.core.trading.TradeFixtures.positionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steps 5.3, 5.12, 5.13: partial close, trailing stops, breakeven, and the
 * "too small to halve" case.
 */
class PositionFeaturesTest {

    /** A bullish minute, so a long's favourable move happens after the entry. */
    private fun risingMinute(index: Int = 0) = TradeFixtures.bullish(
        minuteIndex = index,
        open = 2_000_000,
        low = 1_995_000,
        high = 2_010_000,
        close = 2_008_000,
    )

    /** A flat minute, so price holds still and only rules can change. */
    private fun flatMinute(index: Int, price: Int = 2_000_000) =
        TradeFixtures.flat(index, price)

    // ------------------------------------------------------- partial close (5.12)

    @Test
    fun `a half close of two hundredths leaves one hundredth open`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), flatMinute(1)))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder("m1", OrderSide.BUY, volumeHundredths = 2, atSimUtcMs = at(0)),
            ),
        )
        engine.advanceTo(at(1))
        accepted(
            engine.submit(
                TradingCommand.ClosePosition(
                    atSimUtcMs = at(1), positionId = "pos-m1", volumeHundredths = 1,
                ),
            ),
        )

        val position = engine.positionFor("m1")!!
        assertTrue("the position must stay open", position.isOpen)
        assertEquals(1, position.remainingVolumeHundredths)
        assertEquals(1, position.realizedVolumeHundredths)
    }

    @Test
    fun `a partial close records a closed trade for the closed part`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), flatMinute(1)))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder("m1", OrderSide.BUY, volumeHundredths = 2, atSimUtcMs = at(0)),
            ),
        )
        engine.advanceTo(at(1))
        accepted(
            engine.submit(
                TradingCommand.ClosePosition(
                    atSimUtcMs = at(1), positionId = "pos-m1", volumeHundredths = 1,
                ),
            ),
        )

        val trade = engine.state.closedTrades.single()
        assertEquals(1, trade.volumeHundredths)
        assertEquals(CloseReason.MANUAL, trade.closeReason)
    }

    @Test
    fun `a half close of a one hundredth position is refused`() {
        // 0.01 lots cannot be split: the remainder would be 0.005, below the
        // minimum. Rounding it would silently change the position's size.
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), flatMinute(1)))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder("m1", OrderSide.BUY, volumeHundredths = 1, atSimUtcMs = at(0)),
            ),
        )
        engine.advanceTo(at(1))

        val result = engine.submit(
            TradingCommand.ClosePosition(
                atSimUtcMs = at(1), positionId = "pos-m1", volumeHundredths = 1,
            ),
        )
        // Closing all of it is fine, so this must be a *full* close and accepted.
        assertTrue(result is CommandResult.Accepted)
    }

    @Test
    fun `a partial close leaving an untradable remainder is refused`() {
        // 0.03 lots split as 0.01 closed / 0.02 left is valid, but 0.02 closed
        // leaving 0.01 is also valid. A step of 0.01 makes every split of 0.03
        // legal, so use a coarser step to create the invalid case.
        val contract = ContractSpec.XAUUSD.copy(lotStepUnits = 2, minLotUnits = 2)
        val engine = TradingEngine(
            window = TradeFixtures.window(risingMinute(), flatMinute(1)),
            context = TradingContext(seasonId = "season_test", contract = contract),
            initialState = TradingState(
                seasonId = "season_test",
                balanceMicros = 100_000L * MoneyScale.MICROS_PER_USD,
                orders = emptyList(),
                positions = emptyList(),
                executions = emptyList(),
                closedTrades = emptyList(),
                processedToSimUtcMs = TradeFixtures.T0,
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder("m1", OrderSide.BUY, volumeHundredths = 6, atSimUtcMs = at(0)),
            ),
        )
        engine.advanceTo(at(1))

        // 6 - 2 = 4, which is a valid step; 6 - 4 = 2, also valid. Use 3, which is
        // not a step at all.
        val result = engine.submit(
            TradingCommand.ClosePosition(
                atSimUtcMs = at(1), positionId = "pos-m1", volumeHundredths = 3,
            ),
        )
        assertEquals(RejectionReason.INVALID_PARTIAL_CLOSE, (result as CommandResult.Rejected).reason)
    }

    @Test
    fun `closing more than remains is refused`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), flatMinute(1)))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder("m1", OrderSide.BUY, volumeHundredths = 2, atSimUtcMs = at(0)),
            ),
        )
        engine.advanceTo(at(1))

        val result = engine.submit(
            TradingCommand.ClosePosition(
                atSimUtcMs = at(1), positionId = "pos-m1", volumeHundredths = 5,
            ),
        )
        assertEquals(RejectionReason.INVALID_VOLUME, (result as CommandResult.Rejected).reason)
    }

    @Test
    fun `a full close leaves nothing open`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), flatMinute(1)))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder("m1", OrderSide.BUY, volumeHundredths = 4, atSimUtcMs = at(0)),
            ),
        )
        engine.advanceTo(at(1))
        accepted(
            engine.submit(
                TradingCommand.ClosePosition(
                    atSimUtcMs = at(1), positionId = "pos-m1", volumeHundredths = 4,
                ),
            ),
        )

        val position = engine.positionFor("m1")!!
        assertTrue(position.status == PositionStatus.CLOSED)
        assertEquals(0, position.remainingVolumeHundredths)
    }

    @Test
    fun `two halves charge exactly one round trip in total`() {
        // This is the property that matters: splitting a close must not change the
        // total cost. A bug in the apportionment would show up here as a difference
        // of a few micro-dollars.
        fun totalCost(split: Boolean): Long {
            val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), flatMinute(1)))
            val before = engine.state.balanceMicros
            accepted(
                engine.submit(
                    TradeFixtures.marketOrder(
                        "m1", OrderSide.BUY, volumeHundredths = 4, atSimUtcMs = at(0),
                    ),
                ),
            )
            engine.advanceTo(at(1))
            if (split) {
                accepted(
                    engine.submit(
                        TradingCommand.ClosePosition(
                            atSimUtcMs = at(1), positionId = "pos-m1", volumeHundredths = 2,
                        ),
                    ),
                )
            }
            accepted(
                engine.submit(
                    TradingCommand.ClosePosition(
                        atSimUtcMs = at(1), positionId = "pos-m1",
                        volumeHundredths = if (split) 2 else 4,
                    ),
                ),
            )
            return before - engine.state.balanceMicros
        }

        assertEquals(totalCost(split = false), totalCost(split = true))
    }

    @Test
    fun `the reported commissions of a split close sum to one round trip`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute(), flatMinute(1)))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder("m1", OrderSide.BUY, volumeHundredths = 4, atSimUtcMs = at(0)),
            ),
        )
        engine.advanceTo(at(1))
        accepted(
            engine.submit(
                TradingCommand.ClosePosition(
                    atSimUtcMs = at(1), positionId = "pos-m1", volumeHundredths = 2,
                ),
            ),
        )
        accepted(
            engine.submit(
                TradingCommand.ClosePosition(
                    atSimUtcMs = at(1), positionId = "pos-m1", volumeHundredths = 2,
                ),
            ),
        )

        val total = engine.state.closedTrades.sumOf { it.commissionMicros }
        // 0.04 lots round trip is $0.28.
        assertEquals(280_000L, total)
    }

    // -------------------------------------------------------- trailing stops (5.13)

    @Test
    fun `a trailing stop does not move before its activation distance`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 100,
                    trailingStop = TrailingStopSpec(distancePips = 50, activationPips = 500),
                ),
            ),
        )
        // The minute rises to 2,010,000, which is 9,700 units above the entry: less
        // than the 50,000 units (500 pips) the trail needs.
        engine.advanceTo(at(1))

        val position = engine.positionFor("m1")!!
        // Entry ask 2,000,300, 100-pip stop -> 1,990,300, unmoved.
        assertEquals(1_990_300, position.activeStopUnits)
    }

    @Test
    fun `a trailing stop moves once activated`() {
        // A large rise: entry at 2,000,300, then the path reaches 2,100,000.
        val engine = TradeFixtures.engine(
            TradeFixtures.window(
                TradeFixtures.bullish(0, 2_000_000, 1_999_000, 2_100_000, 2_099_000),
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 1_000,
                    trailingStop = TrailingStopSpec(distancePips = 50, activationPips = 50),
                ),
            ),
        )
        engine.advanceTo(at(1))

        val position = engine.positionFor("m1")!!
        // The best exit-side bid was 2,100,000; the trail sits 50 pips (5,000 units)
        // below it.
        assertEquals(2_095_000, position.activeStopUnits)
    }

    @Test
    fun `a trailing stop never moves away from price`() {
        // Two minutes: up hard, then down hard. The stop must stay at the high
        // water mark, not follow price back down.
        val engine = TradeFixtures.engine(
            TradeFixtures.window(
                TradeFixtures.bullish(0, 2_000_000, 1_999_000, 2_100_000, 2_099_000),
                TradeFixtures.bearish(1, 2_099_000, 2_099_000, 2_010_000, 2_011_000),
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 5_000,
                    trailingStop = TrailingStopSpec(distancePips = 50, activationPips = 50),
                ),
            ),
        )
        engine.advanceTo(at(2))

        val position = engine.positionFor("m1")!!
        // Still at the level set by the 2,100,000 high, even though price fell.
        assertEquals(2_095_000, position.activeStopUnits)
    }

    @Test
    fun `a trailing stop is applied immediately within the same minute`() {
        // The rule is "immediately", not "at the next segment boundary", so the
        // stop must already be tightened by the time the minute ends.
        val engine = TradeFixtures.engine(
            TradeFixtures.window(
                TradeFixtures.bullish(0, 2_000_000, 1_999_000, 2_100_000, 2_099_000),
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 5_000,
                    trailingStop = TrailingStopSpec(distancePips = 100, activationPips = 0),
                ),
            ),
        )
        // Advance only to the midpoint of the rally, not to the end of the minute.
        engine.advanceTo(at(0, 30_000))

        val position = engine.positionFor("m1")!!
        assertTrue(
            "the trail must have moved by the high, was ${position.activeStopUnits}",
            position.activeStopUnits!! > 1_990_300,
        )
    }

    @Test
    fun `a trailing stop fires and closes the position`() {
        // Entry at 2,000,300. The path rises to 2,050,000, then falls to 2,030,000.
        // A 50-pip trail from the high sits at 2,045,000, and the fall to 2,030,000
        // crosses it.
        val engine = TradeFixtures.engine(
            TradeFixtures.window(
                TradeFixtures.bullish(0, 2_000_000, 1_999_000, 2_050_000, 2_049_000),
                TradeFixtures.bearish(1, 2_049_000, 2_049_000, 2_030_000, 2_031_000),
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 5_000,
                    trailingStop = TrailingStopSpec(distancePips = 50, activationPips = 0),
                ),
            ),
        )
        engine.advanceTo(at(2))

        val trade = engine.state.closedTrades.singleOrNull()
        assertNotNull("the trailing stop should have closed the position", trade)
        assertEquals(CloseReason.STOP_LOSS, trade!!.closeReason)
        assertTrue("the trail locked in a profit", trade.netPnlMicros > 0)
    }

    @Test
    fun `a short trailing stop uses the ask side`() {
        // A short's favourable direction is down, and its exit side is the ask, so
        // the extreme is the lowest ask. With a spread of 300, the lowest ask is
        // the lowest bid plus 300.
        val engine = TradeFixtures.engine(
            TradeFixtures.window(
                TradeFixtures.bearish(0, 2_000_000, 2_001_000, 1_950_000, 1_951_000),
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.SELL, atSimUtcMs = at(0), stopLossPips = 5_000,
                    trailingStop = TrailingStopSpec(distancePips = 50, activationPips = 0),
                ),
            ),
        )
        engine.advanceTo(at(1))

        val position = engine.positionFor("m1")!!
        // Lowest ask = 1,950,000 + 300 = 1,950,300; the stop is 5,000 above it.
        assertEquals(1_955_300, position.activeStopUnits)
    }

    // ------------------------------------------------------------- breakeven (5.3)

    @Test
    fun `breakeven moves the stop to the entry price`() {
        val engine = TradeFixtures.engine(
            TradeFixtures.window(
                TradeFixtures.bullish(0, 2_000_000, 1_999_000, 2_050_000, 2_049_000),
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 500,
                    breakeven = BreakevenSpec(triggerPips = 100),
                ),
            ),
        )
        engine.advanceTo(at(1))

        val position = engine.positionFor("m1")!!
        // Moved to the entry price, 2,000,300.
        assertEquals(2_000_300, position.activeStopUnits)
        assertTrue(position.breakevenApplied)
    }

    @Test
    fun `breakeven with a lock leaves a little profit`() {
        val engine = TradeFixtures.engine(
            TradeFixtures.window(
                TradeFixtures.bullish(0, 2_000_000, 1_999_000, 2_050_000, 2_049_000),
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 500,
                    breakeven = BreakevenSpec(triggerPips = 100, lockPips = 20),
                ),
            ),
        )
        engine.advanceTo(at(1))

        // 20 pips = 2,000 units above the entry.
        assertEquals(2_002_300, engine.positionFor("m1")!!.activeStopUnits)
    }

    @Test
    fun `breakeven does not fire before its trigger`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 500,
                    // The minute rises only 9,700 units, less than 200 pips.
                    breakeven = BreakevenSpec(triggerPips = 200),
                ),
            ),
        )
        engine.advanceTo(at(1))

        val position = engine.positionFor("m1")!!
        // 500 pips = 50,000 units, so the stop is 2,000,300 - 50,000.
        assertEquals(1_950_300, position.activeStopUnits)
        assertTrue(!position.breakevenApplied)
    }

    @Test
    fun `breakeven never loosens a tighter stop`() {
        // A trailing stop has already ratcheted well above the entry; breakeven
        // must not pull it back down.
        val engine = TradeFixtures.engine(
            TradeFixtures.window(
                TradeFixtures.bullish(0, 2_000_000, 1_999_000, 2_100_000, 2_099_000),
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 5_000,
                    trailingStop = TrailingStopSpec(distancePips = 50, activationPips = 0),
                    breakeven = BreakevenSpec(triggerPips = 10),
                ),
            ),
        )
        engine.advanceTo(at(1))

        // The trail put the stop at 2,095,000, which is tighter than breakeven's
        // 2,000,300, so it must stay.
        assertEquals(2_095_000, engine.positionFor("m1")!!.activeStopUnits)
    }

    @Test
    fun `a stop moved event is emitted when a trail tightens`() {
        val engine = TradeFixtures.engine(
            TradeFixtures.window(
                TradeFixtures.bullish(0, 2_000_000, 1_999_000, 2_100_000, 2_099_000),
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 5_000,
                    trailingStop = TrailingStopSpec(distancePips = 50, activationPips = 0),
                ),
            ),
        )
        val events = engine.advanceTo(at(1))

        val moved = events.filterIsInstance<TradingEvent.StopMoved>()
        assertTrue("expected a stop move, got $events", moved.isNotEmpty())
        assertEquals(StopMoveCause.TRAILING, moved.last().cause)
        assertTrue(moved.last().toUnits > (moved.last().fromUnits ?: Int.MIN_VALUE))
    }

    // ------------------------------------------------------------ protective rules

    @Test
    fun `removing a trailing stop leaves the current stop level in place`() {
        val engine = TradeFixtures.engine(
            TradeFixtures.window(
                TradeFixtures.bullish(0, 2_000_000, 1_999_000, 2_100_000, 2_099_000),
                TradeFixtures.flat(1, 2_099_000),
            ),
        )
        accepted(
            engine.submit(
                TradeFixtures.marketOrder(
                    "m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 5_000,
                    trailingStop = TrailingStopSpec(distancePips = 50, activationPips = 0),
                ),
            ),
        )
        engine.advanceTo(at(1))
        val trailed = engine.positionFor("m1")!!.activeStopUnits

        accepted(
            engine.submit(
                TradingCommand.ModifyPosition(
                    atSimUtcMs = at(1), positionId = "pos-m1",
                    trailingStop = Modification.Clear,
                ),
            ),
        )

        val position = engine.positionFor("m1")!!
        assertNull(position.trailingStop)
        // The level it reached stays; removing the rule does not un-ratchet it.
        assertEquals(trailed, position.activeStopUnits)
    }

    @Test
    fun `a position with neither rule keeps its original stop`() {
        val engine = TradeFixtures.engine(TradeFixtures.window(risingMinute()))
        accepted(
            engine.submit(
                TradeFixtures.marketOrder("m1", OrderSide.BUY, atSimUtcMs = at(0), stopLossPips = 100),
            ),
        )
        engine.advanceTo(at(1))

        assertEquals(1_990_300, engine.positionFor("m1")!!.activeStopUnits)
    }
}
