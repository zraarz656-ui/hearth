package com.tradequest.app.core.replay

import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.simulation.path.CANDLE_DURATION_MS
import com.tradequest.app.core.simulation.path.GapReason
import com.tradequest.app.core.simulation.path.MarketPathWindow
import com.tradequest.app.core.trading.AccountState
import com.tradequest.app.core.trading.TradingContext
import com.tradequest.app.core.trading.TradingEngine
import com.tradequest.app.core.trading.TradingEvent
import com.tradequest.app.core.trading.TradingState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What a replay run did (Step 7.7). Published so the UI can react. */
data class ReplayOutcome(
    val seasonId: String,
    /** The simulated time the pipeline has now processed up to. */
    val processedToSimUtcMs: Long,
    /** The simulated time the run was asked to reach. */
    val targetSimUtcMs: Long,
    /** How many batches were committed. */
    val batches: Int,
    /** Every event produced, in order, across all batches. */
    val events: List<TradingEvent>,
    /** The account as it stands after the run (Step 7.7). */
    val account: AccountState,
    /**
     * True when [targetSimUtcMs] was reached.
     *
     * False means the dataset ran out first, which is the clock's dataset-end
     * rule (Step 3.8) rather than an error.
     */
    val reachedTarget: Boolean,
)

/** One committed batch, or the final outcome (Step 7.6). */
sealed interface ReplayProgress {
    /** A batch was committed. [account] reflects the state after it. */
    data class Batch(
        val processedToSimUtcMs: Long,
        val targetSimUtcMs: Long,
        val account: AccountState,
        val events: List<TradingEvent>,
    ) : ReplayProgress

    data class Complete(val outcome: ReplayOutcome) : ReplayProgress
}

/**
 * The replay processor (Steps 7.1-7.2, 7.6-7.7).
 *
 * # What it does
 *
 * Given a season and a target simulated time, it loads the persisted state,
 * builds the market path over the interval still owed, advances the trading
 * engine in bounded batches, and commits each batch atomically before moving on.
 *
 * # Why the processor is season-scoped
 *
 * The dataset, the contract, the commission schedule and the risk policy are all
 * fixed for the life of a season, and all of them change what a replay *means*.
 * Passing them once at construction means a run cannot accidentally replay one
 * season's interval against another season's dataset, which is the failure
 * `docs/architecture.md` rule 6 exists to prevent. The only per-run inputs are
 * the season id and the target instant.
 *
 * # Why batching, and why the commit is per batch
 *
 * Step 7.6 requires bounded intervals so the UI is not blocked, and Step 7.4
 * requires the checkpoint and the trading changes to be durable together. Doing
 * both means committing at every batch boundary rather than once at the end: a
 * process killed mid-catch-up then resumes from the last completed batch instead
 * of reprocessing the whole interval. The cost is more writes; the benefit is
 * that an interrupted run loses at most one batch of work.
 *
 * # Why resuming is safe without replaying from the start
 *
 * [TradingState] carries `processedToSimUtcMs`, and the engine refuses to
 * re-process an instant it has already walked. So the processor advances from
 * the stored cursor, and a batch that was already committed is a no-op rather
 * than a duplicate. That is the whole of Step 7.5's idempotency for fills,
 * commissions, liquidations and strikes: they happen inside `advanceTo`, which
 * only ever moves forward. Notifications are held back until the commit that
 * covers them has succeeded, so a crash before the commit re-delivers nothing.
 *
 * # Foreground and background equivalence
 *
 * The engine's result does not depend on how an interval is divided — the Phase 5
 * test `advancing in smaller steps gives the same result as one large step` pins
 * that. So a foreground run in one-minute batches and a background run in a
 * single large batch reach the same state, and this class does not have to care
 * which one it is in.
 */
