package com.tradequest.app.core.database.player

import androidx.room.withTransaction
import com.tradequest.app.core.challenge.ChallengeOutcome
import com.tradequest.app.core.challenge.ChallengeRules
import com.tradequest.app.core.challenge.ChallengeScore
import com.tradequest.app.core.challenge.SeasonMode
import com.tradequest.app.core.challenge.SeasonStatus
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.RoomMarketRepository
import com.tradequest.app.core.market.MarketRepository
import kotlinx.coroutines.flow.Flow

/**
 * Season lifecycle against `tradequest.db` (Steps 11.1-11.3).
 *
 * Step 2.5 requires that every season references exactly one dataset. The two
 * databases are separate files and `docs/architecture.md` rule 6 forbids
 * cross-database joins, so SQLite cannot enforce this with a foreign key. It is
 * enforced here instead: [startSeason] refuses to create a season unless the
 * dataset exists in `market.db`.
 *
 * # Why switching datasets is one transaction
 *
 * Step 11.3 asks that starting a new challenge abandon the previous one and
 * preserve its history. Those are two writes, and a crash between them would leave
 * either two ACTIVE seasons (the invariant Step 11.2 exists to prevent) or an
 * abandoned season with no successor. Both writes are in one transaction, so the
 * database only ever holds the states that are valid.
 */
