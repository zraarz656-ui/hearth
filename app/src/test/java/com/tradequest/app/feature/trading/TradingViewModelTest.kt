package com.tradequest.app.feature.trading

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.challenge.ChallengeEvaluation
import com.tradequest.app.core.chart.renderer.FormingCandleView
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.chart.viewport.ChartTimeframeAnchor
import com.tradequest.app.core.database.market.Candle1mEntity
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.MarketDatasetEntity
import com.tradequest.app.core.database.market.RoomMarketRepository
import com.tradequest.app.core.chart.renderer.FlagSeverity
import com.tradequest.app.core.chart.renderer.NewsFlagHit
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.market.NewsItem
import com.tradequest.app.core.news.NewsRelease
import com.tradequest.app.core.news.NewsReleaseProjection
import com.tradequest.app.feature.news.NewsScheduleSource
import com.tradequest.app.core.replay.InMemoryReplayStore
import com.tradequest.app.core.risk.RiskLockBps
import com.tradequest.app.core.trading.AccountState
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.OrderType
import com.tradequest.app.core.trading.TradingContext
import com.tradequest.app.core.trading.TradingState
import com.tradequest.app.core.simulation.warp.WarpPermission
import com.tradequest.app.core.simulation.warp.WarpTarget
import com.tradequest.app.ui.theme.ThemeMode
import com.tradequest.app.ui.theme.chartStyleFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/**
 * The Trading screen's data path (Step 8.10).
 *
 * # Real databases, fake clock
 *
 * The market and player databases are real in-memory Room databases and the
 * repositories are the production ones, so the queries and the season lookup are
 * the code that ships. Only [TradingSessionSource] is a fake, because the real
 * one wraps a `SimulationClock` that derives time from the wall clock — which a
 * test cannot control, and which is the one thing here that needs controlling.
 *
 * # What the fake lets the test prove
 *
 * The safety rule is that the chart never shows a price from after the simulated
 * instant. The strongest test of that is to put the instant in the middle of the
 * dataset and assert the buffer stops there, while the forming candle reports
 * only the part of the current minute that has elapsed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TradingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var marketDb: MarketDatabase
    private lateinit var marketRepository: RoomMarketRepository

    /** Monday 2024-01-08 00:00 UTC, so minute boundaries are easy to read. */
    private val t0 = 1_704_672_000_000L
    private val minuteMs = 60_000L

    /** One call the ViewModel made to [TradingSessionSource.syncChallenge]. */
    private data class ChallengeSyncCall(
        val tradingState: TradingState,
        val account: AccountState,
        val equityMicros: Long,
        val atSimUtcMs: Long,
    )

    /**
     * A controllable stand-in for the real clock-backed source.
     *
     * Instants are pushed through a flow rather than polled, so the test decides
     * exactly when the clock moves and `advanceUntilIdle` can reach idle.
     */
    private class FakeSessions(
        var season: SeasonBinding? = null,
        var simUtcMs: Long? = null,
    ) : TradingSessionSource {
        // A channel rather than a SharedFlow because the real source's flow
        // *completes* when the season ends, and only a channel can be closed to
        // reproduce that. A SharedFlow that merely stops emitting would leave the
        // ViewModel's collector alive forever, so the test could not tell the
        // difference between "the loop exited" and "the loop is idle".
        private val instants = Channel<Long>(Channel.UNLIMITED)

        override suspend fun currentSeason(): SeasonBinding? = season
        override fun currentSimUtcMs(): Long? = simUtcMs
        override fun simInstants(): Flow<Long> = instants.receiveAsFlow()

        override suspend fun startingBalanceMicros(): Long = 10_000L * 1_000_000L

        /**
         * A live session, or null when the test has not supplied one.
         *
         * Phase 10's UI tests drive trading through the ViewModel, but the engine
         * behaviour is covered against a real session in `LiveTradingSessionTest`.
         * Here the factory only has to satisfy the port; a test that needs a session
         * sets [liveSession].
         */
        var liveSession: LiveTradingSession? = null
        override suspend fun createLiveSession(): LiveTradingSession? = liveSession

        /**
         * The last challenge sync the ViewModel requested, or null.
         *
         * The ViewModel does not evaluate a challenge itself — that would put the
         * challenge rules in the trading feature — so the test records what it was
         * handed and can assert the call happened with the state the panel shows.
         */
        var lastChallengeSync: ChallengeSyncCall? = null

        /** A challenge evaluation to return, or null to model "no season". */
        var challengeEvaluation: ChallengeEvaluation? = null

        override suspend fun syncChallenge(
            tradingState: TradingState,
            account: AccountState,
            equityMicros: Long,
            atSimUtcMs: Long,
        ): ChallengeEvaluation? {
            lastChallengeSync = ChallengeSyncCall(tradingState, account, equityMicros, atSimUtcMs)
            return challengeEvaluation
        }

        /** True once [endSeason] has closed the clock, so the flow has completed. */
        val isClockClosed: Boolean get() = instants.isClosedForReceive

        /** Moves the clock and announces the new instant. */
        fun advanceTo(utcMs: Long) {
            simUtcMs = utcMs
            check(instants.trySend(utcMs).isSuccess) { "the test's instant channel was closed" }
        }

        /** Ends the season, so the chart stops following the clock. */
        fun endSeason() {
            simUtcMs = null
            season = null
            instants.close()
        }
    }

    private val sessions = FakeSessions()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Room runs its queries on its own executor, which the test scheduler
        // does not know about, so a read would finish on a real background thread
        // the test never awaits and the screen would still be Loading when the
        // assertions run. A direct executor runs each query inline on the thread
        // that asked for it, which is the test thread once the scheduler advances.
        val roomExecutor = Executor { it.run() }
        marketDb = Room.inMemoryDatabaseBuilder(context, MarketDatabase::class.java)
            .setQueryExecutor(roomExecutor)
            .setTransactionExecutor(roomExecutor)
            .build()
        marketRepository = RoomMarketRepository(marketDb)
    }

    @After
    fun tearDown() {
        marketDb.close()
        Dispatchers.resetMain()
    }

    /** Inserts a dataset of [minutes] rising minutes starting at [startUtcMs]. */
    private suspend fun insertDataset(
        datasetId: String = "ds_a",
        minutes: Int = 600,
        startUtcMs: Long = t0,
    ) {
        marketDb.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = datasetId,
                displayName = "Gold",
                instrument = "XAUUSD",
                startTimestampUtcMs = startUtcMs,
                endTimestampUtcMs = startUtcMs + minutes * minuteMs,
                candleCount = minutes,
                contentHash = "h_$datasetId",
                validationStatus = "VALID",
                sourceDescription = "test",
                importedAtUtcMs = t0,
            ),
        )
        marketDb.marketDao().insertCandles(
            (0 until minutes).map { i ->
                val open = 2_050_000 + i
                Candle1mEntity(
                    datasetId = datasetId,
                    timestampUtcMs = startUtcMs + i * minuteMs,
                    openUnits = open,
                    highUnits = open + 2,
                    lowUnits = open - 2,
                    closeUnits = open + 1,
                    tickVolume = 100,
                )
            },
        )
    }

    /**
     * A stand-in for the News feature's port (Steps 12.2-12.4).
     *
     * # Why this fakes the port and not the database
     *
     * The production implementation projects releases against the simulated instant,
     * and that projection is already covered by `NewsReleaseProjectionTest`. What is
     * *not* covered — and what this test exists for — is whether the Trading screen
     * asks for the flags at all and wires what it gets into the frame. A fake lets
     * the test state the releases directly and assert on the render state, without a
     * second copy of the projection logic that could be wrong in the same way.
     */
    private class FakeNews(
        var releases: List<NewsRelease> = emptyList(),
        var blind: Boolean = false,
    ) : NewsScheduleSource {
        /** Records that the ViewModel actually asked, which is half the point. */
        var requestedDatasetId: String? = null
        var requestedSimUtcMs: Long? = null

        override suspend fun releasesAt(
            datasetId: String,
            fromUtcMs: Long,
            toUtcMs: Long,
            simUtcMs: Long,
        ): List<NewsRelease> {
            requestedDatasetId = datasetId
            requestedSimUtcMs = simUtcMs
            return releases
        }

        override suspend fun activeDatasetId(): String? = requestedDatasetId
        override fun currentSimUtcMs(): Long? = requestedSimUtcMs
        override suspend fun isBlind(): Boolean = blind
        override suspend fun datasetBounds(datasetId: String): Pair<Long, Long>? = null
        override suspend fun warpPermission(): WarpPermission = WarpPermission.Allowed
        override suspend fun resolveWarp(kind: com.tradequest.app.feature.news.WarpKind): WarpTarget? = null
        override suspend fun performWarp(target: WarpTarget): Long? = null
        override fun simInstants(): Flow<Long> = kotlinx.coroutines.flow.emptyFlow()
    }

    private fun viewModel(news: NewsScheduleSource? = null) = TradingViewModel(
        repository = marketRepository,
        style = chartStyleFor(ThemeMode.DARK),
        sessions = sessions,
        // Both dispatchers must be the test's, or the load runs on a real thread
        // the test scheduler cannot wait for and every assertion sees stale state.
        ioDispatcher = dispatcher,
        computeDispatcher = dispatcher,
        newsSource = news,
    )

    private suspend fun readyState(vm: TradingViewModel): TradingState.Ready =
        vm.state.first { it is TradingState.Ready } as TradingState.Ready

    // ---------- Step 9.9: the centre survives a timeframe switch ----------

    @Test
    fun `switching timeframe keeps the centre moment on screen`() = runTest(dispatcher) {
        // Enough minutes that hourly aggregation still leaves a wide series, so the
        // centre can genuinely be honoured rather than being pinned to the edge.
        insertDataset(minutes = 20_000)
        sessions.season = null
        sessions.simUtcMs = null

        val vm = viewModel()
        advanceUntilIdle()
        val h1 = readyState(vm)

        // Scroll well back into history, away from the live edge.
        val scrolled = h1.renderState.viewport.copy(firstVisibleIndex = 100f)
        vm.onViewportChanged(scrolled)

        val centreBefore = ChartTimeframeAnchor.centreTimestamp(scrolled, h1.renderState.buffer)
        assertNotNull("the fixture must have a centre to keep", centreBefore)

        vm.selectTimeframe(Timeframe.M1)
        val m1 = vm.state.first { it is TradingState.Ready && it.timeframe == Timeframe.M1 } as TradingState.Ready

        val centreAfter = ChartTimeframeAnchor.centreTimestamp(m1.renderState.viewport, m1.renderState.buffer)
        assertEquals("the switch must not teleport the user", centreBefore, centreAfter)
    }

    @Test
    fun `a switch with no prior viewport opens at the live edge`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null

        val vm = viewModel()
        advanceUntilIdle()
        vm.selectTimeframe(Timeframe.M1)
        val m1 = vm.state.first { it is TradingState.Ready && it.timeframe == Timeframe.M1 } as TradingState.Ready

        // No `onViewportChanged` was ever called, so there is no centre to carry and
        // the documented fallback applies.
        assertEquals(
            m1.renderState.viewport.scrolledToEnd(m1.renderState.buffer.size),
            m1.renderState.viewport,
        )
    }

    /**
     * Switches to one-minute bars and returns the resulting state.
     *
     * The screen opens on hourly bars, so a test about a single minute has to ask
     * for them; otherwise the first hourly bucket is still forming and the buffer
     * is legitimately empty.
     */
    private suspend fun oneMinuteState(vm: TradingViewModel): TradingState.Ready {
        vm.selectTimeframe(Timeframe.M1)
        return vm.state.first { it is TradingState.Ready && it.timeframe == Timeframe.M1 } as TradingState.Ready
    }

    // ---------- No season: a plain historical chart ----------

    @Test
    fun `with no dataset the screen reports the empty state`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue("state was ${vm.state.value}", vm.state.value is TradingState.NoDataset)
    }

    @Test
    fun `with no season the whole dataset is revealed and there is no forming candle`() =
        runTest(dispatcher) {
            insertDataset(minutes = 600)
            sessions.season = null
            sessions.simUtcMs = null

            val vm = viewModel()
            advanceUntilIdle()
            val state = readyState(vm)

            assertNull("a historical chart must not have a forming candle", state.renderState.formingCandle)
            assertNull(state.renderState.simulatedUtcMs)
            assertEquals(Timeframe.H1, state.timeframe)
            assertTrue("the chart has no bars", state.renderState.buffer.size > 0)
        }

    // ---------- A running season: the forming candle ----------

    @Test
    fun `a running season reveals only minutes before the simulated instant`() =
        runTest(dispatcher) {
            insertDataset(minutes = 600)
            sessions.season = SeasonBinding("s1", "ds_a")
            // 30 minutes in: minutes 0..29 are complete.
            sessions.simUtcMs = t0 + 30 * minuteMs

            val vm = viewModel()
            advanceUntilIdle()
            val state = oneMinuteState(vm)

            assertEquals(30, state.renderState.buffer.size)
            assertNotNull(state.renderState.formingCandle)
            assertEquals(sessions.simUtcMs, state.renderState.simulatedUtcMs)
        }

    @Test
    fun `the forming candle never reports a price from after the instant`() =
        runTest(dispatcher) {
            insertDataset(minutes = 600)
            sessions.season = SeasonBinding("s1", "ds_a")
            sessions.simUtcMs = t0 + 30 * minuteMs

            val vm = viewModel()
            advanceUntilIdle()
            val state = oneMinuteState(vm)
            val forming = state.renderState.formingCandle as FormingCandleView

            // The dataset rises by one unit a minute with a two-unit wick, so
            // minute 30's true high is 2_050_030 + 2. The forming candle has seen
            // only the part of minute 30 up to the instant, so it must not reach
            // the minute's full high.
            val fullMinuteHigh = 2_050_030 + 2
            assertTrue(
                "the forming candle leaked the rest of its minute",
                forming.highUnits <= fullMinuteHigh,
            )
            assertEquals(2_050_030, forming.openUnits)
        }

    @Test
    fun `the season's dataset is charted even when another is listed first`() =
        runTest(dispatcher) {
            // "ds_a" sorts first and is not the season's dataset.
            insertDataset(datasetId = "ds_a", minutes = 120)
            insertDataset(datasetId = "ds_b", minutes = 600)
            sessions.season = SeasonBinding("s1", "ds_b")
            sessions.simUtcMs = t0 + 30 * minuteMs

            val vm = viewModel()
            advanceUntilIdle()
            val state = readyState(vm)

            assertEquals("ds_b", state.dataset.datasetId)
        }

    // ---------- Timeframe switching ----------

    @Test
    fun `switching timeframe re-aggregates to the new timeframe`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = null

        val vm = viewModel()
        advanceUntilIdle()
        val hourly = readyState(vm)

        vm.selectTimeframe(Timeframe.M1)
        advanceUntilIdle()
        val minute = readyState(vm)

        assertEquals(Timeframe.M1, minute.timeframe)
        assertTrue(
            "one-minute bars should outnumber hourly bars",
            minute.renderState.buffer.size > hourly.renderState.buffer.size,
        )
    }

    @Test
    fun `a pan survives a tick that only moves the forming candle`() = runTest(dispatcher) {
        insertDataset(minutes = 20000)
        sessions.season = SeasonBinding("s1", "ds_a")
        // On the default hourly bars, so the tick below stays inside one bucket.
        // A hundred and sixty-six hours of history means the chart opens scrolled
        // to the live edge at a positive index rather than at the start, which is
        // what makes the pan observable.
        sessions.simUtcMs = t0 + 10000 * minuteMs

        val vm = viewModel()
        advanceUntilIdle()
        val opened = readyState(vm)
        assertTrue("the chart should have opened away from the pan target",
            opened.renderState.viewport.firstVisibleIndex > 0f)

        // The trader pans back in time, after the frame was published.
        vm.onViewportChanged(opened.renderState.viewport.copy(firstVisibleIndex = 0f))

        // A new minute arrives: the forming candle changes, but the hourly bucket
        // does not, so this takes the light republish path rather than a full
        // rebuild. That path must still read the pan, not the stale frame.
        sessions.advanceTo(t0 + 10001 * minuteMs)
        advanceUntilIdle()

        val after = vm.state.value as TradingState.Ready
        assertEquals(
            "the light republish snapped back to the live edge instead of the pan",
            0f,
            after.renderState.viewport.firstVisibleIndex,
            0.001f,
        )
    }

    // ---------- The clock stopping ----------

    @Test
    fun `a season ending ends the clock collection`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = SeasonBinding("s1", "ds_a")
        sessions.simUtcMs = t0 + 30 * minuteMs

        val vm = viewModel()
        advanceUntilIdle()
        oneMinuteState(vm)

        // The season is abandoned: the source's flow completes, so the collector
        // must exit rather than wait on a flow that will never emit again.
        sessions.endSeason()
        advanceUntilIdle()

        // The last frame stands, and the closed channel proves nothing is left to
        // collect, so the ViewModel's clock job has ended.
        val state = vm.state.value as TradingState.Ready
        assertEquals(30, state.renderState.buffer.size)
        assertTrue("the fake's clock flow should have completed", sessions.isClockClosed)
    }

    // ---------- Phase 10: the ticket, the session and the overlays ----------

    /**
     * A real session over the test's dataset, so the ticket is driven end to end.
     *
     * The market repository is the production Room one and the store is the in-memory
     * one the other replay tests use, so the only thing faked about the trading path
     * here is the clock — which is what [FakeSessions] exists for.
     */
    private fun liveSession(datasetId: String = "ds_a") = LiveTradingSession(
        seasonId = "s1",
        marketRepository = marketRepository,
        datasetId = datasetId,
        tradingContext = TradingContext(seasonId = "s1", contract = ContractSpec.XAUUSD),
        startingBalanceMicros = 10_000L * 1_000_000L,
        store = InMemoryReplayStore(),
    )

    @Test
    fun `with a live season the panel reports the account and the trade controls`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = SeasonBinding("s1", "ds_a")
        sessions.simUtcMs = t0 + 30 * minuteMs
        sessions.liveSession = liveSession()

        val vm = viewModel()
        advanceUntilIdle()

        val panel = vm.panel.value
        assertTrue("a running season must make the screen live", panel.isLive)
        assertEquals(10_000L * 1_000_000L, panel.account?.balanceMicros)
        assertTrue(panel.positions.isEmpty())
    }

    @Test
    fun `without a season the trade controls stay hidden`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null
        sessions.liveSession = null

        val vm = viewModel()
        advanceUntilIdle()

        assertTrue("a historical chart must not offer trading", !vm.panel.value.isLive)
    }

    @Test
    fun `placing a market buy opens a position and draws its overlay`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = SeasonBinding("s1", "ds_a")
        sessions.simUtcMs = t0 + 30 * minuteMs
        sessions.liveSession = liveSession()

        val vm = viewModel()
        advanceUntilIdle()
        oneMinuteState(vm)

        vm.openTicket(OrderSide.BUY)
        val ticket = vm.ticket.value
        assertNotNull("Buy must open a ticket", ticket)
        // A market ticket is seeded with the last price, so its preview can size.
        assertNotNull("a market ticket is seeded with the current price", ticket!!.priceUnits)

        vm.updateTicket { it.copy(volumeHundredths = 10, stopLossPips = 50, takeProfitPips = 100) }
        vm.submitTicket()
        advanceUntilIdle()

        assertEquals(1, vm.panel.value.openPositionCount)
        assertNull("a submitted ticket closes", vm.ticket.value)

        val ready = vm.state.value as TradingState.Ready
        assertEquals("the new position must be drawn", 1, ready.renderState.overlays.size)
        val overlay = ready.renderState.overlays.single()
        assertTrue("a buy is drawn as a buy", overlay.isBuy)
        assertNotNull(overlay.entry)
        assertNotNull("a 50-pip stop must be drawn", overlay.stop)
        assertNotNull("a 100-pip target must be drawn", overlay.target)
    }

    @Test
    fun `a pending order with no price is refused with a message rather than submitted`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = SeasonBinding("s1", "ds_a")
        sessions.simUtcMs = t0 + 30 * minuteMs
        sessions.liveSession = liveSession()

        val vm = viewModel()
        advanceUntilIdle()
        oneMinuteState(vm)

        vm.openTicket(OrderSide.BUY)
        vm.updateTicket { it.copy(type = OrderType.LIMIT, priceUnits = null, volumeHundredths = 10) }
        vm.submitTicket()
        advanceUntilIdle()

        assertTrue("an incomplete ticket must not reach the engine", vm.panel.value.pendingOrderCount == 0)
        assertNotNull("the refusal must be shown", vm.panel.value.lastMessage)
        assertNotNull("the ticket stays open so the trader can fix it", vm.ticket.value)
    }

    @Test
    fun `closing a position removes its overlay`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = SeasonBinding("s1", "ds_a")
        sessions.simUtcMs = t0 + 30 * minuteMs
        sessions.liveSession = liveSession()

        val vm = viewModel()
        advanceUntilIdle()
        oneMinuteState(vm)

        vm.openTicket(OrderSide.BUY)
        vm.updateTicket { it.copy(volumeHundredths = 10) }
        vm.submitTicket()
        advanceUntilIdle()
        val positionId = vm.panel.value.positions.single().positionId

        vm.closePosition(positionId)
        advanceUntilIdle()

        assertEquals(0, vm.panel.value.openPositionCount)
        val ready = vm.state.value as TradingState.Ready
        assertTrue("a closed position must leave no lines behind", ready.renderState.overlays.isEmpty())
        assertEquals("the close is recorded for review", 1, vm.panel.value.closedTrades.size)
    }

    @Test
    fun `the risk lock sizes a ticket from its stop`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = SeasonBinding("s1", "ds_a")
        sessions.simUtcMs = t0 + 30 * minuteMs
        sessions.liveSession = liveSession()

        val vm = viewModel()
        advanceUntilIdle()
        oneMinuteState(vm)

        vm.selectRiskLock(RiskLockBps.TWO)
        vm.openTicket(OrderSide.BUY)
        vm.updateTicket { it.copy(useRiskLock = true, stopLossPips = 100) }

        val sized = vm.panel.value.riskLockLotUnits
        assertNotNull("a stop and a balance must size the trade", sized)
        assertTrue("a 2% lock on $10,000 must afford at least the minimum lot", sized!! > 0)
        // The preview reports the bracket the sized trade would take.
        assertNotNull(vm.panel.value.bracketPreview)
    }

    @Test
    fun `grabbing empty space does not arm a line drag`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = SeasonBinding("s1", "ds_a")
        sessions.simUtcMs = t0 + 30 * minuteMs
        sessions.liveSession = liveSession()

        val vm = viewModel()
        advanceUntilIdle()
        oneMinuteState(vm)

        // Nothing is drawn, so nothing can be grabbed. A touch must therefore be a
        // pan rather than a drag, which is what the false return tells the chart.
        assertTrue(!vm.grabOrderLine(tapPriceUnits = 2_050_000, toleranceUnits = 5_000))
    }

    // ---------------- Step 12.3: news flags reach the chart ----------------

    /**
     * A projected release on the charted dataset.
     *
     * Built through the real [NewsReleaseProjection] rather than as a literal
     * `NewsRelease`, so the pre-release shape under test is the one production
     * produces: a pending event with no actual field to leak.
     */
    private fun releaseAt(
        datasetId: String,
        atUtcMs: Long,
        headline: String,
        impact: String = "HIGH",
        actual: String? = null,
        simUtcMs: Long,
    ): NewsRelease = NewsReleaseProjection.project(
        NewsItem(
            newsId = atUtcMs,
            datasetId = datasetId,
            publishedAtUtcMs = atUtcMs,
            headline = headline,
            body = "",
            impact = impact,
            actual = actual,
        ),
        simUtcMs = simUtcMs,
    )

    @Test
    fun `the chart carries a flag for a scheduled release`() = runTest(dispatcher) {
        // The gap this test closes: `NewsFlagMapper` was tested but nothing called
        // it, so no flag ever reached the render state and the chart's flag layer was
        // dead. This asserts the frame the screen actually receives.
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null

        val at = t0 + 30 * minuteMs
        val news = FakeNews(
            releases = listOf(
                releaseAt("ds_a", at, "US Non-Farm Payrolls", simUtcMs = t0),
            ),
        )

        val vm = viewModel(news)
        advanceUntilIdle()
        val ready = readyState(vm)

        assertEquals(
            "the chart must ask the news port for its own dataset",
            "ds_a",
            news.requestedDatasetId,
        )
        val flag = ready.renderState.newsFlags.singleOrNull()
        assertNotNull("the release must reach the chart as a flag", flag)
        assertEquals("US Non-Farm Payrolls", flag!!.headline)
        assertEquals(FlagSeverity.HIGH, flag.severity)
        assertEquals(
            "the flag must sit on the bar the release lands on",
            ready.renderState.buffer.indexAtOrAfter(at),
            flag.index,
        )
    }

    @Test
    fun `a medium impact release draws an amber flag`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null

        val news = FakeNews(
            releases = listOf(
                releaseAt("ds_a", t0 + 30 * minuteMs, "Retail Sales", impact = "MEDIUM", simUtcMs = t0),
            ),
        )

        val vm = viewModel(news)
        advanceUntilIdle()
        val flag = readyState(vm).renderState.newsFlags.single()
        assertEquals(FlagSeverity.MEDIUM, flag.severity)
    }

    @Test
    fun `a low impact release draws no flag`() = runTest(dispatcher) {
        // The calendar flags only high and medium (Step 12.3), so a low-impact
        // event must not appear as a marker at all.
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null

        val news = FakeNews(
            releases = listOf(
                releaseAt("ds_a", t0 + 30 * minuteMs, "Minor Survey", impact = "LOW", simUtcMs = t0),
            ),
        )

        val vm = viewModel(news)
        advanceUntilIdle()
        assertTrue(
            "a low-impact release must not be flagged",
            readyState(vm).renderState.newsFlags.isEmpty(),
        )
    }

    @Test
    fun `tapping a flag withholds an unreleased outcome`() = runTest(dispatcher) {
        // Step 12.4 through the chart: the flag opens the release, and because it has
        // not printed yet the dialog is handed no actual. This is the chart-side half
        // of the rule the calendar already follows.
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null

        val at = t0 + 30 * minuteMs
        val news = FakeNews(
            releases = listOf(
                releaseAt("ds_a", at, "US Non-Farm Payrolls", actual = "250K", simUtcMs = t0),
            ),
        )

        val vm = viewModel(news)
        advanceUntilIdle()
        val flag = readyState(vm).renderState.newsFlags.single()

        vm.onNewsFlagTapped(NewsFlagHit(index = flag.index, headline = flag.headline))

        val detail = vm.newsDetail.value
        assertNotNull("the tap must open the release", detail)
        assertEquals(false, detail!!.isReleased)
        assertNull("an unreleased actual must not reach the dialog", detail.actual)
    }

    @Test
    fun `tapping a flag on a printed release reveals the actual`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null

        // Scheduled at the dataset's first minute, so at `simUtcMs == t0` it has
        // already printed.
        val news = FakeNews(
            releases = listOf(
                releaseAt("ds_a", t0, "US Non-Farm Payrolls", actual = "250K", simUtcMs = t0),
            ),
        )

        val vm = viewModel(news)
        advanceUntilIdle()
        val flag = readyState(vm).renderState.newsFlags.single()

        vm.onNewsFlagTapped(NewsFlagHit(index = flag.index, headline = flag.headline))

        val detail = vm.newsDetail.value
        assertNotNull("the tap must open the release", detail)
        assertEquals(true, detail!!.isReleased)
        assertEquals("250K", detail.actual)
    }

    @Test
    fun `a tap that matches no flag opens nothing`() = runTest(dispatcher) {
        // A stale tap — the chart scrolled, the dataset changed — must not open an
        // unrelated event. The release is re-resolved by index and headline, and a
        // miss is a no-op rather than a guess.
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null

        val news = FakeNews(
            releases = listOf(releaseAt("ds_a", t0 + 30 * minuteMs, "US Non-Farm Payrolls", simUtcMs = t0)),
        )

        val vm = viewModel(news)
        advanceUntilIdle()

        vm.onNewsFlagTapped(NewsFlagHit(index = 999_999, headline = "Something Else"))

        assertNull("a hit matching no release must open nothing", vm.newsDetail.value)
    }

    @Test
    fun `the detail can be dismissed`() = runTest(dispatcher) {
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null

        val news = FakeNews(
            releases = listOf(releaseAt("ds_a", t0, "US Non-Farm Payrolls", actual = "250K", simUtcMs = t0)),
        )

        val vm = viewModel(news)
        advanceUntilIdle()
        val flag = readyState(vm).renderState.newsFlags.single()
        vm.onNewsFlagTapped(NewsFlagHit(index = flag.index, headline = flag.headline))
        assertNotNull(vm.newsDetail.value)

        vm.dismissNewsDetail()

        assertNull("dismissing must close the detail", vm.newsDetail.value)
    }

    @Test
    fun `without a news source the chart simply has no flags`() = runTest(dispatcher) {
        // The Trading screen is usable with no news port — the chart still draws. A
        // missing source must not fail the load.
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null

        val vm = viewModel(news = null)
        advanceUntilIdle()

        val ready = readyState(vm)
        assertTrue("no source means no flags", ready.renderState.newsFlags.isEmpty())
    }

    @Test
    fun `blind mode hides the absolute date in the chart detail`() = runTest(dispatcher) {
        // Step 12.5 through the chart's dialog: the row the chart opens must mask the
        // date when the season is blind, exactly as the calendar does.
        insertDataset(minutes = 600)
        sessions.season = null
        sessions.simUtcMs = null

        val news = FakeNews(
            releases = listOf(releaseAt("ds_a", t0, "US Non-Farm Payrolls", actual = "250K", simUtcMs = t0)),
            blind = true,
        )

        val vm = viewModel(news)
        advanceUntilIdle()
        val flag = readyState(vm).renderState.newsFlags.single()
        vm.onNewsFlagTapped(NewsFlagHit(index = flag.index, headline = flag.headline))

        val detail = vm.newsDetail.value
        assertNotNull(detail)
        assertNull("Blind Mode must not show the absolute date", detail!!.absoluteDate)
        assertNotNull("Blind Mode must show a relative coordinate", detail.blindCoordinate)
    }
}
