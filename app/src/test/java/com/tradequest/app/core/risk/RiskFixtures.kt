package com.tradequest.app.core.risk

import com.tradequest.app.core.accounting.CommissionSchedule
import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.simulation.path.MarketPathWindow
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.MarginMath
import com.tradequest.app.core.trading.TradingContext
import com.tradequest.app.core.trading.TradingEngine
import com.tradequest.app.core.trading.TradingState

/**
 * Fixtures for the Phase 6 tests.
 *
 * # Why the account numbers are what they are
 *
 * Gold at $2,000 with 100 ounces a lot and 1:100 leverage reserves $2,000 of
 * margin per lot. Against a $10,000 balance that is a margin level of 500% at one
 * lot — comfortable. The stop-out tests need the level to fall below 50%, which
 * needs a larger position, and the numbers here are chosen so each scenario is
 * reachable with a plausible move rather than an absurd one.
 */
internal object RiskFixtures {

    /** 2024-01-01T00:00:00Z, a Monday. Matches `TradeFixtures.T0`. */
    const val T0: Long = 1_704_067_200_000L

    const val MINUTE: Long = 60_000L

    val CONTRACT: ContractSpec = ContractSpec.XAUUSD

    /** $10,000, the plan's starting balance. */
    val START_BALANCE: Long = 10_000L * MoneyScale.MICROS_PER_USD

    /** $9,000, the plan's static floor. */
    val STATIC_FLOOR: Long = 9_000L * MoneyScale.MICROS_PER_USD

    fun at(minuteIndex: Int, elapsedMs: Int = 0): Long = T0 + minuteIndex * MINUTE + elapsedMs

    fun flat(minuteIndex: Int, price: Int, volume: Int = 100): HistoricalCandle =
        HistoricalCandle(
            timestampUtcMs = at(minuteIndex),
            openUnits = price,
            highUnits = price,
            lowUnits = price,
            closeUnits = price,
            tickVolume = volume,
        )

    /** A candle that falls from [open] to [close] via [low], as a bearish path. */
    fun falling(
        minuteIndex: Int,
        open: Int,
        high: Int,
        low: Int,
        close: Int,
        volume: Int = 100,
    ): HistoricalCandle = HistoricalCandle(
        timestampUtcMs = at(minuteIndex),
        openUnits = open,
        highUnits = high,
        lowUnits = low,
        closeUnits = close,
        tickVolume = volume,
    )

    /** A candle that rises from [open] to [close] via [high]. */
    fun rising(
        minuteIndex: Int,
        open: Int,
        low: Int,
        high: Int,
        close: Int,
        volume: Int = 100,
    ): HistoricalCandle = HistoricalCandle(
        timestampUtcMs = at(minuteIndex),
        openUnits = open,
        highUnits = high,
        lowUnits = low,
        closeUnits = close,
        tickVolume = volume,
    )

    fun window(vararg candles: HistoricalCandle): MarketPathWindow =
        MarketPathWindow.of("ds_test", candles.toList())

    /** An engine with the prop-firm risk policy active. */
    fun riskEngine(
        window: MarketPathWindow,
        startingBalanceMicros: Long = START_BALANCE,
        limits: RiskLimits = RiskLimits.PROP_FIRM_STANDARD,
        commission: CommissionSchedule = CommissionSchedule.STANDARD,
    ): TradingEngine = TradingEngine(
        window = window,
        context = TradingContext(
            seasonId = "season_test",
            contract = CONTRACT,
            commission = commission,
            riskPolicy = PropFirmRiskPolicy(limits),
        ),
        initialState = TradingState(
            seasonId = "season_test",
            balanceMicros = startingBalanceMicros,
            orders = emptyList(),
            positions = emptyList(),
            executions = emptyList(),
            closedTrades = emptyList(),
            processedToSimUtcMs = T0,
            dayStartEquityMicros = startingBalanceMicros,
        ),
    )

    /** The margin level for a given equity and size, at [price]. */
    fun marginLevel(equityMicros: Long, lotUnits: Int, price: Int = 2_000_000): Long? {
        val used = MarginMath.marginMicrosFor(
            entryPriceUnits = price,
            lotUnits = lotUnits,
            ouncesPerLot = CONTRACT.ouncesPerLot,
            leverage = CONTRACT.leverage,
        )
        return MarginMath.marginLevelPercent(equityMicros, used)
    }
}
