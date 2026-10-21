package com.tradequest.app.core.trading

import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.simulation.path.MarketPathWindow

/**
 * Shared fixtures for the Phase 5 tests.
 *
 * # Why a helper rather than inline literals
 *
 * The engine's behaviour depends on the *path*, not just on OHLC: which waypoint
 * is crossed first decides whether a stop or a target is hit. Writing candles by
 * hand at each test would make it easy to build one whose shape does not match
 * what the test assumes, and then the test would be asserting the wrong thing
 * while still passing. These helpers make the path shape explicit.
 *
 * All prices are integer thousandths of a USD. 2,000,000 is $2,000.000.
 */
internal object TradeFixtures {

    /** A fixed, DST-free start: 2024-01-01T00:00:00Z, a Monday. */
    const val T0: Long = 1_704_067_200_000L

    const val MINUTE: Long = 60_000L

    /**
     * A candle with a flat path: open == high == low == close.
     *
     * Useful when a test wants price not to move, so that the only thing that can
     * trigger is an external change such as a spread widening.
     */
    fun flat(
        minuteIndex: Int,
        price: Int,
        volume: Int = 100,
    ): HistoricalCandle = HistoricalCandle(
        timestampUtcMs = T0 + minuteIndex * MINUTE,
        openUnits = price,
        highUnits = price,
        lowUnits = price,
        closeUnits = price,
        tickVolume = volume,
    )

    /**
     * A bullish candle: open -> low -> high -> close.
     *
     * The path rises, so a buy-side level below the high is crossed on the way up
     * and a sell-side level above the low is crossed on the way up too.
     */
    fun bullish(
        minuteIndex: Int,
        open: Int,
        low: Int,
        high: Int,
        close: Int,
        volume: Int = 100,
    ): HistoricalCandle = HistoricalCandle(
        timestampUtcMs = T0 + minuteIndex * MINUTE,
        openUnits = open,
        highUnits = high,
        lowUnits = low,
        closeUnits = close,
        tickVolume = volume,
    )

    /**
     * A bearish candle: open -> high -> low -> close.
     *
     * The path falls first, so a long's stop is hit before any rally, and a
     * short's target is reached on the way down.
     */
    fun bearish(
        minuteIndex: Int,
        open: Int,
        high: Int,
        low: Int,
        close: Int,
        volume: Int = 100,
    ): HistoricalCandle = HistoricalCandle(
        timestampUtcMs = T0 + minuteIndex * MINUTE,
        openUnits = open,
        highUnits = high,
        lowUnits = low,
        closeUnits = close,
        tickVolume = volume,
    )

    /** A window over [candles], dataset id `ds_test`. */
    fun window(vararg candles: HistoricalCandle): MarketPathWindow =
        MarketPathWindow.of("ds_test", candles.toList())

    /** The simulated instant [minuteIndex] minutes after the epoch of the fixture. */
    fun at(minuteIndex: Int, elapsedMs: Int = 0): Long = T0 + minuteIndex * MINUTE + elapsedMs

    /** A fresh engine over [window], starting at [T0] with no positions. */
    fun engine(
        window: MarketPathWindow,
        startingBalanceMicros: Long = 100_000L * 1_000_000L,
        newsReleaseTimesUtcMs: List<Long> = emptyList(),
    ): TradingEngine = TradingEngine(
        window = window,
        context = TradingContext(
            seasonId = "season_test",
            newsReleaseTimesUtcMs = newsReleaseTimesUtcMs,
        ),
        initialState = TradingState(
            seasonId = "season_test",
            balanceMicros = startingBalanceMicros,
            orders = emptyList(),
            positions = emptyList(),
            executions = emptyList(),
            closedTrades = emptyList(),
            processedToSimUtcMs = T0,
        ),
    )

    /** Convenience: a market order command. */
    fun marketOrder(
        orderId: String,
        side: OrderSide,
        volumeHundredths: Int = 10,
        atSimUtcMs: Long,
        stopLossPips: Int? = null,
        takeProfitPips: Int? = null,
        trailingStop: TrailingStopSpec? = null,
        breakeven: BreakevenSpec? = null,
    ): TradingCommand.PlaceOrder = TradingCommand.PlaceOrder(
        atSimUtcMs = atSimUtcMs,
        orderId = orderId,
        type = OrderType.MARKET,
        side = side,
        volumeHundredths = volumeHundredths,
        stopLossPips = stopLossPips,
        takeProfitPips = takeProfitPips,
        trailingStop = trailingStop,
        breakeven = breakeven,
    )

    /** Convenience: a pending order command. */
    fun pendingOrder(
        orderId: String,
        type: OrderType,
        side: OrderSide,
        priceUnits: Int,
        volumeHundredths: Int = 10,
        atSimUtcMs: Long,
        stopLossPips: Int? = null,
        takeProfitPips: Int? = null,
    ): TradingCommand.PlaceOrder = TradingCommand.PlaceOrder(
        atSimUtcMs = atSimUtcMs,
        orderId = orderId,
        type = type,
        side = side,
        volumeHundredths = volumeHundredths,
        priceUnits = priceUnits,
        stopLossPips = stopLossPips,
        takeProfitPips = takeProfitPips,
    )

    /**
     * The domain order a [pendingOrder] command would become.
     *
     * Trigger derivation takes the domain type, not the command, so tests of that
     * layer need a real `TradeOrder` rather than a command.
     */
    fun pendingOrderDomain(
        orderId: String,
        type: OrderType,
        side: OrderSide,
        priceUnits: Int,
        volumeHundredths: Int = 10,
        atSimUtcMs: Long = T0,
    ): TradeOrder = TradeOrder(
        orderId = orderId,
        seasonId = "season_test",
        placedAtSimUtcMs = atSimUtcMs,
        type = type,
        side = side,
        volumeHundredths = volumeHundredths,
        requestedPriceUnits = priceUnits,
        stopLossPips = null,
        takeProfitPips = null,
        trailingStop = null,
        breakeven = null,
        status = OrderStatus.PENDING,
        resolvedAtSimUtcMs = null,
        rejectionReason = null,
    )

    /** The position opened by [orderId], or null. */
    fun TradingEngine.positionFor(orderId: String): Position? =
        state.positions.firstOrNull { it.positionId == "pos-$orderId" }

    /** Asserts a command was accepted, failing with the reason otherwise. */
    fun accepted(result: CommandResult): CommandResult.Accepted {
        if (result is CommandResult.Rejected) {
            throw AssertionError("expected acceptance but was rejected: ${result.reason} ${result.detail}")
        }
        return result as CommandResult.Accepted
    }
}
