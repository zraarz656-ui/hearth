package com.tradequest.app.core.challenge

/**
 * The season facts the challenge service needs that do not come from the engine.
 *
 * A domain value, not the `SeasonEntity` row, per `docs/architecture.md` rule 5.
 */
data class ChallengeSeasonFacts(
    val seasonId: String,
    val datasetId: String,
    val datasetDisplayName: String,
    val mode: SeasonMode,
    val status: SeasonStatus,
    val startingBalanceMicros: Long,
    val peakEquityMicros: Long,
    val startedAtSimUtcMs: Long,

    /**
     * Why the season ended, as stored, or null while it is running.
     *
     * Read from the season row rather than re-derived from the engine, because an
     * ABANDONED season is not a state the engine can produce: it ended because a
     * new challenge superseded it (Step 11.3), which is a lifecycle fact, not a
     * trading one.
     */
    val outcome: ChallengeOutcome?,

    /**
     * The progress last written for this season.
     *
     * Carried so [ChallengeService.sync] can skip a write when nothing has
     * changed. The chart ticks several times a second and most ticks move nothing
     * that matters — a trade count, a strike, the equity's peak — so without this
     * every tick would be a database write for no reason.
     */
    val balanceMicros: Long,
    val equityMicros: Long,
    val tradingDayCount: Int,
    val dailyStrikeCount: Int,
    val scorePoints: Int,
)

/**
 * Reads and writes a season's challenge progress (Steps 11.5, 11.8, 11.9).
 *
 * # Why this is a port
 *
 * The service that evaluates a challenge must persist its verdict without knowing
 * that Room or `tradequest.db` exist — `docs/architecture.md` rule 4. Declaring
 * the port in `core.challenge` and implementing it in the database layer keeps the
 * dependency pointing inwards, exactly as `ReplayStore` and
 * [ChallengeHistoryRepository] do.
 *
 * # Why progress and completion are separate calls
 *
 * A running season writes its progress on every tick, and those writes are cheap
 * and frequent. A terminal season writes its status and its history record once.
 * Merging them would force every tick to consider whether it was the last, and
 * would make the "record exactly one history entry" guarantee depend on a
 * comparison rather than on a code path that runs once. Two calls, each with one
 * job.
 */
interface ChallengeProgressStore {

    /** The season's facts, or null when the season is unknown. */
    suspend fun seasonFacts(seasonId: String): ChallengeSeasonFacts?

    /**
     * Persists a running season's progress (Step 11.8).
     *
     * Writes the balance, equity, peak, day count and strike count, so a resumed
     * season and the dashboard read the same numbers. Does not touch the status:
     * a running season stays ACTIVE until [complete] says otherwise.
     */
    suspend fun saveProgress(
        seasonId: String,
        balanceMicros: Long,
        equityMicros: Long,
        peakEquityMicros: Long,
        tradingDayCount: Int,
        dailyStrikeCount: Int,
        scorePoints: Int,
    )

    /**
     * Marks the season terminal and writes its history record (Steps 11.5, 11.9).
     *
     * Both writes happen in one transaction, so a crash cannot leave a failed
     * season with no history entry, nor a history entry for a season still shown as
     * running.
     */
    suspend fun complete(evaluation: ChallengeEvaluation)

    /**
     * How many attempts this dataset has had, used to number the next (Step 11.11).
     *
     * Read only when a season ends, not on every tick: the count is needed to
     * number a history record, and nothing else. A `COUNT(*)` per frame would be
     * a query per frame for a figure that changes once a season.
     */
    suspend fun attemptCount(datasetId: String): Int
}
