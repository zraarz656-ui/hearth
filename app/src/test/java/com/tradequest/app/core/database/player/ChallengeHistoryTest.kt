package com.tradequest.app.core.database.player

import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.core.challenge.ChallengeHistoryEntry
import com.tradequest.app.core.challenge.ChallengeOutcome
import com.tradequest.app.core.challenge.ChallengeScore
import com.tradequest.app.core.challenge.ChallengeTitle
import com.tradequest.app.core.challenge.PersonalBest
import com.tradequest.app.core.challenge.SeasonMode
import com.tradequest.app.core.challenge.SeasonStatus
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.MarketDatasetEntity
import com.tradequest.app.core.database.market.RoomMarketRepository
import kotlinx.coroutines.flow.first
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
 * Steps 11.9-11.12: challenge history, personal bests and replay, through the real
 * Room-backed repositories.
 *
 * # Why these use the database and not a fake
 *
 * The plan's acceptance criteria are about what *survives*: "previous history
 * remains saved", "replay works", "datasets never combine". Each of those is a
 * claim about storage, so a fake would prove only that the fake agrees with itself.
 * These drive `RoomChallengeHistoryRepository` over real SQLite and assert what a
 * subsequent read returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChallengeHistoryTest {

    private lateinit var playerDb: PlayerDatabase
    private lateinit var marketDb: MarketDatabase
    private lateinit var repo: PlayerRepository
    private lateinit var history: RoomChallengeHistoryRepository

    private val t0 = 1_705_276_800_000L
    private val startMicros = 10_000L * 1_000_000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        playerDb = PlayerDatabase.create(ctx)
        marketDb = MarketDatabase.create(ctx)
        repo = PlayerRepository(playerDb, RoomMarketRepository(marketDb))
        history = RoomChallengeHistoryRepository(playerDb)
    }

    @After
    fun tearDown() {
        playerDb.close()
        marketDb.close()
    }

    private suspend fun insertDataset(id: String, name: String = id) {
        marketDb.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = id, displayName = name, instrument = "XAUUSD",
                startTimestampUtcMs = t0, endTimestampUtcMs = t0 + 60_000L,
                candleCount = 0, contentHash = "h_$id", validationStatus = "VALID",
                sourceDescription = "t", importedAtUtcMs = t0,
            ),
        )
    }

    /** Writes a terminal record directly, as the challenge service would. */
    private suspend fun record(
        seasonId: String,
        datasetId: String,
        datasetDisplayName: String,
        status: SeasonStatus,
        outcome: ChallengeOutcome,
        scorePoints: Int,
        ordinal: Int,
        profitMicros: Long,
        completedAtRealUtcMs: Long = t0,
    ) {
        history.record(
            ChallengeHistoryEntry(
                recordId = "record-$seasonId",
                seasonId = seasonId,
                datasetId = datasetId,
                datasetDisplayName = datasetDisplayName,
                mode = SeasonMode.CHALLENGE,
                status = status,
                outcome = outcome,
                startingBalanceMicros = startMicros,
                finalBalanceMicros = startMicros + profitMicros,
                finalEquityMicros = startMicros + profitMicros,
                peakEquityMicros = maxOf(startMicros, startMicros + profitMicros),
                tradingDayCount = 20,
                dailyStrikeCount = 0,
                score = ChallengeScore(scorePoints, 30, 20, 10),
                title = ChallengeTitle.forScore(scorePoints + 60),
                attemptOrdinal = ordinal,
                closedTradeCount = 20,
                startedAtSimUtcMs = t0,
                completedAtSimUtcMs = t0,
                completedAtRealUtcMs = completedAtRealUtcMs,
            ),
        )
    }

    // ---------- Step 11.9, 11.12: history ----------

    @Test
    fun `history is empty before any attempt finishes`() = runTest {
        assertTrue(history.observeHistory().first().isEmpty())
    }

    @Test
    fun `history lists passed, failed and abandoned attempts`() = runTest {
        record("s1", "ds_a", "Gold A", SeasonStatus.PASSED, ChallengeOutcome.TARGET_REACHED, 40, 1, 1_500L * 1_000_000L, t0 + 3)
        record("s2", "ds_a", "Gold A", SeasonStatus.FAILED, ChallengeOutcome.OVERALL_LOSS_BREACHED, 0, 2, -1_000L * 1_000_000L, t0 + 2)
        record("s3", "ds_b", "Gold B", SeasonStatus.ABANDONED, ChallengeOutcome.SUPERSEDED, 10, 1, -200L * 1_000_000L, t0 + 1)

        val listed = history.observeHistory().first()
        assertEquals(3, listed.size)
        // Newest first, by the real completion instant.
        assertEquals(listOf("s1", "s2", "s3"), listed.map { it.seasonId })
        assertEquals(
            setOf(SeasonStatus.PASSED, SeasonStatus.FAILED, SeasonStatus.ABANDONED),
            listed.map { it.status }.toSet(),
        )
    }

    @Test
    fun `history survives the season it describes being deleted`() = runTest {
        insertDataset("ds_a", "Gold A")
        insertDataset("ds_b", "Gold B")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        repo.startSeason("s2", "ds_b", SeasonMode.CHALLENGE, t0, t0, confirmAbandon = true)

        assertEquals(SeasonDeleteResult.Deleted, repo.deleteSeason("s1"))

        val listed = history.observeHistory().first()
        assertEquals(1, listed.size)
        assertEquals("s1", listed.single().seasonId)
        assertEquals(SeasonStatus.ABANDONED, listed.single().status)
    }

    @Test
    fun `recording the same season twice keeps one entry`() = runTest {
        // The dashboard and the replay worker can both notice a season has ended, so
        // the write must be idempotent by season.
        record("s1", "ds_a", "Gold A", SeasonStatus.FAILED, ChallengeOutcome.OVERALL_LOSS_BREACHED, 0, 1, -1_000L * 1_000_000L)
        record("s1", "ds_a", "Gold A", SeasonStatus.FAILED, ChallengeOutcome.OVERALL_LOSS_BREACHED, 0, 1, -1_000L * 1_000_000L)

        assertEquals(1, history.observeHistory().first().size)
    }

    // ---------- Step 11.11, 11.12: personal bests ----------

    @Test
    fun `a dataset with no attempts has no personal best`() = runTest {
        val best = history.personalBest("ds_a")
        assertNull(best.bestOverall)
        assertNull(best.bestFirstAttempt)
        assertEquals(0, best.attemptCount)
    }

    @Test
    fun `the best overall is the highest-scoring attempt of any ordinal`() = runTest {
        record("s1", "ds_a", "Gold A", SeasonStatus.FAILED, ChallengeOutcome.OVERALL_LOSS_BREACHED, 10, 1, -500L * 1_000_000L, t0 + 1)
        record("s2", "ds_a", "Gold A", SeasonStatus.PASSED, ChallengeOutcome.TARGET_REACHED, 40, 2, 1_500L * 1_000_000L, t0 + 2)

        val best = history.personalBest("ds_a")
        assertEquals("s2", best.bestOverall!!.seasonId)
        assertEquals(2, best.attemptCount)
    }

    @Test
    fun `the first-attempt best is kept separate from the overall best`() = runTest {
        // Step 11.11: a trader comparing against their own first try must be able to
        // see it, even when a later attempt scored higher.
        record("s1", "ds_a", "Gold A", SeasonStatus.FAILED, ChallengeOutcome.OVERALL_LOSS_BREACHED, 5, 1, -400L * 1_000_000L, t0 + 1)
        record("s2", "ds_a", "Gold A", SeasonStatus.PASSED, ChallengeOutcome.TARGET_REACHED, 40, 2, 1_500L * 1_000_000L, t0 + 2)

        val best = history.personalBest("ds_a")
        assertEquals("s2", best.bestOverall!!.seasonId)
        assertEquals("s1", best.bestFirstAttempt!!.seasonId)
        assertTrue(best.bestFirstAttempt!!.isFirstAttempt)
    }

    @Test
    fun `personal bests do not mix datasets`() = runTest {
        record("s1", "ds_a", "Gold A", SeasonStatus.PASSED, ChallengeOutcome.TARGET_REACHED, 40, 1, 1_500L * 1_000_000L, t0 + 1)
        record("s2", "ds_b", "Gold B", SeasonStatus.FAILED, ChallengeOutcome.OVERALL_LOSS_BREACHED, 0, 1, -1_000L * 1_000_000L, t0 + 2)

        val a = history.personalBest("ds_a")
        val b = history.personalBest("ds_b")

        assertEquals("s1", a.bestOverall!!.seasonId)
        assertEquals("s2", b.bestOverall!!.seasonId)
        assertEquals(1, a.attemptCount)
        assertEquals(1, b.attemptCount)
    }

    @Test
    fun `best-first ordering breaks a score tie by profit`() = runTest {
        record("s1", "ds_a", "Gold A", SeasonStatus.PASSED, ChallengeOutcome.TARGET_REACHED, 40, 1, 1_000L * 1_000_000L, t0 + 1)
        record("s2", "ds_a", "Gold A", SeasonStatus.PASSED, ChallengeOutcome.TARGET_REACHED, 40, 2, 1_400L * 1_000_000L, t0 + 2)

        val best = history.personalBest("ds_a")
        assertEquals("s2", best.bestOverall!!.seasonId)
    }

    // ---------- Step 11.10, 11.12: replay ----------

    @Test
    fun `replaying a dataset starts a fresh season at the starting balance`() = runTest {
        insertDataset("ds_a", "Gold A")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        // Drive the first attempt to a finished state with real progress.
        playerDb.playerDao().updateSeasonProgress(
            seasonId = "s1", balanceMicros = 11_500_000_000L, equityMicros = 11_500_000_000L,
            peakEquityMicros = 11_500_000_000L, tradingDayCount = 20, dailyStrikeCount = 0,
            scorePoints = 90,
        )
        playerDb.playerDao().updateSeasonStatus("s1", SeasonStatus.PASSED.name, ChallengeOutcome.TARGET_REACHED.name, t0 + 1_000L)

        // The replay: the same dataset, a new season (Step 11.10).
        val replay = repo.startSeason("s1_replay", "ds_a", SeasonMode.CHALLENGE, t0, t0 + 5_000L)
        assertTrue(replay is SeasonStartResult.Started)

        val fresh = playerDb.playerDao().getSeason("s1_replay")!!
        assertEquals("ds_a", fresh.datasetId)
        assertEquals(startMicros, fresh.balanceMicros)
        assertEquals(startMicros, fresh.equityMicros)
        assertEquals(0, fresh.tradingDayCount)
        assertEquals(0, fresh.scorePoints)
        assertEquals("ACTIVE", fresh.status)
    }

    @Test
    fun `replay numbers the attempt and keeps the first distinguishable`() = runTest {
        insertDataset("ds_a", "Gold A")
        // First attempt: abandoned by starting a second season on the same dataset.
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        repo.startSeason("s2", "ds_a", SeasonMode.CHALLENGE, t0, t0 + 1_000L, confirmAbandon = true)

        val best = history.personalBest("ds_a")
        assertEquals(1, best.attemptCount)
        assertEquals(1, best.bestOverall!!.attemptOrdinal)
        assertEquals(1, best.bestFirstAttempt!!.attemptOrdinal)
    }

    @Test
    fun `a replay attempt is recorded with the next ordinal`() = runTest {
        record("s1", "ds_a", "Gold A", SeasonStatus.FAILED, ChallengeOutcome.OVERALL_LOSS_BREACHED, 0, 1, -1_000L * 1_000_000L, t0 + 1)
        record("s2", "ds_a", "Gold A", SeasonStatus.PASSED, ChallengeOutcome.TARGET_REACHED, 40, 2, 1_500L * 1_000_000L, t0 + 2)

        val best = history.personalBest("ds_a")
        assertEquals(2, best.attemptCount)
        assertEquals(2, best.bestOverall!!.attemptOrdinal)
        assertEquals(1, best.bestFirstAttempt!!.attemptOrdinal)
    }

    // ---------- Step 11.12: dataset isolation ----------

    @Test
    fun `a season's progress never appears in another dataset's season`() = runTest {
        insertDataset("ds_a", "Gold A")
        insertDataset("ds_b", "Gold B")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        playerDb.playerDao().updateSeasonProgress(
            seasonId = "s1", balanceMicros = 12_000_000_000L, equityMicros = 12_000_000_000L,
            peakEquityMicros = 12_000_000_000L, tradingDayCount = 9, dailyStrikeCount = 2,
            scorePoints = 55,
        )

        repo.startSeason("s2", "ds_b", SeasonMode.CHALLENGE, t0, t0 + 1_000L, confirmAbandon = true)

        val next = playerDb.playerDao().getSeason("s2")!!
        assertEquals("ds_b", next.datasetId)
        assertEquals(startMicros, next.balanceMicros)
        assertEquals(0, next.tradingDayCount)
        assertEquals(0, next.dailyStrikeCount)
        assertEquals(0, next.scorePoints)
    }

    @Test
    fun `only one season is active after a replay`() = runTest {
        insertDataset("ds_a", "Gold A")
        repo.startSeason("s1", "ds_a", SeasonMode.CHALLENGE, t0, t0)
        playerDb.playerDao().updateSeasonStatus("s1", SeasonStatus.FAILED.name, ChallengeOutcome.OVERALL_LOSS_BREACHED.name, t0 + 1)
        repo.startSeason("s2", "ds_a", SeasonMode.CHALLENGE, t0, t0 + 2_000L)

        val active = listOfNotNull(
            playerDb.playerDao().getSeason("s1"),
            playerDb.playerDao().getSeason("s2"),
        ).filter { it.status == "ACTIVE" }
        assertEquals(1, active.size)
        assertEquals("s2", active.single().seasonId)
    }
}