class ReplayProcessor(
    private val marketRepository: MarketRepository,
    private val store: ReplayStore,
    private val datasetId: String,
    private val tradingContext: TradingContext,
    private val startingBalanceMicros: Long,
    private val notifier: ReplayNotifier = NoOpReplayNotifier,
    private val batchMs: Long = DEFAULT_BATCH_MS,
) {

    init {
        require(batchMs > 0) { "batch size must be positive" }
    }

    /**
     * Runs the replay for [seasonId] up to [targetSimUtcMs] (Step 7.1).
     *
     * [startingState] overrides what is stored, for a caller that has already
     * built the state it wants (a fresh season, or a test). When null, the stored
     * state is used, and a season with nothing stored starts at the dataset's
     * first minute.
     */
    suspend fun process(
        seasonId: String,
        targetSimUtcMs: Long,
        startingState: TradingState? = null,
    ): ReplayOutcome {
        val bounds = marketRepository.timeBounds(datasetId)
            ?: return emptyOutcome(seasonId, targetSimUtcMs, startingState)
        val initial = startingState
            ?: store.load(seasonId)
            ?: freshState(seasonId, bounds.first)
        return run(seasonId, initial, targetSimUtcMs) { }
    }

    /**
     * The same run, emitting progress after each committed batch.
     *
     * Used by the UI path, which needs the account to update as the catch-up
     * proceeds rather than only at the end (Step 7.6).
     */
    fun processAsFlow(
        seasonId: String,
        targetSimUtcMs: Long,
    ): Flow<ReplayProgress> = flow {
        val bounds = marketRepository.timeBounds(datasetId)
        if (bounds == null) {
            emit(ReplayProgress.Complete(emptyOutcome(seasonId, targetSimUtcMs, null)))
            return@flow
        }
        val initial = store.load(seasonId) ?: freshState(seasonId, bounds.first)
        val outcome = run(seasonId, initial, targetSimUtcMs) { emit(it) }
        emit(ReplayProgress.Complete(outcome))
    }

    private suspend fun run(
        seasonId: String,
        initial: TradingState,
        targetSimUtcMs: Long,
        onBatch: suspend (ReplayProgress.Batch) -> Unit,
    ): ReplayOutcome {
        val events = mutableListOf<TradingEvent>()
        var state = initial

        // Nothing to do. Returning early is what makes a repeated call with the
        // same target a no-op rather than a second pass over the same instants.
        if (targetSimUtcMs <= state.processedToSimUtcMs) {
            return ReplayOutcome(
                seasonId = seasonId,
                processedToSimUtcMs = state.processedToSimUtcMs,
                targetSimUtcMs = targetSimUtcMs,
                batches = 0,
                events = emptyList(),
                account = AccountState(seasonId, state.balanceMicros, 0L),
                reachedTarget = true,
            )
        }

        val window = buildWindow(state.processedToSimUtcMs, targetSimUtcMs)
            ?: return ReplayOutcome(
                seasonId = seasonId,
                processedToSimUtcMs = state.processedToSimUtcMs,
                targetSimUtcMs = targetSimUtcMs,
                batches = 0,
                events = emptyList(),
                account = AccountState(seasonId, state.balanceMicros, 0L),
                reachedTarget = false,
            )

        val engine = TradingEngine(window, tradingContext, state)
        // The furthest instant that can actually be priced. Advancing past it
        // would move the cursor over minutes that were never replayed, and the
        // dataset's last candle is the end of the replay (Step 3.8).
        val priceableEnd = window.endExclusiveUtcMs ?: state.processedToSimUtcMs
        val stopAt = minOf(targetSimUtcMs, priceableEnd)

        var batches = 0
        while (state.processedToSimUtcMs < stopAt) {
            // A cancellation between batches stops cleanly: what is committed is
            // consistent, and the next run resumes from it.
            currentCoroutineContext().ensureActive()

            val next = minOf(state.processedToSimUtcMs + batchMs, stopAt)
            val batchEvents = engine.advanceTo(next)
            state = engine.state

            // Step 7.4: state and cursor become durable together, before anything
            // is told about it.
            store.commit(state)

            events += batchEvents
            batches++

            onBatch(
                ReplayProgress.Batch(
                    processedToSimUtcMs = state.processedToSimUtcMs,
                    targetSimUtcMs = targetSimUtcMs,
                    account = engine.accountAt(state.processedToSimUtcMs),
                    events = batchEvents,
                ),
            )

            // Step 7.9: only now, after the commit that covers these events.
            ReplayNotifications.mapAll(seasonId, batchEvents).forEach { notifier.notify(it) }
        }

        return ReplayOutcome(
            seasonId = seasonId,
            processedToSimUtcMs = state.processedToSimUtcMs,
            targetSimUtcMs = targetSimUtcMs,
            batches = batches,
            events = events,
            account = engine.accountAt(state.processedToSimUtcMs),
            reachedTarget = state.processedToSimUtcMs >= targetSimUtcMs,
        )
    }

    /**
     * The candles covering `[fromUtcMs, toUtcMs)`, plus one before and one after.
     *
     * # Why the extra candles
     *
     * A gap is classified from the candle that precedes it, and the engine seeds
     * its trading day from the first instant it processes. A window that began
     * exactly at `fromUtcMs` would lose the preceding candle, so it would
     * mis-classify a break at the boundary and would re-seed the trading day at
     * the wrong point on a resume. Including one candle on each side gives the
     * engine the same context it would have had in an uninterrupted run.
     *
     * # Why it is bounded
     *
     * [MarketRepository.DEFAULT_MAX_CANDLE_ROWS] bounds the read. A target far
     * beyond the dataset simply returns fewer candles, and the window ends at the
     * last one.
     */
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

    private fun freshState(seasonId: String, datasetStartUtcMs: Long): TradingState = TradingState(
        seasonId = seasonId,
        balanceMicros = startingBalanceMicros,
        orders = emptyList(),
        positions = emptyList(),
        executions = emptyList(),
        closedTrades = emptyList(),
        processedToSimUtcMs = datasetStartUtcMs,
    )

    private fun emptyOutcome(
        seasonId: String,
        targetSimUtcMs: Long,
        startingState: TradingState?,
    ): ReplayOutcome = ReplayOutcome(
        seasonId = seasonId,
        processedToSimUtcMs = startingState?.processedToSimUtcMs ?: 0L,
        targetSimUtcMs = targetSimUtcMs,
        batches = 0,
        events = emptyList(),
        account = AccountState(
            seasonId = seasonId,
            balanceMicros = startingState?.balanceMicros ?: startingBalanceMicros,
            unrealizedPnlMicros = 0L,
        ),
        reachedTarget = false,
    )

    companion object {
        /**
         * How much simulated time one batch covers.
         *
         * One minute, so a batch boundary lands on a candle boundary and the
         * engine's own segment walk is not cut across a waypoint more than it
         * would be anyway. Larger batches commit less often but block longer.
         */
        const val DEFAULT_BATCH_MS: Long = CANDLE_DURATION_MS
    }
}
