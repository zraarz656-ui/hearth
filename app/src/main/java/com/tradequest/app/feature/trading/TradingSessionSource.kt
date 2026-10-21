package com.tradequest.app.feature.trading

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
 * `SimulationClock` owns no ticker — it derives the instant from a stored anchor
 * and the wall clock — so something has to poll it, and that something is not the
 * ViewModel. Polling inside the ViewModel would put a perpetual `delay` loop in
 * the unit tests, where a test scheduler can never reach idle and every
 * `advanceUntilIdle` hangs. As a flow, the cadence belongs to the production
 * implementation and a test simply emits the instants it wants to test.
 *
 * # Null means no season
 *
 * [currentSeason] returning null, or [simInstants] completing without emitting,
 * both mean "no season is running". The chart then shows the dataset as plain
 * history. That is an expected state — the app can be opened before a season
 * exists — so it is a value, not an error.
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
}
