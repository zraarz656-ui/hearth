package com.tradequest.app.core.accounting

import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.MarginMath
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.Position
import com.tradequest.app.core.trading.PositionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account and margin arithmetic (Steps 6.1-6.6, 6.8).
 *
 * Every expected value here is derived by hand in the comment above it, from the
 * plan's own figures, rather than copied from a run. A test that restates the
 * implementation proves only that the code is self-consistent.
 */
class AccountMathTest {

    private val contract = ContractSpec.XAUUSD
    private val micros = MoneyScale.MICROS_PER_USD

    // -------------------------------------------------------------- Step 6.1

    @Test
    fun `price conversion is exact with no remainder`() {
        // The plan's own example: 2047.204 USD -> 2047204 units -> 2047204000 micros.
        assertEquals(2_047_204_000L, MoneyScale.priceUnitsToMicros(2_047_204))
    }

    @Test
    fun `a thirty dollar move at one lot exceeds Int32`() {
        // The plan's overflow example. 30 USD = 30,000 units; 1 lot = 100 units.
        // 30_000 * 100 * 100 * 1_000_000 / (1_000 * 100) = 3_000_000_000 micros,
        // which exceeds Int.MAX_VALUE (2_147_483_647).
        val microsForMove = MoneyScale.pnlMicrosForPriceDelta(
            deltaUnits = 30_000,
            lotUnits = 100,
            ouncesPerLot = 100,
        )
        assertEquals(3_000_000_000L, microsForMove)
        assertTrue("must exceed Int32", microsForMove > Int.MAX_VALUE.toLong())
    }

    @Test
    fun `checked multiply rejects an overflow rather than wrapping`() {
        val failure = runCatching { CheckedMath.multiply(Long.MAX_VALUE, 2L) }.exceptionOrNull()
        assertTrue("expected a MoneyOverflowException", failure is MoneyOverflowException)
    }

    @Test
    fun `multiplyDivide widens before dividing`() {
        // 3 * 10 / 3 = 10. Dividing first would give 3 * 3 = 9.
        assertEquals(10L, CheckedMath.multiplyDivide(3L, 10L, 3L))
    }

    // -------------------------------------------------------------- Step 6.3

    @Test
    fun `long PnL is exit minus entry`() {
        // Long 2,000.000 -> 2,010.000 is +10 USD an ounce; 1 lot = 100 oz, +1,000 USD.
        val pnl = AccountMath.realizedPnlMicros(
            side = OrderSide.BUY,
            entryPriceUnits = 2_000_000,
            exitPriceUnits = 2_010_000,
            lotUnits = 100,
            ouncesPerLot = 100,
        )
        assertEquals(1_000L * micros, pnl)
    }

    @Test
    fun `short PnL is entry minus exit`() {
        // Short 2,000.000 -> 1,990.000 is +10 USD an ounce, +1,000 USD.
        val pnl = AccountMath.realizedPnlMicros(
            side = OrderSide.SELL,
            entryPriceUnits = 2_000_000,
            exitPriceUnits = 1_990_000,
            lotUnits = 100,
            ouncesPerLot = 100,
        )
        assertEquals(1_000L * micros, pnl)
    }

    @Test
    fun `a losing short is negative`() {
        val pnl = AccountMath.realizedPnlMicros(
            side = OrderSide.SELL,
            entryPriceUnits = 2_000_000,
            exitPriceUnits = 2_010_000,
            lotUnits = 100,
            ouncesPerLot = 100,
        )
        assertEquals(-1_000L * micros, pnl)
    }

    @Test
    fun `partial size scales PnL proportionally`() {
        // 0.10 lots is a tenth of the position, so a tenth of the PnL.
        val pnl = AccountMath.realizedPnlMicros(
            side = OrderSide.BUY,
            entryPriceUnits = 2_000_000,
            exitPriceUnits = 2_010_000,
            lotUnits = 10,
            ouncesPerLot = 100,
        )
        assertEquals(100L * micros, pnl)
    }

    // -------------------------------------------------------------- Step 6.4

    @Test
    fun `equity is balance plus unrealized`() {
        assertEquals(
            11_000L * micros,
            AccountMath.equityMicros(10_000L * micros, 1_000L * micros),
        )
    }

    @Test
    fun `net PnL subtracts both commission halves`() {
        // Gross 1,000, round-trip commission 7, so 993.
        assertEquals(993L * micros, AccountMath.netPnlMicros(1_000L * micros, 7L * micros))
    }

    @Test
    fun `a floor is breached at or below, not only below`() {
        assertTrue("exactly at the floor breaches it", AccountMath.atOrBelow(9_000L, 9_000L))
        assertTrue(AccountMath.atOrBelow(8_999L, 9_000L))
        assertFalse(AccountMath.atOrBelow(9_001L, 9_000L))
    }

