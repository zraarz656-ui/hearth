package com.tradequest.app.core.challenge

import kotlinx.coroutines.flow.Flow

/**
 * A finished season as the history list reads it (Step 11.9).
 *
 * A domain type, not the `ChallengeRecordEntity` it is mapped from, per
 * `docs/architecture.md` rule 5. It carries everything the history and
 * personal-best screens show, so neither has to reach for a second query.
 */
data class ChallengeHistoryEntry(
    val recordId: String,
    val seasonId: String,
    val datasetId: String,
    val datasetDisplayName: String,
    val mode: SeasonMode,
    val status: SeasonStatus,
    val outcome: ChallengeOutcome,

    val startingBalanceMicros: Long,
    val finalBalanceMicros: Long,
    val finalEquityMicros: Long,
    val peakEquityMicros: Long,

    val tradingDayCount: Int,
    val dailyStrikeCount: Int,

    val score: ChallengeScore,
    val title: ChallengeTitle,

    /** 1 for a dataset's first attempt (Step 11.11). */
    val attemptOrdinal: Int,

    val closedTradeCount: Int,
    val startedAtSimUtcMs: Long,
    val completedAtSimUtcMs: Long,
    val completedAtRealUtcMs: Long,
) {
    /** Profit or loss against the starting balance. */
    val profitMicros: Long get() = finalEquityMicros - startingBalanceMicros

    /** True when this was the dataset's first attempt (Step 11.11). */
    val isFirstAttempt: Boolean get() = attemptOrdinal == 1
}

/**
 * A dataset's best result, with its first attempt distinguished (Step 11.11).
 *
 * The plan asks that first-attempt results stay distinguishable from repeats, so
 * the best is reported twice: the best overall, and the best *first* attempt. A
 * trader comparing themselves against their own first try wants the second figure,
 * and a "personal best" headline wants the first. Both are here because collapsing
 * them would answer only one of the two questions.
 */
data class PersonalBest(
    val datasetId: String,
    val datasetDisplayName: String,

    /** The highest-scoring attempt on this dataset, of any ordinal. */
    val bestOverall: ChallengeHistoryEntry?,

    /** The best attempt that was the dataset's first. Null until one exists. */
    val bestFirstAttempt: ChallengeHistoryEntry?,

    /** How many attempts this dataset has had. */
    val attemptCount: Int,
)

/**
 * Challenge history and personal bests (Steps 11.9, 11.11).
 *
 * # Why this is a port
 *
 * The challenge feature must read history without knowing that Room, a database
 * file or a `ChallengeRecordEntity` exist — `docs/architecture.md` rule 4 says a
 * feature reaches the database only through a repository, and rule 2 keeps
 * `core.challenge` pure. Declaring the port here and implementing it in
 * `core.database.player` keeps the dependency pointing inwards, exactly as
 * `ReplayStore` does one package over.
 *
 * # Why writing a record is part of this port
 *
 * A record is written the moment a season ends, by whoever detected the end. If
 * that were a separate interface, a caller could persist a terminal season without
 * recording it and the history would silently lose an attempt. One port makes that
 * a single call site.
 */
interface ChallengeHistoryRepository {

    /** Every recorded attempt, newest first. */
    fun observeHistory(limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<ChallengeHistoryEntry>>

    /** A dataset's recorded attempts, newest first. */
    suspend fun historyFor(datasetId: String): List<ChallengeHistoryEntry>

    /** The record written for [seasonId], or null when none has been written. */
    suspend fun recordForSeason(seasonId: String): ChallengeHistoryEntry?

    /**
     * Writes [entry], replacing any existing record for the same season.
     *
     * Idempotent by `seasonId`: a season that is re-evaluated as terminal (the
     * dashboard and the replay worker can both notice) writes one record, not two.
     */
    suspend fun record(entry: ChallengeHistoryEntry)

    /** A dataset's best results, with the first attempt broken out (Step 11.11). */
    suspend fun personalBest(datasetId: String): PersonalBest

    companion object {
        /** How many history rows the list reads. Bounded so the flow cannot grow forever. */
        const val DEFAULT_HISTORY_LIMIT: Int = 200
    }
}
