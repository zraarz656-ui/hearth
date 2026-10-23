package com.tradequest.app.core.trading

import com.tradequest.app.core.accounting.CommissionSchedule
import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steps 5.4-5.8: contract rules, bid/ask, spread regimes, commission, slippage.
 *
 * These are pure functions, so every expectation here is arithmetic that can be
 * checked by hand. Where a number is not obvious the derivation is in a comment,
 * because a test that asserts a number nobody can verify is not evidence.
 */
class PricingRulesTest {

    // ------------------------------------------------------------ contract (5.4)

    @Test
    fun `one lot is one hundred ounces`() {
        assertEquals(100, ContractSpec.XAUUSD.ouncesPerLot)
    }

    @Test
    fun `minimum lot is one hundredth`() {
        assertEquals(1, ContractSpec.XAUUSD.minLotUnits)
    }

    @Test
    fun `lot increment is one hundredth`() {
        assertEquals(1, ContractSpec.XAUUSD.lotStepUnits)
    }

    @Test
    fun `leverage is one to one hundred`() {
        assertEquals(100, ContractSpec.XAUUSD.leverage)
    }

    @Test
    fun `a valid lot size is accepted`() {
        assertTrue(ContractSpec.XAUUSD.isValidLotUnits(20)) // 0.20 lots
    }

    @Test
    fun `zero volume is rejected`() {
        assertFalse(ContractSpec.XAUUSD.isValidLotUnits(0))
    }

    @Test
    fun `volume above the maximum is rejected`() {
        assertFalse(ContractSpec.XAUUSD.isValidLotUnits(ContractSpec.XAUUSD.maxLotUnits + 1))
    }

    @Test
    fun `a non-step volume is rejected`() {
        val contract = ContractSpec.XAUUSD.copy(lotStepUnits = 10)
        assertFalse(contract.isValidLotUnits(15))
        assertTrue(contract.isValidLotUnits(20))
    }

    @Test
    fun `one pip is one hundred price units`() {
        assertEquals(100, ContractSpec.XAUUSD.unitsPerPip)
        assertEquals(5_000, ContractSpec.XAUUSD.pipsToUnits(50))
    }

    @Test
    fun `the smallest position that can be halved is two hundredths`() {
        // 0.01 lots cannot be split: one half would be 0.005, below the minimum.
        assertEquals(2, ContractSpec.XAUUSD.minLotUnitsForHalfClose)
    }

    // --------------------------------------------------------------- bid/ask (5.5)

    @Test
    fun `ask is bid plus spread`() {
        val quote = Quote(bidPriceUnits = 2_000_000, spreadUnits = 300)
        assertEquals(2_000_300, quote.askPriceUnits)
    }

    @Test
    fun `a long enters at the ask and exits at the bid`() {
        val quote = Quote(bidPriceUnits = 2_000_000, spreadUnits = 300)
        assertEquals(2_000_300, quote.entryPriceUnits(OrderSide.BUY))
        assertEquals(2_000_000, quote.exitPriceUnits(OrderSide.BUY))
    }

    @Test
    fun `a short enters at the bid and exits at the ask`() {
        val quote = Quote(bidPriceUnits = 2_000_000, spreadUnits = 300)
        assertEquals(2_000_000, quote.entryPriceUnits(OrderSide.SELL))
        assertEquals(2_000_300, quote.exitPriceUnits(OrderSide.SELL))
    }

    @Test
    fun `the spread is paid on entry and on exit`() {
        val quote = Quote(bidPriceUnits = 2_000_000, spreadUnits = 300)
        // A round trip at an unchanged bid costs the spread once: in at 2,000,300,
        // out at 2,000,000.
        val lossUnits = quote.entryPriceUnits(OrderSide.BUY) - quote.exitPriceUnits(OrderSide.BUY)
        assertEquals(300, lossUnits)
    }

    // --------------------------------------------------------------- spread (5.6)

    @Test
    fun `the normal spread is three hundred units`() {
        assertEquals(300, SpreadModel.NORMAL_UNITS)
    }

    @Test
    fun `the rollover spread is six hundred units`() {
        assertEquals(600, SpreadModel.ROLLOVER_UNITS)
    }

    @Test
    fun `the news spread is eight hundred units`() {
        assertEquals(800, SpreadModel.NEWS_UNITS)
    }

