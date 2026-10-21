package com.tradequest.app.core.database.player

import androidx.room.withTransaction
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.RoomMarketRepository
import com.tradequest.app.core.market.MarketRepository

/**
 * Season lifecycle against `tradequest.db`.
 *
 * Step 2.5 requires that every season references exactly one dataset. The two
 * databases are separate files and `docs/architecture.md` rule 6 forbids
 * cross-database joins, so SQLite cannot enforce this with a foreign key. It is
 * enforced here instead: [startSeason] refuses to create a season unless the
 * dataset exists in `market.db`.
 */
class PlayerRepository(
    private val db: PlayerDatabase,
    private val marketRepository: MarketRepository,
) {

    private val dao get() = db.playerDao()

    /** Starting balance for a new season: $10,000 (Step 11.1). */
    val startingBalanceMicros: Long get() = 10_000L * 1_000_000L

    /**
     * Creates a season on [datasetId].
     *
     * Returns [SeasonStartResult.DatasetNotFound] when the dataset does not
     * exist, and [SeasonStartResult.AlreadyActive] when another season is
     * already ACTIVE (Step 11.2: only one active Challenge Mode season).
     */
    suspend fun startSeason(
        seasonId: String,
        datasetId: String,
        mode: SeasonMode,
        startedAtSimUtcMs: Long,
        createdAtRealUtcMs: Long,
    ): SeasonStartResult {
        // Verified before the write, and the write itself is guarded by
        // insertSeasonIfNoneActive so two callers cannot both create a season.
        if (marketRepository.getDataset(datasetId) == null) {
            return SeasonStartResult.DatasetNotFound(datasetId)
        }

        val season = SeasonEntity(
            seasonId = seasonId,
            datasetId = datasetId,
            mode = mode.name,
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

        val inserted = dao.insertSeasonIfNoneActive(season)
        return if (inserted) {
            SeasonStartResult.Started(seasonId)
        } else {
            val active = dao.activeSeason()
            SeasonStartResult.AlreadyActive(active?.seasonId)
        }
    }

    suspend fun activeSeason(): SeasonEntity? = dao.activeSeason()

    /**
     * Deletes a season and everything scoped to it.
     *
     * Child rows are removed by `ON DELETE CASCADE`, which is why the foreign
     * keys in `PlayerEntities.kt` are declared that way. Refuses to delete an
     * ACTIVE season, so an in-progress challenge cannot be erased by accident.
     */
    suspend fun deleteSeason(seasonId: String): SeasonDeleteResult = db.withTransaction {
        val season = dao.getSeason(seasonId) ?: return@withTransaction SeasonDeleteResult.NotFound
        if (season.status == SeasonStatus.ACTIVE.name) {
            return@withTransaction SeasonDeleteResult.RefusedActiveSeason
        }
        dao.deleteSeason(seasonId)
        SeasonDeleteResult.Deleted
    }
}

enum class SeasonMode { CHALLENGE, PRACTICE }

enum class SeasonStatus { ACTIVE, PASSED, FAILED, ABANDONED }

sealed interface SeasonStartResult {
    data class Started(val seasonId: String) : SeasonStartResult
    data class AlreadyActive(val existingSeasonId: String?) : SeasonStartResult
    data class DatasetNotFound(val datasetId: String) : SeasonStartResult
}

enum class SeasonDeleteResult { Deleted, NotFound, RefusedActiveSeason }

/** Convenience factory so callers do not have to construct the pair. */
fun createPlayerRepository(
    playerDatabase: PlayerDatabase,
    marketDatabase: MarketDatabase,
): PlayerRepository = PlayerRepository(playerDatabase, RoomMarketRepository(marketDatabase))
