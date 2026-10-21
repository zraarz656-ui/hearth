package com.tradequest.app.core.database.market

import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.market.DatasetClosure
import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.market.PriceScale
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 2.8 market database tests.
 *
 * Runs against the real generated Room code and the real SQLite engine on the
 * JVM through Robolectric. Nothing here is mocked: if Room's generated SQL or a
 * foreign key were wrong, these tests would fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarketDatabaseTest {

    private lateinit var db: MarketDatabase
    private lateinit var dao: MarketDao
    private lateinit var repo: RoomMarketRepository

    private val t0 = 1_705_276_800_000L // Mon 2024-01-15 00:00:00 UTC

    @Before
    fun setUp() {
        db = MarketDatabase.create(ApplicationProvider.getApplicationContext())
        dao = db.marketDao()
        repo = RoomMarketRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- helpers ----------

    private fun dataset(
        id: String = "ds_a",
        start: Long = t0,
        end: Long = t0 + 600_000L,
        candleCount: Int = 0,
        hash: String = "hash_a",
        name: String = "Gold Q1",
    ) = MarketDatasetEntity(
        datasetId = id,
        displayName = name,
        instrument = "XAUUSD",
        startTimestampUtcMs = start,
        endTimestampUtcMs = end,
        candleCount = candleCount,
        contentHash = hash,
        validationStatus = "VALID",
        sourceDescription = "synthetic",
        importedAtUtcMs = t0,
    )

    private fun candle(minute: Long, close: Int = 2_047_000) = HistoricalCandle(
        timestampUtcMs = t0 + minute * 60_000L,
        openUnits = close,
        highUnits = close + 500,
        lowUnits = close - 500,
        closeUnits = close,
        tickVolume = 10,
    )

    // ---------- inserts ----------

    @Test
    fun `inserts a dataset and reads it back`() = runTest {
        dao.insertDataset(dataset(candleCount = 3))
        val read = dao.getDataset("ds_a")
        assertNotNull(read)
        assertEquals("XAUUSD", read!!.instrument)
        assertEquals(3, read.candleCount)
        assertEquals(1, dao.countDatasets())
    }

    @Test
    fun `inserts many candles and counts them`() = runTest {
        dao.insertDataset(dataset())
        val candles = (0 until 500).map { candle(it.toLong()) }
        dao.insertCandles(candles.toEntities("ds_a"))
        assertEquals(500, dao.countCandles("ds_a"))
    }

    // ---------- queries ----------

    @Test
    fun `range query is half open and ordered`() = runTest {
        dao.insertDataset(dataset())
        dao.insertCandles((0 until 10).map { candle(it.toLong()) }.toEntities("ds_a"))

        val rows = repo.candlesInRange("ds_a", t0 + 2 * 60_000L, t0 + 5 * 60_000L)
        assertEquals(listOf(2L, 3L, 4L), rows.map { (it.timestampUtcMs - t0) / 60_000L })
    }

    @Test
    fun `range query excludes the upper bound`() = runTest {
        dao.insertDataset(dataset())
        dao.insertCandles(listOf(candle(0), candle(1)).toEntities("ds_a"))

        val rows = repo.candlesInRange("ds_a", t0, t0 + 60_000L)
        assertEquals(1, rows.size)
        assertEquals(t0, rows.single().timestampUtcMs)
    }

    @Test
    fun `candleAt returns the exact minute and null otherwise`() = runTest {
        dao.insertDataset(dataset())
        dao.insertCandles(listOf(candle(7, close = 2_050_123)).toEntities("ds_a"))

        val hit = repo.candleAt("ds_a", t0 + 7 * 60_000L)
        assertNotNull(hit)
        assertEquals(2_050_123, hit!!.closeUnits)

        assertNull(repo.candleAt("ds_a", t0 + 8 * 60_000L))
    }

    @Test
    fun `timeBounds reports first and last minute`() = runTest {
        dao.insertDataset(dataset())
        dao.insertCandles(listOf(candle(0), candle(5), candle(9)).toEntities("ds_a"))

        val bounds = repo.timeBounds("ds_a")
        assertNotNull(bounds)
        assertEquals(t0, bounds!!.first)
        assertEquals(t0 + 9 * 60_000L, bounds.second)
    }

    @Test
    fun `timeBounds is null for an empty dataset`() = runTest {
        dao.insertDataset(dataset())
        assertNull(repo.timeBounds("ds_a"))
    }

    @Test
    fun `over-range read is rejected rather than truncated`() = runTest {
        dao.insertDataset(dataset())
        dao.insertCandles((0 until 20).map { candle(it.toLong()) }.toEntities("ds_a"))

        val error = runCatching {
            repo.candlesInRange("ds_a", t0, t0 + 3_600_000L, maxRows = 10)
        }.exceptionOrNull()

        assertNotNull("an over-range read must fail loudly", error)
        assertTrue(error!!.message!!.contains("maxRows"))
    }

    @Test
    fun `inverted range is rejected`() = runTest {
        dao.insertDataset(dataset())
        assertNotNull(
            runCatching { repo.candlesInRange("ds_a", t0 + 1000, t0) }.exceptionOrNull(),
        )
    }

    // ---------- duplicate prevention ----------

    @Test
    fun `duplicate candle timestamp in one dataset is rejected`() = runTest {
        dao.insertDataset(dataset())
        dao.insertCandles(listOf(candle(0)).toEntities("ds_a"))

        val error = runCatching {
            dao.insertCandles(listOf(candle(0, close = 2_099_999)).toEntities("ds_a"))
        }.exceptionOrNull()

        assertNotNull("primary key must reject a duplicate minute", error)
        assertEquals(1, dao.countCandles("ds_a"))
        assertEquals(2_047_000, dao.candleAt("ds_a", t0)!!.closeUnits)
    }

    @Test
    fun `same timestamp in two datasets is allowed`() = runTest {
        dao.insertDataset(dataset(id = "ds_a"))
        dao.insertDataset(dataset(id = "ds_b"))
        dao.insertCandles(listOf(candle(0)).toEntities("ds_a"))
        dao.insertCandles(listOf(candle(0)).toEntities("ds_b"))

        assertEquals(1, dao.countCandles("ds_a"))
        assertEquals(1, dao.countCandles("ds_b"))
    }

    @Test
    fun `duplicate dataset id with identical hash is not written twice`() = runTest {
        val manifest = com.tradequest.app.core.market.DatasetManifest(
            datasetId = "ds_a", instrument = "XAUUSD",
            startTimestampUtcMs = t0, endTimestampUtcMs = t0 + 60_000L,
            candleCount = 1, minimumPriceUnits = 1, maximumPriceUnits = 2,
            missingMinuteCount = 0, duplicateCount = 0, validationStatus = "VALID",
            sourceDescription = "s", contentHash = "hash_a",
            weekendClosureDays = 0, holidayClosureDays = 0, unexpectedEmptyDays = 0,
        )
        val request = ImportRequest(
            manifest = manifest,
            candles = listOf(candle(0)),
            displayName = "Gold",
            importedAtUtcMs = t0,
        )

        val first = repo.storeImport(request)
        val second = repo.storeImport(request)

        assertTrue(first is ImportOutcome.Stored)
        assertTrue("second identical import must be a no-op", second is ImportOutcome.AlreadyPresent)
        assertEquals(1, dao.countDatasets())
        assertEquals(1, dao.countCandles("ds_a"))
    }

    @Test
    fun `same dataset id with different content is refused not overwritten`() = runTest {
        fun manifest(hash: String, close: Int) = com.tradequest.app.core.market.DatasetManifest(
            datasetId = "ds_a", instrument = "XAUUSD",
            startTimestampUtcMs = t0, endTimestampUtcMs = t0 + 60_000L,
            candleCount = 1, minimumPriceUnits = 1, maximumPriceUnits = 2,
            missingMinuteCount = 0, duplicateCount = 0, validationStatus = "VALID",
            sourceDescription = "s", contentHash = hash,
            weekendClosureDays = 0, holidayClosureDays = 0, unexpectedEmptyDays = 0,
        )

        repo.storeImport(
            ImportRequest(manifest("hash_1", 2_047_000), listOf(candle(0, 2_047_000)), "G", importedAtUtcMs = t0),
        )
        val clash = repo.storeImport(
            ImportRequest(manifest("hash_2", 2_099_000), listOf(candle(0, 2_099_000)), "G", importedAtUtcMs = t0),
        )

        assertTrue("conflicting content must be refused", clash is ImportOutcome.ContentMismatch)
        // The original content must survive untouched.
        assertEquals("hash_1", dao.getDataset("ds_a")!!.contentHash)
        assertEquals(2_047_000, dao.candleAt("ds_a", t0)!!.closeUnits)
    }

    // ---------- dataset isolation ----------

    @Test
    fun `candle queries never leak across datasets`() = runTest {
        dao.insertDataset(dataset(id = "ds_a"))
        dao.insertDataset(dataset(id = "ds_b"))
        dao.insertCandles((0 until 5).map { candle(it.toLong()) }.toEntities("ds_a"))
        dao.insertCandles((100 until 105).map { candle(it.toLong()) }.toEntities("ds_b"))

        assertEquals(5, repo.candleCount("ds_a"))
        assertEquals(5, repo.candleCount("ds_b"))

        // A wide range on ds_a must not pick up ds_b's minutes.
        val a = repo.candlesInRange("ds_a", 0L, Long.MAX_VALUE, maxRows = 100)
        assertEquals(5, a.size)
        assertTrue(a.all { it.timestampUtcMs < t0 + 100 * 60_000L })
    }

    @Test
    fun `news queries are dataset scoped`() = runTest {
        dao.insertDataset(dataset(id = "ds_a"))
        dao.insertDataset(dataset(id = "ds_b"))
        dao.insertNews(
            listOf(
                NewsEventEntity(datasetId = "ds_a", publishedAtUtcMs = t0, headline = "A", body = "", impact = "HIGH"),
                NewsEventEntity(datasetId = "ds_b", publishedAtUtcMs = t0, headline = "B", body = "", impact = "LOW"),
            ),
        )

        val a = repo.newsInRange("ds_a", 0L, Long.MAX_VALUE)
        assertEquals(1, a.size)
        assertEquals("A", a.single().headline)
    }

    @Test
    fun `news range is half open`() = runTest {
        dao.insertDataset(dataset())
        dao.insertNews(
            listOf(
                NewsEventEntity(datasetId = "ds_a", publishedAtUtcMs = t0, headline = "in", body = "", impact = "LOW"),
                NewsEventEntity(datasetId = "ds_a", publishedAtUtcMs = t0 + 1000, headline = "out", body = "", impact = "LOW"),
            ),
        )

        val rows = repo.newsInRange("ds_a", t0, t0 + 1000)
        assertEquals(listOf("in"), rows.map { it.headline })
    }

    // ---------- foreign keys and deletion restrictions ----------

    @Test
    fun `candle cannot exist without its dataset`() = runTest {
        val error = runCatching {
            dao.insertCandles(listOf(candle(0)).toEntities("ds_ghost"))
        }.exceptionOrNull()
        assertNotNull("foreign key must block an orphan candle", error)
    }

    @Test
    fun `dataset with children cannot be deleted directly`() = runTest {
        dao.insertDataset(dataset())
        dao.insertCandles(listOf(candle(0)).toEntities("ds_a"))

        val error = runCatching { dao.deleteDataset("ds_a") }.exceptionOrNull()
        assertNotNull("RESTRICT must block deleting a dataset with candles", error)
        assertEquals(1, dao.countDatasets())
    }

    @Test
    fun `repository delete removes the dataset and all its children`() = runTest {
        dao.insertDataset(dataset(id = "ds_a"))
        dao.insertCandles(listOf(candle(0), candle(1)).toEntities("ds_a"))
        dao.insertClosures(
            listOf(MarketClosureEntity("ds_a", t0, t0 + 1000, "WEEKEND", 2)),
        )
        dao.insertValidation(
            DatasetValidationEntity("ds_a", "VALID", 0, 0, 0, 0, 100.0, "[]"),
        )

        assertTrue(repo.deleteDataset("ds_a"))

        assertEquals(0, dao.countDatasets())
        assertEquals(0, dao.countCandles("ds_a"))
        assertEquals(0, dao.countClosures("ds_a"))
        assertNull(dao.validationFor("ds_a"))
    }

    @Test
    fun `deleting one dataset leaves the other intact`() = runTest {
        dao.insertDataset(dataset(id = "ds_a"))
        dao.insertDataset(dataset(id = "ds_b"))
        dao.insertCandles(listOf(candle(0)).toEntities("ds_a"))
        dao.insertCandles(listOf(candle(0)).toEntities("ds_b"))

        assertTrue(repo.deleteDataset("ds_a"))

        assertNull(dao.getDataset("ds_a"))
        assertNotNull(dao.getDataset("ds_b"))
        assertEquals(1, dao.countCandles("ds_b"))
    }

    @Test
    fun `delete reports false for an unknown dataset`() = runTest {
        assertFalse(repo.deleteDataset("ds_nope"))
    }

    // ---------- reopening ----------

    @Test
    fun `data survives closing and reopening the database`() = runTest {
        val name = "reopen_market.db"
        val first = MarketDatabase.create(ApplicationProvider.getApplicationContext(), name)
        first.marketDao().insertDataset(dataset(id = "ds_a", candleCount = 2))
        first.marketDao().insertCandles(listOf(candle(0), candle(1)).toEntities("ds_a"))
        first.close()

        val second = MarketDatabase.create(ApplicationProvider.getApplicationContext(), name)
        try {
            assertEquals(2, second.marketDao().countCandles("ds_a"))
            assertEquals("hash_a", second.marketDao().getDataset("ds_a")!!.contentHash)
        } finally {
            second.close()
        }
    }

    @Test
    fun `candle table is stored WITHOUT ROWID and survives reopen`() = runTest {
        val name = "wor_market.db"
        val first = MarketDatabase.create(ApplicationProvider.getApplicationContext(), name)
        first.marketDao().insertDataset(dataset())
        first.marketDao().insertCandles(listOf(candle(0)).toEntities("ds_a"))

        val ddl = first.openHelper.writableDatabase.query(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='Candle1m'",
        ).use { if (it.moveToFirst()) it.getString(0) else "" }
        assertTrue("Candle1m should be WITHOUT ROWID, was: $ddl", ddl.contains("WITHOUT ROWID"))
        first.close()

        val second = MarketDatabase.create(ApplicationProvider.getApplicationContext(), name)
        try {
            assertEquals(1, second.marketDao().countCandles("ds_a"))
            val ddl2 = second.openHelper.writableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='Candle1m'",
            ).use { if (it.moveToFirst()) it.getString(0) else "" }
            assertTrue(ddl2.contains("WITHOUT ROWID"))
        } finally {
            second.close()
        }
    }

    @Test
    fun `Room validates its own schema on reopen`() = runTest {
        // Room checks the identity hash on open. If the WITHOUT ROWID rewrite
        // had disturbed anything Room tracks, this open would throw.
        val name = "identity_market.db"
        MarketDatabase.create(ApplicationProvider.getApplicationContext(), name).close()
        val reopened = MarketDatabase.create(ApplicationProvider.getApplicationContext(), name)
        try {
            assertEquals(0, reopened.marketDao().countDatasets())
        } finally {
            reopened.close()
        }
    }

    // ---------- summaries ----------

    @Test
    fun `summary carries every field the market library displays`() = runTest {
        dao.insertDataset(dataset(candleCount = 3, start = t0, end = t0 + 600_000L))
        dao.insertCandles((0 until 3).map { candle(it.toLong()) }.toEntities("ds_a"))
        dao.insertNews(
            listOf(NewsEventEntity(datasetId = "ds_a", publishedAtUtcMs = t0, headline = "N", body = "", impact = "LOW")),
        )
        dao.insertValidation(DatasetValidationEntity("ds_a", "VALID", 0, 1, 0, 0, 100.0, "[]"))

        val summary = repo.getDataset("ds_a")
        assertNotNull(summary)
        summary!!
        assertEquals("Gold Q1", summary.displayName)
        assertEquals("XAUUSD", summary.instrument)
        assertEquals(3, summary.candleCount)
        assertEquals(1, summary.newsCount)
        assertEquals("VALID", summary.validationStatus)
        assertFalse(summary.hasUnexpectedGaps)
        assertEquals(600_000L, summary.spanMillis)
        assertNotNull("size estimate should be present when dbstat works", summary.estimatedStorageBytes)
    }

    @Test
    fun `summary flags a dataset with validation errors`() = runTest {
        dao.insertDataset(dataset())
        dao.insertValidation(DatasetValidationEntity("ds_a", "INVALID", 2, 0, 5, 1, 91.0, "[]"))

        assertTrue(repo.getDataset("ds_a")!!.hasUnexpectedGaps)
    }

    @Test
    fun `listDatasets returns datasets ordered by start`() = runTest {
        dao.insertDataset(dataset(id = "ds_late", start = t0 + 10_000_000L))
        dao.insertDataset(dataset(id = "ds_early", start = t0))

        assertEquals(listOf("ds_early", "ds_late"), repo.listDatasets().map { it.datasetId })
    }

    @Test
    fun `getDataset is null for an unknown id`() = runTest {
        assertNull(repo.getDataset("ds_nope"))
    }

    // ---------- price scale ----------

    @Test
    fun `prices round trip at full thousandths precision`() = runTest {
        dao.insertDataset(dataset())
        // 2450.505 and 2450.501 are not representable in whole cents.
        val precise = HistoricalCandle(t0, 2_450_505, 2_450_509, 2_450_501, 2_450_507, 3)
        dao.insertCandles(listOf(precise).toEntities("ds_a"))

        val read = repo.candleAt("ds_a", t0)!!
        assertEquals(2_450_505, read.openUnits)
        assertEquals(2_450_509, read.highUnits)
        assertEquals(2_450_501, read.lowUnits)
        assertEquals(2_450_507, read.closeUnits)
        assertEquals(PriceScale.UNITS_PER_USD, 1000)
    }

    @Test
    fun `full int range price survives storage`() = runTest {
        dao.insertDataset(dataset())
        val extreme = HistoricalCandle(t0, 2_050_000, Int.MAX_VALUE, 1, 2_050_000, Int.MAX_VALUE)
        dao.insertCandles(listOf(extreme).toEntities("ds_a"))

        val read = repo.candleAt("ds_a", t0)!!
        assertEquals(Int.MAX_VALUE, read.highUnits)
        assertEquals(Int.MAX_VALUE, read.tickVolume)
    }

    // ---------- closures ----------

    @Test
    fun `closures are stored and read per dataset`() = runTest {
        dao.insertDataset(dataset(id = "ds_a"))
        dao.insertDataset(dataset(id = "ds_b"))
        dao.insertClosures(
            listOf(
                MarketClosureEntity("ds_a", t0, t0 + 3_600_000L, "WEEKEND", 60),
                MarketClosureEntity("ds_b", t0, t0 + 3_600_000L, "HOLIDAY", 60),
            ),
        )

        val a = repo.closures("ds_a")
        assertEquals(1, a.size)
        assertEquals("WEEKEND", a.single().reason)
        assertEquals(60, a.single().missingMinuteCount)
    }

    @Test
    fun `import stores closures news and validation together`() = runTest {
        val manifest = com.tradequest.app.core.market.DatasetManifest(
            datasetId = "ds_full", instrument = "XAUUSD",
            startTimestampUtcMs = t0, endTimestampUtcMs = t0 + 60_000L,
            candleCount = 1, minimumPriceUnits = 1, maximumPriceUnits = 2,
            missingMinuteCount = 60, duplicateCount = 0, validationStatus = "VALID_WITH_WARNINGS",
            sourceDescription = "s", contentHash = "h_full",
            weekendClosureDays = 1, holidayClosureDays = 0, unexpectedEmptyDays = 0,
        )

        val outcome = repo.storeImport(
            ImportRequest(
                manifest = manifest,
                candles = listOf(candle(0)),
                displayName = "Full",
                closures = listOf(DatasetClosure("ds_full", t0, t0 + 3_600_000L, "WEEKEND", 60)),
                news = listOf(PendingNews(t0, "CPI", "body", "HIGH")),
                validationStatus = "VALID_WITH_WARNINGS",
                warningCount = 1,
                coveragePercent = 96.0,
                importedAtUtcMs = t0,
            ),
        )

        assertTrue(outcome is ImportOutcome.Stored)
        assertEquals(1, dao.countCandles("ds_full"))
        assertEquals(1, dao.countClosures("ds_full"))
        assertEquals(1, dao.countNews("ds_full"))
        assertEquals("VALID_WITH_WARNINGS", dao.validationFor("ds_full")!!.status)
        assertEquals("CPI", repo.newsInRange("ds_full", 0L, Long.MAX_VALUE).single().headline)
    }

    @Test
    fun `a failed import leaves nothing behind`() = runTest {
        // The candle list contains a duplicate minute, so the primary key
        // rejects it mid-transaction. The dataset row must not survive.
        val manifest = com.tradequest.app.core.market.DatasetManifest(
            datasetId = "ds_bad", instrument = "XAUUSD",
            startTimestampUtcMs = t0, endTimestampUtcMs = t0 + 60_000L,
            candleCount = 2, minimumPriceUnits = 1, maximumPriceUnits = 2,
            missingMinuteCount = 0, duplicateCount = 0, validationStatus = "VALID",
            sourceDescription = "s", contentHash = "h_bad",
            weekendClosureDays = 0, holidayClosureDays = 0, unexpectedEmptyDays = 0,
        )

        val error = runCatching {
            repo.storeImport(
                ImportRequest(manifest, listOf(candle(0), candle(0)), "Bad", importedAtUtcMs = t0),
            )
        }.exceptionOrNull()

        assertNotNull("a duplicate minute must fail the import", error)
        assertNull("a failed import must not leave a dataset row", dao.getDataset("ds_bad"))
        assertEquals(0, dao.countCandles("ds_bad"))
    }

    // ---------- storage accounting ----------

    @Test
    fun `candle table byte count is available`() = runTest {
        dao.insertDataset(dataset())
        dao.insertCandles((0 until 100).map { candle(it.toLong()) }.toEntities("ds_a"))

        val bytes = dao.candleTableBytes()
        assertNotNull("dbstat should report bytes", bytes)
        assertTrue("a populated table must occupy at least one page", bytes!! > 0)
        assertEquals(100, dao.countAllCandles())
    }
}
