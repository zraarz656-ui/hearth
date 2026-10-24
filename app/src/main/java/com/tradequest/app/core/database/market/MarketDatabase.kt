package com.tradequest.app.core.database.market

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * `market.db` — read-heavy historical market data (Step 2.1).
 *
 * Separate from `tradequest.db` so that wiping player progress cannot touch
 * history, and so a large read-only dataset is not in the same file as
 * frequently rewritten trading state. `docs/architecture.md` rule 6 forbids
 * cross-database joins.
 *
 * Foreign keys are enabled explicitly. Room does not enable SQLite's
 * `foreign_keys` pragma by default on all versions, and without it the
 * `RESTRICT` rules on the child tables are inert, which would silently allow
 * orphaning candles.
 */
@Database(
    entities = [
        MarketDatasetEntity::class,
        Candle1mEntity::class,
        NewsEventEntity::class,
        MarketClosureEntity::class,
        DatasetValidationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class MarketDatabase : RoomDatabase() {

    abstract fun marketDao(): MarketDao

    companion object {
        const val NAME = "market.db"

        fun create(context: Context, name: String = NAME): MarketDatabase =
            Room.databaseBuilder(context.applicationContext, MarketDatabase::class.java, name)
                .addMigrations(*MarketMigrations.ALL)
                .addCallback(withoutRowidCallback())
                .build()

        /**
         * Stores `Candle1m` WITHOUT ROWID, which is where the bulk of the data
         * lives. Failure is non-fatal: the table stays a normal rowid table and
         * the app is unaffected apart from using more space.
         */
        private fun withoutRowidCallback() = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                runCatching { WithoutRowid.apply(db, "Candle1m") }
            }
        }
    }
}
