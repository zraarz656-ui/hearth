package com.tradequest.app.core.risk

import com.tradequest.app.core.accounting.CommissionSchedule
import com.tradequest.app.core.trading.ContractSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 risk-lock sizing (Step 10.6).
 *
 * # What is being verified
 *
 * That a lock turns into a budget exactly, that the presets the plan names all
 * exist, that the budget never yields a size whose risk exceeds it, and that
 * recomputing while the stop is dragged changes the size in the right direction.
 * The last is the property the step is named for.
 */
class RiskLockTest {

    private val contract = ContractSpec.XAUUSD
    private val commission = CommissionSchedule.STANDARD

    // ------------------------------------------------------------ lock values

    @Test
    fun `the plan's presets are all available`() {
        assertEquals(5, RiskLockBps.PRESETS.size)
        assertEquals(listOf(25, 50, 100, 150, 200), RiskLockBps.PRESETS.map { it.bps })
    }

    @Test
    fun `a lock labels itself as a percentage`() {
        assertEquals("0.25%", RiskLockBps.QUARTER.label)
        assertEquals("0.50%", RiskLockBps.HALF.label)
        assertEquals("1.00%", RiskLockBps.ONE.label)
        assertEquals("1.50%", RiskLockBps.ONE_AND_HALF.label)
        assertEquals("2.00%", RiskLockBps.TWO.label)
    }

    @Test
    fun `a custom lock is accepted within range`() {
        assertEquals(75, RiskLockBps.custom(75).bps)
        assertEquals("0.75%", RiskLockBps.custom(75).label)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a lock above 100 percent is refused`() {
        RiskLockBps.custom(10_001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero lock is refused`() {
        RiskLockBps.custom(0)
    }

    // ------------------------------------------------------------- budgeting

    @Test
    fun `a one percent lock of ten thousand dollars is one hundred dollars`() {
        val equity = 10_000L * 1_000_000L
        assertEquals(100L * 1_000_000L, RiskLock.riskBudgetMicros(equity, RiskLockBps.ONE))
    }

    @Test
    fun `a quarter percent lock is exact and not a float approximation`() {
        // The point of basis points: 0.25% of $10,000 is exactly $25, with no
        // binary-floating-point residue in the last micro-dollar.
        val equity = 10_000L * 1_000_000L
        assertEquals(25L * 1_000_000L, RiskLock.riskBudgetMicros(equity, RiskLockBps.QUARTER))
    }

    @Test
    fun `a lock on a non-positive equity yields a non-positive budget`() {
        assertTrue(RiskLock.riskBudgetMicros(0L, RiskLockBps.ONE) <= 0L)
        assertTrue(RiskLock.riskBudgetMicros(-5L, RiskLockBps.ONE) <= 0L)
    }

    // ---------------------------------------------------------------- sizing

    @Test
    fun `a lock sizes a position to at or below its budget`() {
        val equity = 10_000L * 1_000_000L
        val budget = RiskLock.riskBudgetMicros(equity, RiskLockBps.ONE)
        val result = RiskLock.size(
            equityMicros = equity,
            lock = RiskLockBps.ONE,
            stopDistancePips = 50,
            entryPriceUnits = 2_000_000,
            spreadUnits = 30,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        )
        assertTrue("expected a size, got $result", result is PositionSizeResult.Sized)
        val sized = result as PositionSizeResult.Sized
        assertTrue(sized.lotUnits > 0)
        // The whole point of the lock: realised risk never exceeds the budget.
        assertTrue(
            "risk ${sized.riskMicros} exceeded budget $budget",
            sized.riskMicros <= budget,
        )
    }

    @Test
    fun `a wider stop yields a smaller size for the same risk`() {
        val equity = 10_000L * 1_000_000L
        val tight = RiskLock.size(
            equityMicros = equity,
            lock = RiskLockBps.ONE,
            stopDistancePips = 20,
            entryPriceUnits = 2_000_000,
            spreadUnits = 30,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        ) as PositionSizeResult.Sized
        val wide = RiskLock.size(
            equityMicros = equity,
            lock = RiskLockBps.ONE,
            stopDistancePips = 100,
            entryPriceUnits = 2_000_000,
            spreadUnits = 30,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        ) as PositionSizeResult.Sized
        // This is Step 10.6's "recalculate lot size while dragging SL": dragging
        // the stop further away must shrink the position.
        assertTrue(
            "a 100-pip stop (${wide.lotUnits}) should size smaller than a 20-pip stop " +
                "(${tight.lotUnits})",
            wide.lotUnits < tight.lotUnits,
        )
    }

    @Test
    fun `a bigger risk lock yields a bigger size`() {
        val equity = 10_000L * 1_000_000L
        val small = RiskLock.size(
            equityMicros = equity,
            lock = RiskLockBps.QUARTER,
            stopDistancePips = 50,
            entryPriceUnits = 2_000_000,
            spreadUnits = 30,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        ) as PositionSizeResult.Sized
        val large = RiskLock.size(
            equityMicros = equity,
            lock = RiskLockBps.TWO,
            stopDistancePips = 50,
            entryPriceUnits = 2_000_000,
            spreadUnits = 30,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        ) as PositionSizeResult.Sized
        assertTrue(large.lotUnits > small.lotUnits)
    }

    @Test
    fun `a stop too tight for the budget reports TooSmall rather than over-risking`() {
        // A $1 budget cannot cover even the minimum size at a 50-pip stop once
        // spread and commission are counted. Rounding up would exceed the stated
        // risk, which the plan forbids.
        val result = RiskLock.size(
            equityMicros = 1_000_000L,
            lock = RiskLockBps.QUARTER,
            stopDistancePips = 50,
            entryPriceUnits = 2_000_000,
            spreadUnits = 30,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        )
        assertTrue("expected TooSmall, got $result", result is PositionSizeResult.TooSmall)
    }

    @Test
    fun `a zero stop distance cannot be sized`() {
        val result = RiskLock.size(
            equityMicros = 10_000L * 1_000_000L,
            lock = RiskLockBps.ONE,
            stopDistancePips = 0,
            entryPriceUnits = 2_000_000,
            spreadUnits = 30,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        )
        assertTrue(result is PositionSizeResult.TooSmall)
    }

    @Test
    fun `sizing is capped by available margin when that is the binding constraint`() {
        val equity = 100_000L * 1_000_000L
        val unconstrained = RiskLock.size(
            equityMicros = equity,
            lock = RiskLockBps.TWO,
            stopDistancePips = 50,
            entryPriceUnits = 2_000_000,
            spreadUnits = 30,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = commission,
            availableMarginMicros = null,
        ) as PositionSizeResult.Sized
        val constrained = RiskLock.size(
            equityMicros = equity,
            lock = RiskLockBps.TWO,
            stopDistancePips = 50,
            entryPriceUnits = 2_000_000,
            spreadUnits = 30,
            slippageAllowanceUnits = 0,
            contract = contract,
            commission = commission,
            // Enough for a couple of lots at $2,000 gold with 1:100 leverage.
            availableMarginMicros = 5_000L * 1_000_000L,
        ) as PositionSizeResult.Sized
        assertTrue(constrained.limitedByMargin)
        assertTrue(constrained.lotUnits < unconstrained.lotUnits)
        assertNotNull(constrained)
        assertFalse(constrained.riskMicros > RiskLock.riskBudgetMicros(equity, RiskLockBps.TWO))
    }
}
