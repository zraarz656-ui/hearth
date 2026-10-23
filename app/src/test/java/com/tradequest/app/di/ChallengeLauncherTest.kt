package com.tradequest.app.di

import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.challenge.ChallengeLaunchResult
import com.tradequest.app.core.challenge.SeasonStatus
import com.tradequest.app.core.database.market.Candle1mEntity
import com.tradequest.app.core.database.market.MarketDatasetEntity
import com.tradequest.app.core.simulation.clock.PlaybackSpeed
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
 * Steps 11.1-11.3, 11.12: starting a challenge binds the season *and* the clock.
 *
 * # Why this drives the real [AppContainer]
 *
 * The bug this pins was a missing call site: the season could be created and the
 * clock could be attached, but nothing did both, so a started challenge could never
 * advance — the chart had no instant to follow and the replay owed nothing. That is
 * a wiring defect, and only a test that runs the real composition root can catch it.
 * A test of `PlayerRepository` or of `SimulationClock` alone passes either way.
 *
 * The container builds its databases from the application context, so this exercises
 * the same construction the app uses; Robolectric gives each test its own files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChallengeLauncherTest {

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

    /** Inserts a dataset with [minutes] one-minute candles starting at [t0]. */
    private suspend fun insertDataset(id: String, minutes: Int = 10, name: String = id) {
        container.marketDatabase.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = id, displayName = name, instrument = "XAUUSD",
                startTimestampUtcMs = t0, endTimestampUtcMs = t0 + minutes * 60_000L,
                candleCount = minutes, contentHash = "h_$id", validationStatus = "VALID",
                sourceDescription = "t", importedAtUtcMs = t0,
            ),
        )
        container.marketDatabase.marketDao().insertCandles(
            (0 until minutes).map { index ->
                Candle1mEntity(
                    datasetId = id,
                    timestampUtcMs = t0 + index * 60_000L,
                    openUnits = 2_000_000, highUnits = 2_000_100,
                    lowUnits = 1_999_900, closeUnits = 2_000_050,
                    tickVolume = 100,
                )
            },
        )
    }

    /** Inserts a dataset row with no candles. */
    private suspend fun insertEmptyDataset(id: String) {
        container.marketDatabase.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = id, displayName = id, instrument = "XAUUSD",
                startTimestampUtcMs = t0, endTimestampUtcMs = t0,
                candleCount = 0, contentHash = "h_$id", validationStatus = "VALID",
                sourceDescription = "t", importedAtUtcMs = t0,
            ),
        )
    }

    @Test
    fun `starting a challenge creates the season and binds the clock`() = runTest {
        insertDataset("ds_a", name = "Gold A")

        val result = container.challengeLauncher.startChallenge("ds_a")

        assertTrue(result is ChallengeLaunchResult.Started)
        val started = result as ChallengeLaunchResult.Started
        assertEquals("Gold A", started.datasetDisplayName)

        // The season exists, is active, and starts at the plan's $10,000.
        val season = container.playerRepository.activeSeason()
        assertNotNull(season)
        assertEquals(started.seasonId, season!!.seasonId)
        assertEquals("ds_a", season.datasetId)
        assertEquals(10_000L * 1_000_000L, season.balanceMicros)
        assertEquals(SeasonStatus.ACTIVE.name, season.status)

        // The clock is bound to that season, which is the half that was missing.
        assertEquals(started.seasonId, container.simulationClock.currentSeasonId)
        assertTrue(container.simulationClock.isAttached)
        // The clock is anchored at the dataset's first candle and derives historical
        // time from the real clock, so a few milliseconds of real time pass between
        // attaching and reading. The instant is therefore at or just after the
        // dataset's start, never before it and never past its end.
        val simNow = container.simulationClock.currentSimUtcMs()!!
        assertTrue("expected the challenge to start at the dataset's start, was $simNow", simNow >= t0)
        assertTrue("expected the challenge to stay inside the dataset", simNow <= t0 + 9 * 60_000L)
    }

    @Test
    fun `a challenge cannot be started on a dataset that has no candles`() = runTest {
        insertEmptyDataset("ds_empty")

        val result = container.challengeLauncher.startChallenge("ds_empty")

        assertTrue(result is ChallengeLaunchResult.DatasetNotReplayable)
        // Nothing was written, so the single-active-season slot is not consumed by a
        // season that could never advance.
        assertNull(container.playerRepository.activeSeason())
    }

    @Test
    fun `a challenge cannot be started on a dataset that does not exist`() = runTest {
        val result = container.challengeLauncher.startChallenge("ds_missing")

        assertTrue(result is ChallengeLaunchResult.DatasetNotFound)
        assertNull(container.playerRepository.activeSeason())
    }

    @Test
    fun `starting over a running challenge asks before abandoning`() = runTest {
        insertDataset("ds_a", name = "Gold A")
        insertDataset("ds_b", name = "Gold B")
        val first = container.challengeLauncher.startChallenge("ds_a") as ChallengeLaunchResult.Started

        val second = container.challengeLauncher.startChallenge("ds_b")

        assertTrue(second is ChallengeLaunchResult.ConfirmationRequired)
        assertEquals(first.seasonId, (second as ChallengeLaunchResult.ConfirmationRequired).existingSeasonId)
        // The running season and its clock binding are untouched.
        assertEquals(first.seasonId, container.playerRepository.activeSeason()!!.seasonId)
        assertEquals(first.seasonId, container.simulationClock.currentSeasonId)
    }

    @Test
    fun `confirming abandons the old season, starts the new one and rebinds the clock`() = runTest {
        insertDataset("ds_a", name = "Gold A")
        insertDataset("ds_b", name = "Gold B")
        val first = container.challengeLauncher.startChallenge("ds_a") as ChallengeLaunchResult.Started

        val second = container.challengeLauncher
            .startChallenge("ds_b", confirmAbandon = true) as ChallengeLaunchResult.Started

        assertEquals(
            SeasonStatus.ABANDONED.name,
            container.playerDatabase.playerDao().getSeason(first.seasonId)!!.status,
        )
        assertEquals(second.seasonId, container.playerRepository.activeSeason()!!.seasonId)
        // The clock followed the switch rather than staying on the old dataset.
        assertEquals(second.seasonId, container.simulationClock.currentSeasonId)
    }

    @Test
    fun `app start rebinds the clock to a season that outlived the process`() = runTest {
        insertDataset("ds_a", name = "Gold A")
        val started = container.challengeLauncher.startChallenge("ds_a") as ChallengeLaunchResult.Started

        // A fresh container with a fresh clock is what a process restart looks like.
        // The season row survives; the clock's in-memory binding does not.
        val restarted = AppContainer(ApplicationProvider.getApplicationContext())
        try {
            assertNull(restarted.simulationClock.currentSeasonId)

            restarted.onAppStart()

            assertEquals(started.seasonId, restarted.simulationClock.currentSeasonId)
            assertTrue(restarted.simulationClock.isAttached)
        } finally {
            restarted.playerDatabase.close()
            restarted.marketDatabase.close()
        }
    }

    @Test
    fun `app start is harmless when no challenge is running`() = runTest {
        container.onAppStart()
        assertNull(container.simulationClock.currentSeasonId)
    }

    @Test
    fun `the challenge clock runs at one times speed`() = runTest {
        // Step 3.3: Challenge Mode is fixed at 1x, enforced by the clock rather than
        // by the caller. A started challenge must not be accelerated.
        insertDataset("ds_a")
        container.challengeLauncher.startChallenge("ds_a")

        assertEquals(PlaybackSpeed.X1, container.simulationClock.read()!!.speed)
    }
}
