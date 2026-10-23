package com.tradequest.app.feature.trading

import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.simulation.path.CANDLE_DURATION_MS
import com.tradequest.app.core.simulation.path.GapReason
import com.tradequest.app.core.simulation.path.MarketPathWindow
import com.tradequest.app.core.trading.AccountState
import com.tradequest.app.core.trading.BreakevenSpec
import com.tradequest.app.core.trading.CommandResult
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.OrderType
import com.tradequest.app.core.trading.Position
import com.tradequest.app.core.trading.RejectionReason
import com.tradequest.app.core.trading.TradeOrder
import com.tradequest.app.core.trading.TradeOverlay
import com.tradequest.app.core.trading.TradingCommand
import com.tradequest.app.core.trading.TradingContext
import com.tradequest.app.core.trading.TradingEngine
import com.tradequest.app.core.trading.TradingEvent
import com.tradequest.app.core.trading.TradingState
import com.tradequest.app.core.replay.ReplayStore
import com.tradequest.app.core.trading.Modification
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The live, tradeable view of a running season (Phase 10).
 *
 * # Why this exists
 *
 * Before Phase 10 the engine was built inside `ReplayProcessor`, advanced to a
 * target, and discarded — the state was persisted, the events were published, and
 * nothing could be *submitted*. That is the right shape for a catch-up: the
 * question was only "what happened between these two instants". Chart trading asks
 * a different question — "what happens if I place this now" — and answering it
 * needs an engine that is held open between frames and can accept a command at the
 * current instant.
 *
 * This class owns that engine. It is the single place a command becomes an engine
 * call, so the UI cannot reach the engine directly and cannot submit at a time the
 * engine has not reached.
 *
 * # Why the engine is rebuilt rather than kept
 *
 * [TradingEngine] takes an immutable [TradingState] and replaces it on every
 * operation. Holding one instance across a clock advance would mean rebuilding the
 * window anyway, because the window must cover the new interval. Rebuilding the
 * engine from the persisted state on each advance keeps exactly one source of
 * truth — the state — and makes a command's effect independent of how many frames
 * happened to be drawn between two instants. The engine is cheap; the window read
 * is the cost, and that is unavoidable.
 *
 * # Why a mutex
 *
 * A clock tick and a user command can arrive on different coroutines and both
 * mutate the state. Serialising them means a command is applied to a state that
 * has a defined processed-time, rather than racing an advance that is mid-flight.
 * The critical section is short: a command is a state transition, not a replay.
 */
