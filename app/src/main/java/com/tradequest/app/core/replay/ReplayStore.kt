package com.tradequest.app.core.replay

import com.tradequest.app.core.trading.TradingState

/**
 * Durable storage for a season's resumable replay state (Steps 7.3-7.4).
 *
 * # Why this is a port
 *
 * `core.replay` must not depend on `core.database` any more than the clock does.
 * The interface lives here and the Room implementation lives in the database
 * layer, so the dependency points inwards and a test can drive the processor
 * against an in-memory implementation with no database at all.
 *
 * # Why [commit] is one call rather than two
 *
 * Step 7.4 requires the checkpoint and the trading changes to be persisted
 * together. If a caller could write one without the other, a crash between them
 * would leave a state and a processed-time that disagree — the state saying an
 * hour was processed while the clock still owed it, or the reverse. Exposing a
 * single [commit] makes that interleaving unrepresentable: either both are
 * durable or neither is.
 */
interface ReplayStore {

    /**
     * The stored state for [seasonId], or null when nothing is stored yet.
     *
     * A null result means "this season has never been processed", which is
     * different from a stored state at the very start of a dataset. The caller
     * seeds a fresh state for the former and resumes the latter.
     */
    suspend fun load(seasonId: String): TradingState?

    /**
     * Persists [state] and its processed-time, atomically.
     *
     * After this returns, a crash cannot lose the advance: both the trading
     * state and the clock's committed time are durable together.
     */
    suspend fun commit(state: TradingState)

    /** Removes the stored state for [seasonId]. Used when a season is deleted. */
    suspend fun delete(seasonId: String)
}

/**
 * A [ReplayStore] that keeps everything in memory.
 *
 * A complete implementation of the port rather than a stand-in for unfinished
 * work, exactly as `InMemoryClockStore` is: it is correct for a process-lifetime
 * scope and is what the processor tests run against when they are not testing
 * persistence itself.
 */
class InMemoryReplayStore : ReplayStore {

    private val states = mutableMapOf<String, TradingState>()

    override suspend fun load(seasonId: String): TradingState? = states[seasonId]

    override suspend fun commit(state: TradingState) {
        states[state.seasonId] = state
    }

    override suspend fun delete(seasonId: String) {
        states.remove(seasonId)
    }
}
