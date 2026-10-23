package com.tradequest.app.core.database.player

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 7.4: the `ReplayState` migration preserves an existing season.
 *
 * # Why this test matters more than it looks
 *
 * A player's season — balance, trade history, challenge progress — is the whole
 * point of the app. If migration 1->2 were wrong, an update would either fail to
 * open the database or, worse, silently drop the season. `MigrationTestHelper`
 * runs the real migration against real SQLite and then validates the resulting
 * schema against the exported `app/schemas` files, so a hand-written DDL that
 * drifts from the entity is caught here rather than on a user's device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerMigrationTest {

    private val t0 = 1_705_276_800_000L

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlayerDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrating 1 to 2 adds the ReplayState table without touching the season`() {
        // A populated v1 database, exactly as a pre-Phase-7 install would have.
        helper.createDatabase(PlayerDatabase.NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO Season (
                    seasonId, datasetId, mode, status, startingBalanceMicros,
                    balanceMicros, equityMicros, peakEquityMicros, startedAtSimUtcMs,
                    createdAtRealUtcMs, completedAtSimUtcMs, tradingDayCount,
                    dailyStrikeCount, scorePoints, outcomeReason
                ) VALUES (
                    's1', 'ds_a', 'CHALLENGE', 'ACTIVE', 10000000000,
                    9876543210, 9900000000, 10100000000, $t0,
                    $t0, NULL, 3, 1, 250, NULL
                )
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_1_2 and validates the schema against app/schemas.
        val migrated = helper.runMigrationsAndValidate(
            PlayerDatabase.NAME,
            2,
            true,
            *PlayerMigrations.ALL,
        )

        // The season survived with every field intact.
        migrated.query("SELECT * FROM Season WHERE seasonId = 's1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            val balance = cursor.getLong(cursor.getColumnIndexOrThrow("balanceMicros"))
            assertEquals(9_876_543_210L, balance)
            val dayCount = cursor.getInt(cursor.getColumnIndexOrThrow("tradingDayCount"))
            assertEquals(3, dayCount)
        }

        // The new table exists and is empty: a pre-Phase-7 season has no replay
        // state, which the processor treats as "nothing processed yet".
        migrated.query("SELECT COUNT(*) FROM ReplayState").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun `the migrated ReplayState table accepts a row and cascades on season delete`() {
        val migrated = helper.createDatabase(PlayerDatabase.NAME, 2)
        migrated.execSQL(
            """
            INSERT INTO Season (
                seasonId, datasetId, mode, status, startingBalanceMicros,
                balanceMicros, equityMicros, peakEquityMicros, startedAtSimUtcMs,
                createdAtRealUtcMs, completedAtSimUtcMs, tradingDayCount,
                dailyStrikeCount, scorePoints, outcomeReason
            ) VALUES (
                's1', 'ds_a', 'CHALLENGE', 'ACTIVE', 10000000000,
                10000000000, 10000000000, 10000000000, $t0,
                $t0, NULL, 0, 0, 0, NULL
            )
            """.trimIndent(),
        )
        migrated.execSQL(
            """
            INSERT INTO ReplayState (
                seasonId, stateJson, processedToSimUtcMs, schemaVersion, updatedAtRealUtcMs
            ) VALUES ('s1', '{}', $t0, 1, $t0)
            """.trimIndent(),
        )

        migrated.query("SELECT COUNT(*) FROM ReplayState").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        // SQLite disables foreign-key enforcement by default, and Room turns it
        // on for its own connections. This raw helper connection has not, so the
        // pragma is set explicitly: the test is asserting the cascade, not
        // SQLite's default.
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.query("PRAGMA foreign_keys").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        // The foreign key must be declared ON DELETE CASCADE, or deleting a
        // season would leave an orphan replay state behind.
        migrated.execSQL("DELETE FROM Season WHERE seasonId = 's1'")
        migrated.query("SELECT COUNT(*) FROM ReplayState").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun `a season has no replay state until one is written`() = runTest {
        val db = PlayerDatabase.create(
            ApplicationProvider.getApplicationContext(),
            "replay_state_test.db",
        )
        try {
            val dao = db.playerDao()
            dao.insertSeason(
                SeasonEntity(
                    seasonId = "s1",
                    datasetId = "ds_a",
                    mode = "CHALLENGE",
                    status = "ACTIVE",
                    startingBalanceMicros = 10_000_000_000L,
                    balanceMicros = 10_000_000_000L,
                    equityMicros = 10_000_000_000L,
                    peakEquityMicros = 10_000_000_000L,
                    startedAtSimUtcMs = t0,
                    createdAtRealUtcMs = t0,
                    completedAtSimUtcMs = null,
                    tradingDayCount = 0,
                    dailyStrikeCount = 0,
                    scorePoints = 0,
                    outcomeReason = null,
                ),
            )
            assertNull(dao.replayState("s1"))

            dao.upsertReplayState(
                ReplayStateEntity(
                    seasonId = "s1",
                    stateJson = "{}",
                    processedToSimUtcMs = t0,
                    schemaVersion = 1,
                    updatedAtRealUtcMs = t0,
                ),
            )
            val row = dao.replayState("s1")
            assertNotNull(row)
            assertEquals(t0, row!!.processedToSimUtcMs)

            // A second write replaces rather than accumulates.
            dao.upsertReplayState(row.copy(processedToSimUtcMs = t0 + 60_000L))
            assertEquals(t0 + 60_000L, dao.replayState("s1")!!.processedToSimUtcMs)
        } finally {
            db.close()
        }
    }

    // ---------- Step 11.9-11.11: the ChallengeRecord migration ----------

    @Test
    fun `migrating 2 to 3 adds the ChallengeRecord table without touching the season`() {
        // A populated v2 database, exactly as a pre-Phase-11 install would have.
        helper.createDatabase(PlayerDatabase.NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO Season (
                    seasonId, datasetId, mode, status, startingBalanceMicros,
                    balanceMicros, equityMicros, peakEquityMicros, startedAtSimUtcMs,
                    createdAtRealUtcMs, completedAtSimUtcMs, tradingDayCount,
                    dailyStrikeCount, scorePoints, outcomeReason
                ) VALUES (
                    's1', 'ds_a', 'CHALLENGE', 'PASSED', 10000000000,
                    11500000000, 11500000000, 11500000000, $t0,
                    $t0, NULL, 20, 0, 90, 'TARGET_REACHED'
                )
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_2_3 and validates the schema against app/schemas.
        val migrated = helper.runMigrationsAndValidate(
            PlayerDatabase.NAME,
            3,
            true,
            *PlayerMigrations.ALL,
        )

        // The player's season survived with every field intact: this is the data a
        // wrong migration would have cost.
        migrated.query("SELECT * FROM Season WHERE seasonId = 's1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(
                11_500_000_000L,
                cursor.getLong(cursor.getColumnIndexOrThrow("equityMicros")),
            )
            assertEquals(20, cursor.getInt(cursor.getColumnIndexOrThrow("tradingDayCount")))
            assertEquals(
                "TARGET_REACHED",
                cursor.getString(cursor.getColumnIndexOrThrow("outcomeReason")),
            )
        }

        // The new table exists and is empty: a pre-Phase-11 install has no history
        // records, and its next completed season becomes the first.
        migrated.query("SELECT COUNT(*) FROM ChallengeRecord").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun `the migrated ChallengeRecord table accepts a row and keys it by season`() {
        val migrated = helper.createDatabase(PlayerDatabase.NAME, 3)
        migrated.execSQL(
            """
            INSERT INTO ChallengeRecord (
                recordId, seasonId, datasetId, datasetDisplayName, mode, status,
                outcome, startingBalanceMicros, finalBalanceMicros, finalEquityMicros,
                peakEquityMicros, tradingDayCount, dailyStrikeCount, scorePoints,
                profitPoints, riskControlPoints, consistencyPoints, disciplinePoints,
                title, attemptOrdinal, closedTradeCount, startedAtSimUtcMs,
                completedAtSimUtcMs, completedAtRealUtcMs
            ) VALUES (
                'record-s1', 's1', 'ds_a', 'Gold A', 'CHALLENGE', 'PASSED',
                'TARGET_REACHED', 10000000000, 11500000000, 11500000000,
                11500000000, 20, 0, 90,
                40, 30, 20, 10,
                'GOLD_MASTER', 1, 20, $t0,
                $t0, $t0
            )
            """.trimIndent(),
        )

        migrated.query("SELECT COUNT(*) FROM ChallengeRecord").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        // `seasonId` is uniquely indexed: the idempotent write the repository relies
        // on is only idempotent if the database enforces it.
        val duplicate = runCatching {
            migrated.execSQL(
                """
                INSERT INTO ChallengeRecord (
                    recordId, seasonId, datasetId, datasetDisplayName, mode, status,
                    outcome, startingBalanceMicros, finalBalanceMicros, finalEquityMicros,
                    peakEquityMicros, tradingDayCount, dailyStrikeCount, scorePoints,
                    profitPoints, riskControlPoints, consistencyPoints, disciplinePoints,
                    title, attemptOrdinal, closedTradeCount, startedAtSimUtcMs,
                    completedAtSimUtcMs, completedAtRealUtcMs
                ) VALUES (
                    'record-s1-again', 's1', 'ds_a', 'Gold A', 'CHALLENGE', 'PASSED',
                    'TARGET_REACHED', 10000000000, 11500000000, 11500000000,
                    11500000000, 20, 0, 90,
                    40, 30, 20, 10,
                    'GOLD_MASTER', 1, 20, $t0,
                    $t0, $t0
                )
                """.trimIndent(),
            )
        }
        assertTrue("a second record for the same season must be refused", duplicate.isFailure)
        migrated.close()
    }
}