    @Test
    fun `a quiet instant uses the normal spread`() {
        // Mid-afternoon New York on a Tuesday: outside both windows.
        val quiet = NewYorkTradingCalendar.tradingDayStartUtcMs(
            NewYorkTradingCalendar.tradingDateOf(TradeFixtures.T0),
        ) + 20L * 3_600_000L
        assertEquals(SpreadModel.NORMAL_UNITS, SpreadModel.spreadUnitsFor(quiet))
    }

    @Test
    fun `the rollover instant uses the rollover spread`() {
        val dayStart = NewYorkTradingCalendar.tradingDayStartUtcMs(
            NewYorkTradingCalendar.tradingDateOf(TradeFixtures.T0),
        )
        assertEquals(SpreadModel.ROLLOVER_UNITS, SpreadModel.spreadUnitsFor(dayStart))
    }

    @Test
    fun `ten minutes before rollover is still inside the window`() {
        val dayStart = NewYorkTradingCalendar.tradingDayStartUtcMs(
            NewYorkTradingCalendar.tradingDateOf(TradeFixtures.T0),
        )
        assertEquals(
            SpreadModel.ROLLOVER_UNITS,
            SpreadModel.spreadUnitsFor(dayStart - SpreadModel.ROLLOVER_HALF_WINDOW_MS),
        )
    }

    @Test
    fun `ten minutes and one millisecond before rollover is outside the window`() {
        val dayStart = NewYorkTradingCalendar.tradingDayStartUtcMs(
            NewYorkTradingCalendar.tradingDateOf(TradeFixtures.T0),
        )
        assertEquals(
            SpreadModel.NORMAL_UNITS,
            SpreadModel.spreadUnitsFor(dayStart - SpreadModel.ROLLOVER_HALF_WINDOW_MS - 1),
        )
    }

    @Test
    fun `a news instant uses the news spread`() {
        val release = TradeFixtures.T0 + 30L * 3_600_000L
        assertEquals(
            SpreadModel.NEWS_UNITS,
            SpreadModel.spreadUnitsFor(release, listOf(release)),
        )
    }

    @Test
    fun `five minutes after a release is still inside the news window`() {
        val release = TradeFixtures.T0 + 30L * 3_600_000L
        assertEquals(
            SpreadModel.NEWS_UNITS,
            SpreadModel.spreadUnitsFor(release + SpreadModel.NEWS_HALF_WINDOW_MS, listOf(release)),
        )
    }

    @Test
    fun `news inside the rollover window uses the larger spread`() {
        val dayStart = NewYorkTradingCalendar.tradingDayStartUtcMs(
            NewYorkTradingCalendar.tradingDateOf(TradeFixtures.T0),
        )
        // A release exactly at 17:00 New York sits inside both windows. The plan
        // says to take the larger, and the news spread is the larger.
        assertEquals(
            SpreadModel.NEWS_UNITS,
            SpreadModel.spreadUnitsFor(dayStart, listOf(dayStart)),
        )
    }

    // ----------------------------------------------------------- commission (5.7)

    @Test
    fun `a round trip on one lot costs seven dollars`() {
        val schedule = CommissionSchedule.STANDARD
        assertEquals(7L * MoneyScale.MICROS_PER_USD, schedule.roundTripMicros(100))
    }

    @Test
    fun `each side of one lot costs three fifty`() {
        val schedule = CommissionSchedule.STANDARD
        assertEquals(3_500_000L, schedule.perSideMicros(100))
    }

    @Test
    fun `commission scales with volume`() {
        val schedule = CommissionSchedule.STANDARD
        // 0.10 lots: one tenth of $3.50 per side.
        assertEquals(350_000L, schedule.perSideMicros(10))
    }

    @Test
    fun `the two sides of a round trip sum to the round trip charge`() {
        val schedule = CommissionSchedule.STANDARD
        assertEquals(schedule.roundTripMicros(37), 2 * schedule.perSideMicros(37))
    }

    // ------------------------------------------------------------- slippage (5.8)

    @Test
    fun `there is no slippage in normal conditions`() {
        val quiet = TradeFixtures.T0 + 30L * 3_600_000L
        assertEquals(
            0,
            SlippageModel.slippageUnitsFor(
                simUtcMs = quiet,
                orderId = "o1",
                type = OrderType.MARKET,
                newsReleaseTimesUtcMs = emptyList(),
            ),
        )
    }

