package com.tradequest.app.core.replay

import com.tradequest.app.core.simulation.clock.ClockMode
import com.tradequest.app.core.simulation.clock.DatasetBounds
import com.tradequest.app.core.simulation.clock.InMemoryClockStore
import com.tradequest.app.core.simulation.clock.SimulationClock
import com.tradequest.app.core.simulation.clock.TestTimeSource
import com.tradequest.app.core.trading.TradingContext
import com.tradequest.app.core.trading.TradingState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 7.7: the coordinator's ordering guarantee.
 *
 * # The property under test
 *
 * The coordinator replays first and commits the clock second. Getting that order
 * wrong is the single most damaging bug this phase could have: committing the
 * clock before the replay tells it the pipeline has processed time it has not, so
 * the next resume skips that interval and trades are silently lost. These tests
 * pin the order, and the clamp, and the "nothing to do" cases.
 */
class ReplayCoordinatorTest {

    private val datasetStart = ReplayFixtures.T0
    private val datasetEnd = ReplayFixtures.at(60)

    private fun bounds() = DatasetBounds(datasetStart, datasetEnd)

    private fun clock(
        store: InMemoryClockStore,
        time: TestTimeSource,
    ) = SimulationClock(store, time, time)

    private fun processor(
        candles: List<com.tradequest.app.core.market.HistoricalCandle>,
        store: ReplayStore,
        batchMs: Long = ReplayFixtures.MINUTE,
    ) = ReplayProcessor(
        marketRepository = ReplayFixtures.FakeMarketRepository(candles),
        store = store,
        datasetId = ReplayFixtures.DATASET_ID,
        tradingContext = TradingContext(seasonId = ReplayFixtures.SEASON_ID),
        startingBalanceMicros = ReplayFixtures.START_BALANCE_MICROS,
        batchMs = batchMs,
    )

    @Test
    fun `a clock that is not attached has nothing to resume`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clockStore = InMemoryClockStore()
        val subject = ReplayCoordinator(
            clock(clockStore, time),
            processor(ReplayFixtures.flat(10), InMemoryReplayStore()),
        )

