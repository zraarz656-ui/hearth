package com.tradequest.app.core.market

import kotlinx.coroutines.flow.Flow

/**
 * Read access to stored historical market data (Step 2.4).
 *
 * Step 2.5 — dataset isolation — is expressed in this signature:
 *
 *  - Every candle and news method takes a non-null `datasetId`. There is no
 *    overload that omits it, so a caller cannot accidentally read across
 *    datasets.
 *  - No method returns data from more than one dataset. There is deliberately
 *    no "all candles" or "all news" call, and no way to combine datasets.
 *  - Every list-returning method is bounded: either by an explicit time range
 *    plus [maxRows], or by an aggregate. Step 2.4 forbids unbounded queries.
 *
 * Implemented by `core.database.market.RoomMarketRepository`. The interface
 * lives here so `core.market` does not depend on `core.database`.
 */
interface MarketRepository {

    /** Datasets ordered by start time, for the Market Library. */
    fun observeDatasets(): Flow<List<DatasetSummary>>

    suspend fun listDatasets(): List<DatasetSummary>

    suspend fun getDataset(datasetId: String): DatasetSummary?

    suspend fun countDatasets(): Int

    /**
     * Candles for one dataset in `[fromUtcMs, toUtcMs)`.
     *
     * [maxRows] bounds the result. Callers showing a chart window pass the
     * window size; the implementation throws if the range would exceed it
     * rather than silently truncating, because a silently truncated candle
     * series would render a wrong chart.
     */
    suspend fun candlesInRange(
        datasetId: String,
        fromUtcMs: Long,
        toUtcMs: Long,
        maxRows: Int = DEFAULT_MAX_CANDLE_ROWS,
    ): List<HistoricalCandle>

    /** The candle at an exact minute, or null. */
    suspend fun candleAt(datasetId: String, timestampUtcMs: Long): HistoricalCandle?

    suspend fun candleCount(datasetId: String): Int

    /** The first and last stored minute for one dataset, or null when empty. */
    suspend fun timeBounds(datasetId: String): Pair<Long, Long>?

    suspend fun newsInRange(
        datasetId: String,
        fromUtcMs: Long,
        toUtcMs: Long,
        maxRows: Int = DEFAULT_MAX_NEWS_ROWS,
    ): List<NewsItem>

    suspend fun newsCount(datasetId: String): Int

    /**
     * The next high-impact release at or after [afterUtcMs], or null (Step 12.6).
     *
     * Returns the domain [NewsItem], not the entity, so Time Warp's caller stays
     * on the domain side of the repository boundary. Impact matching is
     * case-insensitive inside the implementation, because the stored string is
     * external data.
     */
    suspend fun nextHighImpactNews(datasetId: String, afterUtcMs: Long): NewsItem?

    suspend fun closures(datasetId: String): List<DatasetClosure>

    suspend fun validation(datasetId: String): DatasetValidationInfo?

    /**
     * Deletes one dataset and everything scoped to it, in one transaction.
     *
     * Returns false when the dataset does not exist. Refuses to delete while
     * the dataset is referenced by an active season; that check is performed by
     * the caller, because a season lives in a different database and
     * `docs/architecture.md` rule 6 forbids cross-database joins.
     */
    suspend fun deleteDataset(datasetId: String): Boolean

    companion object {
        /**
         * Upper bound on a single candle read.
         *
         * Three months of continuous minutes is about 130,000, so this allows a
         * whole dataset to be read deliberately while still preventing an
         * accidental unbounded scan.
         */
        const val DEFAULT_MAX_CANDLE_ROWS: Int = 200_000

        const val DEFAULT_MAX_NEWS_ROWS: Int = 10_000
    }
}