class PlayerRepository(
    private val db: PlayerDatabase,
    private val marketRepository: MarketRepository,
    private val challengeRules: ChallengeRules = ChallengeRules.STANDARD,
) {

    private val dao get() = db.playerDao()

    /** Starting balance for a new season: $10,000 (Step 11.1). */
    val startingBalanceMicros: Long get() = STARTING_BALANCE_MICROS

    /**
     * Creates a season on [datasetId], abandoning any active one first (Step 11.3).
     *
     * Returns [SeasonStartResult.DatasetNotFound] when the dataset does not exist.
     * A pre-existing active season is *not* an error here — it is the dataset
     * switch the plan describes — so it is abandoned inside the same transaction
     * and its summary is recorded before the new season is inserted.
     *
     * # Why [confirmAbandon] is a parameter
     *
     * Step 11.3 requires confirmation before a running challenge is abandoned. The
     * repository cannot show a dialog, so the caller passes the user's answer. A
     * caller that passes `false` while a season is active gets
     * [SeasonStartResult.ConfirmationRequired] and nothing is written, which makes
     * "abandon without asking" unrepresentable at the call site rather than a
     * convention the UI must remember.
     */
    suspend fun startSeason(
        seasonId: String,
        datasetId: String,
        mode: SeasonMode,
        startedAtSimUtcMs: Long,
        createdAtRealUtcMs: Long,
        confirmAbandon: Boolean = false,
        blindMode: Boolean = false,
    ): SeasonStartResult {
        val dataset = marketRepository.getDataset(datasetId)
            ?: return SeasonStartResult.DatasetNotFound(datasetId)

        return db.withTransaction {
            val active = dao.activeSeason()
            if (active != null) {
                if (!confirmAbandon) {
                    return@withTransaction SeasonStartResult.ConfirmationRequired(active.seasonId)
                }
                // The summary is written before the status changes, and both are in
                // this transaction, so a crash cannot record an abandonment without
                // abandoning, nor abandon without recording.
                recordAbandoned(active, createdAtRealUtcMs)
            }

            val season = SeasonEntity(
                seasonId = seasonId,
                datasetId = datasetId,
                mode = mode.name,
                blindMode = blindMode,
                status = SeasonStatus.ACTIVE.name,
                startingBalanceMicros = startingBalanceMicros,
                balanceMicros = startingBalanceMicros,
                equityMicros = startingBalanceMicros,
                peakEquityMicros = startingBalanceMicros,
                startedAtSimUtcMs = startedAtSimUtcMs,
                createdAtRealUtcMs = createdAtRealUtcMs,
                completedAtSimUtcMs = null,
                tradingDayCount = 0,
                dailyStrikeCount = 0,
                scorePoints = 0,
                outcomeReason = null,
            )
            dao.insertSeason(season)
            SeasonStartResult.Started(seasonId, dataset.displayName)
        }
    }

    suspend fun activeSeason(): SeasonEntity? = dao.activeSeason()

    /**
     * The active season, re-emitted whenever its row changes.
     *
     * # Why the Challenge screen needs this and not [activeSeason]
     *
     * The dashboard's figures are written to the season row by the challenge
     * service on every tick that moves them, and the Challenge screen is a separate
     * destination from the chart. Without a flow the screen would show whatever was
     * stored when it was first opened and never update. Observing the row is what
     * makes it follow the season without the screen polling or knowing that a
     * `Season` table exists.
     */
    fun observeActiveSeason(): Flow<SeasonEntity?> = dao.observeActiveSeason()

    /**
     * Deletes a season and everything scoped to it.
     *
     * Child rows are removed by `ON DELETE CASCADE`, which is why the foreign
     * keys in `PlayerEntities.kt` are declared that way. Refuses to delete an
     * ACTIVE season, so an in-progress challenge cannot be erased by accident.
     *
     * A `ChallengeRecord` is deliberately *not* deleted: it has no foreign key to
     * the season precisely so a player's history survives a season being removed
     * (Steps 3.4, 11.9). Deleting a season forgets the trades, not the attempt.
     */
    suspend fun deleteSeason(seasonId: String): SeasonDeleteResult = db.withTransaction {
        val season = dao.getSeason(seasonId) ?: return@withTransaction SeasonDeleteResult.NotFound
        if (season.status == SeasonStatus.ACTIVE.name) {
            return@withTransaction SeasonDeleteResult.RefusedActiveSeason
        }
        dao.deleteSeason(seasonId)
        SeasonDeleteResult.Deleted
    }

    /**
     * Writes the history summary for an ACTIVE season being abandoned (Step 11.3).
     *
     * Called inside [startSeason]'s transaction, so it is not public: an abandoned
     * season is always the prelude to a new one, and exposing this would let a
     * caller abandon a season with no successor and leave the app with nothing
     * active.
     */
    private suspend fun recordAbandoned(season: SeasonEntity, nowRealUtcMs: Long) {
        val rules = challengeRules
        val score = ChallengeScore(
            profitPoints = 0,
            riskControlPoints = riskControlPoints(season.dailyStrikeCount, rules),
            consistencyPoints = 0,
            disciplinePoints = 0,
        )
        val record = ChallengeRecordEntity(
            recordId = recordIdFor(season.seasonId),
            seasonId = season.seasonId,
            datasetId = season.datasetId,
            datasetDisplayName = datasetNameFor(season.datasetId),
            mode = season.mode,
            status = SeasonStatus.ABANDONED.name,
            outcome = ChallengeOutcome.SUPERSEDED.name,
            startingBalanceMicros = season.startingBalanceMicros,
            finalBalanceMicros = season.balanceMicros,
            finalEquityMicros = season.equityMicros,
            peakEquityMicros = season.peakEquityMicros,
            tradingDayCount = season.tradingDayCount,
            dailyStrikeCount = season.dailyStrikeCount,
            scorePoints = score.total,
            profitPoints = score.profitPoints,
            riskControlPoints = score.riskControlPoints,
            consistencyPoints = score.consistencyPoints,
            disciplinePoints = score.disciplinePoints,
            title = score.title.name,
            attemptOrdinal = dao.challengeRecordCount(season.datasetId) + 1,
            closedTradeCount = dao.countClosedTrades(season.seasonId),
            startedAtSimUtcMs = season.startedAtSimUtcMs,
            completedAtSimUtcMs = season.completedAtSimUtcMs ?: season.startedAtSimUtcMs,
            completedAtRealUtcMs = nowRealUtcMs,
        )
        dao.upsertChallengeRecord(record)
        dao.abandonActiveSeason(
            completedAtSimUtcMs = record.completedAtSimUtcMs,
            reason = ChallengeOutcome.SUPERSEDED.name,
        )
    }

    /**
     * The risk-control component of an abandoned season's score.
     *
     * Only this component is meaningful for an abandoned season: there is no final
     * profit to measure progress from and no consistency to judge, so the other
     * three are zero. Scoring the strikes it did accrue is honest — it says the
     * season was not abandoned at the first loss — and is the same arithmetic
     * `ChallengeScorer` uses, restated here because the scorer's other inputs
     * (peak profit, best day, trade mix) are not all on the season row.
     */
    private fun riskControlPoints(dailyStrikeCount: Int, rules: ChallengeRules): Int {
        val allowed = rules.maxDailyStrikes
        if (allowed <= 0) return 30
        val remaining = (allowed - dailyStrikeCount).coerceAtLeast(0)
        return remaining * 30 / allowed
    }

    private suspend fun datasetNameFor(datasetId: String): String =
        marketRepository.getDataset(datasetId)?.displayName ?: datasetId

    private fun recordIdFor(seasonId: String): String = "record-$seasonId"

    companion object {
        /** $10,000, the plan's starting balance (Step 11.1). */
        val STARTING_BALANCE_MICROS: Long = 10_000L * 1_000_000L
    }
}

sealed interface SeasonStartResult {
    /**
     * The season was created. [datasetDisplayName] is carried so a caller can show
     * which dataset the new challenge runs on without a second lookup.
     */
    data class Started(val seasonId: String, val datasetDisplayName: String) : SeasonStartResult

    /**
     * A season is already ACTIVE and the caller did not confirm abandoning it
     * (Step 11.3). [existingSeasonId] is the season that would be abandoned.
     */
    data class ConfirmationRequired(val existingSeasonId: String) : SeasonStartResult

    data class DatasetNotFound(val datasetId: String) : SeasonStartResult
}

enum class SeasonDeleteResult { Deleted, NotFound, RefusedActiveSeason }

/** Convenience factory so callers do not have to construct the pair. */
fun createPlayerRepository(
    playerDatabase: PlayerDatabase,
    marketDatabase: MarketDatabase,
): PlayerRepository = PlayerRepository(playerDatabase, RoomMarketRepository(marketDatabase))
