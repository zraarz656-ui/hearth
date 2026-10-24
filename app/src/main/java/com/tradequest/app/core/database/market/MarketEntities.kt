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

/**
 * A scheduled economic release, scoped to exactly one dataset (Steps 2.5, 12.1).
 *
 * # Why the release fields are nullable
 *
 * [forecast], [previous] and [actual] are all genuinely optional in the source
 * data, and they are optional for *different* reasons:
 *
 * - [forecast] and [previous] are unknown for a release the calendar carries
 *   with no consensus, and `NULL` says so. A `0.0` default would be a fabricated
 *   number that the UI would then display as if it were real.
 * - [actual] is `NULL` until the release happens. That is not a gap in the data;
 *   it is the whole point of the column. See [NewsReleaseProjection] for the
 *   rule that decides when a stored actual becomes visible.
 *
 * [reactionNote] is the one field whose visibility is time-gated on a *price*
 * window rather than on the release instant itself (Step 12.4), so it is stored
 * with the window length it needs rather than revealed immediately.
 */
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

    /**
     * Long-form description. Retained from the Phase 2 feed shape.
     *
     * Kept rather than dropped: removing a `NOT NULL` column in SQLite requires
     * rebuilding the table, which is exactly the kind of migration that can
     * silently lose rows. The column is harmless here — the calendar shows
     * [headline] and uses this only as an optional detail body — and keeping it
     * makes migration 1->2 purely additive.
     */
    @ColumnInfo(name = "body")
    val body: String = "",

    /** Category as stored; parsed through `NewsCategory.fromStored`. */
    @ColumnInfo(name = "category", defaultValue = "OTHER")
    val category: String = "OTHER",

    /** Impact as stored; parsed through `NewsImpact.fromStored`. */
    @ColumnInfo(name = "impact")
    val impact: String,

    /** Consensus forecast, or null when the source has none. */
    @ColumnInfo(name = "forecast")
    val forecast: String? = null,

    /** Previous reading, or null when the source has none. */
    @ColumnInfo(name = "previous")
    val previous: String? = null,

    /** The released figure. Null before the release, and null if never recorded. */
    @ColumnInfo(name = "actual")
    val actual: String? = null,

    /** Free-text note on how the market reacted. Gated on a price window. */
    @ColumnInfo(name = "reactionNote")
    val reactionNote: String? = null,

    /**
     * How long after the release the reaction note becomes visible, in minutes.
     *
     * Stored per event because the window is a property of the release, not of
     * the app: a rate decision moves the market for longer than a minor survey.
     * Zero means the note is visible as soon as the release is.
     */
    @ColumnInfo(name = "reactionWindowMinutes", defaultValue = "0")
    val reactionWindowMinutes: Int = 0,
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
