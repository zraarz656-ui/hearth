package com.tradequest.app.core.database.player

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data access for `tradequest.db`.
 *
 * Every season-scoped query takes a non-null `seasonId`. There is no method
 * that reads positions, orders, executions or stats across seasons, because
 * `AGENTS.md` forbids transferring positions or balances between datasets.
 *
 * List-returning methods take an explicit limit where the row count could grow
 * without bound (Step 2.4).
 */
@Dao
interface PlayerDao {

    // ---------- Seasons ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSeason(season: SeasonEntity)

    @Query("SELECT * FROM Season WHERE seasonId = :seasonId")
    suspend fun getSeason(seasonId: String): SeasonEntity?

    @Query("SELECT * FROM Season ORDER BY createdAtRealUtcMs DESC")
    fun observeSeasons(): Flow<List<SeasonEntity>>

    @Query("SELECT * FROM Season WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun activeSeason(): SeasonEntity?

    @Query("SELECT * FROM Season WHERE status = 'ACTIVE' LIMIT 1")
    fun observeActiveSeason(): Flow<SeasonEntity?>

    /**
     * Inserts a season only if no other season is ACTIVE.
     *
     * Step 11.2: only one Challenge Mode season may be active at a time. The
     * check and the insert share a transaction so two concurrent callers cannot
     * both observe an empty active set and both insert.
     *
     * Returns true when the season was inserted.
     */
    @Transaction
    suspend fun insertSeasonIfNoneActive(season: SeasonEntity): Boolean {
        if (activeSeason() != null) return false
        insertSeason(season)
        return true
    }

    @Query(
        "UPDATE Season SET status = :status, outcomeReason = :reason, " +
            "completedAtSimUtcMs = :completedAtSimUtcMs WHERE seasonId = :seasonId",
    )
    suspend fun updateSeasonStatus(
        seasonId: String,
        status: String,
        reason: String?,
        completedAtSimUtcMs: Long?,
    ): Int

    @Query(
        "UPDATE Season SET balanceMicros = :balanceMicros, equityMicros = :equityMicros, " +
            "peakEquityMicros = :peakEquityMicros, tradingDayCount = :tradingDayCount, " +
            "dailyStrikeCount = :dailyStrikeCount, scorePoints = :scorePoints " +
            "WHERE seasonId = :seasonId",
    )
    suspend fun updateSeasonProgress(
        seasonId: String,
        balanceMicros: Long,
        equityMicros: Long,
        peakEquityMicros: Long,
        tradingDayCount: Int,
        dailyStrikeCount: Int,
        scorePoints: Int,
    ): Int

    @Query("DELETE FROM Season WHERE seasonId = :seasonId")
    suspend fun deleteSeason(seasonId: String): Int

    @Query("SELECT COUNT(*) FROM Season")
    suspend fun countSeasons(): Int

    // ---------- Orders ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrder(order: TradeOrderEntity)

    @Query("SELECT * FROM TradeOrder WHERE seasonId = :seasonId AND orderId = :orderId")
    suspend fun getOrder(seasonId: String, orderId: String): TradeOrderEntity?

    @Query(
        "SELECT * FROM TradeOrder WHERE seasonId = :seasonId AND status = 'PENDING' " +
            "ORDER BY placedAtSimUtcMs ASC LIMIT :limit",
    )
    suspend fun pendingOrders(seasonId: String, limit: Int): List<TradeOrderEntity>

    @Query("UPDATE TradeOrder SET status = :status, resolvedAtSimUtcMs = :resolvedAtSimUtcMs WHERE orderId = :orderId")
    suspend fun resolveOrder(orderId: String, status: String, resolvedAtSimUtcMs: Long): Int

    @Query("SELECT COUNT(*) FROM TradeOrder WHERE seasonId = :seasonId")
    suspend fun countOrders(seasonId: String): Int

    // ---------- Executions ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExecution(execution: ExecutionEntity)

    @Query(
        "SELECT * FROM Execution WHERE seasonId = :seasonId " +
            "ORDER BY executedAtSimUtcMs ASC LIMIT :limit",
    )
    suspend fun executionsFor(seasonId: String, limit: Int): List<ExecutionEntity>

    @Query("SELECT COUNT(*) FROM Execution WHERE seasonId = :seasonId")
    suspend fun countExecutions(seasonId: String): Int

    // ---------- Positions ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPosition(position: PositionEntity)

    @Query("SELECT * FROM Position WHERE seasonId = :seasonId AND positionId = :positionId")
    suspend fun getPosition(seasonId: String, positionId: String): PositionEntity?

    @Query(
        "SELECT * FROM Position WHERE seasonId = :seasonId AND status = 'OPEN' " +
            "ORDER BY openedAtSimUtcMs ASC LIMIT :limit",
    )
    suspend fun openPositions(seasonId: String, limit: Int): List<PositionEntity>

    @Query("SELECT COUNT(*) FROM Position WHERE seasonId = :seasonId AND status = 'OPEN'")
    suspend fun countOpenPositions(seasonId: String): Int

    @Query(
        "UPDATE Position SET status = 'CLOSED', closedAtSimUtcMs = :closedAtSimUtcMs, " +
            "closePriceUnits = :closePriceUnits WHERE positionId = :positionId",
    )
    suspend fun closePosition(
        positionId: String,
        closedAtSimUtcMs: Long,
        closePriceUnits: Int,
    ): Int

    // ---------- Closed trades ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertClosedTrade(trade: ClosedTradeEntity)

    @Query(
        "SELECT * FROM ClosedTrade WHERE seasonId = :seasonId " +
            "ORDER BY closeTimeSimUtcMs DESC LIMIT :limit",
    )
    suspend fun closedTrades(seasonId: String, limit: Int): List<ClosedTradeEntity>

    @Query("SELECT COUNT(*) FROM ClosedTrade WHERE seasonId = :seasonId")
    suspend fun countClosedTrades(seasonId: String): Int

    // ---------- Daily stats ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyStats(stats: DailyStatsEntity)

    @Query(
        "SELECT * FROM DailyStats WHERE seasonId = :seasonId " +
            "ORDER BY tradingDayStartSimUtcMs ASC",
    )
    suspend fun dailyStats(seasonId: String): List<DailyStatsEntity>

    @Query("SELECT COUNT(*) FROM DailyStats WHERE seasonId = :seasonId")
    suspend fun countDailyStats(seasonId: String): Int

    // ---------- Equity snapshots ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEquitySnapshot(snapshot: EquitySnapshotEntity)

    @Query(
        "SELECT * FROM EquitySnapshot WHERE seasonId = :seasonId " +
            "ORDER BY timestampSimUtcMs ASC LIMIT :limit",
    )
    suspend fun equitySnapshots(seasonId: String, limit: Int): List<EquitySnapshotEntity>

    // ---------- Drawings ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDrawing(drawing: DrawingEntity)

    @Query("SELECT * FROM Drawing WHERE seasonId = :seasonId ORDER BY createdAtSimUtcMs ASC")
    suspend fun drawings(seasonId: String): List<DrawingEntity>

    @Query("DELETE FROM Drawing WHERE seasonId = :seasonId AND drawingId = :drawingId")
    suspend fun deleteDrawing(seasonId: String, drawingId: String): Int

    // ---------- Journal ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJournalEntry(entry: TradeJournalEntity)

    @Query(
        "SELECT * FROM TradeJournal WHERE seasonId = :seasonId " +
            "ORDER BY createdAtSimUtcMs DESC LIMIT :limit",
    )
    suspend fun journalEntries(seasonId: String, limit: Int): List<TradeJournalEntity>

    // ---------- Badges ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBadge(badge: BadgeEntity): Long

    @Query("SELECT * FROM Badge WHERE seasonId = :seasonId ORDER BY earnedAtSimUtcMs ASC")
    suspend fun badges(seasonId: String): List<BadgeEntity>

    // ---------- Replay checkpoint ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: ReplayCheckpointEntity)

    @Query("SELECT * FROM ReplayCheckpoint WHERE seasonId = :seasonId")
    suspend fun checkpoint(seasonId: String): ReplayCheckpointEntity?

    @Query("DELETE FROM ReplayCheckpoint WHERE seasonId = :seasonId")
    suspend fun deleteCheckpoint(seasonId: String): Int

    @Query("SELECT COUNT(*) FROM ReplayCheckpoint WHERE seasonId = :seasonId")
    suspend fun countCheckpoints(seasonId: String): Int

    // ---------- Replay state ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReplayState(state: ReplayStateEntity)

    @Query("SELECT * FROM ReplayState WHERE seasonId = :seasonId")
    suspend fun replayState(seasonId: String): ReplayStateEntity?

    @Query("SELECT COUNT(*) FROM ReplayState WHERE seasonId = :seasonId")
    suspend fun replayStateCount(seasonId: String): Int

    @Query("DELETE FROM ReplayState WHERE seasonId = :seasonId")
    suspend fun deleteReplayState(seasonId: String): Int

    // ---------- Settings ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: SettingEntity)

    @Query("SELECT value FROM Settings WHERE `key` = :key")
    suspend fun getSetting(key: String): String?
}
