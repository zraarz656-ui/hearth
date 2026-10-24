package com.tradequest.app.core.market

import java.time.Instant

/**
 * Read models returned by [MarketRepository].
 *
 * These live in `core.market`, not `core.database`, so that the repository
 * interface can name them without `core.market` depending on `core.database`.
 * They are domain types, deliberately distinct from the Room entities that
 * back them (Step 2.3).
 */

/** A dataset as the Market Library lists it (Step 2.6). */
data class DatasetSummary(
    val datasetId: String,
    val displayName: String,
    val instrument: String,
    val startTimestampUtcMs: Long,
    val endTimestampUtcMs: Long,
    val candleCount: Int,
    val newsCount: Int,
    val validationStatus: String,
    val hasUnexpectedGaps: Boolean,
    /**
     * Estimated bytes on disk, or null when the size could not be measured.
     *
     * This is an estimate, not an exact figure: SQLite's `dbstat` reports bytes
     * per table and index, not per row, so a single dataset's share of a shared
     * table cannot be read directly. See `MarketDao.candleTableBytes`.
     */
    val estimatedStorageBytes: Long?,
) {
    val startInstant: Instant get() = Instant.ofEpochMilli(startTimestampUtcMs)
    val endInstant: Instant get() = Instant.ofEpochMilli(endTimestampUtcMs)

    /** Covered span, excluding the final minute. */
    val spanMillis: Long get() = (endTimestampUtcMs - startTimestampUtcMs).coerceAtLeast(0L)

    val spanDays: Long get() = spanMillis / 86_400_000L
}

/** A news event inside one dataset. */
data class NewsItem(
    val newsId: Long,
    val datasetId: String,
    val publishedAtUtcMs: Long,
    val headline: String,
    val body: String,
    val impact: String,
    /**
     * Category as stored, parsed through `NewsCategory.fromStored` (Step 12.1).
     *
     * Left as the stored string here, like [impact]: this type is the
     * repository's read model, and parsing is the domain's job. The parsed
     * enums appear on `NewsRelease`.
     */
    val category: String = "OTHER",
    /** Consensus forecast, or null when the source has none. */
    val forecast: String? = null,
    /** Previous reading, or null when the source has none. */
    val previous: String? = null,
    /** The released figure, or null before the release. */
    val actual: String? = null,
    /** Free-text reaction note; visibility is gated by the projection. */
    val reactionNote: String? = null,
    /** Minutes after release before the reaction note becomes visible. */
    val reactionWindowMinutes: Int = 0,
)

/** A stored market closure inside one dataset. */
data class DatasetClosure(
    val datasetId: String,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val reason: String,
    val missingMinuteCount: Int,
)

/** The stored validation verdict for one dataset. */
data class DatasetValidationInfo(
    val datasetId: String,
    val status: String,
    val errorCount: Int,
    val warningCount: Int,
    val missingMinuteCount: Int,
    val duplicateCount: Int,
    val coveragePercent: Double,
    val issuesJson: String,
)
