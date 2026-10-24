package com.tradequest.app.core.challenge

/**
 * What starting a challenge did (Steps 11.1-11.3).
 *
 * A domain result rather than the database layer's `SeasonStartResult`: this is
 * what the Market Library acts on, and it must not see a `SeasonEntity` or the
 * player database (`docs/architecture.md` rule 5).
 */
sealed interface ChallengeLaunchResult {

    /** A challenge is running on the dataset. */
    data class Started(
        val seasonId: String,
        val datasetDisplayName: String,
    ) : ChallengeLaunchResult

    /**
     * A challenge is already running and the trader has not confirmed abandoning it
     * (Step 11.3). Nothing was written.
     */
    data class ConfirmationRequired(val existingSeasonId: String) : ChallengeLaunchResult

    /** The dataset is not in the library, so there is nothing to run a challenge on. */
    data class DatasetNotFound(val datasetId: String) : ChallengeLaunchResult

    /**
     * The dataset exists but has no candles to replay.
     *
     * Distinct from [DatasetNotFound] because the remedy differs: one is "import
     * it", the other is "this dataset is empty". A season on it could never advance
     * (Step 3.8), so it is refused rather than started and left stuck.
     */
    data class DatasetNotReplayable(val datasetId: String) : ChallengeLaunchResult
}

/**
 * Starts a prop-firm challenge and makes it live (Steps 11.1-11.3).
 *
 * # Why this port exists
 *
 * Starting a challenge is two writes in two places that must agree: a season row in
 * `tradequest.db`, and the simulation clock bound to that season's dataset. The
 * clock is process-scoped and derived from a persisted anchor, so a season with no
 * attached clock is not merely untidy — it is a season that can never advance:
 * `TradingSessionSource.currentSimUtcMs()` returns null and the replay owes nothing,
 * so the chart is dead history and no rule is ever evaluated.
 *
 * Only the composition root knows both halves. `feature/market` must not reach into
 * `core.database` (rule 4), so it cannot create the season; and it must not drive
 * `SimulationClock` either, because binding a clock to a dataset is a season
 * decision, not a screen's. This port is the seam: the feature asks for a challenge,
 * the composition root creates the season and attaches the clock, and the feature
 * receives a result it can render. Same shape as `TradingSessionSource`, one
 * package over.
 *
 * # Why [confirmAbandon] is a parameter
 *
 * Step 11.3 requires confirmation before a running challenge is abandoned. A port
 * cannot show a dialog, so the caller passes the trader's answer, and a caller that
 * passes `false` while a challenge runs gets [ChallengeLaunchResult.ConfirmationRequired]
 * with nothing written. That makes "abandon without asking" unrepresentable at the
 * call site rather than a convention the UI has to remember.
 */
interface ChallengeLauncher {

    /**
     * Starts a challenge on [datasetId], abandoning any running one when
     * [confirmAbandon] is true (Steps 11.1-11.3).
     *
     * Suspending because it writes to the player database and reads the dataset's
     * bounds from `market.db`.
     */
    suspend fun startChallenge(
        datasetId: String,
        confirmAbandon: Boolean = false,
        blindMode: Boolean = false,
    ): ChallengeLaunchResult
}