        assertNull(subject.resume(ReplayFixtures.SEASON_ID))
    }

    @Test
    fun `resuming catches the pipeline up to the clock`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clockStore = InMemoryClockStore()
        val clock = clock(clockStore, time)
        clock.attach(
            seasonId = ReplayFixtures.SEASON_ID,
            bounds = bounds(),
            startSimUtcMs = datasetStart,
            mode = ClockMode.PRACTICE,
        )
        // Ten minutes of real time passes at 1x.
        time.advance(10 * ReplayFixtures.MINUTE)

        val replayStore = InMemoryReplayStore()
        val subject = ReplayCoordinator(
            clock,
            processor(ReplayFixtures.flat(60), replayStore),
        )

        val result = subject.resume(ReplayFixtures.SEASON_ID)
        assertNotNull(result)
        assertEquals(ReplayFixtures.at(10), result!!.outcome.processedToSimUtcMs)
        assertTrue(result.outcome.reachedTarget)

        // And the clock now knows the pipeline is caught up, so a second resume
        // has nothing owed.
        assertEquals(0L, clock.read()!!.pendingSimMs)
        assertEquals(ReplayFixtures.at(10), replayStore.load(ReplayFixtures.SEASON_ID)!!.processedToSimUtcMs)
    }

    @Test
    fun `the clock is committed only after the replay, so nothing is skipped`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clockStore = InMemoryClockStore()
        val clock = clock(clockStore, time)
        clock.attach(
            seasonId = ReplayFixtures.SEASON_ID,
            bounds = bounds(),
            startSimUtcMs = datasetStart,
            mode = ClockMode.PRACTICE,
        )
        time.advance(10 * ReplayFixtures.MINUTE)

        // A replay store that fails on its 3rd commit. If the coordinator
        // committed the clock first, the pending interval would be gone despite
        // the replay not having finished.
        val failing = FailingStore(failOnCommit = 3)
        val subject = ReplayCoordinator(clock, processor(ReplayFixtures.flat(60), failing))

        try {
            subject.resume(ReplayFixtures.SEASON_ID)
            throw AssertionError("expected the replay to fail")
        } catch (e: Boom) {
            // expected
        }

        // The clock must still owe time, because the replay never reached it.
        assertTrue(
            "the clock must not have been committed by a failed replay",
            clock.read()!!.pendingSimMs > 0L,
        )
    }

    @Test
    fun `a dataset that runs out first is clamped, not marked complete`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clockStore = InMemoryClockStore()
        val clock = clock(clockStore, time)
        clock.attach(
            seasonId = ReplayFixtures.SEASON_ID,
            bounds = bounds(),
            startSimUtcMs = datasetStart,
            mode = ClockMode.PRACTICE,
        )
        // More real time than the dataset covers.
        time.advance(120 * ReplayFixtures.MINUTE)

        val replayStore = InMemoryReplayStore()
        // Only 10 minutes of candles exist.
        val subject = ReplayCoordinator(clock, processor(ReplayFixtures.flat(10), replayStore))

        val result = subject.resume(ReplayFixtures.SEASON_ID)!!
        assertFalse(result.outcome.reachedTarget)
        assertTrue(result.clampedToDatasetEnd)
        // The cursor stopped at the data, not at the clock's target.
        assertEquals(ReplayFixtures.at(10), result.outcome.processedToSimUtcMs)
        // And the clock was told only what was actually processed.
        assertEquals(ReplayFixtures.at(10), clock.read()!!.lastCommittedSimUtcMs)
    }

    @Test
    fun `owed time is reported even when the catch-up was clamped`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clockStore = InMemoryClockStore()
        val clock = clock(clockStore, time)
        clock.attach(
            seasonId = ReplayFixtures.SEASON_ID,
            bounds = bounds(),
            startSimUtcMs = datasetStart,
            mode = ClockMode.PRACTICE,
        )
        time.advance(30 * ReplayFixtures.MINUTE)

        val subject = ReplayCoordinator(clock, processor(ReplayFixtures.flat(10), InMemoryReplayStore()))
        val result = subject.resume(ReplayFixtures.SEASON_ID)!!

        assertEquals(30 * ReplayFixtures.MINUTE, result.owedSimMs)
        assertTrue(result.clampedToDatasetEnd)
    }

    @Test
    fun `resuming twice with no new time owed does no work the second time`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clockStore = InMemoryClockStore()
        val clock = clock(clockStore, time)
        clock.attach(
            seasonId = ReplayFixtures.SEASON_ID,
            bounds = bounds(),
            startSimUtcMs = datasetStart,
            mode = ClockMode.PRACTICE,
        )
        time.advance(10 * ReplayFixtures.MINUTE)

        val replayStore = InMemoryReplayStore()
        val subject = ReplayCoordinator(clock, processor(ReplayFixtures.flat(60), replayStore))

        val first = subject.resume(ReplayFixtures.SEASON_ID)!!
        val second = subject.resume(ReplayFixtures.SEASON_ID)!!

        assertTrue(first.outcome.batches > 0)
        assertEquals(0, second.outcome.batches)
        assertEquals(first.outcome.account.balanceMicros, second.outcome.account.balanceMicros)
    }

    @Test
    fun `the flow form reaches the same state as the one-shot form`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clockStore = InMemoryClockStore()
        val clock = clock(clockStore, time)
        clock.attach(
            seasonId = ReplayFixtures.SEASON_ID,
            bounds = bounds(),
            startSimUtcMs = datasetStart,
            mode = ClockMode.PRACTICE,
        )
        time.advance(20 * ReplayFixtures.MINUTE)

        val candles = ReplayFixtures.climbing(60)
        val flowStore = InMemoryReplayStore()
        val subject = ReplayCoordinator(clock, processor(candles, flowStore))

        val progress = subject.resumeAsFlow(ReplayFixtures.SEASON_ID).toList()
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.last() is ReplayProgress.Complete)

        val finalAccount = (progress.last() as ReplayProgress.Complete).outcome.account
        val stored = flowStore.load(ReplayFixtures.SEASON_ID)!!
        assertEquals(finalAccount.balanceMicros, stored.balanceMicros)
        assertEquals(ReplayFixtures.at(20), stored.processedToSimUtcMs)
        assertEquals(0L, clock.read()!!.pendingSimMs)
    }

    @Test
    fun `the flow form clamps the clock too when the dataset runs out`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clockStore = InMemoryClockStore()
        val clock = clock(clockStore, time)
        clock.attach(
            seasonId = ReplayFixtures.SEASON_ID,
            bounds = bounds(),
            startSimUtcMs = datasetStart,
            mode = ClockMode.PRACTICE,
        )
        // Owed more than the dataset holds.
        time.advance(120 * ReplayFixtures.MINUTE)

        val replayStore = InMemoryReplayStore()
        val subject = ReplayCoordinator(clock, processor(ReplayFixtures.flat(10), replayStore))

        subject.resumeAsFlow(ReplayFixtures.SEASON_ID).toList()

        // The clock must be committed to what was processed, not to the target.
        // Committing the target here would mark the unprocessed remainder as done.
        assertEquals(ReplayFixtures.at(10), clock.read()!!.lastCommittedSimUtcMs)
        assertEquals(ReplayFixtures.at(10), replayStore.load(ReplayFixtures.SEASON_ID)!!.processedToSimUtcMs)
    }

    /** A store that throws [Boom] on its [failOnCommit]-th commit. */
    private class FailingStore(private val failOnCommit: Int) : ReplayStore {
        private val states = mutableMapOf<String, TradingState>()
        private var commits = 0

        override suspend fun load(seasonId: String): TradingState? = states[seasonId]

        override suspend fun commit(state: TradingState) {
            commits++
            if (commits == failOnCommit) throw Boom()
            states[state.seasonId] = state
        }

        override suspend fun delete(seasonId: String) {
            states.remove(seasonId)
        }
    }

    private class Boom : RuntimeException("simulated failure")
}
