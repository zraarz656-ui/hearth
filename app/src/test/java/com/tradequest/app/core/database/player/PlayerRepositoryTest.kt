package com.tradequest.app.core.database.player

import androidx.test.core.app.ApplicationProvider
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
 * Step 2.5 dataset isolation across the two databases.
 *
 * `Season.datasetId` is a logical reference, not a foreign key, because
 * `market.db` and `tradequest.db` are separate files. These tests pin the
 * behaviour that the missing foreign key would otherwise have provided.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerRepositoryTest {

    private lateinit var playerDb: PlayerDatabase
    private lateinit var marketDb: MarketDatabase
    private lateinit var repo: PlayerRepository

    private val t0 = 1_705_276_800_000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        playerDb = PlayerDatabase.create(ctx)
        marketDb = MarketDatabase.create(ctx)
        repo = PlayerRepository(playerDb, RoomMarketRepository(marketDb))
    }

    @After
    fun tearDown() {
        playerDb.close()
        marketDb.close()
    }

    private suspend fun insertDataset(id: String) {
        marketDb.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = id, displayName = id, instrument = "XAUUSD",
                startTimestampUtcMs = t0, endTimestampUtcMs = t0 + 60_000L,
                candleCount = 0, contentHash = "h_$id", validationStatus = "VALID",
                sourceDescription = "t", importedAtUtcMs = t0,
            ),
        )
    }

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
        assertEquals("ACTIVE", season.status)
    }

    @Test
    fun `a second active season is refused`() = runTest {
        insertDataset("ds_a")
        insertDataset("ds_b")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)

        val second = repo.startSeason("s2", "ds_b", SeasonMode.CHALLENGE, t0, t0)

        assertTrue(second is SeasonStartResult.AlreadyActive)
        assertEquals("s1", (second as SeasonStartResult.AlreadyActive).existingSeasonId)
        assertEquals(1, playerDb.playerDao().countSeasons())
    }

    @Test
    fun `an active season is refused even for a different dataset`() = runTest {
        insertDataset("ds_a")
        insertDataset("ds_b")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)

        // Step 2.1: no transferring a challenge between datasets.
        val second = repo.startSeason("s2", "ds_b", SeasonMode.PRACTICE, t0, t0)
        assertTrue(second is SeasonStartResult.AlreadyActive)
    }

    @Test
    fun `an active season cannot be deleted`() = runTest {
        insertDataset("ds_a")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)

        assertEquals(SeasonDeleteResult.RefusedActiveSeason, repo.deleteSeason("s1"))
        assertNotNull(playerDb.playerDao().getSeason("s1"))
    }

    @Test
    fun `a finished season can be deleted`() = runTest {
        insertDataset("ds_a")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        playerDb.playerDao().updateSeasonStatus("s1", "FAILED", "daily loss", t0 + 1000)

        assertEquals(SeasonDeleteResult.Deleted, repo.deleteSeason("s1"))
        assertNull(playerDb.playerDao().getSeason("s1"))
    }

    @Test
    fun `deleting an unknown season reports not found`() = runTest {
        assertEquals(SeasonDeleteResult.NotFound, repo.deleteSeason("nope"))
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
}
