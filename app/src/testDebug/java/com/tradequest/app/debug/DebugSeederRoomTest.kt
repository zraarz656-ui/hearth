package com.tradequest.app.debug

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.RoomMarketRepository
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Temporary: verifies the debug seeder end-to-end against a real Room database.
 * Deleted once the on-device build is confirmed; not part of the committed suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugSeederRoomTest {

    private lateinit var db: MarketDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Room runs queries on its own executor; a direct executor keeps every
        // query on the test thread so runTest's scheduler can await it.
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, MarketDatabase::class.java)
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * Runs [block] off the main thread.
     *
     * Under Robolectric the test thread *is* the main looper, so Room's
     * main-thread check would fire. Production calls the seeder on
     * `Dispatchers.IO`, so this mirrors that rather than disabling the check —
     * a seeder that touched the database on the main thread would be a real bug
     * and should fail here.
     */
    private suspend fun <T> offMain(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }

    @Test
    fun `seeds the bundled csv into an empty database`() = runTest {
        val summary = offMain { DebugDatasetSeeder.seed(context, db) }
        println("summary=$summary")

        assertTrue("expected a successful seed, got: $summary", summary.startsWith("OK"))

        val repository = RoomMarketRepository(db)
        assertEquals(1, offMain { repository.countDatasets() })

        val stored = offMain { repository.observeDatasets().first().single() }
        println("stored displayName=${stored.displayName} candles=${stored.candleCount}")
        assertEquals(20_548, stored.candleCount)
        assertEquals("XAUUSD", stored.instrument)
    }

    @Test
    fun `second seed on a populated database is a no-op`() = runTest {
        assertTrue(offMain { DebugDatasetSeeder.seed(context, db) }.startsWith("OK"))

        val second = offMain { DebugDatasetSeeder.seed(context, db) }
        println("second=$second")
        assertTrue("expected skip, got: $second", second.startsWith("skipped"))

        assertEquals(1, offMain { RoomMarketRepository(db).countDatasets() })
    }
}
