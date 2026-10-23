package com.tradequest.app.core.database.player

import androidx.room.withTransaction
import com.tradequest.app.core.challenge.ChallengeEvaluation
import com.tradequest.app.core.challenge.ChallengeOutcome
import com.tradequest.app.core.challenge.ChallengeProgressStore
import com.tradequest.app.core.challenge.ChallengeSeasonFacts
import com.tradequest.app.core.challenge.SeasonMode
import com.tradequest.app.core.challenge.SeasonStatus

/**
 * The [ChallengeProgressStore] adapter over `tradequest.db` (Steps 11.5, 11.8-11.9).
 *
 * Keeps `core.challenge` free of any database dependency: the port is declared
 * there, and the mapping to [SeasonEntity] / [ChallengeRecordEntity] lives here,
 * alongside the entities. The dependency points inwards.
 */
class RoomChallengeProgressStore(
    private val db: PlayerDatabase,
) : ChallengeProgressStore {

    private val dao get() = db.playerDao()

    override suspend fun seasonFacts(seasonId: String): ChallengeSeasonFacts? {
        val row = dao.getSeason(seasonId) ?: return null
        return ChallengeSeasonFacts(
            seasonId = row.seasonId,
            datasetId = row.datasetId,
            datasetDisplayName = row.datasetId,
            mode = parseMode(row.mode),
            status = parseStatus(row.status),
            startingBalanceMicros = row.startingBalanceMicros,
            peakEquityMicros = row.peakEquityMicros,
            startedAtSimUtcMs = row.startedAtSimUtcMs,
            outcome = row.outcomeReason?.let(::parseOutcome),
            balanceMicros = row.balanceMicros,
            equityMicros = row.equityMicros,
            tradingDayCount = row.tradingDayCount,
            dailyStrikeCount = row.dailyStrikeCount,
            scorePoints = row.scorePoints,
        )
    }

    override suspend fun saveProgress(
        seasonId: String,
        balanceMicros: Long,
        equityMicros: Long,
        peakEquityMicros: Long,
        tradingDayCount: Int,
        dailyStrikeCount: Int,
        scorePoints: Int,
    ) {
        dao.updateSeasonProgress(
            seasonId = seasonId,
            balanceMicros = balanceMicros,
            equityMicros = equityMicros,
            peakEquityMicros = peakEquityMicros,
            tradingDayCount = tradingDayCount,
            dailyStrikeCount = dailyStrikeCount,
            scorePoints = scorePoints,
        )
    }

    override suspend fun complete(evaluation: ChallengeEvaluation) {
        val progress = evaluation.progress
        // The ordinal is read here, at completion, because it is the count of
        // attempts that preceded this one and only the store can answer it.
        val ordinal = dao.challengeRecordCount(progress.datasetId) + 1
        val entry = evaluation.historyEntry(recordIdFor(progress.seasonId), ordinal)
            ?: return // Not terminal: nothing to record. Guarded rather than asserted
                     // because the caller may race a verdict that flipped to running.

        db.withTransaction {
            dao.updateSeasonStatus(
                seasonId = progress.seasonId,
                status = evaluation.status.name,
                reason = evaluation.outcome?.name,
                completedAtSimUtcMs = progress.atSimUtcMs,
            )
            dao.upsertChallengeRecord(ChallengeMappers.toEntity(entry))
        }
    }

    override suspend fun attemptCount(datasetId: String): Int =
        dao.challengeRecordCount(datasetId)

    private fun recordIdFor(seasonId: String): String = "record-$seasonId"

    private fun parseMode(value: String): SeasonMode =
        SeasonMode.entries.firstOrNull { it.name == value }
            ?: error("unknown season mode in storage: $value")

    private fun parseStatus(value: String): SeasonStatus =
        SeasonStatus.entries.firstOrNull { it.name == value }
            ?: error("unknown season status in storage: $value")

    private fun parseOutcome(value: String): ChallengeOutcome =
        ChallengeOutcome.entries.firstOrNull { it.name == value }
            ?: error("unknown challenge outcome in storage: $value")
}
