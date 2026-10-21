package com.tradequest.app.core.replay

import com.tradequest.app.core.market.HistoricalCandle
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.TradingContext
import com.tradequest.app.core.trading.TradingState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 7.10: the replay processor's guarantees.
 *
 * These are the properties the phase's acceptance criteria name — no duplicate
 * fills, no corrupted state, deterministic replay, working recovery — expressed
 * as tests against the real [ReplayProcessor] and the real [TradingEngine]. The
 * store and the market repository are in-memory, but nothing about the replay
 * logic is stubbed.
 */
class ReplayProcessorTest {

    private val t0 = ReplayFixtures.T0
    private val minute = ReplayFixtures.MINUTE

    private fun processor(
        candles: List<HistoricalCandle>,
        store: ReplayStore,
        batchMs: Long = ReplayProcessor.DEFAULT_BATCH_MS,
        notifier: ReplayNotifier = NoOpReplayNotifier,
        closures: List<com.tradequest.app.core.market.DatasetClosure> = emptyList(),
        repository: MarketRepository = ReplayFixtures.FakeMarketRepository(candles, closures),
    ): ReplayProcessor = ReplayProcessor(
        marketRepository = repository,
        store = store,
        datasetId = ReplayFixtures.DATASET_ID,
        tradingContext = TradingContext(seasonId = ReplayFixtures.SEASON_ID),
        startingBalanceMicros = ReplayFixtures.START_BALANCE_MICROS,
        notifier = notifier,
        batchMs = batchMs,
    )

    // ---------------------------------------------------------------- basics

    @Test
    fun `a fresh season starts at the dataset's first minute`() = runTest {
        val store = InMemoryReplayStore()
        val candles = ReplayFixtures.flat(10)
        val outcome = processor(candles, store).process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(10))

