package com.tradequest.app.core.database.player

import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.challenge.ChallengeHistoryRepository
import com.tradequest.app.core.challenge.ChallengeOutcome
import com.tradequest.app.core.challenge.SeasonMode
import com.tradequest.app.core.challenge.SeasonStatus
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.MarketDatasetEntity
import com.tradequest.app.core.database.market.RoomMarketRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Steps 2.5, 11.1-11.3: dataset isolation and the season lifecycle.
 *
 * `Season.datasetId` is a logical reference, not a foreign key, because
 * `market.db` and `tradequest.db` are separate files. These tests pin the
 * behaviour that the missing foreign key would otherwise have provided, plus the
 * dataset-switch rules Phase 11 adds on top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerRepositoryTest {

    private lateinit var playerDb: PlayerDatabase
    private lateinit var marketDb: MarketDatabase
    private lateinit var repo: PlayerRepository
    private lateinit var history: ChallengeHistoryRepository

    private val t0 = 1_705_276_800_000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        playerDb = PlayerDatabase.create(ctx)
        marketDb = MarketDatabase.create(ctx)
        repo = PlayerRepository(playerDb, RoomMarketRepository(marketDb))
        history = RoomChallengeHistoryRepository(playerDb)
    }

    @After
    fun tearDown() {
        playerDb.close()
        marketDb.close()
    }

    private suspend fun insertDataset(id: String, name: String = id) {
        marketDb.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = id, displayName = name, instrument = "XAUUSD",
                startTimestampUtcMs = t0, endTimestampUtcMs = t0 + 60_000L,
                candleCount = 0, contentHash = "h_$id", validationStatus = "VALID",
                sourceDescription = "t", importedAtUtcMs = t0,
            ),
        )
    }

    // ---------- Starting state (Step 11.1) ----------

    @Test
    fun `a season cannot be created on a dataset that does not exist`() = runTest {
        val result = repo.startSeason("s1", "ds_missing", SeasonMode.CHALLENGE, t0, t0)

        assertTrue(result is SeasonStartResult.DatasetNotFound)
        assertEquals(0, playerDb.playerDao().countSeasons())
    }

    @Test
    fun `a season starts on an existing dataset with the standard balance`() = runTest {
        insertDataset("ds_a")

        val result = repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)

        assertTrue(result is SeasonStartResult.Started)
        val season = playerDb.playerDao().getSeason("s1")!!
        assertEquals("ds_a", season.datasetId)
        assertEquals(10_000_000_000L, season.balanceMicros)
        assertEquals(10_000_000_000L, season.equityMicros)
        assertEquals(0, season.tradingDayCount)
        assertEquals(0, season.dailyStrikeCount)
        assertEquals(0, season.scorePoints)
        assertEquals("ACTIVE", season.status)
    }

    @Test
    fun `every season starts fresh with no carried-over progress`() = runTest {
        insertDataset("ds_a")
        insertDataset("ds_b")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        // Drive the first season into a state that must not leak into the next.
        playerDb.playerDao().updateSeasonProgress(
            seasonId = "s1", balanceMicros = 11_500_000_000L, equityMicros = 11_500_000_000L,
            peakEquityMicros = 11_500_000_000L, tradingDayCount = 20, dailyStrikeCount = 1,
            scorePoints = 90,
        )

        repo.startSeason("s2", "ds_b", SeasonMode.CHALLENGE, t0, t0, confirmAbandon = true)

        val fresh = playerDb.playerDao().getSeason("s2")!!
        assertEquals(10_000_000_000L, fresh.balanceMicros)
        assertEquals(10_000_000_000L, fresh.equityMicros)
        assertEquals(10_000_000_000L, fresh.peakEquityMicros)
        assertEquals(0, fresh.tradingDayCount)
        assertEquals(0, fresh.dailyStrikeCount)
        assertEquals(0, fresh.scorePoints)
    }

    // ---------- Single active challenge (Step 11.2) ----------

    @Test
    fun `starting over a running challenge requires confirmation`() = runTest {
        insertDataset("ds_a")
        insertDataset("ds_b")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)

        val second = repo.startSeason("s2", "ds_b", SeasonMode.CHALLENGE, t0, t0)

        assertTrue(second is SeasonStartResult.ConfirmationRequired)
        assertEquals("s1", (second as SeasonStartResult.ConfirmationRequired).existingSeasonId)
        assertEquals(1, playerDb.playerDao().countSeasons())
        // Nothing was abandoned: the refusal must not have side effects.
        assertEquals("ACTIVE", playerDb.playerDao().getSeason("s1")!!.status)
    }

    @Test
    fun `at most one season is active at a time`() = runTest {
        insertDataset("ds_a")
        insertDataset("ds_b")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)

        repo.startSeason("s2", "ds_b", SeasonMode.CHALLENGE, t0, t0, confirmAbandon = true)

        val seasons = listOfNotNull(
            playerDb.playerDao().getSeason("s1"),
            playerDb.playerDao().getSeason("s2"),
        )
        assertEquals(1, seasons.count { it.status == "ACTIVE" })
        assertEquals(2, playerDb.playerDao().countSeasons())
    }

    // ---------- Dataset switching (Step 11.3) ----------

    @Test
    fun `switching datasets abandons the previous challenge and keeps its history`() = runTest {
        insertDataset("ds_a", "Gold 2024")
        insertDataset("ds_b", "Gold 2025")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        playerDb.playerDao().updateSeasonProgress(
            seasonId = "s1", balanceMicros = 9_800_000_000L, equityMicros = 9_800_000_000L,
            peakEquityMicros = 10_100_000_000L, tradingDayCount = 4, dailyStrikeCount = 1,
            scorePoints = 0,
        )

        val result = repo.startSeason("s2", "ds_b", SeasonMode.CHALLENGE, t0, t0 + 5_000L, confirmAbandon = true)

        assertTrue(result is SeasonStartResult.Started)
        val abandoned = playerDb.playerDao().getSeason("s1")!!
        assertEquals("ABANDONED", abandoned.status)
        assertEquals(ChallengeOutcome.SUPERSEDED.name, abandoned.outcomeReason)
        assertNotNull(abandoned.completedAtSimUtcMs)

        // The history survived the switch and describes the abandoned season.
        val record = history.recordForSeason("s1")
        assertNotNull(record)
        assertEquals("Gold 2024", record!!.datasetDisplayName)
        assertEquals(SeasonStatus.ABANDONED, record.status)
        assertEquals(ChallengeOutcome.SUPERSEDED, record.outcome)
        assertEquals(4, record.tradingDayCount)
        assertEquals(9_800_000_000L, record.finalEquityMicros)
        assertEquals(1, record.attemptOrdinal)
    }

    @Test
    fun `a dataset switch starts the new season at the fresh balance`() = runTest {
        insertDataset("ds_a")
        insertDataset("ds_b")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        repo.startSeason("s2", "ds_b", SeasonMode.CHALLENGE, t0, t0, confirmAbandon = true)

        val next = playerDb.playerDao().getSeason("s2")!!
        assertEquals("ds_b", next.datasetId)
        assertEquals(10_000_000_000L, next.balanceMicros)
        assertEquals("ACTIVE", next.status)
    }

    @Test
    fun `each season references exactly one dataset`() = runTest {
        insertDataset("ds_a")
        insertDataset("ds_b")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        playerDb.playerDao().updateSeasonStatus("s1", "PASSED", null, t0 + 1)
        repo.startSeason("s2", "ds_b", SeasonMode.CHALLENGE, t0, t0)

        assertEquals("ds_a", playerDb.playerDao().getSeason("s1")!!.datasetId)
        assertEquals("ds_b", playerDb.playerDao().getSeason("s2")!!.datasetId)
    }

    // ---------- Deletion ----------

    @Test
    fun `an active season cannot be deleted`() = runTest {
        insertDataset("ds_a")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)

        assertEquals(SeasonDeleteResult.RefusedActiveSeason, repo.deleteSeason("s1"))
        assertNotNull(playerDb.playerDao().getSeason("s1"))
    }

    @Test
    fun `a finished season can be deleted but its history record remains`() = runTest {
        insertDataset("ds_a")
        insertDataset("ds_b")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        repo.startSeason("s2", "ds_b", SeasonMode.CHALLENGE, t0, t0, confirmAbandon = true)

        assertEquals(SeasonDeleteResult.Deleted, repo.deleteSeason("s1"))
        assertNull(playerDb.playerDao().getSeason("s1"))
        // Step 3.4: previous attempts remain saved. Deleting the season forgets its
        // trades, not the fact that the attempt happened.
        assertNotNull(history.recordForSeason("s1"))
    }

    @Test
    fun `deleting an unknown season reports not found`() = runTest {
        assertEquals(SeasonDeleteResult.NotFound, repo.deleteSeason("nope"))
    }
}
