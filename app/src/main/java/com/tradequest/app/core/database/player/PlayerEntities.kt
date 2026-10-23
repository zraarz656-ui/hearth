package com.tradequest.app.core.database.player

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities for `tradequest.db` (Step 2.2).
 *
 * These are persistence shapes, not domain models. `docs/architecture.md`
 * rule 5 forbids using Room entities as domain models; every read maps to a
 * `core.trading` / `core.challenge` type. See `PlayerMappers.kt`.
 *
 * Unit conventions, from `docs/adr-0001-price-scale.md` and
 * `docs/architecture.md`:
 *  - money   -> `Long` micro-dollars (`$10,000.00` = `10_000_000_000`)
 *  - price   -> `Int` thousandths of a USD (`$2,450.505` = `2_450_505`)
 *  - volume  -> `Int` hundredths of a lot (`0.20` lots = `20`)
 *  - distance-> `Int` pips (`1` pip = `0.10` USD)
 *
 * `Season.datasetId` deliberately has NO foreign key. `market.db` and
 * `tradequest.db` are separate databases and `docs/architecture.md` rule 6
 * forbids cross-database joins, so SQLite cannot enforce it. The rule that
 * every season references exactly one existing dataset is enforced by
 * `PlayerRepository` at the point a season is created, and is covered by test.
 */

/** One challenge or practice season. References exactly one dataset (Step 2.5). */
@Entity(
    tableName = "Season",
    indices = [Index(value = ["datasetId"])],
)
data class SeasonEntity(
    @PrimaryKey
    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    /** Logical reference to `market.db`'s `MarketDataset.datasetId`. Not an FK. */
    @ColumnInfo(name = "datasetId")
    val datasetId: String,

    /** CHALLENGE or PRACTICE. */
    @ColumnInfo(name = "mode")
    val mode: String,

    /** ACTIVE, PASSED, FAILED or ABANDONED. */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "startingBalanceMicros")
    val startingBalanceMicros: Long,

    @ColumnInfo(name = "balanceMicros")
    val balanceMicros: Long,

    @ColumnInfo(name = "equityMicros")
    val equityMicros: Long,

    @ColumnInfo(name = "peakEquityMicros")
    val peakEquityMicros: Long,

    /** Historical (simulated) time the season started. */
    @ColumnInfo(name = "startedAtSimUtcMs")
    val startedAtSimUtcMs: Long,

    @ColumnInfo(name = "createdAtRealUtcMs")
    val createdAtRealUtcMs: Long,

    @ColumnInfo(name = "completedAtSimUtcMs")
    val completedAtSimUtcMs: Long?,

    @ColumnInfo(name = "tradingDayCount")
    val tradingDayCount: Int,

    @ColumnInfo(name = "dailyStrikeCount")
    val dailyStrikeCount: Int,

    @ColumnInfo(name = "scorePoints")
    val scorePoints: Int,

    @ColumnInfo(name = "outcomeReason")
    val outcomeReason: String?,
)

/**
 * An order, in any state. Kept separate from [ExecutionEntity] because one
 * order may produce several partial fills.
 */
@Entity(
    tableName = "TradeOrder",
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["seasonId", "placedAtSimUtcMs"])],
)
data class TradeOrderEntity(
    @PrimaryKey
    @ColumnInfo(name = "orderId")
    val orderId: String,

    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    @ColumnInfo(name = "placedAtSimUtcMs")
    val placedAtSimUtcMs: Long,

    /** MARKET, LIMIT or STOP. */
    @ColumnInfo(name = "orderType")
    val orderType: String,

    /** BUY or SELL. */
    @ColumnInfo(name = "side")
    val side: String,

    @ColumnInfo(name = "volumeHundredths")
    val volumeHundredths: Int,

    /** Null for market orders. */
    @ColumnInfo(name = "requestedPriceUnits")
    val requestedPriceUnits: Int?,

    @ColumnInfo(name = "stopLossPips")
    val stopLossPips: Int?,

    @ColumnInfo(name = "takeProfitPips")
    val takeProfitPips: Int?,

    /** PENDING, FILLED, CANCELLED or REJECTED. */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "resolvedAtSimUtcMs")
    val resolvedAtSimUtcMs: Long?,

    @ColumnInfo(name = "rejectionReason")
    val rejectionReason: String?,
)

/** One fill. */
@Entity(
    tableName = "Execution",
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TradeOrderEntity::class,
            parentColumns = ["orderId"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["seasonId", "executedAtSimUtcMs"]),
        Index(value = ["orderId"]),
    ],
)
data class ExecutionEntity(
    @PrimaryKey
    @ColumnInfo(name = "executionId")
    val executionId: String,

    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    @ColumnInfo(name = "orderId")
    val orderId: String,

    @ColumnInfo(name = "executedAtSimUtcMs")
    val executedAtSimUtcMs: Long,

    @ColumnInfo(name = "priceUnits")
    val priceUnits: Int,

    @ColumnInfo(name = "volumeHundredths")
    val volumeHundredths: Int,

    @ColumnInfo(name = "commissionMicros")
    val commissionMicros: Long,

    /** Signed difference from the requested price, in price units. */
    @ColumnInfo(name = "slippageUnits")
    val slippageUnits: Int,
)