    // -------------------------------------------------------------- Step 6.5

    @Test
    fun `margin is price times ounces times lots over leverage`() {
        // $2,000 gold, 100 oz, 1 lot, 1:100 = 200,000 / 100 = 2,000 USD.
        val margin = MarginMath.marginMicrosFor(
            entryPriceUnits = 2_000_000,
            lotUnits = 100,
            ouncesPerLot = 100,
            leverage = 100,
        )
        assertEquals(2_000L * micros, margin)
    }

    @Test
    fun `margin scales with size`() {
        val margin = MarginMath.marginMicrosFor(
            entryPriceUnits = 2_000_000,
            lotUnits = 10,
            ouncesPerLot = 100,
            leverage = 100,
        )
        assertEquals(200L * micros, margin)
    }

    @Test
    fun `only the remaining size is margined`() {
        // Half closed, so half the margin: 1,000 USD.
        val position = position(lotUnits = 100, realizedVolume = 50)
        assertEquals(1_000L * micros, MarginMath.marginMicrosFor(position, contract))
    }

    // -------------------------------------------------------------- Step 6.6

    @Test
    fun `margin level is equity over used margin as a percentage`() {
        // Equity 10,000, used 2,000 -> 500%.
        assertEquals(500L, MarginMath.marginLevelPercent(10_000L * micros, 2_000L * micros))
    }

    @Test
    fun `zero used margin yields no level rather than zero or infinity`() {
        assertNull(MarginMath.marginLevelPercent(10_000L * micros, 0L))
    }

    @Test
    fun `used margin sums the open positions only`() {
        val open = listOf(position("a", 100), position("b", 100))
        assertEquals(4_000L * micros, MarginMath.usedMarginMicros(open, contract))
    }

    @Test
    fun `a closed position reserves no margin`() {
        val closed = position("a", 100).copy(status = PositionStatus.CLOSED)
        assertEquals(0L, MarginMath.usedMarginMicros(listOf(closed), contract))
    }

    // -------------------------------------------------------------- Step 6.8

    @Test
    fun `stop-out order puts the largest loser first`() {
        // a: entry 2,000, marked 1,900 -> -1,000 USD at 1 lot.
        // b: entry 2,000, marked 1,950 -> -500 USD at 1 lot.
        val a = position("a", 100, openPrice = 2_000_000)
        val b = position("b", 100, openPrice = 2_000_000)
        val marks = mapOf("a" to 1_900_000, "b" to 1_950_000)

        val order = MarginMath.stopOutOrder(
            listOf(b, a),
            { marks.getValue(it.positionId) },
            contract,
        )
        assertEquals(listOf("a", "b"), order.map { it.positionId })
    }

    @Test
    fun `stop-out order breaks ties by position id for determinism`() {
        val a = position("a", 100, openPrice = 2_000_000)
        val b = position("b", 100, openPrice = 2_000_000)
        val marks = mapOf("a" to 1_950_000, "b" to 1_950_000)

        // Same loss, so the id decides; passing b first must not change the result.
        val order = MarginMath.stopOutOrder(
            listOf(b, a),
            { marks.getValue(it.positionId) },
            contract,
        )
        assertEquals(listOf("a", "b"), order.map { it.positionId })
    }

    @Test
    fun `a winner is ordered after a loser`() {
        val loser = position("lose", 100, openPrice = 2_000_000)
        val winner = position("win", 100, openPrice = 2_000_000)
        val marks = mapOf("lose" to 1_990_000, "win" to 2_010_000)

        val order = MarginMath.stopOutOrder(
            listOf(winner, loser),
            { marks.getValue(it.positionId) },
            contract,
        )
        assertEquals(listOf("lose", "win"), order.map { it.positionId })
    }

    private fun position(
        id: String = "pos-1",
        lotUnits: Int,
        openPrice: Int = 2_000_000,
        realizedVolume: Int = 0,
    ) = Position(
        positionId = id,
        seasonId = "season_test",
        openedAtSimUtcMs = 0L,
        side = OrderSide.BUY,
        volumeHundredths = lotUnits,
        openPriceUnits = openPrice,
        stopLossPips = null,
        takeProfitPips = null,
        trailingStop = null,
        breakeven = null,
        status = PositionStatus.OPEN,
        closedAtSimUtcMs = null,
        closePriceUnits = null,
        activeStopUnits = null,
        targetUnits = null,
        favorableExtremeUnits = openPrice,
        breakevenApplied = false,
        entryCommissionMicros = 0L,
        realizedPnlMicros = 0L,
        realizedVolumeHundredths = realizedVolume,
    )
}
