package com.tradequest.app.core.simulation.clock

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 3.9 tests: clock progression, pause/resume, playback speed, restart
 * recovery, and the dataset endpoint.
 *
 * Every test drives time by hand through [TestTimeSource], so no test sleeps
 * and none depends on wall-clock timing.
 */
class SimulationClockTest {

    private val datasetStart = 1_700_000_000_000L
    private val datasetEnd = datasetStart + 90L * 24 * 60 * 60 * 1000

    private fun bounds() = DatasetBounds(datasetStart, datasetEnd)

    private fun fixture(
        startWall: Long = 1_800_000_000_000L,
        mode: ClockMode = ClockMode.PRACTICE,
        speed: PlaybackSpeed = PlaybackSpeed.X1,
    ): Triple<SimulationClock, TestTimeSource, InMemoryClockStore> {
        val time = TestTimeSource(startWall)
        val store = InMemoryClockStore()
        val clock = SimulationClock(store, time, time)
        return Triple(clock, time, store)
    }

    private suspend fun SimulationClock.attachPractice(
        startSim: Long = datasetStart,
        speed: PlaybackSpeed = PlaybackSpeed.X1,
    ) = attach(
        seasonId = SEASON,
        bounds = bounds(),
        startSimUtcMs = startSim,
        mode = ClockMode.PRACTICE,
        speed = speed,
    )

    // ---------- Progression ----------

    @Test
    fun `reading before attach is null`() {
        val (clock, _, _) = fixture()
        assertNull(clock.read())
        assertNull(clock.currentSimUtcMs())
        assertFalse(clock.isAttached)
    }

    @Test
    fun `historical time advances at 1x real elapsed time`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice()

        time.advance(60_000L)

