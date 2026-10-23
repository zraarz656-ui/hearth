package com.tradequest.app.core.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 bracket economics (Step 10.5).
 *
 * # What is being verified
 *
 * That the risk and reward zones are priced from the lines, that a stop on the
 * wrong side is reported as absent rather than negative, and — the part most
 * likely to be wrong — that the R:R ratio is exact rather than a float that prints
 * 2.9999998 for 3:1.
 */
class BracketMathTest {

    private val contract = ContractSpec.XAUUSD

    // ------------------------------------------------------ risk/reward zones

    @Test
    fun `a buy's risk and reward are priced from the stop and target`() {
        // Entry 2000.000, stop 50 pips below, target 100 pips above, at 0.10 lots.
        // 50 pips = 5 USD of price. On 0.10 lots of 100oz that is 0.10*100*5 = $50.
        val economics = BracketMath.economics(
            plan = BracketPlan(
                side = OrderSide.BUY,
                entryPriceUnits = 2_000_000,
                stopPriceUnits = 1_995_000,
                targetPriceUnits = 2_010_000,
            ),
            lotUnits = 10,
            contract = contract,
        )
        assertEquals(50L * 1_000_000L, economics.riskMicros)
        assertEquals(100L * 1_000_000L, economics.rewardMicros)
        assertEquals(50, economics.stopDistancePips)
        assertEquals(100, economics.targetDistancePips)
        assertTrue(economics.isComplete)
    }

    @Test
    fun `a sell's risk and reward are mirrored`() {
        // Sell: stop above entry, target below.
        val economics = BracketMath.economics(
            plan = BracketPlan(
                side = OrderSide.SELL,
                entryPriceUnits = 2_000_000,
                stopPriceUnits = 2_005_000,
                targetPriceUnits = 1_990_000,
            ),
            lotUnits = 10,
            contract = contract,
        )
        assertEquals(50L * 1_000_000L, economics.riskMicros)
        assertEquals(100L * 1_000_000L, economics.rewardMicros)
    }

    @Test
    fun `an entry with no stop or target has no risk, reward or ratio`() {
        val economics = BracketMath.economics(
            plan = BracketPlan(side = OrderSide.BUY, entryPriceUnits = 2_000_000),
            lotUnits = 10,
            contract = contract,
        )
        assertNull(economics.riskMicros)
        assertNull(economics.rewardMicros)
        assertNull(economics.riskReward)
        assertFalse(economics.isComplete)
    }

    @Test
    fun `a stop on the wrong side of a buy entry is reported as absent`() {
        // A buy stopped above its entry is invalid, not a tiny stop. Reporting a
        // negative risk would put "-$50 risk" in front of the trader.
        val economics = BracketMath.economics(
            plan = BracketPlan(
                side = OrderSide.BUY,
                entryPriceUnits = 2_000_000,
                stopPriceUnits = 2_005_000,
            ),
            lotUnits = 10,
            contract = contract,
        )
        assertNull(economics.riskMicros)
        assertNull(economics.stopDistancePips)
    }

    @Test
    fun `risk and reward scale with lot size`() {
        val plan = BracketPlan(
            side = OrderSide.BUY,
            entryPriceUnits = 2_000_000,
            stopPriceUnits = 1_995_000,
            targetPriceUnits = 2_010_000,
        )
        val small = BracketMath.economics(plan, lotUnits = 10, contract = contract)
        val large = BracketMath.economics(plan, lotUnits = 100, contract = contract)
        assertEquals(small.riskMicros!! * 10, large.riskMicros)
        assertEquals(small.rewardMicros!! * 10, large.rewardMicros)
        // The ratio is size-independent, which is the useful property.
        assertEquals(small.riskReward, large.riskReward)
    }

    // ------------------------------------------------------------- R:R ratio

    @Test
    fun `a two to one bracket reads as two to one`() {
        val ratio = RiskReward.of(rewardMicros = 200L * 1_000_000L, riskMicros = 100L * 1_000_000L)
        assertNotNull(ratio)
        assertEquals("2.0 : 1", ratio!!.label)
    }

    @Test
    fun `a three to one bracket is exact and not a float approximation`() {
        // The reason the ratio is an integer pair: 300/100 as a Float is 3.0, but
        // 3_000_000/1_000_000 and similar money figures are not exactly
        // representable, and a trader reading "3.0" must mean a real 3:1.
        val ratio = RiskReward.of(rewardMicros = 3_000_000L, riskMicros = 1_000_000L)
        assertEquals("3.0 : 1", ratio!!.label)
    }

    @Test
    fun `an odd ratio is reduced to lowest terms`() {
        val ratio = RiskReward.of(rewardMicros = 300L, riskMicros = 200L)
        // 300:200 reduces to 3:2, which is 1.5:1.
        assertEquals("1.50 : 1", ratio!!.label)
    }

    @Test
    fun `a reward smaller than the risk still shows a ratio`() {
        val ratio = RiskReward.of(rewardMicros = 50L * 1_000_000L, riskMicros = 100L * 1_000_000L)
        assertEquals("0.50 : 1", ratio!!.label)
    }

    @Test
    fun `a non-positive risk has no ratio`() {
        assertNull(RiskReward.of(rewardMicros = 100L, riskMicros = 0L))
        assertNull(RiskReward.of(rewardMicros = 100L, riskMicros = -5L))
    }

    @Test
    fun `a zero reward is a valid but poor ratio`() {
        val ratio = RiskReward.of(rewardMicros = 0L, riskMicros = 100L)
        assertEquals("0.0 : 1", ratio!!.label)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a ratio with zero risk cannot be constructed`() {
        RiskReward(reward = 1, risk = 0)
    }

    // --------------------------------------------------------- distance money

    @Test
    fun `money for a distance matches the position PnL helper`() {
        // The bracket and the engine must agree on what a pip is worth, so this
        // pins the wrapper to the same helper the engine uses.
        val fromBracket = BracketMath.moneyForDistance(50, lotUnits = 10, contract = contract)
        val fromHelper = com.tradequest.app.core.accounting.MoneyScale.pnlMicrosForPriceDelta(
            deltaUnits = 50 * contract.unitsPerPip,
            lotUnits = 10,
            ouncesPerLot = contract.ouncesPerLot,
        )
        assertEquals(fromHelper, fromBracket)
    }
}