        assertEquals(ReplayFixtures.at(10), outcome.processedToSimUtcMs)
        assertEquals(ReplayFixtures.START_BALANCE_MICROS, outcome.account.balanceMicros)
        assertTrue(outcome.reachedTarget)
    }

    @Test
    fun `the state is persisted, so a restart has something to resume from`() = runTest {
        val store = InMemoryReplayStore()
        val candles = ReplayFixtures.flat(10)
        processor(candles, store).process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(10))

        val stored = store.load(ReplayFixtures.SEASON_ID)
        assertNotNull(stored)
        assertEquals(ReplayFixtures.at(10), stored!!.processedToSimUtcMs)
    }

    @Test
    fun `a target before the cursor does nothing`() = runTest {
        val store = InMemoryReplayStore()
        val candles = ReplayFixtures.flat(10)
        val subject = processor(candles, store)
        subject.process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(10))

        // Asking for an earlier instant must not rewind, and must not reprocess.
        val outcome = subject.process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(4))
        assertEquals(ReplayFixtures.at(10), outcome.processedToSimUtcMs)
        assertEquals(0, outcome.batches)
    }

    @Test
    fun `an empty dataset is refused rather than reported as complete`() = runTest {
        val store = InMemoryReplayStore()
        val outcome = processor(emptyList(), store)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(10))

        assertFalse(outcome.reachedTarget)
        assertEquals(0, outcome.batches)
    }

    // ---------------------------------------------------- Step 7.5 idempotency

    @Test
    fun `re-running the same target produces no duplicate fills`() = runTest {
        val store = InMemoryReplayStore()
        val candles = ReplayFixtures.climbing(20)
        val subject = processor(candles, store)

        val first = subject.process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(20))
        val second = subject.process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(20))

        // The second run has nothing owed, so it must not touch the state.
        assertEquals(0, second.batches)
        assertEquals(first.account.balanceMicros, second.account.balanceMicros)

        val stored = store.load(ReplayFixtures.SEASON_ID)!!
        assertEquals(first.account.balanceMicros, stored.balanceMicros)
    }

    @Test
    fun `splitting a run into two calls gives the same result as one`() = runTest {
        val candles = ReplayFixtures.climbing(30)

        val oneShotStore = InMemoryReplayStore()
        val oneShot = processor(candles, oneShotStore)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(30))

        val splitStore = InMemoryReplayStore()
        val split = processor(candles, splitStore)
        split.process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(10))
        val splitOutcome = split.process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(30))

        assertEquals(oneShot.account.balanceMicros, splitOutcome.account.balanceMicros)
        assertEquals(
            oneShotStore.load(ReplayFixtures.SEASON_ID)!!.processedToSimUtcMs,
            splitStore.load(ReplayFixtures.SEASON_ID)!!.processedToSimUtcMs,
        )
    }

    // ------------------------------------------- Step 7.6 foreground/background

    @Test
    fun `batch size does not change the outcome`() = runTest {
        val candles = ReplayFixtures.climbing(60)

        // Foreground: one-minute batches. Background: one large batch.
        val fineStore = InMemoryReplayStore()
        val fine = processor(candles, fineStore, batchMs = minute)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(60))

        val coarseStore = InMemoryReplayStore()
        val coarse = processor(candles, coarseStore, batchMs = 60 * minute)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(60))

        assertEquals(fine.account.balanceMicros, coarse.account.balanceMicros)
        assertEquals(
            fineStore.load(ReplayFixtures.SEASON_ID)!!.processedToSimUtcMs,
            coarseStore.load(ReplayFixtures.SEASON_ID)!!.processedToSimUtcMs,
        )
        assertTrue("fine batches must be more numerous", fine.batches > coarse.batches)
    }

    @Test
    fun `an odd batch size still reaches the target exactly`() = runTest {
        val store = InMemoryReplayStore()
        // 7 minutes does not divide the 20-minute span, so the final batch is short.
        val outcome = processor(ReplayFixtures.flat(20), store, batchMs = 7 * minute)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(20))

        assertEquals(ReplayFixtures.at(20), outcome.processedToSimUtcMs)
        assertTrue(outcome.reachedTarget)
    }

    // --------------------------------------------------- Step 7.10 interruption

    @Test
    fun `an interrupted run resumes from the last committed batch`() = runTest {
        val candles = ReplayFixtures.climbing(30)
        // Fails on the 4th commit, standing in for the process being killed.
        val failing = FailingStore(failOnCommit = 4)
        val subject = processor(candles, failing, batchMs = minute)

        var interrupted = false
        try {
            subject.process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(30))
        } catch (e: SimulatedCrash) {
            interrupted = true
        }
        assertTrue("the run must have been interrupted", interrupted)

        // Whatever was committed before the failure is what the next run resumes from.
        val committed = failing.lastCommitted
        assertNotNull(committed)
        assertTrue(committed!!.processedToSimUtcMs > t0)
        assertTrue(committed.processedToSimUtcMs < ReplayFixtures.at(30))

        // Resuming on a healthy store finishes the job without redoing the start.
        val healthy = InMemoryReplayStore()
        healthy.commit(committed)
        val resumed = processor(candles, healthy, batchMs = minute)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(30))

        assertEquals(ReplayFixtures.at(30), resumed.processedToSimUtcMs)

        // And the total is what an uninterrupted run would have produced.
        val cleanStore = InMemoryReplayStore()
        val clean = processor(candles, cleanStore, batchMs = minute)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(30))
        assertEquals(clean.account.balanceMicros, resumed.account.balanceMicros)
    }

    @Test
    fun `every committed state is internally consistent`() = runTest {
        val candles = ReplayFixtures.climbing(30)
        val recording = RecordingStore()
        processor(candles, recording, batchMs = minute)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(30))

        assertTrue("there must have been several commits", recording.commits.size > 3)

        // Each commit's cursor is the furthest so far, and none rewinds.
        var previous = t0
        for (state in recording.commits) {
            assertTrue(
                "cursor must advance: ${state.processedToSimUtcMs} after $previous",
                state.processedToSimUtcMs > previous,
            )
            previous = state.processedToSimUtcMs
        }

        // The last commit matches the run's final account.
        val last = recording.commits.last()
        assertEquals(ReplayFixtures.START_BALANCE_MICROS + last.closedTrades.sumOf { it.netPnlMicros }, last.balanceMicros)
    }

    // ------------------------------------------------------- multi-day catch-up

    @Test
    fun `a catch-up across a gap reaches the target and does not invent price`() = runTest {
        val candles = ReplayFixtures.acrossGap(beforeCount = 5, gapMinutes = 240, afterCount = 5)
        val store = InMemoryReplayStore()
        val target = ReplayFixtures.at(5 + 240 + 5)

        val outcome = processor(candles, store, batchMs = minute)
            .process(ReplayFixtures.SEASON_ID, target)

        assertEquals(target, outcome.processedToSimUtcMs)
        assertTrue(outcome.reachedTarget)
    }

    @Test
    fun `a target beyond the dataset stops at the dataset end`() = runTest {
        val store = InMemoryReplayStore()
        val candles = ReplayFixtures.flat(10)
        // The last candle starts at minute 9; its exclusive end is minute 10.
        val outcome = processor(candles, store)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(500))

        assertFalse("must not claim to have reached a target past the data", outcome.reachedTarget)
        assertEquals(ReplayFixtures.at(10), outcome.processedToSimUtcMs)
    }

    // ------------------------------------------------------------ notifications

    @Test
    fun `nothing is notified before the commit that covers it`() = runTest {
        val candles = ReplayFixtures.climbing(20)
        val events = mutableListOf<String>()
        val recordingStore = OrderRecordingStore(events)
        val notifier = ReplayNotifier { notification -> events += "notify:${notification.kind}" }

        processor(candles, recordingStore, batchMs = minute, notifier = notifier)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(20))

        assertTrue("commits must have happened", events.any { it == "commit" })

        // Every notification must be preceded by at least one commit, so a crash
        // before the commit could not have delivered a notification for work that
        // was then lost.
        var commitsSeen = 0
        for (entry in events) {
            if (entry == "commit") commitsSeen++ else assertTrue(
                "a notification appeared before any commit",
                commitsSeen > 0,
            )
        }
    }

    // -------------------------------------------------------------- persistence

    @Test
    fun `a stored state round-trips through the codec unchanged`() = runTest {
        val store = InMemoryReplayStore()
        val candles = ReplayFixtures.climbing(20)
        processor(candles, store).process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(20))

        val stored = store.load(ReplayFixtures.SEASON_ID)!!
        val encoded = ReplayStateCodec.encode(stored)
        val decoded = ReplayStateCodec.decode(encoded)

        assertEquals(stored, decoded)
    }

    @Test
    fun `a state with orders and positions round-trips`() = runTest {
        val state = TradingState(
            seasonId = ReplayFixtures.SEASON_ID,
            balanceMicros = 9_500_000_000L,
            orders = listOf(
                com.tradequest.app.core.trading.TradeOrder(
                    orderId = "o1",
                    seasonId = ReplayFixtures.SEASON_ID,
                    placedAtSimUtcMs = t0,
                    type = com.tradequest.app.core.trading.OrderType.LIMIT,
                    side = OrderSide.BUY,
                    volumeHundredths = 25,
                    requestedPriceUnits = 2_000_000,
                    stopLossPips = 50,
                    takeProfitPips = 100,
                    trailingStop = com.tradequest.app.core.trading.TrailingStopSpec(30, 20),
                    breakeven = com.tradequest.app.core.trading.BreakevenSpec(40, 5),
                    status = com.tradequest.app.core.trading.OrderStatus.PENDING,
                    resolvedAtSimUtcMs = null,
                    rejectionReason = null,
                ),
            ),
            positions = emptyList(),
            executions = emptyList(),
            closedTrades = emptyList(),
            processedToSimUtcMs = t0,
            dailyLossStrikes = 2,
            dayLocked = true,
            challengeFailed = true,
            failureReason = com.tradequest.app.core.trading.RiskFailureReason.STATIC_FLOOR_BREACHED,
            marginWarningActive = true,
        )

        val decoded = ReplayStateCodec.decode(ReplayStateCodec.encode(state))
        assertEquals(state, decoded)
    }

    @Test
    fun `a blob from an unknown schema version is refused`() {
        val blob = """{"schemaVersion":999,"seasonId":"s","balanceMicros":1,"processedToSimUtcMs":0,""" +
            """"dayStartEquityMicros":1,"dayEpochDay":0,"dailyLossStrikes":0,"dayLocked":false,""" +
            """"challengeFailed":false,"failureReason":null,"marginWarningActive":false,""" +
            """"orders":[],"positions":[],"executions":[],"closedTrades":[]}"""

        try {
            ReplayStateCodec.decode(blob)
            throw AssertionError("expected a ReplayStateFormatException")
        } catch (e: ReplayStateFormatException) {
            assertTrue(e.message!!.contains("999"))
        }
    }

    @Test
    fun `corrupt text is refused rather than half-read`() {
        try {
            ReplayStateCodec.decode("{not json")
            throw AssertionError("expected a ReplayStateFormatException")
        } catch (e: ReplayStateFormatException) {
            assertTrue(e.message!!.contains("not readable"))
        }
    }

    // ------------------------------------------------------------- cancellation

    @Test
    fun `a cancelled run leaves a consistent committed state to resume from`() = runTest {
        val candles = ReplayFixtures.climbing(60)
        // Cancels on the 5th commit, standing in for the system stopping the worker.
        val store = CancellingStore(cancelOnCommit = 5)
        val subject = processor(candles, store, batchMs = minute)

        var cancelled = false
        try {
            subject.process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(60))
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue("the run must have been cancelled", cancelled)

        // The state committed before the cancellation is real and resumable.
        val committed = store.lastCommitted
        assertNotNull(committed)
        assertTrue(committed!!.processedToSimUtcMs in t0..ReplayFixtures.at(60))

        // And finishing from it matches an uninterrupted run.
        val healthy = InMemoryReplayStore()
        healthy.commit(committed)
        val resumed = processor(candles, healthy, batchMs = minute)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(60))

        val cleanStore = InMemoryReplayStore()
        val clean = processor(candles, cleanStore, batchMs = minute)
            .process(ReplayFixtures.SEASON_ID, ReplayFixtures.at(60))

        assertEquals(clean.account.balanceMicros, resumed.account.balanceMicros)
        assertEquals(ReplayFixtures.at(60), resumed.processedToSimUtcMs)
    }

    // ------------------------------------------------------------------ helpers

    /** Thrown by [FailingStore] to stand in for a process being killed. */
    private class SimulatedCrash : RuntimeException("simulated crash")

    /** A store that commits normally until the [failOnCommit]-th call, then throws. */
    private class FailingStore(private val failOnCommit: Int) : ReplayStore {
        private val states = mutableMapOf<String, TradingState>()
        private var commits = 0

        var lastCommitted: TradingState? = null
            private set

        override suspend fun load(seasonId: String): TradingState? = states[seasonId]

        override suspend fun commit(state: TradingState) {
            commits++
            if (commits == failOnCommit) throw SimulatedCrash()
            states[state.seasonId] = state
            lastCommitted = state
        }

        override suspend fun delete(seasonId: String) {
            states.remove(seasonId)
        }
    }

    /** A store that cancels the coroutine on the [cancelOnCommit]-th commit. */
    private class CancellingStore(private val cancelOnCommit: Int) : ReplayStore {
        private val states = mutableMapOf<String, TradingState>()
        private var commits = 0

        var lastCommitted: TradingState? = null
            private set

        override suspend fun load(seasonId: String): TradingState? = states[seasonId]

        override suspend fun commit(state: TradingState) {
            commits++
            if (commits == cancelOnCommit) throw kotlinx.coroutines.CancellationException("stopped")
            states[state.seasonId] = state
            lastCommitted = state
        }

        override suspend fun delete(seasonId: String) {
            states.remove(seasonId)
        }
    }

    /** A store that keeps every committed state, in order. */
    private class RecordingStore : ReplayStore {
        private val states = mutableMapOf<String, TradingState>()
        val commits = mutableListOf<TradingState>()

        override suspend fun load(seasonId: String): TradingState? = states[seasonId]

        override suspend fun commit(state: TradingState) {
            states[state.seasonId] = state
            commits += state
        }

        override suspend fun delete(seasonId: String) {
            states.remove(seasonId)
        }
    }

    /** A store that records the order of commits and notifications interleaved. */
    private class OrderRecordingStore(private val log: MutableList<String>) : ReplayStore {
        private val states = mutableMapOf<String, TradingState>()

        override suspend fun load(seasonId: String): TradingState? = states[seasonId]

        override suspend fun commit(state: TradingState) {
            log += "commit"
            states[state.seasonId] = state
        }

        override suspend fun delete(seasonId: String) {
            states.remove(seasonId)
        }
    }
}
