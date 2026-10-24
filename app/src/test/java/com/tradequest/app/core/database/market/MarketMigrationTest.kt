package com.tradequest.app.core.database.market

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 12.1: the economic-calendar migration for `market.db`.
 *
 * # Why this test matters
 *
 * `market.db` holds imported historical datasets, and re-importing one costs a
 * network fetch and several minutes of parsing. If migration 1->2 were wrong, an
 * update would either fail to open the database or silently discard the user's
 * library. `MigrationTestHelper` runs the real migration against real SQLite and
 * validates the result against the exported `app/schemas` files, so a
 * hand-written DDL that drifts from [NewsEventEntity] is caught here rather than
 * on a device.
 *
 * # Why the file name is not [MarketDatabase.NAME]
 *
 * Unit tests run against the debug variant, whose application class
 * (`DebugTradeQuestApplication`) seeds a dataset by opening `market.db` on a
 * background coroutine during `onCreate`. That coroutine creates the real
 * `market.db` at the *current* version (2) while this test is running, and a
 * `createDatabase("market.db", 1)` that loses the race is refused as a
 * downgrade from 2 to 1. The race is invisible on a fast machine and fatal on a
 * slow one. A dedicated file name removes the shared resource rather than
 * papering over the timing, and schema validation is keyed by the database
 * class, not the file name, so it still runs.
 *
 * `MarketMigrations`' own KDoc names this test as the record of what was
 * verified; before it existed, that claim was not true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarketMigrationTest {

    private val t0 = 1_705_276_800_000L // Mon 2024-01-15 00:00:00 UTC

    /** A file name no other test or the debug seeder touches. */
    private val dbName = "market-migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MarketDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrating 1 to 2 adds the calendar columns and defaults an old headline`() {
        // A populated v1 database, exactly as a pre-Phase-12 install would have:
        // a news row with a headline and no release data at all.
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO NewsEvent (
                    newsId, datasetId, publishedAtUtcMs, headline, body, impact
                ) VALUES (
                    1, 'ds_a', $t0, 'NFP beats expectations', 'Full text', 'HIGH'
                )
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_1_2 and validates the schema against app/schemas.
        val migrated = helper.runMigrationsAndValidate(
            dbName,
            2,
            true,
            *MarketMigrations.ALL,
        )

        // The row survived, and the new columns carry their defaults: an old
        // headline becomes an event with category OTHER and no release data,
        // which is what it was.
        migrated.query("SELECT * FROM NewsEvent WHERE newsId = 1").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(
                "NFP beats expectations",
                cursor.getString(cursor.getColumnIndexOrThrow("headline")),
            )
            assertEquals("OTHER", cursor.getString(cursor.getColumnIndexOrThrow("category")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("reactionWindowMinutes")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("forecast")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("previous")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("actual")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("reactionNote")))
        }
        migrated.close()
    }
}
