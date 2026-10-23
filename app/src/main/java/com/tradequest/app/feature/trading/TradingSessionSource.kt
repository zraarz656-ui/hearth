package com.tradequest.app.feature.trading

import com.tradequest.app.core.challenge.ChallengeEvaluation
import com.tradequest.app.core.trading.AccountState
import com.tradequest.app.core.trading.TradingState
import kotlinx.coroutines.flow.Flow

/**
 * The season the chart is replaying: which one, and which dataset it runs on.
 *
 * The two travel together because they must agree. A season's forming candle can
 * only be built from the dataset the season was started on; pairing one season's
 * instant with another dataset's candles would show the trader prices from a
 * series they never chose.
 */
data class SeasonBinding(
    val seasonId: String,
    val datasetId: String,
)

/**
 * Where the Trading screen learns about the running season.
 *
 * # Why a port rather than reading `SimulationClock` directly
 *
 * `TradingViewModel` must be unit-testable without Room, a database or a wall
 * clock. A test supplies a fixed binding and a flow of instants it controls, and
 * the ViewModel never learns that a clock anchor, a replay worker or a
 * `SeasonEntity` exist. The production implementation lives at the composition
 * root, which is the only place that knows both the season and its dataset.
 *
 * # Why the instant arrives as a flow
 *
 * `SimulationClock` owns no ticker ŌĆö it derives the instant from a stored anchor
 * and the wall clock ŌĆö so something has to poll it, and that something is not the
 * ViewModel. Polling inside the ViewModel would put a perpetual `delay` loop in
 * the unit tests, where a test scheduler can never reach idle and every
 * `advanceUntilIdle` hangs. As a flow, the cadence belongs to the production
 * implementation and a test simply emits the instants it wants to test.
 *
 * # Null means no season
 *
 * [currentSeason] returning null, or [simInstants] completing without emitting,
 * both mean "no season is running". The chart then shows the dataset as plain
 * history. That is an expected state ŌĆö the app can be opened before a season
 * exists ŌĆö so it is a value, not an error.
 */
interface TradingSessionSource {

    /** The running season, or null when there is none. May perform IO. */
    suspend fun currentSeason(): SeasonBinding?

    /**
     * The current simulated instant in UTC epoch milliseconds, or null when no
     * season is running.
     *
     * A synchronous read of in-memory state, used to build one frame. It must not
     * touch the database.
     */
    fun currentSimUtcMs(): Long?

    /**
     * Emits the simulated instant whenever it changes.
     *
     * The chart collects this to follow the clock. A flow that completes without
     * emitting means no season is running, which stops the chart following
     * anything.
     */
    fun simInstants(): Flow<Long>

    /**
     * The balance a new season starts with, in micro-dollars (Step 6.10).
     *
     * Read here rather than passed by the screen because the Trading feature must
     * not know where a season's balance is stored.
     */
    suspend fun startingBalanceMicros(): Long

    /**
     * Builds a live trading session for the running season, or null when there is
     * nothing to trade (Phase 10, Step 10.1).
     *
     * # Why the session is built here and not in the ViewModel
     *
     * A session needs a [TradingContext] — the season id, the news instants that
     * widen the spread, and the risk policy — and the rule "one season trades one
     * dataset under one rule set" is enforced where both are known, which is the
     * composition root. The ViewModel receives a ready session and never learns how
     * one is assembled, exactly as it receives a [SeasonBinding] rather than a
     * `SeasonEntity`.
     *
     * Suspending because it reads the dataset's bounds and news, the same reads
     * [com.tradequest.app.core.replay.ReplayProcessor] performs.
     */
    suspend fun createLiveSession(): LiveTradingSession?

    /**
     * Evaluates and persists the running season's challenge state (Steps 11.5-11.9).
     *
     * # Why this is on this port
     *
     * Evaluating a challenge needs three things the Trading feature must not
     * assemble itself: the season's stored facts, the engine's state, and the
     * challenge service. All three live behind this port, at the composition root,
     * which is the only place that knows both the season and the engine. The
     * ViewModel hands over the state it already holds and receives a verdict.
     *
     * Returns null when no season is running or the season is already terminal, so
     * a caller treats "nothing to evaluate" as a value rather than an error.
     *
     * Suspending because it writes to the player database. Called after a clock
     * advance and after a command, not per frame — see the ViewModel.
     */
    suspend fun syncChallenge(
        tradingState: TradingState,
        account: AccountState,
        equityMicros: Long,
        atSimUtcMs: Long,
    ): ChallengeEvaluation?
}
