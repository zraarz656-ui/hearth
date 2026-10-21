package com.tradequest.app.core.database.market

import androidx.test.core.app.ApplicationProvider
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
 * Smoke test proving the real Room stack runs on the JVM.
 *
 * This exists to establish that database tests need no emulator and no mock.
 * The real generated Room code and the real SQLite engine are exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarketDatabaseSmokeTest {

    private lateinit var db: MarketDatabase

    @Before
    fun setUp() {
        db = MarketDatabase.create(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `database opens and reports zero datasets`() = runTest {
        assertEquals(0, db.marketDao().countDatasets())
    }

    @Test
    fun `inserts and reads back a dataset`() = runTest {
        val dao = db.marketDao()
        val inserted = dao.insertDataset(
            MarketDatasetEntity(
                datasetId = "ds_smoke",
                displayName = "Smoke",
                instrument = "XAUUSD",
                startTimestampUtcMs = 1_705_276_800_000L,
                endTimestampUtcMs = 1_705_276_860_000L,
                candleCount = 2,
                contentHash = "hash",
                validationStatus = "VALID",
                sourceDescription = "test",
                importedAtUtcMs = 0L,
            ),
        )
        assertTrue(inserted > 0)

        val read = dao.getDataset("ds_smoke")
        assertNotNull(read)
        assertEquals("XAUUSD", read!!.instrument)
    }

    @Test
    fun `duplicate dataset id is ignored not overwritten`() = runTest {
        val dao = db.marketDao()
        fun entity(name: String) = MarketDatasetEntity(
            datasetId = "ds_dup",
            displayName = name,
            instrument = "XAUUSD",
            startTimestampUtcMs = 1L,
            endTimestampUtcMs = 2L,
            candleCount = 1,
            contentHash = "hash",
            validationStatus = "VALID",
            sourceDescription = "test",
            importedAtUtcMs = 0L,
        )

        assertTrue(dao.insertDataset(entity("first")) > 0)
        assertEquals(-1L, dao.insertDataset(entity("second")))

        assertEquals("first", dao.getDataset("ds_dup")!!.displayName)
        assertEquals(1, dao.countDatasets())
    }

    @Test
    fun `candle round trips and is scoped to its dataset`() = runTest {
        val dao = db.marketDao()
        dao.insertDataset(
            MarketDatasetEntity(
                datasetId = "ds_a",
                displayName = "A",
                instrument = "XAUUSD",
                startTimestampUtcMs = 0L,
                endTimestampUtcMs = 0L,
                candleCount = 0,
                contentHash = "h",
                validationStatus = "VALID",
                sourceDescription = "t",
                importedAtUtcMs = 0L,
            ),
        )

        dao.insertCandles(
            listOf(
                Candle1mEntity("ds_a", 1_705_276_800_000L, 2_047_204, 2_047_525, 2_047_101, 2_047_499, 42),
            ),
        )

        val read = dao.candleAt("ds_a", 1_705_276_800_000L)
        assertNotNull(read)
        assertEquals(2_047_204, read!!.openUnits)
        assertEquals(42, read.tickVolume)

        assertNull(dao.candleAt("ds_b", 1_705_276_800_000L))
    }

    @Test
    fun `foreign key prevents an orphan candle`() = runTest {
        val dao = db.marketDao()
        val orphan = listOf(
            Candle1mEntity("ds_missing", 1L, 1, 1, 1, 1, 0),
        )

        val threw = runCatching { dao.insertCandles(orphan) }.isFailure
        assertTrue("inserting a candle for a missing dataset must fail", threw)
        assertEquals(0, dao.countCandles("ds_missing"))
    }
}