/** An open or closed position. */
@Entity(
    tableName = "Position",
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["seasonId", "status"])],
)
data class PositionEntity(
    @PrimaryKey
    @ColumnInfo(name = "positionId")
    val positionId: String,

    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    @ColumnInfo(name = "openedAtSimUtcMs")
    val openedAtSimUtcMs: Long,

    @ColumnInfo(name = "side")
    val side: String,

    @ColumnInfo(name = "volumeHundredths")
    val volumeHundredths: Int,

    @ColumnInfo(name = "openPriceUnits")
    val openPriceUnits: Int,

    @ColumnInfo(name = "stopLossPips")
    val stopLossPips: Int?,

    @ColumnInfo(name = "takeProfitPips")
    val takeProfitPips: Int?,

    /** OPEN or CLOSED. */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "closedAtSimUtcMs")
    val closedAtSimUtcMs: Long?,

    @ColumnInfo(name = "closePriceUnits")
    val closePriceUnits: Int?,
)

/** A completed round trip, retained for history and scoring. */
@Entity(
    tableName = "ClosedTrade",
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["seasonId", "closeTimeSimUtcMs"])],
)
data class ClosedTradeEntity(
    @PrimaryKey
    @ColumnInfo(name = "tradeId")
    val tradeId: String,

    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    @ColumnInfo(name = "positionId")
    val positionId: String,

    @ColumnInfo(name = "side")
    val side: String,

    @ColumnInfo(name = "volumeHundredths")
    val volumeHundredths: Int,

    @ColumnInfo(name = "openPriceUnits")
    val openPriceUnits: Int,

    @ColumnInfo(name = "closePriceUnits")
    val closePriceUnits: Int,

    @ColumnInfo(name = "openTimeSimUtcMs")
    val openTimeSimUtcMs: Long,

    @ColumnInfo(name = "closeTimeSimUtcMs")
    val closeTimeSimUtcMs: Long,

    @ColumnInfo(name = "grossPnlMicros")
    val grossPnlMicros: Long,

    @ColumnInfo(name = "commissionMicros")
    val commissionMicros: Long,

    @ColumnInfo(name = "swapMicros")
    val swapMicros: Long,

    @ColumnInfo(name = "netPnlMicros")
    val netPnlMicros: Long,

    /** STOP_LOSS, TAKE_PROFIT, MANUAL, STOP_OUT or SEASON_END. */
    @ColumnInfo(name = "closeReason")
    val closeReason: String,

    /** Realised R multiple in millionths, so R stays exact without Double. */
    @ColumnInfo(name = "realizedRMicros")
    val realizedRMicros: Long,
)

/** Per-trading-day aggregates. The trading day rolls at 17:00 New York. */
@Entity(
    tableName = "DailyStats",
    primaryKeys = ["seasonId", "tradingDayStartSimUtcMs"],
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DailyStatsEntity(
    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    @ColumnInfo(name = "tradingDayStartSimUtcMs")
    val tradingDayStartSimUtcMs: Long,

    @ColumnInfo(name = "openingEquityMicros")
    val openingEquityMicros: Long,

    @ColumnInfo(name = "closingEquityMicros")
    val closingEquityMicros: Long,

    @ColumnInfo(name = "highEquityMicros")
    val highEquityMicros: Long,

    @ColumnInfo(name = "lowEquityMicros")
    val lowEquityMicros: Long,

    @ColumnInfo(name = "realizedPnlMicros")
    val realizedPnlMicros: Long,

    @ColumnInfo(name = "tradeCount")
    val tradeCount: Int,

    @ColumnInfo(name = "strikeRecorded")
    val strikeRecorded: Boolean,

    /** Day-start equity x 0.97, frozen at rollover (Step 6.9). */
    @ColumnInfo(name = "dailyFloorMicros")
    val dailyFloorMicros: Long,
)

/** Point-in-time equity, for the equity curve. */
@Entity(
    tableName = "EquitySnapshot",
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["seasonId", "timestampSimUtcMs"])],
)
data class EquitySnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "snapshotId")
    val snapshotId: Long = 0,

    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    @ColumnInfo(name = "timestampSimUtcMs")
    val timestampSimUtcMs: Long,

    @ColumnInfo(name = "equityMicros")
    val equityMicros: Long,

    @ColumnInfo(name = "balanceMicros")
    val balanceMicros: Long,
)

