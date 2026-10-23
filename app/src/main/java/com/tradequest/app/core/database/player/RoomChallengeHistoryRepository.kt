package com.tradequest.app.core.database.player

import com.tradequest.app.core.challenge.ChallengeHistoryEntry
import com.tradequest.app.core.challenge.ChallengeHistoryRepository
import com.tradequest.app.core.challenge.PersonalBest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The [ChallengeHistoryRepository] adapter over `tradequest.db` (Steps 11.9, 11.11).
 *
 * This is what keeps `core.challenge` free of any database dependency: the port is
 * declared there, and the mapping to [ChallengeRecordEntity] lives here, alongside
 * the entity. The dependency points inwards.
 */
class RoomChallengeHistoryRepository(
    private val db: PlayerDatabase,
) : ChallengeHistoryRepository {

    private val dao get() = db.playerDao()

    override fun observeHistory(limit: Int): Flow<List<ChallengeHistoryEntry>> =
        dao.observeChallengeRecords(limit).map { rows -> rows.map(ChallengeMappers::toDomain) }

    override suspend fun historyFor(datasetId: String): List<ChallengeHistoryEntry> =
        dao.challengeRecordsFor(datasetId, HISTORY_PER_DATASET_LIMIT)
            .map(ChallengeMappers::toDomain)

    override suspend fun recordForSeason(seasonId: String): ChallengeHistoryEntry? =
        dao.challengeRecordForSeason(seasonId)?.let(ChallengeMappers::toDomain)

    override suspend fun record(entry: ChallengeHistoryEntry) {
        dao.upsertChallengeRecord(ChallengeMappers.toEntity(entry))
    }

    override suspend fun personalBest(datasetId: String): PersonalBest {
        val attempts = historyFor(datasetId)
        val bestOverall = dao.bestChallengeRecord(datasetId)?.let(ChallengeMappers::toDomain)
        return PersonalBest(
            datasetId = datasetId,
            datasetDisplayName = bestOverall?.datasetDisplayName
                ?: attempts.firstOrNull()?.datasetDisplayName
                ?: datasetId,
            bestOverall = bestOverall,
            // The first attempt is looked up by ordinal rather than by taking the
            // oldest row, because a dataset's first attempt may have been recorded
            // before this table existed and therefore may be absent. Reading the
            // stored ordinal is the honest answer: if no attempt was recorded as the
            // first, there is no first-attempt best to show.
            bestFirstAttempt = attempts.filter { it.isFirstAttempt }.maxWithOrNull(BEST_ORDER),
            attemptCount = dao.challengeRecordCount(datasetId),
        )
    }

    private companion object {
        /**
         * A bound on a dataset's attempts, so a flow cannot grow without limit.
         *
         * High enough that no realistic player reaches it, low enough that the query
         * is bounded — the same reasoning the DAO applies to its other list reads.
         */
        const val HISTORY_PER_DATASET_LIMIT: Int = 500

        /**
         * Best-first ordering, matching the SQL that picks [PersonalBest.bestOverall].
         *
         * Stated in Kotlin as well as in SQL because the two answer the same
         * question and a reader should be able to see they agree. Score first, then
         * profit, then record id so the order is total.
         */
        val BEST_ORDER: Comparator<ChallengeHistoryEntry> =
            compareByDescending<ChallengeHistoryEntry> { it.score.total }
                .thenByDescending { it.profitMicros }
                .thenBy { it.recordId }
    }
}
