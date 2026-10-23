package com.tradequest.app.core.risk

import com.tradequest.app.core.accounting.CommissionSchedule
import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.trading.ContractSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Position sizing from a planned risk (Step 6.11).
 *
 * The arithmetic is derived by hand here because the whole point of the step is
 * that the size never exceeds the stated risk. A test that trusted the
 * implementation would not catch the one error that matters.
 */
class PositionSizingTest {

    private val contract = ContractSpec.XAUUSD
    private val commission = CommissionSchedule.STANDARD
    private val micros = MoneyScale.MICROS_PER_USD

    /** The normal spread, 300 price units. */
    private val normalSpread = 300

    // ---------------------------------------------------------- risk per lot

    @Test
    fun `risk per lot includes the stop, the spread and the commission`() {
        // 50 pips = 5,000 units = $5 an ounce. At 100 oz that is $500 a lot.
        // The spread is paid once on the round trip: $0.30 an ounce -> $30 a lot.
        // Commission is $7 a lot. Total 500 + 30 + 7 = 537.
        val risk = PositionSizing.riskPerLotMicros(
            stopDistancePips = 50,
            spreadUnits = normalSpread,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = commission,
        )
        assertEquals(537L * micros, risk)
    }

    @Test
    fun `a slippage allowance raises the planned risk`() {
        // A 100-unit ($0.10) allowance adds $10 an ounce-lot: 537 + 10 = 547.
        val risk = PositionSizing.riskPerLotMicros(
            stopDistancePips = 50,
            spreadUnits = normalSpread,
            slippageAllowanceUnits = 100,
            contract = contract,
            commission = commission,
        )
        assertEquals(547L * micros, risk)
    }

    // ------------------------------------------------------------- rounding

    @Test
    fun `size is rounded down and never exceeds the budget`() {
        // $100 budget over $537 a lot is 0.1862 lots, so 0.18. The realised risk
        // is 537 * 18 / 100 = $96.66, which is under the budget. One step more
        // would be 537 * 19 / 100 = $102.03, which is over.
        val result = PositionSizing.size(
            request = PositionSizeRequest(
                riskBudgetMicros = 100L * micros,
                stopDistancePips = 50,
            ),
            spreadUnits = normalSpread,
            entryPriceUnits = 2_000_000,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        )
        val sized = result as PositionSizeResult.Sized
        assertEquals(18, sized.lotUnits)
        assertEquals(96_660_000L, sized.riskMicros)
        assertTrue(sized.roundedDown)
        assertTrue("must not exceed the budget", sized.riskMicros <= 100L * micros)
    }

    @Test
    fun `an exact multiple does not report rounding`() {
        // $537 a lot and a $537 budget is exactly 1.00 lots.
        val result = PositionSizing.size(
            request = PositionSizeRequest(
                riskBudgetMicros = 537L * micros,
                stopDistancePips = 50,
            ),
            spreadUnits = normalSpread,
            entryPriceUnits = 2_000_000,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        )
        val sized = result as PositionSizeResult.Sized
        assertEquals(100, sized.lotUnits)
        assertFalse(sized.roundedDown)
        assertEquals(537L * micros, sized.riskMicros)
    }

    @Test
    fun `a budget below the minimum size is refused, not rounded up`() {
        // $537 a lot means 0.01 lots risks $5.37. A $5 budget cannot cover it.
        val result = PositionSizing.size(
            request = PositionSizeRequest(
                riskBudgetMicros = 5L * micros,
                stopDistancePips = 50,
            ),
            spreadUnits = normalSpread,
            entryPriceUnits = 2_000_000,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        )
        val tooSmall = result as PositionSizeResult.TooSmall
        assertEquals(5_370_000L, tooSmall.minimumRiskMicros)
    }

    @Test
    fun `a missing stop is refused`() {
        val result = PositionSizing.size(
            request = PositionSizeRequest(
                riskBudgetMicros = 100L * micros,
                stopDistancePips = 0,
            ),
            spreadUnits = normalSpread,
            entryPriceUnits = 2_000_000,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        )
        assertTrue(result is PositionSizeResult.TooSmall)
    }

    // --------------------------------------------------------------- margin

    @Test
    fun `available margin caps the size`() {
        // $2,000 of margin a lot, so $400 of headroom supports 0.20 lots.
        val result = PositionSizing.size(
            request = PositionSizeRequest(
                riskBudgetMicros = 1_000L * micros,
                stopDistancePips = 50,
            ),
            spreadUnits = normalSpread,
            entryPriceUnits = 2_000_000,
            contract = contract,
            commission = commission,
            availableMarginMicros = 400L * micros,
        )
        val sized = result as PositionSizeResult.Sized
        assertEquals(20, sized.lotUnits)
        assertTrue(sized.limitedByMargin)
    }

    @Test
    fun `affordable size divides rather than searches`() {
        // $5,000 of margin at $2,000 a lot is 2.50 lots.
        assertEquals(
            250,
            PositionSizing.affordableLotUnits(5_000L * micros, 2_000_000, contract),
        )
    }

    @Test
    fun `no margin headroom affords nothing`() {
        assertEquals(0, PositionSizing.affordableLotUnits(0L, 2_000_000, contract))
    }

    @Test
    fun `margin that cannot support the minimum is refused`() {
        // $400 of headroom at $2,000 a lot is 0.20 lots, which is tradable, so a
        // much smaller headroom is needed to make the minimum unaffordable.
        val result = PositionSizing.size(
            request = PositionSizeRequest(
                riskBudgetMicros = 100L * micros,
                stopDistancePips = 50,
            ),
            spreadUnits = normalSpread,
            entryPriceUnits = 2_000_000,
            contract = contract,
            commission = commission,
            availableMarginMicros = 10L * micros,
        )
        assertTrue(result is PositionSizeResult.TooSmall)
    }
}