/** A chart drawing, scoped to one season. */
@Entity(
    tableName = "Drawing",
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["seasonId"])],
)
data class DrawingEntity(
    @PrimaryKey
    @ColumnInfo(name = "drawingId")
    val drawingId: String,

    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    @ColumnInfo(name = "toolType")
    val toolType: String,

    /** JSON array of anchor points in price units and epoch ms. */
    @ColumnInfo(name = "pointsJson")
    val pointsJson: String,

    @ColumnInfo(name = "colorArgb")
    val colorArgb: Int,

    @ColumnInfo(name = "strokeWidthPx")
    val strokeWidthPx: Float,

    @ColumnInfo(name = "createdAtSimUtcMs")
    val createdAtSimUtcMs: Long,
)

/** A free-text journal entry, optionally attached to a trade. */
@Entity(
    tableName = "TradeJournal",
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["seasonId", "createdAtSimUtcMs"])],
)
data class TradeJournalEntity(
    @PrimaryKey
    @ColumnInfo(name = "journalId")
    val journalId: String,

    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    /** Null for a standalone note. */
    @ColumnInfo(name = "tradeId")
    val tradeId: String?,

    @ColumnInfo(name = "createdAtSimUtcMs")
    val createdAtSimUtcMs: Long,

    @ColumnInfo(name = "note")
    val note: String,

    @ColumnInfo(name = "tags")
    val tags: String,
)

/** An earned badge, scoped to one season. */
@Entity(
    tableName = "Badge",
    primaryKeys = ["seasonId", "badgeId"],
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BadgeEntity(
    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    @ColumnInfo(name = "badgeId")
    val badgeId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "earnedAtSimUtcMs")
    val earnedAtSimUtcMs: Long,
)

/**
 * The resume point for one season.
 *
 * One row per season: the key is the season id, so a checkpoint is replaced
 * rather than accumulated.
 */
@Entity(
    tableName = "ReplayCheckpoint",
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReplayCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    @ColumnInfo(name = "lastCommittedSimUtcMs")
    val lastCommittedSimUtcMs: Long,

    @ColumnInfo(name = "historicalAnchorSimUtcMs")
    val historicalAnchorSimUtcMs: Long,

    @ColumnInfo(name = "realAnchorUtcMs")
    val realAnchorUtcMs: Long,

    @ColumnInfo(name = "playbackSpeed")
    val playbackSpeed: Int,

    @ColumnInfo(name = "isPaused")
    val isPaused: Boolean,

    @ColumnInfo(name = "updatedAtRealUtcMs")
    val updatedAtRealUtcMs: Long,
)

/**
 * Global key-value settings.
 *
 * Deliberately not season-scoped: settings are app-wide preferences, and
 * scoping them to a season would lose them when a season ends.
 */
@Entity(tableName = "Settings")
data class SettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String,
)

/**
 * The resumable replay state of one season (Steps 7.3-7.4).
 *
 * # Why a separate table from `ReplayCheckpoint`
 *
 * `ReplayCheckpoint` holds the *clock* anchor — where simulated time is — and is
 * written by `RoomClockStore`. This holds the *engine's* state — balance, orders,
 * positions, executions, closed trades and the daily-loss bookkeeping — and is
 * written by the replay processor. They are written at different moments by
 * different components, and merging them would force the clock to rewrite the
 * whole trading state on every pause.
 *
 * What Step 7.4 does require is that a *replay batch's* trading changes and its
 * processed-time commit together, and those both live in this row: the state blob
 * already contains `processedToSimUtcMs`. So one row is one atomic unit, and
 * `RoomReplayStore` writes it inside a transaction.
 *
 * # Why the state is a blob rather than columns
 *
 * The engine's state is a deep, heterogeneous graph — orders with optional
 * trailing-stop and breakeven sub-objects, positions with a dozen fields each,
 * four separate collections. Normalising it would be dozens of columns and
 * several tables, all of which would need a migration whenever a domain field
 * changes, and all of which would have to be written atomically anyway. A single
 * versioned blob keeps the schema stable and puts the format under
 * `ReplayStateCodec`, where the version check lives.
 *
 * The state is bounded by construction: the engine caps the per-segment fill
 * loops, and a season's executions and closed trades are what a user actually
 * traded. It is not an unbounded log.
 */
@Entity(
    tableName = "ReplayState",
    foreignKeys = [
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReplayStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "seasonId")
    val seasonId: String,

    /** The encoded [com.tradequest.app.core.trading.TradingState]. See `ReplayStateCodec`. */
    @ColumnInfo(name = "stateJson")
    val stateJson: String,

    /**
     * The simulated time this state has been processed up to.
     *
     * Duplicated out of the blob deliberately. It is the field a resume reads to
     * decide how much work is owed, so having it as a column makes that check a
     * query rather than a deserialisation, and lets a later phase index it.
     */
    @ColumnInfo(name = "processedToSimUtcMs")
    val processedToSimUtcMs: Long,

    /** The codec version the blob was written with. */
    @ColumnInfo(name = "schemaVersion")
    val schemaVersion: Int,

    @ColumnInfo(name = "updatedAtRealUtcMs")
    val updatedAtRealUtcMs: Long,
)
