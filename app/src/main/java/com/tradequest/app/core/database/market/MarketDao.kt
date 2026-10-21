package com.tradequest.app.core.database.market

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for `market.db`.
 *
 * Step 2.5: every candle and news query is scoped to exactly one dataset. There
 * is deliberately no query here that returns candles or news across datasets,
 * and no method that accepts a nullable dataset id. Scoping is enforced by the
 * shape of this interface, not by convention.
 *
 * Step 2.4: no unbounded queries. Every list-returning method either takes a
 * time range or an explicit limit, or returns a single aggregate row.
 */
@Dao
interface MarketDao {

    // ---------- Datasets ----------

    /**
     * Inserts a dataset, ignoring the call if the id already exists.
     *
     * Returns the row id, or -1 when the dataset was already present.
     * [datasetId] derives from the candle content hash, so an existing id
     * implies identical content. This is the mechanism behind Step 2.7's
     * "never overwrite a dataset with different content under the same id".
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDataset(dataset: MarketDatasetEntity): Long

    @Query("SELECT * FROM MarketDataset WHERE datasetId = :datasetId")
    suspend fun getDataset(datasetId: String): MarketDatasetEntity?

    @Query("SELECT * FROM MarketDataset ORDER BY startTimestampUtcMs ASC")
    fun observeDatasets(): Flow<List<MarketDatasetEntity>>

    @Query("SELECT * FROM MarketDataset ORDER BY startTimestampUtcMs ASC")
    suspend fun observeDatasetsOnce(): List<MarketDatasetEntity>

    @Query("SELECT COUNT(*) FROM MarketDataset")
    suspend fun countDatasets(): Int

    /**
     * Total allocated bytes of the `Candle1m` table and its indexes.
     *
     * `dbstat` reports page bytes per table or index, never per row: its `path`
     * column is the B-tree path, which is `/` for a table root and an index
     * name for an index. Verified — `path` never contains a dataset id, so a
     * per-dataset byte count cannot be read from it. `MarketRepository` divides
     * this by the total candle count to estimate per-dataset size, and labels
     * the result as an estimate.
     *
     * `dbstat` is an optional SQLite module. The repository treats failure as
     * "size unavailable".
     */
    @Query("SELECT SUM(pgsize) FROM dbstat WHERE name = 'Candle1m'")
    suspend fun candleTableBytes(): Long?

    @Query("SELECT COUNT(*) FROM Candle1m")
    suspend fun countAllCandles(): Int

    @Query("DELETE FROM MarketDataset WHERE datasetId = :datasetId")
    suspend fun deleteDataset(datasetId: String): Int

    // ---------- Candles ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCandles(candles: List<Candle1mEntity>)

    @Query("SELECT COUNT(*) FROM Candle1m WHERE datasetId = :datasetId")
    suspend fun countCandles(datasetId: String): Int

    @Query(
        "SELECT * FROM Candle1m WHERE datasetId = :datasetId " +
            "AND timestampUtcMs >= :fromUtcMs AND timestampUtcMs < :toUtcMs " +
            "ORDER BY timestampUtcMs ASC LIMIT :limit",
    )
    suspend fun candlesInRange(
        datasetId: String,
        fromUtcMs: Long,
        toUtcMs: Long,
        limit: Int,
    ): List<Candle1mEntity>

    /**
     * The single candle at an exact minute, or null.
     *
     * Used by the simulation clock to resolve a timestamp without loading a
     * window. Bounded by construction: at most one row.
     */
    @Query(
        "SELECT * FROM Candle1m WHERE datasetId = :datasetId " +
            "AND timestampUtcMs = :timestampUtcMs LIMIT 1",
    )
    suspend fun candleAt(datasetId: String, timestampUtcMs: Long): Candle1mEntity?

    @Query(
        "SELECT * FROM Candle1m WHERE datasetId = :datasetId " +
            "ORDER BY timestampUtcMs ASC LIMIT 1",
    )
    suspend fun firstCandle(datasetId: String): Candle1mEntity?

    @Query(
        "SELECT * FROM Candle1m WHERE datasetId = :datasetId " +
            "ORDER BY timestampUtcMs DESC LIMIT 1",
    )
    suspend fun lastCandle(datasetId: String): Candle1mEntity?

    @Query(
        "SELECT MIN(timestampUtcMs) FROM Candle1m WHERE datasetId = :datasetId",
    )
    suspend fun minimumTimestamp(datasetId: String): Long?

    @Query(
        "SELECT MAX(timestampUtcMs) FROM Candle1m WHERE datasetId = :datasetId",
    )
    suspend fun maximumTimestamp(datasetId: String): Long?

    @Query("DELETE FROM Candle1m WHERE datasetId = :datasetId")
    suspend fun deleteCandles(datasetId: String): Int

    // ---------- News ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNews(events: List<NewsEventEntity>)

    @Query("SELECT COUNT(*) FROM NewsEvent WHERE datasetId = :datasetId")
    suspend fun countNews(datasetId: String): Int

    @Query(
        "SELECT * FROM NewsEvent WHERE datasetId = :datasetId " +
            "AND publishedAtUtcMs >= :fromUtcMs AND publishedAtUtcMs < :toUtcMs " +
            "ORDER BY publishedAtUtcMs ASC LIMIT :limit",
    )
    suspend fun newsInRange(
        datasetId: String,
        fromUtcMs: Long,
        toUtcMs: Long,
        limit: Int,
    ): List<NewsEventEntity>

    @Query("DELETE FROM NewsEvent WHERE datasetId = :datasetId")
    suspend fun deleteNews(datasetId: String): Int

    // ---------- Closures ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertClosures(closures: List<MarketClosureEntity>)

    @Query(
        "SELECT * FROM MarketClosure WHERE datasetId = :datasetId " +
            "ORDER BY startUtcMs ASC",
    )
    suspend fun closuresFor(datasetId: String): List<MarketClosureEntity>

    @Query("SELECT COUNT(*) FROM MarketClosure WHERE datasetId = :datasetId")
    suspend fun countClosures(datasetId: String): Int

    @Query("DELETE FROM MarketClosure WHERE datasetId = :datasetId")
    suspend fun deleteClosures(datasetId: String): Int

    // ---------- Validation ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertValidation(validation: DatasetValidationEntity)

    @Query("SELECT * FROM DatasetValidation WHERE datasetId = :datasetId")
    suspend fun validationFor(datasetId: String): DatasetValidationEntity?

    @Query("DELETE FROM DatasetValidation WHERE datasetId = :datasetId")
    suspend fun deleteValidation(datasetId: String): Int
}
