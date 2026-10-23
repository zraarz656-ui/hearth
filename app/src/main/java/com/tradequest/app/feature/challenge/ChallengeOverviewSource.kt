package com.tradequest.app.feature.challenge

import com.tradequest.app.core.challenge.ChallengeDashboardState
import com.tradequest.app.core.challenge.ChallengeHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * Where the Challenge screen learns about the running season and past attempts
 * (Steps 11.8-11.11).
 *
 * # Why a port rather than reading the database
 *
 * `docs/architecture.md` rule 4 forbids a feature reaching into `core.database`.
 * The dashboard needs three things the feature must not assemble itself: the
 * active season's stored facts, its persisted replay state, and the challenge
 * arithmetic that turns those into a verdict. All three live behind this port,
 * whose production implementation sits at the composition root — the only place
 * that knows a season, a replay store and a challenge service at once. This is
 * the same shape as `TradingSessionSource`, one package over.
 *
 * # Why the dashboard is a flow and the history is a flow
 *
 * Both change while the screen is open. The dashboard changes when the trading
 * screen writes progress; the history changes when a season ends. A one-shot read
 * would show a stale figure after the trader returned from the chart, which is
 * exactly when they look at it.
 */
interface ChallengeOverviewSource {

    /**
     * The active season's dashboard, re-evaluated whenever its stored progress
     * changes.
     *
     * Emits null when no season is running. That is a value, not an error: the app
     * can be opened before any challenge exists.
     */
    fun observeDashboard(): Flow<ChallengeDashboardState?>

    /** Every recorded attempt, newest first (Step 11.9). */
    fun observeHistory(): Flow<List<ChallengeHistoryEntry>>

    /**
     * The personal bests, grouped by dataset (Step 11.11).
     *
     * Derived from the history rather than a second query, so the two cannot
     * disagree about what a dataset's best attempt was.
     */
    fun observePersonalBests(): Flow<List<DatasetBest>>
}

/**
 * A dataset's best results, as the personal-best list shows them (Step 11.11).
 *
 * A feature-layer value rather than `core.challenge.PersonalBest`: that type is
 * answered per dataset by a query, and this list is the same answer for every
 * dataset the player has attempted. Flattening the history once is cheaper than a
 * query per dataset, and the grouping rule ("attempts of one dataset together")
 * is a presentation concern.
 */
data class DatasetBest(
    val datasetId: String,
    val datasetDisplayName: String,

    /** The highest-scoring attempt of any ordinal. */
    val bestOverall: ChallengeHistoryEntry,

    /** The best attempt that was the dataset's first, or null when none was. */
    val bestFirstAttempt: ChallengeHistoryEntry?,

    /** How many attempts this dataset has had. */
    val attemptCount: Int,
)
