package com.tradequest.app.core.database.market

import androidx.room.withTransaction
import com.tradequest.app.core.market.DatasetClosure
import com.tradequest.app.core.market.DatasetManifest
import com.tradequest.app.core.market.DatasetSummary
import com.tradequest.app.core.market.DatasetValidationInfo
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.market.NewsItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [MarketRepository].
 *
 * All multi-step writes run in a single transaction, so a dataset is never left
 * half-imported: either its metadata, candles, closures and validation all
 * exist, or none of them do.
 */
class RoomMarketRepository(
    private val db: MarketDatabase,
) : MarketRepository {

    private val dao get() = db.marketDao()

    override fun observeDatasets(): Flow<List<DatasetSummary>> =
        dao.observeDatasets().map { rows -> rows.map { it.toSummaryLite() } }

    override suspend fun listDatasets(): List<DatasetSummary> =
        dao.observeDatasetsOnce().map { it.toSummaryLite() }

    override suspend fun getDataset(datasetId: String): DatasetSummary? {
        val row = dao.getDataset(datasetId) ?: return null
        return row.toSummaryLite()
    }

    /**
     * Builds a summary. The per-dataset size is an estimate derived from the
     * total candle-table bytes divided by the total candle count, because
     * `dbstat` cannot report bytes per row. See [MarketDao.candleTableBytes].
     */
    private suspend fun MarketDatasetEntity.toSummaryLite(): DatasetSummary {
        val validation = dao.validationFor(datasetId)
        val news = dao.countNews(datasetId)
        return toSummary(
            newsCount = news,
            validation = validation,
            estimatedStorageBytes = estimateBytesFor(candleCount),
        )
    }

    private suspend fun estimateBytesFor(candleCount: Int): Long? {
        if (candleCount <= 0) return 0L
        val totalBytes = runCatching { dao.candleTableBytes() }.getOrNull() ?: return null
        val totalCandles = dao.countAllCandles()
        if (totalCandles <= 0) return null
        return totalBytes * candleCount / totalCandles
    }

    override suspend fun countDatasets(): Int = dao.countDatasets()

    override suspend fun candlesInRange(
        datasetId: String,
        fromUtcMs: Long,
        toUtcMs: Long,
        maxRows: Int,
    ): List<HistoricalCandle> {
        require(maxRows > 0) { "maxRows must be positive, was $maxRows" }
        require(fromUtcMs <= toUtcMs) { "fromUtcMs ($fromUtcMs) must not exceed toUtcMs ($toUtcMs)" }

        // Fetch one row beyond the budget so an over-range request is detected
        // rather than silently truncated. A truncated series would draw a
        // chart with a missing tail and no visible error.
        val probe = dao.candlesInRange(datasetId, fromUtcMs, toUtcMs, maxRows + 1)
        require(probe.size <= maxRows) {
            "candle range for $datasetId exceeds maxRows=$maxRows; " +
                "narrow the range or raise the limit deliberately"
        }
        return probe.toDomainCandles()
    }

    override suspend fun candleAt(datasetId: String, timestampUtcMs: Long): HistoricalCandle? =
        dao.candleAt(datasetId, timestampUtcMs)?.toDomain()

    override suspend fun candleCount(datasetId: String): Int = dao.countCandles(datasetId)

    override suspend fun timeBounds(datasetId: String): Pair<Long, Long>? {
        val min = dao.minimumTimestamp(datasetId) ?: return null
        val max = dao.maximumTimestamp(datasetId) ?: return null
        return min to max
    }

    override suspend fun newsInRange(
        datasetId: String,
        fromUtcMs: Long,
        toUtcMs: Long,
        maxRows: Int,
    ): List<NewsItem> {
        require(maxRows > 0) { "maxRows must be positive, was $maxRows" }
        require(fromUtcMs <= toUtcMs) { "fromUtcMs ($fromUtcMs) must not exceed toUtcMs ($toUtcMs)" }

        val probe = dao.newsInRange(datasetId, fromUtcMs, toUtcMs, maxRows + 1)
        require(probe.size <= maxRows) {
            "news range for $datasetId exceeds maxRows=$maxRows"
        }
        return probe.map { it.toDomain() }
    }

    override suspend fun newsCount(datasetId: String): Int = dao.countNews(datasetId)

    override suspend fun nextHighImpactNews(datasetId: String, afterUtcMs: Long): NewsItem? =
        dao.highImpactNewsFrom(datasetId, afterUtcMs, limit = 1).firstOrNull()?.toDomain()

    override suspend fun closures(datasetId: String): List<DatasetClosure> =
        dao.closuresFor(datasetId).map { it.toDomain() }

    override suspend fun validation(datasetId: String): DatasetValidationInfo? =
        dao.validationFor(datasetId)?.toDomain()

    override suspend fun deleteDataset(datasetId: String): Boolean = db.withTransaction {
        if (dao.getDataset(datasetId) == null) return@withTransaction false
        // Child rows first: the foreign keys are RESTRICT, so deleting the
        // parent while children exist would be refused by SQLite.
        dao.deleteCandles(datasetId)
        dao.deleteNews(datasetId)
        dao.deleteClosures(datasetId)
        dao.deleteValidation(datasetId)
        dao.deleteDataset(datasetId) > 0
    }

    /**
     * Stores a validated import (Step 2.7).
     *
     * Returns [ImportOutcome.AlreadyPresent] when a dataset with this id already
     * exists. The id derives from the candle content hash, so an existing id
     * implies identical content; an existing id with *different* content cannot
     * occur through this method, and is rejected rather than overwritten.
     */
    suspend fun storeImport(request: ImportRequest): ImportOutcome = db.withTransaction {
        val existing = dao.getDataset(request.manifest.datasetId)
        if (existing != null) {
            return@withTransaction if (existing.contentHash == request.manifest.contentHash) {
                ImportOutcome.AlreadyPresent(request.manifest.datasetId)
            } else {
                // Same id, different content. Overwriting would corrupt any
                // season already replaying this dataset.
                ImportOutcome.ContentMismatch(
                    datasetId = request.manifest.datasetId,
                    storedHash = existing.contentHash,
                    incomingHash = request.manifest.contentHash,
                )
            }
        }

        val manifest = request.manifest
        dao.insertDataset(
            MarketDatasetEntity(
                datasetId = manifest.datasetId,
                displayName = request.displayName,
                instrument = manifest.instrument,
                startTimestampUtcMs = manifest.startTimestampUtcMs,
                endTimestampUtcMs = manifest.endTimestampUtcMs,
                candleCount = manifest.candleCount,
                contentHash = manifest.contentHash,
                validationStatus = manifest.validationStatus,
                sourceDescription = manifest.sourceDescription,
                importedAtUtcMs = request.importedAtUtcMs,
            ),
        )

        dao.insertCandles(request.candles.toEntities(manifest.datasetId))

        val closures = request.closures.map { c ->
            MarketClosureEntity(
                datasetId = manifest.datasetId,
                startUtcMs = c.startUtcMs,
                endUtcMs = c.endUtcMs,
                reason = c.reason,
                missingMinuteCount = c.missingMinuteCount,
            )
        }
        if (closures.isNotEmpty()) dao.insertClosures(closures)

        if (request.news.isNotEmpty()) {
            dao.insertNews(
                request.news.map { n ->
                    NewsEventEntity(
                        datasetId = manifest.datasetId,
                        publishedAtUtcMs = n.publishedAtUtcMs,
                        headline = n.headline,
                        body = n.body,
                        category = n.category,
                        impact = n.impact,
                        forecast = n.forecast,
                        previous = n.previous,
                        actual = n.actual,
                        reactionNote = n.reactionNote,
                        reactionWindowMinutes = n.reactionWindowMinutes,
                    )
                },
            )
        }

        dao.insertValidation(
            DatasetValidationEntity(
                datasetId = manifest.datasetId,
                status = request.validationStatus,
                errorCount = request.errorCount,
                warningCount = request.warningCount,
                missingMinuteCount = manifest.missingMinuteCount,
                duplicateCount = manifest.duplicateCount,
                coveragePercent = request.coveragePercent,
                issuesJson = request.issuesJson,
            ),
        )

        ImportOutcome.Stored(manifest.datasetId)
    }
}