    @Test
    fun `slippage during news is between twenty cents and one dollar`() {
        val release = TradeFixtures.T0 + 30L * 3_600_000L
        for (index in 0 until 500) {
            val units = SlippageModel.slippageUnitsFor(
                simUtcMs = release,
                orderId = "order-$index",
                type = OrderType.MARKET,
                newsReleaseTimesUtcMs = listOf(release),
            )
            assertTrue("slippage $units below the minimum", units >= SlippageModel.MIN_UNITS)
            assertTrue("slippage $units above the maximum", units <= SlippageModel.MAX_UNITS)
        }
    }

    @Test
    fun `slippage is adverse for a buyer and for a seller`() {
        assertEquals(2_000_500, SlippageModel.applyAdverse(2_000_000, 500, OrderSide.BUY))
        assertEquals(1_999_500, SlippageModel.applyAdverse(2_000_000, 500, OrderSide.SELL))
    }

    @Test
    fun `a limit order does not slip during news`() {
        val release = TradeFixtures.T0 + 30L * 3_600_000L
        // A limit cannot fill worse than its price, so charging slippage would
        // produce a fill the user never agreed to.
        assertEquals(
            0,
            SlippageModel.slippageUnitsFor(
                simUtcMs = release,
                orderId = "o1",
                type = OrderType.LIMIT,
                newsReleaseTimesUtcMs = listOf(release),
            ),
        )
    }

    @Test
    fun `a gap fill does not slip on top of the gap`() {
        val release = TradeFixtures.T0 + 30L * 3_600_000L
        // The gap price already contains the adverse move; slipping as well would
        // charge twice for the same event.
        assertEquals(
            0,
            SlippageModel.slippageUnitsFor(
                simUtcMs = release,
                orderId = "o1",
                type = OrderType.MARKET,
                newsReleaseTimesUtcMs = listOf(release),
                inGap = true,
            ),
        )
    }

    @Test
    fun `the same order at the same instant always slips the same amount`() {
        val release = TradeFixtures.T0 + 30L * 3_600_000L
        val first = SlippageModel.slippageUnitsFor(
            release, "o1", OrderType.MARKET, listOf(release),
        )
        val second = SlippageModel.slippageUnitsFor(
            release, "o1", OrderType.MARKET, listOf(release),
        )
        assertEquals(first, second)
    }

    @Test
    fun `different orders at the same instant slip by different amounts`() {
        val release = TradeFixtures.T0 + 30L * 3_600_000L
        val values = (0 until 40).map {
            SlippageModel.slippageUnitsFor(release, "order-$it", OrderType.MARKET, listOf(release))
        }.toSet()
        // If the hash were not order-sensitive every fill would slip identically,
        // which would be a constant dressed up as a model.
        assertTrue("expected varied slippage, got $values", values.size > 1)
    }

    // ------------------------------------------------------------ money scale

    @Test
    fun `a one dollar move on one lot is one hundred dollars`() {
        // $1.00 = 1000 units, 1 lot = 100 oz.
        assertEquals(
            100L * MoneyScale.MICROS_PER_USD,
            MoneyScale.pnlMicrosForPriceDelta(1_000, 100, 100),
        )
    }

    @Test
    fun `a thirty dollar move on one lot does not overflow`() {
        // The plan's own example: 30 USD at 1 lot is 3e9 micro-dollars, which is
        // past Int32_MAX. This must be computed in Long throughout.
        val micros = MoneyScale.pnlMicrosForPriceDelta(30_000, 100, 100)
        assertEquals(3_000L * MoneyScale.MICROS_PER_USD, micros)
        assertTrue("value must exceed Int32_MAX to be a real overflow test", micros > Int.MAX_VALUE)
    }

    @Test
    fun `price to micros is exact with no rounding`() {
        // 2047.204 USD -> 2047204 units -> 2047204000 micros, the plan's example.
        assertEquals(2_047_204_000L, MoneyScale.priceUnitsToMicros(2_047_204))
    }

    @Test
    fun `a losing move is negative`() {
        assertEquals(
            -100L * MoneyScale.MICROS_PER_USD,
            MoneyScale.pnlMicrosForPriceDelta(-1_000, 100, 100),
        )
    }

