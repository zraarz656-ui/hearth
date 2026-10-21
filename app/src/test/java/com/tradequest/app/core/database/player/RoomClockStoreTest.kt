package com.tradequest.app.core.database.player

import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.simulation.clock.ClockAnchor
import com.tradequest.app.core.simulation.clock.ClockMode
import com.tradequest.app.core.simulation.clock.PlaybackSpeed
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 3.5: the clock anchor survives a database round trip.
 *
 * These run against real SQLite through Room, not an in-memory fake, because
 * the point is to prove the mapping between [ClockAnchor] and the
 * `ReplayCheckpoint` row is lossless. A fake would only prove the fake works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomClockStoreTest {

    private lateinit var db: PlayerDatabase
    private lateinit var store: RoomClockStore

    private val sim0 = 1_705_276_800_000L
    private val real0 = 1_800_000_000_000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = PlayerDatabase.create(ctx)
        store = RoomClockStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertSeason(id: String, mode: String = "PRACTICE") {
        db.playerDao().insertSeason(
            SeasonEntity(
                seasonId = id,
                datasetId = "ds_test",
                mode = mode,
                status = "ACTIVE",
                startingBalanceMicros = 10_000_000_000L,
                balanceMicros = 10_000_000_000L,
                equityMicros = 10_000_000_000L,
                peakEquityMicros = 10_000_000_000L,
                startedAtSimUtcMs = sim0,
                createdAtRealUtcMs = real0,
                completedAtSimUtcMs = null,
                tradingDayCount = 0,
                dailyStrikeCount = 0,
                scorePoints = 0,
                outcomeReason = null,
            ),
        )
    }

    @Test
    fun `an anchor round-trips without loss`() = runTest {
        insertSeason("s1")
        val anchor = ClockAnchor(
            historicalAnchorSimUtcMs = sim0 + 123_456L,
            realAnchorUtcMs = real0 + 654_321L,
            mode = ClockMode.PRACTICE,
            speed = PlaybackSpeed.X30,
            isPaused = true,
            lastCommittedSimUtcMs = sim0 + 100_000L,
        )

        store.save("s1", anchor)

        assertEquals(anchor, store.load("s1"))
    }

    @Test
    fun `loading a season with no checkpoint returns null`() = runTest {
        insertSeason("s1")
        assertNull(store.load("s1"))
    }

    @Test
    fun `loading an unknown season returns null`() = runTest {
        assertNull(store.load("no-such-season"))
    }

    @Test
    fun `saving twice replaces rather than accumulates`() = runTest {
        insertSeason("s1")
        val first = ClockAnchor(
            historicalAnchorSimUtcMs = sim0,
            realAnchorUtcMs = real0,
            mode = ClockMode.PRACTICE,
            speed = PlaybackSpeed.X1,
            isPaused = false,
            lastCommittedSimUtcMs = sim0,
        )
        val second = first.copy(
            historicalAnchorSimUtcMs = sim0 + 60_000L,
            speed = PlaybackSpeed.X10,
        )

        store.save("s1", first)
        store.save("s1", second)

        assertEquals(second, store.load("s1"))
        assertEquals(1, db.playerDao().countCheckpoints("s1"))
    }

    @Test
    fun `an unknown stored speed falls back to 1x`() = runTest {
        insertSeason("s1")
        db.playerDao().upsertCheckpoint(
            ReplayCheckpointEntity(
                seasonId = "s1",
                lastCommittedSimUtcMs = sim0,
                historicalAnchorSimUtcMs = sim0,
                realAnchorUtcMs = real0,
                playbackSpeed = 7, // not a supported multiplier
                isPaused = false,
                updatedAtRealUtcMs = real0,
            ),
        )

        assertEquals(PlaybackSpeed.X1, store.load("s1")!!.speed)
    }

    @Test
    fun `the clock mode follows the season mode`() = runTest {
        insertSeason("challenge-season", mode = "CHALLENGE")
        insertSeason("practice-season", mode = "PRACTICE")
        val anchor = ClockAnchor(
            historicalAnchorSimUtcMs = sim0,
            realAnchorUtcMs = real0,
            mode = ClockMode.PRACTICE,
            speed = PlaybackSpeed.X1,
            isPaused = false,
            lastCommittedSimUtcMs = sim0,
        )
        store.save("challenge-season", anchor)
        store.save("practice-season", anchor)

        assertEquals(ClockMode.CHALLENGE, store.load("challenge-season")!!.mode)
        assertEquals(ClockMode.PRACTICE, store.load("practice-season")!!.mode)
    }

    @Test
    fun `delete removes the checkpoint`() = runTest {
        insertSeason("s1")
        store.save(
            "s1",
            ClockAnchor(
                historicalAnchorSimUtcMs = sim0,
                realAnchorUtcMs = real0,
                mode = ClockMode.PRACTICE,
                speed = PlaybackSpeed.X1,
                isPaused = false,
                lastCommittedSimUtcMs = sim0,
            ),
        )

        store.delete("s1")

        assertNull(store.load("s1"))
    }

    @Test
    fun `an unknown season mode maps to CHALLENGE, the conservative choice`() {
        // Challenge Mode is locked to 1x, so an unrecognised mode cannot let a
        // season run fast.
        assertEquals(ClockMode.CHALLENGE, RoomClockStore.clockModeOf("SOMETHING_NEW"))
        assertEquals(ClockMode.CHALLENGE, RoomClockStore.clockModeOf("CHALLENGE"))
        assertEquals(ClockMode.PRACTICE, RoomClockStore.clockModeOf("PRACTICE"))
    }

    @Test
    fun `a checkpoint for a deleted season is removed by cascade`() = runTest {
        insertSeason("s1")
        store.save(
            "s1",
            ClockAnchor(
                historicalAnchorSimUtcMs = sim0,
                realAnchorUtcMs = real0,
                mode = ClockMode.PRACTICE,
                speed = PlaybackSpeed.X1,
                isPaused = false,
                lastCommittedSimUtcMs = sim0,
            ),
        )

        db.playerDao().deleteSeason("s1")

        assertEquals(0, db.playerDao().countCheckpoints("s1"))
        assertNull(db.playerDao().checkpoint("s1"))
    }

    @Test
    fun `an anchor for a season row that does not exist is not returned`() = runTest {
        // The checkpoint has a foreign key to Season, so this cannot be written
        // through the DAO. Assert the foreign key is actually enforced instead.
        val error = runCatching {
            db.playerDao().upsertCheckpoint(
                ReplayCheckpointEntity(
                    seasonId = "orphan",
                    lastCommittedSimUtcMs = sim0,
                    historicalAnchorSimUtcMs = sim0,
                    realAnchorUtcMs = real0,
                    playbackSpeed = 1,
                    isPaused = false,
                    updatedAtRealUtcMs = real0,
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            "a checkpoint without a season must be rejected by the foreign key",
            error != null,
        )
    }
}
