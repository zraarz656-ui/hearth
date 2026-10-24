package com.tradequest.app.di

import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.challenge.ChallengeLaunchResult
import com.tradequest.app.core.database.market.Candle1mEntity
import com.tradequest.app.core.database.market.MarketDatasetEntity
import com.tradequest.app.core.trading.CommandResult
import com.tradequest.app.core.trading.OrderSide
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
 * Phase 10, Step 10.10: the live session the *app* builds is tradable.
 *
 * # Why this drives the real [AppContainer]
 *
 * `LiveTradingSessionTest` proves the session class works, but it constructs the
 * session itself from a fake market repository and a hand-built
 * [com.tradequest.app.core.trading.TradingContext]. The app never does that: it
 * goes through `TradingSessionSource.createLiveSession`, which reads the season,
 * resolves the dataset's bounds and news, and assembles the context at the
 * composition root. Nothing exercised that path.
 *
 * That is the same shape of gap as the Phase 11 launcher bug: each half works and
 * the join between them is untested. A wiring defect here would show as a Trading
 * screen that opens to an empty chart, or a Buy that silently does nothing, and no
 * existing test would fail. This test runs the real container against a seeded
 * dataset and trades through the session the app would actually use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TradingSessionWiringTest {

    private lateinit var container: AppContainer

    private val t0 = 1_705_276_800_000L

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        container.playerDatabase.close()
        container.marketDatabase.close()
    }

    /**
     * Inserts a dataset whose candles drift upward, so a market buy opens at a
     * known price and the position can be marked to market.
     */
    private suspend fun insertRisingDataset(id: String, minutes: Int = 30) {
        container.marketDatabase.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = id, displayName = id, instrument = "XAUUSD",
                startTimestampUtcMs = t0, endTimestampUtcMs = t0 + (minutes - 1) * 60_000L,
                candleCount = minutes, contentHash = "h_$id", validationStatus = "VALID",
                sourceDescription = "t", importedAtUtcMs = t0,
            ),
        )
        container.marketDatabase.marketDao().insertCandles(
            (0 until minutes).map { index ->
                val open = 2_000_000 + index * 1_000
                Candle1mEntity(
                    datasetId = id,
                    timestampUtcMs = t0 + index * 60_000L,
                    openUnits = open,
                    highUnits = open + 500,
                    lowUnits = open - 500,
                    closeUnits = open + 400,
                    tickVolume = 100,
                )
            },
        )
    }

    /** Starts a challenge on a seeded dataset, the way the Market Library does. */
    private suspend fun startChallenge(id: String): String {
        insertRisingDataset(id)
        val result = container.challengeLauncher.startChallenge(id)
        assertTrue("expected the challenge to start, got $result", result is ChallengeLaunchResult.Started)
        return (result as ChallengeLaunchResult.Started).seasonId
    }

    // ------------------------------------------------ Step 10.1: the session opens

    @Test
    fun `the app's live session opens over the active season's dataset`() = runTest {
        val seasonId = startChallenge("ds_live")

        val session = container.tradingSessionSource.createLiveSession()

        assertNotNull("the container must build a session for an active season", session)
        val state = session!!.open()
        assertNotNull(state)
        assertEquals(seasonId, state!!.seasonId)
        // The plan's $10,000 start, read through the port rather than passed in.
        assertEquals(10_000L * 1_000_000L, state.balanceMicros)
        // Opening seeds at the dataset's first candle.
        assertEquals(t0, state.processedToSimUtcMs)
    }

    @Test
    fun `the app's live session is null when no season is running`() = runTest {
        assertNull(container.tradingSessionSource.createLiveSession())
    }

    @Test
    fun `the app's live session is null for a season on an empty dataset`() = runTest {
        // The launcher refuses to start on an empty dataset, so this season is
        // inserted directly: the point is that createLiveSession itself refuses
        // rather than handing back a session that can never price anything.
        container.playerDatabase.playerDao().insertSeason(
            com.tradequest.app.core.database.player.SeasonEntity(
                seasonId = "s_empty", datasetId = "ds_empty", mode = "CHALLENGE",
                status = "ACTIVE", startingBalanceMicros = 10_000_000_000L,
                balanceMicros = 10_000_000_000L, equityMicros = 10_000_000_000L,
                peakEquityMicros = 10_000_000_000L, startedAtSimUtcMs = t0,
                createdAtRealUtcMs = t0, completedAtSimUtcMs = null,
                tradingDayCount = 0, dailyStrikeCount = 0, scorePoints = 0,
                outcomeReason = null,
            ),
        )
        container.marketDatabase.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = "ds_empty", displayName = "empty", instrument = "XAUUSD",
                startTimestampUtcMs = t0, endTimestampUtcMs = t0, candleCount = 0,
                contentHash = "h_e", validationStatus = "VALID",
                sourceDescription = "t", importedAtUtcMs = t0,
            ),
        )

        assertNull(container.tradingSessionSource.createLiveSession())
    }

    // ------------------------------------------- Step 10.1: trading through it

    @Test
    fun `a market buy through the app's session opens a real position`() = runTest {
        startChallenge("ds_live")
        val session = container.tradingSessionSource.createLiveSession()!!
        session.open()
        session.advanceTo(t0 + 2 * 60_000L)

        val result = session.placeMarket(
            orderId = "o1",
            side = OrderSide.BUY,
            volumeHundredths = 10,
            atSimUtcMs = t0 + 2 * 60_000L,
        )

        assertTrue("expected acceptance, got $result", result is CommandResult.Accepted)
        assertEquals(1, session.openPositions().size)
        assertEquals(OrderSide.BUY, session.openPositions().first().side)
    }

    @Test
    fun `closing a position through the app's session realises a profit`() = runTest {
        startChallenge("ds_live")
        val session = container.tradingSessionSource.createLiveSession()!!
        session.open()
        val entry = t0 + 2 * 60_000L
        session.advanceTo(entry)
        session.placeMarket("o1", OrderSide.BUY, 10, entry)

        // Advance well past entry: the rising series moves the position into profit.
        val exit = t0 + 10 * 60_000L
        session.advanceTo(exit)
        val closed = session.closePosition(
            positionId = session.openPositions().first().positionId,
            atSimUtcMs = exit,
        )

        assertTrue("expected the close to be accepted, got $closed", closed is CommandResult.Accepted)
        assertEquals(0, session.openPositions().size)
        assertEquals(1, session.closedTrades().size)
        assertTrue(
            "a buy in a rising market must profit",
            session.closedTrades().first().netPnlMicros > 0,
        )
    }

    @Test
    fun `the app's session and the replay path price from the same dataset`() = runTest {
        // The live session and the replay processor are built from the same season
        // and dataset at the composition root, so a trade placed live must land in
        // the same balance the replay store then reports.
        val seasonId = startChallenge("ds_live")
        val session = container.tradingSessionSource.createLiveSession()!!
        session.open()
        val entry = t0 + 2 * 60_000L
        session.advanceTo(entry)
        session.placeMarket("o1", OrderSide.BUY, 10, entry)

        // The session persists its state, so the store the replay path reads is the
        // same one the live path wrote.
        val stored = container.replayStore.load(seasonId)
        assertNotNull("the live session must commit its state", stored)
        assertEquals(entry, stored!!.processedToSimUtcMs)
    }

    // ------------------------------- Step 11.5-11.9: the challenge sync through the port

    @Test
    fun `syncChallenge evaluates and persists the running season`() = runTest {
        val seasonId = startChallenge("ds_live")
        val session = container.tradingSessionSource.createLiveSession()!!
        session.open()
        val entry = t0 + 2 * 60_000L
        session.advanceTo(entry)
        session.placeMarket("o1", OrderSide.BUY, 10, entry)
        val exit = t0 + 10 * 60_000L
        session.advanceTo(exit)
        session.closePosition(session.openPositions().first().positionId, exit)

        val state = container.replayStore.load(seasonId)!!
        val account = session.account()

        val evaluation = container.tradingSessionSource.syncChallenge(
            tradingState = state,
            account = account,
            equityMicros = account.equityMicros,
            atSimUtcMs = exit,
        )

        assertNotNull("a running season must evaluate", evaluation)
        // The profit is real and positive, so the stored season's balance must have
        // moved with it — the write actually happened.
        val season = container.playerDatabase.playerDao().getSeason(seasonId)!!
        assertEquals(account.balanceMicros, season.balanceMicros)
        assertTrue(
            "the stored balance must reflect the realised profit",
            season.balanceMicros > 10_000_000_000L,
        )
    }

    @Test
    fun `syncChallenge reports nothing for a terminal season`() = runTest {
        val seasonId = startChallenge("ds_live")
        container.playerDatabase.playerDao().updateSeasonStatus(
            seasonId = seasonId,
            status = "ABANDONED",
            reason = "ABANDONED",
            completedAtSimUtcMs = null,
        )

        val evaluation = container.tradingSessionSource.syncChallenge(
            tradingState = container.replayStore.load(seasonId)
                ?: com.tradequest.app.core.trading.TradingState(
                    seasonId = seasonId, balanceMicros = 10_000_000_000L,
                    orders = emptyList(), positions = emptyList(),
                    executions = emptyList(), closedTrades = emptyList(),
                    processedToSimUtcMs = t0,
                ),
            account = com.tradequest.app.core.trading.AccountState(
                seasonId = seasonId, balanceMicros = 10_000_000_000L,
                unrealizedPnlMicros = 0L,
            ),
            equityMicros = 10_000_000_000L,
            atSimUtcMs = t0,
        )

        assertNull("a terminal season must not be re-evaluated", evaluation)
    }

    // --------------------------------- Step 12.7: position processing during a warp

    @Test
    fun `a warp processes the intervening market so a bracket is filled`() = runTest {
        // Journey D: a position is open with a target, the clock warps forward, and
        // the price movement in between must be evaluated — not skipped. A warp that
        // only moved the clock would leave the position open at the end, which is the
        // failure the plan calls "trading consequences skipped".
        val seasonId = startChallenge("ds_live")
        val session = container.tradingSessionSource.createLiveSession()!!
        session.open()
        val entry = t0 + 2 * 60_000L
        session.advanceTo(entry)

        // The seeded series rises $1.00 per minute, so a buy with a 3-pip target is
        // reached within a few intervening bars.
        val opened = session.placeMarket(
            orderId = "o1",
            side = OrderSide.BUY,
            volumeHundredths = 10,
            atSimUtcMs = entry,
            takeProfitPips = 3,
        )
        assertTrue("expected acceptance, got $opened", opened is CommandResult.Accepted)
        assertEquals(1, session.openPositions().size)

        // Warp forward through the same call the News screen uses.
        val destination = t0 + 25 * 60_000L
        val reached = container.newsScheduleSource.performWarp(
            com.tradequest.app.core.simulation.warp.WarpTarget.SkipWeekend(
                atUtcMs = destination,
                isBeyondDataset = false,
            ),
        )
        assertNotNull("the warp must move the clock", reached)

        // The target was processed during the catch-up: the trade is closed, in
        // profit, and says why. That is the evidence the intervening bars were
        // evaluated rather than the jump being a bare clock write.
        val stored = container.replayStore.load(seasonId)!!
        assertTrue(
            "the warp must have processed up to the destination",
            stored.processedToSimUtcMs >= destination,
        )
        val closed = stored.closedTrades.singleOrNull()
        assertNotNull("the bracket must have filled during the warp", closed)
        assertEquals(
            com.tradequest.app.core.trading.CloseReason.TAKE_PROFIT,
            closed!!.closeReason,
        )
        assertTrue("a filled target must realise a profit", closed.netPnlMicros > 0)
    }

    @Test
    fun `a challenge with exposure refuses the warp, and a flat one allows it`() = runTest {
        // Step 12.7's two halves, through the app's own permission read rather than
        // a hand-built call: the counts must come from the live session.
        startChallenge("ds_live")
        val session = container.tradingSessionSource.createLiveSession()!!
        session.open()

        assertEquals(
            "a flat challenge must allow the warp",
            com.tradequest.app.core.simulation.warp.WarpPermission.Allowed,
            container.newsScheduleSource.warpPermission(),
        )

        val entry = t0 + 2 * 60_000L
        session.advanceTo(entry)
        session.placeMarket("o1", OrderSide.BUY, 10, entry)

        val permission = container.newsScheduleSource.warpPermission()
        assertTrue(
            "a challenge with an open position must refuse the warp, was $permission",
            permission is com.tradequest.app.core.simulation.warp.WarpPermission.RefusedExposure,
        )
    }
}
