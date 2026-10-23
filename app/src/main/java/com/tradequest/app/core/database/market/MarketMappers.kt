package com.tradequest.app.core.database.market

import com.tradequest.app.core.market.DatasetClosure
import com.tradequest.app.core.market.DatasetSummary
import com.tradequest.app.core.market.DatasetValidationInfo
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.market.NewsItem

/**
 * Mapping between `market.db` persistence rows and `core.market` domain types
 * (Step 2.3).
 *
 * `docs/architecture.md` rule 5 forbids exposing Room entities as domain
 * models, so every boundary crossing goes through a function here. Column and
 * field names differ deliberately: `openUnits` in the entity is the same
 * quantity as `openUnits` in `HistoricalCandle`, but they are separate types so
 * that a schema change cannot silently alter engine behaviour.
 */

fun Candle1mEntity.toDomain(): HistoricalCandle = HistoricalCandle(
    timestampUtcMs = timestampUtcMs,
    openUnits = openUnits,
    highUnits = highUnits,
    lowUnits = lowUnits,
    closeUnits = closeUnits,
    tickVolume = tickVolume,
)

fun HistoricalCandle.toEntity(datasetId: String): Candle1mEntity = Candle1mEntity(
    datasetId = datasetId,
    timestampUtcMs = timestampUtcMs,
    openUnits = openUnits,
    highUnits = highUnits,
    lowUnits = lowUnits,
    closeUnits = closeUnits,
    tickVolume = tickVolume,
)

fun List<Candle1mEntity>.toDomainCandles(): List<HistoricalCandle> = map { it.toDomain() }

fun List<HistoricalCandle>.toEntities(datasetId: String): List<Candle1mEntity> =
    map { it.toEntity(datasetId) }

fun MarketDatasetEntity.toSummary(
    newsCount: Int,
    validation: DatasetValidationEntity?,
    estimatedStorageBytes: Long?,
): DatasetSummary = DatasetSummary(
    datasetId = datasetId,
    displayName = displayName,
    instrument = instrument,
    startTimestampUtcMs = startTimestampUtcMs,
    endTimestampUtcMs = endTimestampUtcMs,
    candleCount = candleCount,
    newsCount = newsCount,
    validationStatus = validation?.status ?: validationStatus,
    hasUnexpectedGaps = validation?.let { it.errorCount > 0 || it.missingMinuteCount > 0 } == true,
    estimatedStorageBytes = estimatedStorageBytes,
)

fun NewsEventEntity.toDomain(): NewsItem = NewsItem(
    newsId = newsId,
    datasetId = datasetId,
    publishedAtUtcMs = publishedAtUtcMs,
    headline = headline,
    body = body,
    impact = impact,
)

fun MarketClosureEntity.toDomain(): DatasetClosure = DatasetClosure(
    datasetId = datasetId,
    startUtcMs = startUtcMs,
    endUtcMs = endUtcMs,
    reason = reason,
    missingMinuteCount = missingMinuteCount,
)

fun DatasetValidationEntity.toDomain(): DatasetValidationInfo = DatasetValidationInfo(
    datasetId = datasetId,
    status = status,
    errorCount = errorCount,
    warningCount = warningCount,
    missingMinuteCount = missingMinuteCount,
    duplicateCount = duplicateCount,
    coveragePercent = coveragePercent,
    issuesJson = issuesJson,
)