/** A validated import ready to be persisted. */
data class ImportRequest(
    val manifest: DatasetManifest,
    val candles: List<HistoricalCandle>,
    val displayName: String,
    val closures: List<DatasetClosure> = emptyList(),
    val news: List<PendingNews> = emptyList(),
    val validationStatus: String = "VALID",
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val coveragePercent: Double = 100.0,
    val issuesJson: String = "[]",
    val importedAtUtcMs: Long,
)

/** News supplied with an import, before it has a database id (Steps 2.4, 12.1). */
data class PendingNews(
    val publishedAtUtcMs: Long,
    val headline: String,
    val body: String = "",
    val impact: String,
    val category: String = "OTHER",
    val forecast: String? = null,
    val previous: String? = null,
    val actual: String? = null,
    val reactionNote: String? = null,
    val reactionWindowMinutes: Int = 0,
)

sealed interface ImportOutcome {
    /** Newly written. */
    data class Stored(val datasetId: String) : ImportOutcome

    /** Already present with identical content; nothing was written. */
    data class AlreadyPresent(val datasetId: String) : ImportOutcome

    /**
     * The id exists but holds different content. This indicates a broken
     * content-hash derivation, and is refused rather than overwritten.
     */
    data class ContentMismatch(
        val datasetId: String,
        val storedHash: String,
        val incomingHash: String,
    ) : ImportOutcome
}
