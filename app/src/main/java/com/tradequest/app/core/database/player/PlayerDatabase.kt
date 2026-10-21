package com.tradequest.app.core.database.player

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * `tradequest.db` — mutable player state (Step 2.2).
 *
 * Separate from `market.db` so player progress and historical data can be
 * wiped independently. `docs/architecture.md` rule 6 forbids cross-database
 * joins, which is why `Season.datasetId` is a logical reference rather than a
 * foreign key.
 */
@Database(
    entities = [
        SeasonEntity::class,
        TradeOrderEntity::class,
        ExecutionEntity::class,
        PositionEntity::class,
        ClosedTradeEntity::class,
        DailyStatsEntity::class,
        EquitySnapshotEntity::class,
        DrawingEntity::class,
        TradeJournalEntity::class,
        BadgeEntity::class,
        ReplayCheckpointEntity::class,
        ReplayStateEntity::class,
        SettingEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PlayerDatabase : RoomDatabase() {

    abstract fun playerDao(): PlayerDao

    companion object {
        const val NAME = "tradequest.db"

        fun create(context: Context, name: String = NAME): PlayerDatabase =
            Room.databaseBuilder(context.applicationContext, PlayerDatabase::class.java, name)
                .addMigrations(*PlayerMigrations.ALL)
                .build()
    }
}
