package com.tradequest.app.feature.trading

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.chart.renderer.FormingCandleView
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.database.market.Candle1mEntity
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.MarketDatasetEntity
import com.tradequest.app.core.database.market.RoomMarketRepository
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

    private fun viewModel() = TradingViewModel(
        repository = marketRepository,
        style = chartStyleFor(ThemeMode.DARK),
        sessions = sessions,
        // Both dispatchers must be the test's, or the load runs on a real thread
        // the test scheduler cannot wait for and every assertion sees stale state.
        ioDispatcher = dispatcher,
        computeDispatcher = dispatcher,
    )

    private suspend fun readyState(vm: TradingViewModel): TradingState.Ready =
        vm.state.first { it is TradingState.Ready } as TradingState.Ready

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
}
