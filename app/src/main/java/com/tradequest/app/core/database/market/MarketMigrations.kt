package com.tradequest.app.core.database.market

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for `market.db`.
 *
 * # Why these are additive
 *
 * `market.db` holds imported historical datasets. Re-importing one costs a
 * network fetch and several minutes of parsing, and Phase 15 is the only import
 * entry point, so a destructive fallback would leave the user with an empty
 * Market Library until they re-imported. Every migration here therefore only
 * adds columns or tables; none rewrites an existing row.
 *
 * # Why the DDL is written by hand
 *
 * Room validates the schema against the exported files under `app/schemas` when
 * it opens the database, so a migration whose DDL drifts from the entity fails
 * loudly on the next open rather than silently at some later read. The exported
 * schema is the record of what was verified; `MarketMigrationTest` runs the real
 * migration against real SQLite and validates the result.
 */
object MarketMigrations {

    /**
     * 1 -> 2: turns the Phase 2 news feed into an economic calendar (Step 12.1).
     *
     * Six columns are added to `NewsEvent`. All six are nullable or carry a
     * default, so existing rows — which have none of these fields — keep their
     * values and gain the defaults. A pre-Phase-12 news row becomes an event with
     * category `OTHER`, no forecast, no previous and no actual, which is exactly
     * what it was: a headline with no release data attached.
     *
     * `body` is deliberately left in place. Dropping a `NOT NULL` column in
     * SQLite means rebuilding the table, and the app still has no use for a
     * rebuild here.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `NewsEvent` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'OTHER'")
            db.execSQL("ALTER TABLE `NewsEvent` ADD COLUMN `forecast` TEXT")
            db.execSQL("ALTER TABLE `NewsEvent` ADD COLUMN `previous` TEXT")
            db.execSQL("ALTER TABLE `NewsEvent` ADD COLUMN `actual` TEXT")
            db.execSQL("ALTER TABLE `NewsEvent` ADD COLUMN `reactionNote` TEXT")
            db.execSQL(
                "ALTER TABLE `NewsEvent` ADD COLUMN `reactionWindowMinutes` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    /** Every migration, in order, for [MarketDatabase] to install. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
