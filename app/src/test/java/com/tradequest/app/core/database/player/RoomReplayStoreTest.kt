package com.tradequest.app.core.database.player

import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.replay.ReplayFixtures
import com.tradequest.app.core.replay.ReplayProcessor
import com.tradequest.app.core.replay.ReplayStateCodec
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.OrderStatus
import com.tradequest.app.core.trading.OrderType
import com.tradequest.app.core.trading.Position
import com.tradequest.app.core.trading.PositionStatus
import com.tradequest.app.core.trading.RiskFailureReason
import com.tradequest.app.core.trading.TradeOrder
import com.tradequest.app.core.trading.TradingContext
import com.tradequest.app.core.trading.TradingState
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
 * Steps 7.3-7.4: the Room-backed replay store, on real SQLite.
 *
 * # What this covers that the in-memory tests do not
 *
 * The processor tests prove the replay *logic* is correct against a trivial
 * store. This proves the store that ships is correct: that a state survives a
 * full encode/decode round-trip through SQLite with every field intact, that a
 * commit replaces rather than appends, and that a whole replay driven through
 * the real database produces the same result as one driven through memory. That
 * last test is the one that would catch a codec that drops a field the engine
 * needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomReplayStoreTest {

    private lateinit var db: PlayerDatabase
    private lateinit var store: RoomReplayStore
    private lateinit var dao: PlayerDao

    private val t0 = ReplayFixtures.T0

    @Before
    fun setUp() {
        db = PlayerDatabase.create(
            ApplicationProvider.getApplicationContext(),
            "replay_store_test.db",
        )
        store = RoomReplayStore(db)
        dao = db.playerDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertSeason(id: String = ReplayFixtures.SEASON_ID) = runTest {
        dao.insertSeason(
            SeasonEntity(
                seasonId = id,
                datasetId = ReplayFixtures.DATASET_ID,
                mode = "CHALLENGE",
                status = "ACTIVE",
                startingBalanceMicros = ReplayFixtures.START_BALANCE_MICROS,
                balanceMicros = ReplayFixtures.START_BALANCE_MICROS,
                equityMicros = ReplayFixtures.START_BALANCE_MICROS,
                peakEquityMicros = ReplayFixtures.START_BALANCE_MICROS,
                startedAtSimUtcMs = t0,
                createdAtRealUtcMs = t0,
                completedAtSimUtcMs = null,
                tradingDayCount = 0,
                dailyStrikeCount = 0,
                scorePoints = 0,
                outcomeReason = null,
            ),
        )
    }

    @Test
    fun `an unprocessed season loads as null, not as an empty state`() = runTest {
        insertSeason()
        assertNull(store.load(ReplayFixtures.SEASON_ID))
    }

    @Test
    fun `a committed state loads back identical`() = runTest {
        insertSeason()
        val state = TradingState(
            seasonId = ReplayFixtures.SEASON_ID,
            balanceMicros = 10_250_000_000L,
            orders = listOf(
                TradeOrder(
                    orderId = "o1",
                    seasonId = ReplayFixtures.SEASON_ID,
                    placedAtSimUtcMs = t0,
                    type = OrderType.STOP,
                    side = OrderSide.SELL,
                    volumeHundredths = 50,
                    requestedPriceUnits = 1_999_000,
                    stopLossPips = 75,
                    takeProfitPips = 150,
                    trailingStop = null,
                    breakeven = null,
                    status = OrderStatus.PENDING,
                    resolvedAtSimUtcMs = null,
                    rejectionReason = null,
                ),
            ),
            positions = emptyList(),
            executions = emptyList(),
            closedTrades = emptyList(),
            processedToSimUtcMs = t0 + 3_600_000L,
            dayStartEquityMicros = 10_100_000_000L,
            dayEpochDay = 19_723L,
            dailyLossStrikes = 1,
            dayLocked = true,
            challengeFailed = false,
            failureReason = RiskFailureReason.STATIC_FLOOR_BREACHED,
            marginWarningActive = true,
        )

        store.commit(state)

        val loaded = store.load(ReplayFixtures.SEASON_ID)
        assertNotNull(loaded)
        assertEquals(state, loaded)
    }

    @Test
    fun `committing twice replaces rather than accumulating`() = runTest {
        insertSeason()
        val first = TradingState(
            seasonId = ReplayFixtures.SEASON_ID,
            balanceMicros = 10_000_000_000L,
            orders = emptyList(),
            positions = emptyList(),
            executions = emptyList(),
            closedTrades = emptyList(),
            processedToSimUtcMs = t0 + 60_000L,
        )
        store.commit(first)
        store.commit(first.copy(balanceMicros = 9_900_000_000L, processedToSimUtcMs = t0 + 120_000L))

        assertEquals(1, dao.replayStateCount(ReplayFixtures.SEASON_ID))
        val loaded = store.load(ReplayFixtures.SEASON_ID)!!
        assertEquals(9_900_000_000L, loaded.balanceMicros)
        assertEquals(t0 + 120_000L, loaded.processedToSimUtcMs)
    }

    @Test
    fun `the stored cursor column agrees with the encoded state`() = runTest {
        insertSeason()
        val state = TradingState(
            seasonId = ReplayFixtures.SEASON_ID,
            balanceMicros = 10_000_000_000L,
            orders = emptyList(),
            positions = emptyList(),
            executions = emptyList(),
            closedTrades = emptyList(),
            processedToSimUtcMs = t0 + 7 * 60_000L,
        )
        store.commit(state)

        // The column exists so a query can find the cursor without decoding the
        // blob. If it ever disagreed with the blob, a resume would trust the wrong
        // one, so it is asserted to match.
        val row = dao.replayState(ReplayFixtures.SEASON_ID)!!
        assertEquals(state.processedToSimUtcMs, row.processedToSimUtcMs)
        assertEquals(ReplayStateCodec.SCHEMA_VERSION, row.schemaVersion)
    }

    @Test
    fun `a commit that violates the foreign key rolls back and writes nothing`() = runTest {
        insertSeason()
        val good = TradingState(
            seasonId = ReplayFixtures.SEASON_ID,
            balanceMicros = 10_000_000_000L,
            orders = emptyList(),
            positions = emptyList(),
            executions = emptyList(),
            closedTrades = emptyList(),
            processedToSimUtcMs = t0 + 60_000L,
        )
        store.commit(good)

        // Room enables foreign keys on its own connections, so a state for a
        // season that does not exist is rejected by SQLite. The write happens
        // inside the store's transaction, so the failure must roll back and leave
        // the existing row exactly as it was.
        val orphan = good.copy(seasonId = "no-such-season", processedToSimUtcMs = t0 + 120_000L)
        try {
            store.commit(orphan)
            throw AssertionError("expected the foreign key to reject the write")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // expected
        }

        assertNull(store.load("no-such-season"))
        assertEquals(good, store.load(ReplayFixtures.SEASON_ID))
    }

    @Test
    fun `deleting removes the row`() = runTest {
        insertSeason()
        store.commit(
            TradingState(
                seasonId = ReplayFixtures.SEASON_ID,
                balanceMicros = 10_000_000_000L,
                orders = emptyList(),
                positions = emptyList(),
                executions = emptyList(),
                closedTrades = emptyList(),
                processedToSimUtcMs = t0,
            ),
        )
        store.delete(ReplayFixtures.SEASON_ID)
        assertNull(store.load(ReplayFixtures.SEASON_ID))
    }

    @Test
    fun `a whole replay through the real database matches one through memory`() = runTest {
        insertSeason()
        val candles = ReplayFixtures.climbing(40)
        val repository = ReplayFixtures.FakeMarketRepository(candles)

        val realStore = RoomReplayStore(db)
        val viaRoom = ReplayProcessor(
            marketRepository = repository,
            store = realStore,
            datasetId = ReplayFixtures.DATASET_ID,
            tradingContext = TradingContext(seasonId = ReplayFixtures.SEASON_ID),
            startingBalanceMicros = ReplayFixtures.START_BALANCE_MICROS,
        ).process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(40))

        val viaMemory = ReplayProcessor(
            marketRepository = repository,
            store = com.tradequest.app.core.replay.InMemoryReplayStore(),
            datasetId = ReplayFixtures.DATASET_ID,
            tradingContext = TradingContext(seasonId = ReplayFixtures.SEASON_ID),
            startingBalanceMicros = ReplayFixtures.START_BALANCE_MICROS,
        ).process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(40))

        assertEquals(viaMemory.account.balanceMicros, viaRoom.account.balanceMicros)
        assertEquals(viaMemory.processedToSimUtcMs, viaRoom.processedToSimUtcMs)

        // And a second processor resuming from the database reaches the same place
        // as if it had never stopped — the property a restart depends on.
        val resumed = ReplayProcessor(
            marketRepository = repository,
            store = realStore,
            datasetId = ReplayFixtures.DATASET_ID,
            tradingContext = TradingContext(seasonId = ReplayFixtures.SEASON_ID),
            startingBalanceMicros = ReplayFixtures.START_BALANCE_MICROS,
        ).process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(40))

        assertEquals(0, resumed.batches)
        assertEquals(viaRoom.account.balanceMicros, resumed.account.balanceMicros)
    }

    @Test
    fun `a position's fields all survive the round trip`() = runTest {
        insertSeason()
        val position = Position(
            positionId = "pos-1",
            seasonId = ReplayFixtures.SEASON_ID,
            openedAtSimUtcMs = t0,
            side = OrderSide.BUY,
            volumeHundredths = 25,
            openPriceUnits = 2_000_500,
            stopLossPips = 100,
            takeProfitPips = 200,
            trailingStop = null,
            breakeven = null,
            status = PositionStatus.OPEN,
            closedAtSimUtcMs = null,
            closePriceUnits = null,
            activeStopUnits = 1_990_000,
            targetUnits = 2_020_000,
            favorableExtremeUnits = 2_001_000,
            breakevenApplied = false,
            entryCommissionMicros = 1_250L,
            realizedPnlMicros = 0L,
            realizedVolumeHundredths = 0,
        )
        val state = TradingState(
            seasonId = ReplayFixtures.SEASON_ID,
            balanceMicros = 10_000_000_000L,
            orders = emptyList(),
            positions = listOf(position),
            executions = emptyList(),
            closedTrades = emptyList(),
            processedToSimUtcMs = t0,
        )
        store.commit(state)

        val loaded = store.load(ReplayFixtures.SEASON_ID)!!
        assertEquals(1, loaded.positions.size)
        assertEquals(position, loaded.positions.single())
    }
}
