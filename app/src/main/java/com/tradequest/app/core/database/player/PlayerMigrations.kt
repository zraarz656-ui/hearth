package com.tradequest.app.core.database.player

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for `tradequest.db`.
 *
 * # Why a migration rather than a destructive fallback
 *
 * A player's season is the whole point of the app: balances, trade history and
 * challenge progress. Dropping and recreating the database on a schema change
 * would erase all of it silently. So every version bump carries a real migration,
 * and a migration that cannot preserve the data is a reason not to bump the
 * version yet.
 *
 * Each migration is additive — it creates what is new and touches nothing that
 * already exists — which is what makes it safe to run against a populated
 * database.
 */
object PlayerMigrations {

    /**
     * 1 -> 2: adds the `ReplayState` table (Steps 7.3-7.4).
     *
     * A brand-new table, so an existing season simply has no row in it. That is
     * the correct state for a season created before this phase: the replay
     * processor treats a missing row as "nothing processed yet" and starts from
     * the dataset's first minute. No existing data is read or rewritten.
     *
     * The DDL must match what Room generates for [ReplayStateEntity] exactly, or
     * Room's schema validation will refuse to open the database. It is kept in
     * step with the entity by hand, and the exported schema under `app/schemas`
     * is the record of what was verified.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ReplayState` (
                    `seasonId` TEXT NOT NULL,
                    `stateJson` TEXT NOT NULL,
                    `processedToSimUtcMs` INTEGER NOT NULL,
                    `schemaVersion` INTEGER NOT NULL,
                    `updatedAtRealUtcMs` INTEGER NOT NULL,
                    PRIMARY KEY(`seasonId`),
                    FOREIGN KEY(`seasonId`) REFERENCES `Season`(`seasonId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

    /** Every migration, in version order. Passed to the database builder. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