class LiveTradingSession(
    private val seasonId: String,
    private val marketRepository: MarketRepository,
    private val datasetId: String,
    private val tradingContext: TradingContext,
    private val startingBalanceMicros: Long,
    private val store: ReplayStore? = null,
) {

    /** The contract this session trades. Always XAUUSD. */
    val contract: ContractSpec get() = tradingContext.contract

    private val mutex = Mutex()
    private var state: TradingState? = null

    /**
     * The current state, or null before the first [open].
     *
     * Read without the mutex because a state reference is assigned atomically and
     * a reader only ever needs *a* consistent state, not the latest one. A caller
     * that needs to read-then-write uses [mutate].
     */
    val currentState: TradingState? get() = state

    /**
     * Loads the persisted state, or seeds a fresh one at the dataset's start.
     *
     * Called once when the screen appears. A season that has already been replayed
     * resumes from its stored cursor rather than replaying from the beginning.
     */
    suspend fun open(): TradingState? = mutex.withLock {
        val bounds = marketRepository.timeBounds(datasetId)
            ?: return@withLock null
        val loaded = store?.load(seasonId)
        val initial = loaded ?: TradingState(
            seasonId = seasonId,
            balanceMicros = startingBalanceMicros,
            orders = emptyList(),
            positions = emptyList(),
            executions = emptyList(),
            closedTrades = emptyList(),
            processedToSimUtcMs = bounds.first,
        )
        state = initial
        initial
    }

    /**
     * Advances the engine to [targetSimUtcMs], returning the events produced.
     *
     * A no-op when the target is not ahead of the processed cursor, which is what
     * makes a repeated tick at the same instant cheap rather than a second pass.
     */
    suspend fun advanceTo(targetSimUtcMs: Long): List<TradingEvent> = mutex.withLock {
        val current = state ?: return@withLock emptyList()
        if (targetSimUtcMs <= current.processedToSimUtcMs) return@withLock emptyList()

        val window = buildWindow(current.processedToSimUtcMs, targetSimUtcMs)
            ?: return@withLock emptyList()
        val engine = TradingEngine(window, tradingContext, current)
        // The furthest instant that can actually be priced. Advancing past it would
        // move the cursor over minutes that were never replayed.
        val priceableEnd = window.endExclusiveUtcMs ?: current.processedToSimUtcMs
        val stopAt = minOf(targetSimUtcMs, priceableEnd)

        val events = engine.advanceTo(stopAt)
        val next = engine.state
        state = next
        store?.commit(next)
        events
    }

    /**
     * Submits [command] to the engine, returning the result.
     *
     * The command's own `atSimUtcMs` decides when it takes effect; the engine
     * refuses one that predates the processed cursor. Callers should stamp commands
     * with the current simulated instant.
     */
    suspend fun submit(command: TradingCommand): CommandResult = mutex.withLock {
        val current = state ?: return@withLock CommandResult.Rejected(
            RejectionReason.POSITION_NOT_FOUND,
            "the session is not open",
        )
        // Checked here as well as in the engine, because the window below cannot be
        // built for an instant behind the cursor and would otherwise report the
        // misleading MARKET_CLOSED. A stale command — the shape a mistimed chart
        // gesture produces — must be refused as a look-back, which is what it is.
        if (command.atSimUtcMs < current.processedToSimUtcMs) {
            return@withLock CommandResult.Rejected(
                RejectionReason.LOOK_BACK_COMMAND,
                "command at ${command.atSimUtcMs} precedes processed time " +
                    "${current.processedToSimUtcMs}",
            )
        }
        // The window only has to cover the command's instant, but the engine needs
        // the candle context around it. A one-minute span is enough for a market
        // order; a pending order resolves on a later advance, which builds its own
        // window.
        val window = buildWindow(current.processedToSimUtcMs, command.atSimUtcMs)
        if (window == null) {
            return@withLock CommandResult.Rejected(
                RejectionReason.MARKET_CLOSED,
                "no market data covers that instant",
            )
        }
        val engine = TradingEngine(window, tradingContext, current)
        val result = engine.submit(command)
        if (result is CommandResult.Accepted) {
            val next = engine.state
            state = next
            store?.commit(next)
        }
        result
    }

    /** The account as it stands, marked at the current executable prices. */
    suspend fun account(): AccountState = mutex.withLock {
        val current = state
            ?: return@withLock AccountState(seasonId, startingBalanceMicros, 0L)
        val window = buildWindow(current.processedToSimUtcMs, current.processedToSimUtcMs)
            ?: return@withLock AccountState(seasonId, current.balanceMicros, 0L)
        TradingEngine(window, tradingContext, current).accountAt(current.processedToSimUtcMs)
    }

    /**
     * Every overlay a chart should draw: open positions and pending orders.
     *
     * Built here rather than in the composable so the drag logic and the draw code
     * read one list, and a drawn line is always a draggable one.
     */
    suspend fun overlays(): List<TradeOverlay> = mutex.withLock {
        val current = state ?: return@withLock emptyList()
        current.openPositions.map { TradeOverlay.of(it) } +
            current.pendingOrders.mapNotNull { TradeOverlay.of(it, contract) }
    }

    /**
     * The unrealised PnL of each open position at [markPriceUnits], by position id.
     *
     * One map rather than a call per position, so a frame marks every position at
     * one price and the figures on screen cannot disagree with each other.
     */
    fun markToMarket(markPriceUnits: Int): Map<String, Long> {
        val current = state ?: return emptyMap()
        return current.openPositions.associate { position ->
            position.positionId to TradeOverlay.unrealizedPnlMicros(
                position = position,
                markPriceUnits = markPriceUnits,
                contract = contract,
            )
        }
    }

    /**
     * The exit-side mark of each open position at the current instant, by position id.
     *
     * Uses the engine's own marking rule rather than recomputing it, so a PnL shown
     * on screen is the PnL a close would settle at. The marks are read through the
     * same window the engine would use.
     */
    suspend fun bidPrices(): Map<String, Int> = mutex.withLock {
        val current = state ?: return@withLock emptyMap()
        val window = buildWindow(current.processedToSimUtcMs, current.processedToSimUtcMs)
            ?: return@withLock emptyMap()
        TradingEngine(window, tradingContext, current)
            .markPricesAt(current.processedToSimUtcMs)
    }

    /** The open positions, for the positions panel. */
    fun openPositions(): List<Position> = state?.openPositions.orEmpty()

    /** The pending orders, for the pending panel. */
    fun pendingOrders(): List<TradeOrder> = state?.pendingOrders.orEmpty()

    /** Everything the account has closed, newest first, for the history panel. */
    fun closedTrades() = state?.closedTrades.orEmpty()

    /**
     * Places a market order at the current instant (Step 10.1).
     *
     * A convenience over [submit] so the Buy/Sell buttons do not each have to build
     * a [TradingCommand.PlaceOrder] with its optional fields spelled out.
     */
    suspend fun placeMarket(
        orderId: String,
        side: OrderSide,
        volumeHundredths: Int,
        atSimUtcMs: Long,
        stopLossPips: Int? = null,
        takeProfitPips: Int? = null,
    ): CommandResult = submit(
        TradingCommand.PlaceOrder(
            atSimUtcMs = atSimUtcMs,
            orderId = orderId,
            type = OrderType.MARKET,
            side = side,
            volumeHundredths = volumeHundredths,
            stopLossPips = stopLossPips,
            takeProfitPips = takeProfitPips,
        ),
    )

    /** Places a pending order at [priceUnits] (Step 10.2). */
    suspend fun placePending(
        orderId: String,
        type: OrderType,
        side: OrderSide,
        volumeHundredths: Int,
        priceUnits: Int,
        atSimUtcMs: Long,
        stopLossPips: Int? = null,
        takeProfitPips: Int? = null,
    ): CommandResult = submit(
        TradingCommand.PlaceOrder(
            atSimUtcMs = atSimUtcMs,
            orderId = orderId,
            type = type,
            side = side,
            volumeHundredths = volumeHundredths,
            priceUnits = priceUnits,
            stopLossPips = stopLossPips,
            takeProfitPips = takeProfitPips,
        ),
    )

    /** Moves a pending order's entry, stop or target (Step 10.4). */
    suspend fun modifyOrder(
        orderId: String,
        atSimUtcMs: Long,
        priceUnits: Modification<Int> = Modification.Keep,
        stopLossPips: Modification<Int> = Modification.Keep,
        takeProfitPips: Modification<Int> = Modification.Keep,
    ): CommandResult = submit(
        TradingCommand.ModifyOrder(
            atSimUtcMs = atSimUtcMs,
            orderId = orderId,
            priceUnits = priceUnits,
            stopLossPips = stopLossPips,
            takeProfitPips = takeProfitPips,
        ),
    )

    /** Moves an open position's stop, target, or breakeven rule (Steps 10.4, 10.7). */
    suspend fun modifyPosition(
        positionId: String,
        atSimUtcMs: Long,
        stopLossPips: Modification<Int> = Modification.Keep,
        takeProfitPips: Modification<Int> = Modification.Keep,
        breakeven: Modification<BreakevenSpec> = Modification.Keep,
    ): CommandResult = submit(
        TradingCommand.ModifyPosition(
            atSimUtcMs = atSimUtcMs,
            positionId = positionId,
            stopLossPips = stopLossPips,
            takeProfitPips = takeProfitPips,
            breakeven = breakeven,
        ),
    )

    /** Closes a position fully, or [volumeHundredths] of it (Step 10.7). */
    suspend fun closePosition(
        positionId: String,
        atSimUtcMs: Long,
        volumeHundredths: Int? = null,
    ): CommandResult = submit(
        TradingCommand.ClosePosition(
            atSimUtcMs = atSimUtcMs,
            positionId = positionId,
            volumeHundredths = volumeHundredths,
        ),
    )

    /** Cancels a pending order (Step 10.7). */
    suspend fun cancelOrder(orderId: String, atSimUtcMs: Long): CommandResult = submit(
        TradingCommand.CancelOrder(atSimUtcMs = atSimUtcMs, orderId = orderId),
    )

    /**
     * Arms a move-to-breakeven rule on a position (Step 10.7).
     *
     * # Why this is a rule and not an immediate stop move
     *
     * The engine stores a stop as a **pip distance from entry** and rejects a
     * distance of zero, so a stop sitting exactly at the entry price cannot be
     * expressed that way. Breakeven is instead a rule the engine applies: once
     * price has moved [triggerPips] in the position's favour, it ratchets the stop
     * to the entry plus [lockPips]. With `lockPips = 0` that is the entry price
     * exactly, which is what "breakeven" means.
     *
     * The consequence a caller must know: the stop moves on the next favourable
     * move, not at the instant of this call. A position that is already in profit
     * by more than [triggerPips] moves on the very next advance, which for a live
     * chart is the next frame.
     *
     * The default trigger of one pip is the smallest the engine accepts
     * ([BreakevenSpec] requires a positive trigger), so this is the closest thing to
     * an immediate breakeven the model allows.
     */
    suspend fun armBreakeven(
        positionId: String,
        atSimUtcMs: Long,
        triggerPips: Int = DEFAULT_BREAKEVEN_TRIGGER_PIPS,
        lockPips: Int = 0,
    ): CommandResult = modifyPosition(
        positionId = positionId,
        atSimUtcMs = atSimUtcMs,
        breakeven = Modification.Set(BreakevenSpec(triggerPips, lockPips)),
    )

    private suspend fun buildWindow(fromUtcMs: Long, toUtcMs: Long): MarketPathWindow? {
        val closures = marketRepository.closures(datasetId)
        val candles: List<HistoricalCandle> = marketRepository.candlesInRange(
            datasetId = datasetId,
            fromUtcMs = fromUtcMs - CANDLE_DURATION_MS,
            toUtcMs = toUtcMs + CANDLE_DURATION_MS,
        )
        if (candles.isEmpty()) return null

        val closureStarts = closures.map { it.startUtcMs }.toSet()
        return MarketPathWindow.of(datasetId, candles) { atUtcMs ->
            if (closureStarts.contains(atUtcMs)) {
                GapReason.MARKET_CLOSURE
            } else {
                GapReason.MISSING_MINUTES
            }
        }
    }

    private companion object {
        /**
         * The smallest favourable move that arms a breakeven stop.
         *
         * One pip, because [BreakevenSpec] rejects a zero trigger and the plan does
         * not name a default. It is small enough to behave like "as soon as the
         * trade is in profit", which is what the button implies.
         */
        const val DEFAULT_BREAKEVEN_TRIGGER_PIPS: Int = 1
    }
}
