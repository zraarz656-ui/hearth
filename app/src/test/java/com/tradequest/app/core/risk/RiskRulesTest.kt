package com.tradequest.app.core.risk

import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.Position
import com.tradequest.app.core.trading.PositionStatus
import com.tradequest.app.core.trading.RiskFailureReason
import com.tradequest.app.core.trading.RiskSnapshot
import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The risk rules and the prop-firm policy (Steps 6.5-6.10).
 *
 * These drive the policy directly rather than through a market path, so a
 * failure points at the rule instead of at the engine. The engine-level
 * behaviour is covered by `RiskIntegrationTest`.
 */
class RiskRulesTest {

    private val contract = RiskFixtures.CONTRACT
    private val micros = MoneyScale.MICROS_PER_USD
    private val limits = RiskLimits.PROP_FIRM_STANDARD
    private val policy = PropFirmRiskPolicy(limits)

    private val day0 = NewYorkTradingCalendar.tradingDateOf(RiskFixtures.T0).toEpochDay()

    // ---------------------------------------------------------------- floors

    @Test
    fun `daily floor is three percent below the day start`() {
        // 10,000 * 97 / 100 = 9,700.
        assertEquals(9_700L * micros, RiskRules.dailyFloorMicros(10_000L * micros, limits))
    }

    @Test
    fun `daily floor is breached exactly at the boundary`() {
        assertTrue(RiskRules.dailyFloorBreached(9_700L * micros, 10_000L * micros, limits))
        assertFalse(RiskRules.dailyFloorBreached(9_700L * micros + 1, 10_000L * micros, limits))
    }

    @Test
    fun `static floor is nine thousand dollars`() {
        assertTrue(RiskRules.staticFloorBreached(9_000L * micros, limits))
        assertFalse(RiskRules.staticFloorBreached(9_000L * micros + 1, limits))
    }

    @Test
    fun `limits reject a stop-out above the warning`() {
        val failure = runCatching {
            RiskLimits(marginWarningPercent = 50, stopOutPercent = 60)
        }.exceptionOrNull()
        assertTrue("expected an IllegalArgumentException", failure is IllegalArgumentException)
    }

    // --------------------------------------------------------------- warning

    @Test
    fun `a level at or below one hundred percent warns`() {
        assertTrue(policy.evaluate(snapshot(equity = 10_000L * micros, marginLevel = 100L)).marginWarning)
        assertTrue(policy.evaluate(snapshot(equity = 10_000L * micros, marginLevel = 99L)).marginWarning)
        assertFalse(policy.evaluate(snapshot(equity = 10_000L * micros, marginLevel = 101L)).marginWarning)
    }

    @Test
    fun `no level means no warning`() {
        assertFalse(policy.evaluate(snapshot(equity = 10_000L * micros, marginLevel = null)).marginWarning)
    }

    // -------------------------------------------------------------- stop-out

    @Test
    fun `a level at or below fifty percent requires a stop-out`() {
        assertTrue(policy.evaluate(snapshot(equity = 1_000L * micros, marginLevel = 50L)).stopOutRequired)
        assertFalse(policy.evaluate(snapshot(equity = 10_000L * micros, marginLevel = 51L)).stopOutRequired)
    }

    @Test
    fun `nothing open never triggers a stop-out`() {
        assertFalse(policy.evaluate(snapshot(equity = 10_000L * micros, marginLevel = null)).stopOutRequired)
    }

    // ---------------------------------------------------------------- policy

    @Test
    fun `a healthy account decides nothing`() {
        val decision = policy.evaluate(snapshot(equity = 10_000L * micros, marginLevel = 500L))
        assertFalse(decision.stopOutRequired)
        assertFalse(decision.marginWarning)
        assertFalse(decision.dayLocked)
        assertNull(decision.failureReason)
    }

    @Test
    fun `the daily floor records a strike and locks the day`() {
        val decision = policy.evaluate(
            snapshot(equity = 9_700L * micros, marginLevel = 300L, dayStart = 10_000L * micros),
        )
        assertTrue("the daily floor was reached", decision.dailyFloorBreached)
        assertEquals(1, decision.dailyLossStrikes)
        assertTrue(decision.dayLocked)
        assertNull("one strike is not a failure", decision.failureReason)
    }

    @Test
    fun `a locked day does not strike again`() {
        // The day is already locked, so re-evaluating the same breach must not
        // add a second strike; otherwise one bad day would exhaust the allowance.
        val decision = policy.evaluate(
            snapshot(
                equity = 9_600L * micros,
                marginLevel = 300L,
                dayStart = 10_000L * micros,
                dayLocked = true,
                strikes = 1,
            ),
        )
        assertEquals(1, decision.dailyLossStrikes)
    }