    @Test
    fun `micros format as signed dollars and cents`() {
        assertEquals("10000.00", MoneyScale.formatMicros(10_000L * MoneyScale.MICROS_PER_USD))
        assertEquals("-0.35", MoneyScale.formatMicros(-350_000L))
        assertEquals("50.70", MoneyScale.formatMicros(50_700_000L))
    }

    // ------------------------------------------------------- trigger derivation

    @Test
    fun `a buy limit triggers below the market on the bid`() {
        val order = TradeFixtures.pendingOrderDomain(
            "o1", OrderType.LIMIT, OrderSide.BUY, priceUnits = 1_999_000,
            atSimUtcMs = TradeFixtures.T0,
        )
        val trigger = TriggerDerivation.forOrder(order, spreadUnits = 300, ContractSpec.XAUUSD)
        // A buy triggers on the ask, so the bid level is the price less the spread.
        assertEquals(1_998_700, trigger!!.thresholdUnits)
        assertEquals(TriggerDirection.FALLING, trigger.direction)
    }

    @Test
    fun `a sell limit triggers above the market on the bid`() {
        val order = TradeFixtures.pendingOrderDomain(
            "o1", OrderType.LIMIT, OrderSide.SELL, priceUnits = 2_001_000,
            atSimUtcMs = TradeFixtures.T0,
        )
        val trigger = TriggerDerivation.forOrder(order, spreadUnits = 300, ContractSpec.XAUUSD)
        // A sell triggers on the bid directly, so no spread adjustment.
        assertEquals(2_001_000, trigger!!.thresholdUnits)
        assertEquals(TriggerDirection.RISING, trigger.direction)
    }

    @Test
    fun `a buy stop triggers above the market`() {
        val order = TradeFixtures.pendingOrderDomain(
            "o1", OrderType.STOP, OrderSide.BUY, priceUnits = 2_001_000,
            atSimUtcMs = TradeFixtures.T0,
        )
        val trigger = TriggerDerivation.forOrder(order, spreadUnits = 300, ContractSpec.XAUUSD)
        assertEquals(2_000_700, trigger!!.thresholdUnits)
        assertEquals(TriggerDirection.RISING, trigger.direction)
    }

    @Test
    fun `a sell stop triggers below the market`() {
        val order = TradeFixtures.pendingOrderDomain(
            "o1", OrderType.STOP, OrderSide.SELL, priceUnits = 1_999_000,
            atSimUtcMs = TradeFixtures.T0,
        )
        val trigger = TriggerDerivation.forOrder(order, spreadUnits = 300, ContractSpec.XAUUSD)
        assertEquals(1_999_000, trigger!!.thresholdUnits)
        assertEquals(TriggerDirection.FALLING, trigger.direction)
    }

    @Test
    fun `a widening spread moves a buy trigger further away`() {
        val order = TradeFixtures.pendingOrderDomain(
            "o1", OrderType.STOP, OrderSide.BUY, priceUnits = 2_001_000,
            atSimUtcMs = TradeFixtures.T0,
        )
        val normal = TriggerDerivation.forOrder(order, 300, ContractSpec.XAUUSD)!!
        val news = TriggerDerivation.forOrder(order, 800, ContractSpec.XAUUSD)!!
        assertNotEquals(normal.thresholdUnits, news.thresholdUnits)
        assertTrue(news.thresholdUnits < normal.thresholdUnits)
    }

    @Test
    fun `stop and target levels sit on the correct side of the entry`() {
        // 50 pips = 5,000 units.
        assertEquals(
            1_995_000,
            stopLevelFor(OrderSide.BUY, 2_000_000, 50, ContractSpec.XAUUSD),
        )
        assertEquals(
            2_005_000,
            stopLevelFor(OrderSide.SELL, 2_000_000, 50, ContractSpec.XAUUSD),
        )
        assertEquals(
            2_005_000,
            targetLevelFor(OrderSide.BUY, 2_000_000, 50, ContractSpec.XAUUSD),
        )
        assertEquals(
            1_995_000,
            targetLevelFor(OrderSide.SELL, 2_000_000, 50, ContractSpec.XAUUSD),
        )
    }
}
