package com.tradequest.app.core.database.market

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities for `market.db` (Step 2.1).
 *
 * These are persistence shapes, not domain models. `AGENTS.md` and
 * `docs/architecture.md` rule 5 forbid using Room entities as domain models,
 * so every read maps to a `core.market` type. See `MarketMappers.kt`.
 *
 * Price columns hold integer **thousandths of a USD**, never cents. See
 * `docs/adr-0001-price-scale.md`.
 */

/**
 * One imported historical dataset.
 *
 * [datasetId] is derived from the candle content hash, so the same data always
 * maps to the same id. It is immutable: a dataset is never overwritten with
 * different content under the same id (Step 2.7).
 */
@Entity(tableName = "MarketDataset")
data class MarketDatasetEntity(
    @PrimaryKey
    @ColumnInfo(name = "datasetId")
    val datasetId: String,

    @ColumnInfo(name = "displayName")
    val displayName: String,

    @ColumnInfo(name = "instrument")
    val instrument: String,

    @ColumnInfo(name = "startTimestampUtcMs")
    val startTimestampUtcMs: Long,

    @ColumnInfo(name = "endTimestampUtcMs")
    val endTimestampUtcMs: Long,

    @ColumnInfo(name = "candleCount")
    val candleCount: Int,

    @ColumnInfo(name = "contentHash")
    val contentHash: String,

    @ColumnInfo(name = "validationStatus")
    val validationStatus: String,

    @ColumnInfo(name = "sourceDescription")
    val sourceDescription: String,

    @ColumnInfo(name = "importedAtUtcMs")
    val importedAtUtcMs: Long,
)

/**
 * One minute of price history.
 *
 * Primary key is `(datasetId, timestampUtcMs)`, which both enforces duplicate
 * prevention and provides the covering index for the range queries the chart
 * will issue. A separate `timestampUtcMs` index is deliberately NOT declared:
 * it would be unusable without a dataset scope, and Step 2.5 forbids unscoped
 * candle access.
 */
@Entity(
    tableName = "Candle1m",
    primaryKeys = ["datasetId", "timestampUtcMs"],
    foreignKeys = [
        ForeignKey(
            entity = MarketDatasetEntity::class,
            parentColumns = ["datasetId"],
            childColumns = ["datasetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class Candle1mEntity(
    @ColumnInfo(name = "datasetId")
    val datasetId: String,

    @ColumnInfo(name = "timestampUtcMs")
    val timestampUtcMs: Long,

    @ColumnInfo(name = "openUnits")
    val openUnits: Int,

    @ColumnInfo(name = "highUnits")
    val highUnits: Int,

    @ColumnInfo(name = "lowUnits")
    val lowUnits: Int,

    @ColumnInfo(name = "closeUnits")
    val closeUnits: Int,

    @ColumnInfo(name = "tickVolume")
    val tickVolume: Int,
)

/** A news event, scoped to exactly one dataset (Step 2.5). */
@Entity(
    tableName = "NewsEvent",
    foreignKeys = [
        ForeignKey(
            entity = MarketDatasetEntity::class,
            parentColumns = ["datasetId"],
            childColumns = ["datasetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["datasetId", "publishedAtUtcMs"])],
)
data class NewsEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "newsId")
    val newsId: Long = 0,

    @ColumnInfo(name = "datasetId")
    val datasetId: String,

    @ColumnInfo(name = "publishedAtUtcMs")
    val publishedAtUtcMs: Long,

    @ColumnInfo(name = "headline")
    val headline: String,

    @ColumnInfo(name = "body")
    val body: String,

    @ColumnInfo(name = "impact")
    val impact: String,
)

/** A detected market closure within one dataset. */
@Entity(
    tableName = "MarketClosure",
    primaryKeys = ["datasetId", "startUtcMs"],
    foreignKeys = [
        ForeignKey(
            entity = MarketDatasetEntity::class,
            parentColumns = ["datasetId"],
            childColumns = ["datasetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class MarketClosureEntity(
    @ColumnInfo(name = "datasetId")
    val datasetId: String,

    @ColumnInfo(name = "startUtcMs")
    val startUtcMs: Long,

    @ColumnInfo(name = "endUtcMs")
    val endUtcMs: Long,

    @ColumnInfo(name = "reason")
    val reason: String,

    @ColumnInfo(name = "missingMinuteCount")
    val missingMinuteCount: Int,
)

/** The stored validation verdict for one dataset. */
@Entity(
    tableName = "DatasetValidation",
    foreignKeys = [
        ForeignKey(
            entity = MarketDatasetEntity::class,
            parentColumns = ["datasetId"],
            childColumns = ["datasetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class DatasetValidationEntity(
    @PrimaryKey
    @ColumnInfo(name = "datasetId")
    val datasetId: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "errorCount")
    val errorCount: Int,

    @ColumnInfo(name = "warningCount")
    val warningCount: Int,

    @ColumnInfo(name = "missingMinuteCount")
    val missingMinuteCount: Int,

    @ColumnInfo(name = "duplicateCount")
    val duplicateCount: Int,

    @ColumnInfo(name = "coveragePercent")
    val coveragePercent: Double,

    /** JSON array of issue codes and messages, for display in the library. */
    @ColumnInfo(name = "issuesJson")
    val issuesJson: String,
)