    @Test
    fun `a third strike fails the challenge`() {
        val decision = policy.evaluate(
            snapshot(
                equity = 9_600L * micros,
                marginLevel = 300L,
                dayStart = 10_000L * micros,
                strikes = 2,
            ),
        )
        assertEquals(3, decision.dailyLossStrikes)
        assertEquals(RiskFailureReason.DAILY_LOSS_STRIKES_EXHAUSTED, decision.failureReason)
    }

    @Test
    fun `the static floor fails the challenge`() {
        val decision = policy.evaluate(
            snapshot(equity = 9_000L * micros, marginLevel = 200L),
        )
        assertEquals(RiskFailureReason.STATIC_FLOOR_BREACHED, decision.failureReason)
        assertFalse(
            "a static-floor failure is terminal, not a daily strike",
            decision.dailyFloorBreached,
        )
    }

    @Test
    fun `the static floor is checked before the daily floor`() {
        // Equity 8,900 is below both. The terminal reason must win, or the account
        // would be reported as merely day-locked while it had actually failed.
        val decision = policy.evaluate(snapshot(equity = 8_900L * micros, marginLevel = 200L))
        assertEquals(RiskFailureReason.STATIC_FLOOR_BREACHED, decision.failureReason)
    }

    @Test
    fun `a rollover resets the day and the reference equity`() {
        // A day after T0, so the policy sees the trading date change.
        val nextDayStart = RiskFixtures.T0 + 24L * 60L * 60L * 1_000L
        val decision = policy.evaluate(
            snapshot(
                equity = 10_500L * micros,
                marginLevel = 500L,
                dayStart = 10_000L * micros,
                day = day0,
                atUtcMs = nextDayStart,
                dayLocked = true,
                strikes = 1,
            ),
        )
        assertTrue("the day rolled", decision.newDay)
        assertFalse("the day lock clears", decision.dayLocked)
        assertEquals("the reference equity is the rollover equity", 10_500L * micros, decision.dayStartEquityMicros)
        assertEquals("strikes accumulate across days", 1, decision.dailyLossStrikes)
    }

    @Test
    fun `a failed challenge does not resume at a rollover`() {
        val decision = policy.evaluate(
            snapshot(
                equity = 10_500L * micros,
                marginLevel = 500L,
                day = day0,
                atUtcMs = RiskFixtures.T0 + 24L * 60L * 60L * 1_000L,
                challengeFailed = true,
            ),
        )
        assertTrue("a failed challenge stays locked", decision.dayLocked)
        assertNull("the original failure reason is not re-reported", decision.failureReason)
    }

    @Test
    fun `the stop-out order ranks by unrealised loss`() {
        val loser = position("lose", openPrice = 2_000_000)
        val biggerLoser = position("big", openPrice = 2_000_000)
        val decision = policy.evaluate(
            snapshot(
                equity = 1_000L * micros,
                marginLevel = 40L,
                positions = listOf(loser, biggerLoser),
                marks = mapOf("lose" to 1_990_000, "big" to 1_950_000),
            ),
        )
        assertTrue(decision.stopOutRequired)
        assertEquals(listOf("big", "lose"), decision.stopOutOrder)
    }

    // -------------------------------------------------------------- helpers

    private fun snapshot(
        equity: Long,
        marginLevel: Long?,
        dayStart: Long = 10_000L * micros,
        day: Long = day0,
        atUtcMs: Long = RiskFixtures.T0,
        strikes: Int = 0,
        dayLocked: Boolean = false,
        challengeFailed: Boolean = false,
        positions: List<Position> = emptyList(),
        marks: Map<String, Int> = emptyMap(),
    ) = RiskSnapshot(
        atSimUtcMs = atUtcMs,
        equityMicros = equity,
        openPositions = positions,
        positionMarks = marks,
        contract = contract,
        marginLevelPercent = marginLevel,
        dayStartEquityMicros = dayStart,
        dayEpochDay = day,
        dailyLossStrikes = strikes,
        dayLocked = dayLocked,
        challengeFailed = challengeFailed,
    )

    private fun position(id: String, openPrice: Int, lotUnits: Int = 100) = Position(
        positionId = id,
        seasonId = "season_test",
        openedAtSimUtcMs = RiskFixtures.T0,
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
        realizedVolumeHundredths = 0,
    )
}