        assertEquals(datasetStart + 60_000L, clock.currentSimUtcMs())
    }

    @Test
    fun `historical time advances at the configured multiplier`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice(speed = PlaybackSpeed.X60)

        time.advance(1_000L)

        assertEquals(datasetStart + 60_000L, clock.currentSimUtcMs())
    }

    @Test
    fun `reading twice without advancing real time is stable`() = runTest {
        val (clock, _, _) = fixture()
        clock.attachPractice()

        assertEquals(clock.currentSimUtcMs(), clock.currentSimUtcMs())
    }

    // ---------- Pause and resume ----------

    @Test
    fun `paused clock does not advance`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice()
        time.advance(10_000L)

        clock.pause()
        val frozen = clock.currentSimUtcMs()
        time.advance(3_600_000L)

        assertEquals(frozen, clock.currentSimUtcMs())
    }

    @Test
    fun `time spent paused is not credited on resume`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice()
        time.advance(10_000L)

        clock.pause()
        val frozen = clock.currentSimUtcMs()
        time.advance(3_600_000L)
        clock.resume()
        time.advance(5_000L)

        // Only the 5s after resuming counts, not the hour paused.
        assertEquals(frozen!! + 5_000L, clock.currentSimUtcMs())
    }

    @Test
    fun `pause reports the paused stop reason`() = runTest {
        val (clock, _, _) = fixture()
        clock.attachPractice()
        clock.pause()

        assertEquals(ClockStopReason.PAUSED, clock.read()!!.stopReason)
        assertFalse(clock.read()!!.isAdvancing)
    }

    @Test
    fun `pausing twice keeps the first frozen moment`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice()
        time.advance(10_000L)
        clock.pause()
        val frozen = clock.currentSimUtcMs()

        time.advance(60_000L)
        clock.pause()

        assertEquals(frozen, clock.currentSimUtcMs())
    }

    // ---------- Playback speed ----------

    @Test
    fun `changing speed does not retroactively rescale elapsed time`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice(speed = PlaybackSpeed.X1)
        time.advance(600_000L) // 10 real minutes at 1x

        clock.setSpeed(PlaybackSpeed.X60)
        val afterSwitch = clock.currentSimUtcMs()

        // The 10 minutes already elapsed stay 10 minutes, not 600.
        assertEquals(datasetStart + 600_000L, afterSwitch)

        time.advance(1_000L)
        assertEquals(afterSwitch!! + 60_000L, clock.currentSimUtcMs())
    }

    @Test
    fun `challenge mode refuses a speed other than 1x`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clock = SimulationClock(InMemoryClockStore(), time, time)
        clock.attach(SEASON, bounds(), datasetStart, ClockMode.CHALLENGE)

        assertFalse(clock.setSpeed(PlaybackSpeed.X60))
        assertEquals(PlaybackSpeed.X1, clock.read()!!.speed)

        time.advance(60_000L)
        assertEquals(datasetStart + 60_000L, clock.currentSimUtcMs())
    }

    @Test
    fun `challenge mode accepts 1x`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clock = SimulationClock(InMemoryClockStore(), time, time)
        clock.attach(SEASON, bounds(), datasetStart, ClockMode.CHALLENGE)

        assertTrue(clock.setSpeed(PlaybackSpeed.X1))
    }

    @Test
    fun `a stored 60x is downgraded to 1x for a challenge season`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val store = InMemoryClockStore()
        store.save(
            SEASON,
            ClockAnchor(
                historicalAnchorSimUtcMs = datasetStart,
                realAnchorUtcMs = 1_800_000_000_000L,
                mode = ClockMode.CHALLENGE,
                speed = PlaybackSpeed.X60,
                isPaused = false,
                lastCommittedSimUtcMs = datasetStart,
            ),
        )
        val clock = SimulationClock(store, time, time)
        clock.attach(SEASON, bounds(), datasetStart, ClockMode.CHALLENGE)

        assertEquals(PlaybackSpeed.X1, clock.read()!!.speed)
    }

    @Test
    fun `every documented practice speed is accepted`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice()

        for (speed in PlaybackSpeed.entries) {
            assertTrue("speed $speed must be accepted", clock.setSpeed(speed))
            val before = clock.currentSimUtcMs()!!
            time.advance(1_000L)
            assertEquals(
                "speed $speed advanced the wrong amount",
                before + speed.multiplier * 1_000L,
                clock.currentSimUtcMs(),
            )
        }
    }

    // ---------- Restart recovery (Step 3.3) ----------

    @Test
    fun `closing the app does not pause the season`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val store = InMemoryClockStore()
        SimulationClock(store, time, time).attachPractice()

        // The app is closed: no reads, no writes, just real time passing.
        time.advance(3_600_000L)

        val restarted = SimulationClock(store, time, time)
        restarted.attach(SEASON, bounds(), datasetStart, ClockMode.PRACTICE)

        assertEquals(datasetStart + 3_600_000L, restarted.currentSimUtcMs())
    }

    @Test
    fun `a restored season keeps its own position, not the dataset start`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val store = InMemoryClockStore()
        val first = SimulationClock(store, time, time)
        first.attachPractice()
        time.advance(120_000L)

        val restarted = SimulationClock(store, time, time)
        restarted.attach(SEASON, bounds(), datasetStart, ClockMode.PRACTICE)

        assertEquals(datasetStart + 120_000L, restarted.currentSimUtcMs())
    }

    @Test
    fun `elapsed time while closed is reported as pending work`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val store = InMemoryClockStore()
        SimulationClock(store, time, time).attachPractice()
        time.advance(3_600_000L)

        val restarted = SimulationClock(store, time, time)
        restarted.attach(SEASON, bounds(), datasetStart, ClockMode.PRACTICE)

        val reading = restarted.read()!!
        assertEquals(3_600_000L, reading.pendingSimMs)
        assertEquals(datasetStart, reading.lastCommittedSimUtcMs)
    }

    @Test
    fun `a device clock jumping backwards does not rewind the simulation`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val store = InMemoryClockStore()
        val clock = SimulationClock(store, time, time)
        clock.attachPractice()
        time.advance(60_000L)
        val beforeJump = clock.currentSimUtcMs()

        time.shiftWallClock(-600_000L)

        assertEquals(beforeJump, clock.currentSimUtcMs())
    }

    // ---------- Dataset endpoint (Step 3.8) ----------

    @Test
    fun `advancing past the dataset end stops at the endpoint`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice(startSim = datasetEnd - 1_000L)

        time.advance(600_000L)

        assertEquals(datasetEnd, clock.currentSimUtcMs())
        assertEquals(ClockStopReason.DATASET_END, clock.read()!!.stopReason)
        assertTrue(clock.read()!!.atDatasetEnd)
    }

    @Test
    fun `a huge real interval cannot overflow past the endpoint`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice(speed = PlaybackSpeed.X60)

        // A device clock set far into the future.
        time.shiftWallClock(100_000_000_000_000L)

        assertEquals(datasetEnd, clock.currentSimUtcMs())
    }

    @Test
    fun `advanceBySimMs warps simulated time without real time passing`() = runTest {
        val (clock, _, _) = fixture()
        clock.attachPractice()

        clock.advanceBySimMs(30L * 60_000L)

        assertEquals(datasetStart + 30L * 60_000L, clock.currentSimUtcMs())
    }

    @Test
    fun `a warp past the endpoint stops at the endpoint`() = runTest {
        val (clock, _, _) = fixture()
        clock.attachPractice()

        clock.advanceBySimMs(1_000L * 24 * 60 * 60 * 1000)

        assertEquals(datasetEnd, clock.currentSimUtcMs())
    }

    @Test
    fun `seekTo is clamped to the dataset`() = runTest {
        val (clock, _, _) = fixture()
        clock.attachPractice()

        clock.seekTo(datasetStart - 1_000_000L)
        assertEquals(datasetStart, clock.currentSimUtcMs())

        clock.seekTo(datasetEnd + 1_000_000L)
        assertEquals(datasetEnd, clock.currentSimUtcMs())
    }

    @Test
    fun `a stored anchor outside the dataset is clamped on restore`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val store = InMemoryClockStore()
        store.save(
            SEASON,
            ClockAnchor(
                historicalAnchorSimUtcMs = datasetEnd + 50_000_000L,
                realAnchorUtcMs = 1_800_000_000_000L,
                mode = ClockMode.PRACTICE,
                speed = PlaybackSpeed.X1,
                isPaused = false,
                lastCommittedSimUtcMs = datasetEnd + 50_000_000L,
            ),
        )
        val clock = SimulationClock(store, time, time)
        clock.attach(SEASON, bounds(), datasetStart, ClockMode.PRACTICE)

        assertEquals(datasetEnd, clock.currentSimUtcMs())
    }

    // ---------- Commit ----------

    @Test
    fun `commit narrows pending work but never moves historical time`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice()
        time.advance(600_000L)

        assertTrue(clock.commit(datasetStart + 300_000L))

        val reading = clock.read()!!
        assertEquals(datasetStart + 600_000L, reading.simUtcMs)
        assertEquals(datasetStart + 300_000L, reading.lastCommittedSimUtcMs)
        assertEquals(300_000L, reading.pendingSimMs)
    }

    @Test
    fun `commit beyond the current historical time is refused`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice()
        time.advance(10_000L)

        assertFalse(clock.commit(datasetStart + 60_000L))
    }

    @Test
    fun `commit never moves backwards`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice()
        time.advance(600_000L)
        clock.commit(datasetStart + 300_000L)

        clock.commit(datasetStart + 100_000L)

        assertEquals(datasetStart + 300_000L, clock.read()!!.lastCommittedSimUtcMs)
    }

    @Test
    fun `controls before attach report failure rather than throwing`() = runTest {
        val (clock, _, _) = fixture()

        assertFalse(clock.pause())
        assertFalse(clock.resume())
        assertFalse(clock.setSpeed(PlaybackSpeed.X2))
        assertFalse(clock.advanceBySimMs(1_000L))
        assertFalse(clock.seekTo(datasetStart))
        assertFalse(clock.commit(datasetStart))
    }

    // ---------- Persistence ----------

    @Test
    fun `every control persists the anchor`() = runTest {
        val (clock, time, store) = fixture()
        clock.attachPractice()

        time.advance(60_000L)
        clock.pause()
        val paused = store.load(SEASON)!!

        assertTrue(paused.isPaused)
        assertEquals(datasetStart + 60_000L, paused.historicalAnchorSimUtcMs)
    }

    @Test
    fun `detach stops serving reads`() = runTest {
        val (clock, _, _) = fixture()
        clock.attachPractice()

        clock.detach()

        assertNull(clock.read())
        assertFalse(clock.isAttached)
        assertNull(clock.currentSeasonId)
    }

    @Test
    fun `attaching a second season replaces the first`() = runTest {
        val time = TestTimeSource(1_800_000_000_000L)
        val clock = SimulationClock(InMemoryClockStore(), time, time)
        clock.attach("season-a", bounds(), datasetStart, ClockMode.PRACTICE)
        time.advance(60_000L)

        clock.attach("season-b", bounds(), datasetStart, ClockMode.PRACTICE)

        assertEquals("season-b", clock.currentSeasonId)
        assertEquals(datasetStart, clock.currentSimUtcMs())
    }

    @Test
    fun `pending time is never negative`() = runTest {
        val (clock, time, _) = fixture()
        clock.attachPractice()
        time.advance(600_000L)
        clock.commit(datasetStart + 600_000L)

        assertEquals(0L, clock.read()!!.pendingSimMs)
        assertNotNull(clock.read())
    }

    private companion object {
        const val SEASON = "season-1"
    }
}
