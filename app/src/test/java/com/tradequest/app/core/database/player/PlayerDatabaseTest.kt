package com.tradequest.app.core.database.player

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 2.8 player database tests.
 *
 * Real Room code and real SQLite on the JVM through Robolectric. No mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerDatabaseTest {

    private lateinit var db: PlayerDatabase
    private lateinit var dao: PlayerDao

    private val t0 = 1_705_276_800_000L

    @Before
    fun setUp() {
        db = PlayerDatabase.create(ApplicationProvider.getApplicationContext())
        dao = db.playerDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun season(
        id: String = "s1",
        status: String = "ACTIVE",
        datasetId: String = "ds_a",
        mode: String = "CHALLENGE",
    ) = SeasonEntity(
        seasonId = id,
        datasetId = datasetId,
        mode = mode,
        status = status,
        startingBalanceMicros = 10_000_000_000L,
        balanceMicros = 10_000_000_000L,
        equityMicros = 10_000_000_000L,
        peakEquityMicros = 10_000_000_000L,
        startedAtSimUtcMs = t0,
        createdAtRealUtcMs = t0,
        completedAtSimUtcMs = null,
        tradingDayCount = 0,
        dailyStrikeCount = 0,
        scorePoints = 0,
        outcomeReason = null,
    )

    // ---------- inserts and queries ----------

    @Test
    fun `inserts a season and reads it back`() = runTest {
        dao.insertSeason(season())
        val read = dao.getSeason("s1")
        assertNotNull(read)
        assertEquals(10_000_000_000L, read!!.startingBalanceMicros)
        assertEquals("CHALLENGE", read.mode)
    }

    @Test
    fun `money is stored at full micro-dollar precision`() = runTest {
        val precise = season().copy(balanceMicros = 9_876_543_210L)
        dao.insertSeason(precise)
        assertEquals(9_876_543_210L, dao.getSeason("s1")!!.balanceMicros)
    }

    @Test
    fun `duplicate season id is rejected`() = runTest {
        dao.insertSeason(season())
        val error = runCatching { dao.insertSeason(season()) }.exceptionOrNull()
        assertNotNull("season id is a primary key", error)
    }

    // ---------- single active season ----------

    @Test
    fun `only one active season can exist`() = runTest {
        assertTrue(dao.insertSeasonIfNoneActive(season(id = "s1")))
        assertFalse("a second active season must be refused", dao.insertSeasonIfNoneActive(season(id = "s2")))
        assertEquals(1, dao.countSeasons())
        assertEquals("s1", dao.activeSeason()!!.seasonId)
    }

    @Test
    fun `a new active season is allowed once the previous one ends`() = runTest {
        dao.insertSeasonIfNoneActive(season(id = "s1"))
        dao.updateSeasonStatus("s1", "PASSED", "target reached", t0 + 1000)

        assertTrue(dao.insertSeasonIfNoneActive(season(id = "s2")))
        assertEquals("s2", dao.activeSeason()!!.seasonId)
    }

    @Test
    fun `completed seasons remain in history`() = runTest {
        dao.insertSeasonIfNoneActive(season(id = "s1"))
        dao.updateSeasonStatus("s1", "FAILED", "daily loss", t0 + 1000)
        dao.insertSeasonIfNoneActive(season(id = "s2"))

        assertEquals(2, dao.countSeasons())
        assertEquals("FAILED", dao.getSeason("s1")!!.status)
        assertEquals("daily loss", dao.getSeason("s1")!!.outcomeReason)
    }

    @Test
    fun `a finished season does not block a practice season`() = runTest {
        dao.insertSeasonIfNoneActive(season(id = "s1"))
        dao.updateSeasonStatus("s1", "ABANDONED", null, t0)
        assertTrue(dao.insertSeasonIfNoneActive(season(id = "s2", mode = "PRACTICE")))
    }

    // ---------- orders, executions, positions ----------

    @Test
    fun `orders and executions round trip`() = runTest {
        dao.insertSeason(season())
        dao.insertOrder(
            TradeOrderEntity(
                orderId = "o1", seasonId = "s1", placedAtSimUtcMs = t0,
                orderType = "LIMIT", side = "BUY", volumeHundredths = 20,
                requestedPriceUnits = 2_045_000, stopLossPips = 50, takeProfitPips = 100,
                status = "PENDING", resolvedAtSimUtcMs = null, rejectionReason = null,
            ),
        )
        dao.insertExecution(
            ExecutionEntity(
                executionId = "e1", seasonId = "s1", orderId = "o1",
                executedAtSimUtcMs = t0 + 1000, priceUnits = 2_045_000,
                volumeHundredths = 20, commissionMicros = 140_000L, slippageUnits = 0,
            ),
        )

        assertEquals(1, dao.countOrders("s1"))
        assertEquals(1, dao.countExecutions("s1"))
        assertEquals(1, dao.pendingOrders("s1", limit = 10).size)
        assertEquals(2_045_000, dao.executionsFor("s1", limit = 10).single().priceUnits)
    }

    @Test
    fun `open positions are counted and can be closed`() = runTest {
        dao.insertSeason(season())
        dao.insertPosition(
            PositionEntity(
                positionId = "p1", seasonId = "s1", openedAtSimUtcMs = t0,
                side = "BUY", volumeHundredths = 100, openPriceUnits = 2_045_000,
                stopLossPips = 50, takeProfitPips = null, status = "OPEN",
                closedAtSimUtcMs = null, closePriceUnits = null,
            ),
        )

        assertEquals(1, dao.countOpenPositions("s1"))
        assertEquals(1, dao.openPositions("s1", limit = 10).size)

        dao.closePosition("p1", t0 + 60_000L, 2_050_000)

        assertEquals(0, dao.countOpenPositions("s1"))
        val closed = dao.getPosition("s1", "p1")!!
        assertEquals("CLOSED", closed.status)
        assertEquals(2_050_000, closed.closePriceUnits)
    }

    @Test
    fun `closed trades keep their full pnl breakdown`() = runTest {
        dao.insertSeason(season())
        dao.insertClosedTrade(
            ClosedTradeEntity(
                tradeId = "t1", seasonId = "s1", positionId = "p1", side = "BUY",
                volumeHundredths = 100, openPriceUnits = 2_045_000, closePriceUnits = 2_050_000,
                openTimeSimUtcMs = t0, closeTimeSimUtcMs = t0 + 60_000L,
                grossPnlMicros = 500_000_000L, commissionMicros = 700_000L,
                swapMicros = -10_000L, netPnlMicros = 499_290_000L,
                closeReason = "TAKE_PROFIT", realizedRMicros = 2_000_000L,
            ),
        )

        val trade = dao.closedTrades("s1", limit = 10).single()
        assertEquals(500_000_000L, trade.grossPnlMicros)
        assertEquals(499_290_000L, trade.netPnlMicros)
        assertEquals("TAKE_PROFIT", trade.closeReason)
    }

    // ---------- daily stats and equity ----------

    @Test
    fun `daily stats are keyed by season and trading day`() = runTest {
        dao.insertSeason(season())
        dao.upsertDailyStats(
            DailyStatsEntity(
                seasonId = "s1", tradingDayStartSimUtcMs = t0,
                openingEquityMicros = 10_000_000_000L, closingEquityMicros = 10_200_000_000L,
                highEquityMicros = 10_300_000_000L, lowEquityMicros = 9_950_000_000L,
                realizedPnlMicros = 200_000_000L, tradeCount = 4,
                strikeRecorded = false, dailyFloorMicros = 9_700_000_000L,
            ),
        )
        dao.upsertDailyStats(
            DailyStatsEntity(
                seasonId = "s1", tradingDayStartSimUtcMs = t0 + 86_400_000L,
                openingEquityMicros = 10_200_000_000L, closingEquityMicros = 10_150_000_000L,
                highEquityMicros = 10_250_000_000L, lowEquityMicros = 10_100_000_000L,
                realizedPnlMicros = -50_000_000L, tradeCount = 2,
                strikeRecorded = true, dailyFloorMicros = 9_894_000_000L,
            ),
        )

        val stats = dao.dailyStats("s1")
        assertEquals(2, stats.size)
        assertEquals(t0, stats.first().tradingDayStartSimUtcMs)
        assertTrue(stats.last().strikeRecorded)
    }

    @Test
    fun `upsert replaces the same trading day rather than duplicating it`() = runTest {
        dao.insertSeason(season())
        val day = DailyStatsEntity(
            seasonId = "s1", tradingDayStartSimUtcMs = t0,
            openingEquityMicros = 1L, closingEquityMicros = 1L,
            highEquityMicros = 1L, lowEquityMicros = 1L,
            realizedPnlMicros = 0L, tradeCount = 0,
            strikeRecorded = false, dailyFloorMicros = 1L,
        )
        dao.upsertDailyStats(day)
        dao.upsertDailyStats(day.copy(closingEquityMicros = 777L))

        assertEquals(1, dao.countDailyStats("s1"))
        assertEquals(777L, dao.dailyStats("s1").single().closingEquityMicros)
    }

    @Test
    fun `equity snapshots are ordered by time`() = runTest {
        dao.insertSeason(season())
        dao.insertEquitySnapshot(EquitySnapshotEntity(seasonId = "s1", timestampSimUtcMs = t0 + 1000, equityMicros = 2L, balanceMicros = 1L))
        dao.insertEquitySnapshot(EquitySnapshotEntity(seasonId = "s1", timestampSimUtcMs = t0, equityMicros = 1L, balanceMicros = 1L))

        assertEquals(
            listOf(t0, t0 + 1000),
            dao.equitySnapshots("s1", limit = 10).map { it.timestampSimUtcMs },
        )
    }

    // ---------- drawings and journal ----------

    @Test
    fun `drawings are scoped to a season and can be deleted`() = runTest {
        dao.insertSeason(season(id = "s1"))
        dao.insertSeason(season(id = "s2", status = "FAILED"))
        dao.upsertDrawing(
            DrawingEntity("d1", "s1", "TREND_LINE", "[]", 0xFF00FF00.toInt(), 2f, t0),
        )
        dao.upsertDrawing(
            DrawingEntity("d2", "s2", "TREND_LINE", "[]", 0xFF00FF00.toInt(), 2f, t0),
        )

        assertEquals(1, dao.drawings("s1").size)
        assertEquals(1, dao.drawings("s2").size)

        dao.deleteDrawing("s1", "d1")
        assertEquals(0, dao.drawings("s1").size)
        assertEquals("deleting one season's drawing must not touch the other", 1, dao.drawings("s2").size)
    }

    @Test
    fun `journal entries round trip and allow a null trade`() = runTest {
        dao.insertSeason(season())
        dao.upsertJournalEntry(
            TradeJournalEntity("j1", "s1", tradeId = null, createdAtSimUtcMs = t0, note = "note", tags = "plan"),
        )
        val entry = dao.journalEntries("s1", limit = 10).single()
        assertNull(entry.tradeId)
        assertEquals("note", entry.note)
    }

    // ---------- badges ----------

    @Test
    fun `badges are unique per season`() = runTest {
        dao.insertSeason(season())
        assertTrue(dao.insertBadge(BadgeEntity("s1", "first_trade", "First Trade", t0)) > 0)
        assertEquals(-1L, dao.insertBadge(BadgeEntity("s1", "first_trade", "First Trade", t0)))
        assertEquals(1, dao.badges("s1").size)
    }

    // ---------- checkpoint and settings ----------

    @Test
    fun `replay checkpoint is one row per season and is replaced`() = runTest {
        dao.insertSeason(season())
        dao.upsertCheckpoint(
            ReplayCheckpointEntity("s1", lastCommittedSimUtcMs = t0, historicalAnchorSimUtcMs = t0,
                realAnchorUtcMs = t0, playbackSpeed = 1, isPaused = false, updatedAtRealUtcMs = t0),
        )
        dao.upsertCheckpoint(
            ReplayCheckpointEntity("s1", lastCommittedSimUtcMs = t0 + 60_000L, historicalAnchorSimUtcMs = t0,
                realAnchorUtcMs = t0, playbackSpeed = 30, isPaused = true, updatedAtRealUtcMs = t0 + 1),
        )

        val checkpoint = dao.checkpoint("s1")!!
        assertEquals(t0 + 60_000L, checkpoint.lastCommittedSimUtcMs)
        assertEquals(30, checkpoint.playbackSpeed)
        assertTrue(checkpoint.isPaused)
    }

    @Test
    fun `settings round trip and overwrite`() = runTest {
        dao.putSetting(SettingEntity("theme", "DARK"))
        assertEquals("DARK", dao.getSetting("theme"))
        dao.putSetting(SettingEntity("theme", "LIGHT"))
        assertEquals("LIGHT", dao.getSetting("theme"))
        assertNull(dao.getSetting("missing"))
    }

    // ---------- cascade deletion ----------

    @Test
    fun `deleting a season cascades to every child table`() = runTest {
        dao.insertSeason(season())
        dao.insertOrder(
            TradeOrderEntity("o1", "s1", t0, "MARKET", "BUY", 10, null, null, null, "PENDING", null, null),
        )
        dao.insertExecution(ExecutionEntity("e1", "s1", "o1", t0, 2_045_000, 10, 70_000L, 0))
        dao.insertPosition(
            PositionEntity("p1", "s1", t0, "BUY", 10, 2_045_000, null, null, "OPEN", null, null),
        )
        dao.insertClosedTrade(
            ClosedTradeEntity("t1", "s1", "p1", "BUY", 10, 2_045_000, 2_046_000, t0, t0, 1L, 1L, 0L, 1L, "MANUAL", 1L),
        )
        dao.upsertDailyStats(
            DailyStatsEntity("s1", t0, 1L, 1L, 1L, 1L, 0L, 0, false, 1L),
        )
        dao.insertEquitySnapshot(EquitySnapshotEntity(seasonId = "s1", timestampSimUtcMs = t0, equityMicros = 1L, balanceMicros = 1L))
        dao.upsertDrawing(DrawingEntity("d1", "s1", "LINE", "[]", 0, 1f, t0))
        dao.upsertJournalEntry(TradeJournalEntity("j1", "s1", null, t0, "n", ""))
        dao.insertBadge(BadgeEntity("s1", "b1", "B", t0))
        dao.upsertCheckpoint(
            ReplayCheckpointEntity("s1", t0, t0, t0, 1, false, t0),
        )

        dao.deleteSeason("s1")

        assertNull(dao.getSeason("s1"))
        assertEquals(0, dao.countOrders("s1"))
        assertEquals(0, dao.countExecutions("s1"))
        assertEquals(0, dao.countOpenPositions("s1"))
        assertEquals(0, dao.countClosedTrades("s1"))
        assertEquals(0, dao.countDailyStats("s1"))
        assertEquals(0, dao.equitySnapshots("s1", limit = 10).size)
        assertEquals(0, dao.drawings("s1").size)
        assertEquals(0, dao.journalEntries("s1", limit = 10).size)
        assertEquals(0, dao.badges("s1").size)
        assertNull(dao.checkpoint("s1"))
    }

    @Test
    fun `cascade does not touch another season's data`() = runTest {
        dao.insertSeason(season(id = "s1"))
        dao.insertSeason(season(id = "s2", status = "FAILED"))
        dao.insertClosedTrade(
            ClosedTradeEntity("t1", "s1", "p1", "BUY", 10, 1, 2, t0, t0, 1L, 1L, 0L, 1L, "MANUAL", 1L),
        )
        dao.insertClosedTrade(
            ClosedTradeEntity("t2", "s2", "p2", "BUY", 10, 1, 2, t0, t0, 1L, 1L, 0L, 1L, "MANUAL", 1L),
        )

        dao.deleteSeason("s1")

        assertEquals(0, dao.countClosedTrades("s1"))
        assertEquals(1, dao.countClosedTrades("s2"))
    }

    @Test
    fun `child rows cannot exist without their season`() = runTest {
        val error = runCatching {
            dao.insertClosedTrade(
                ClosedTradeEntity("t1", "ghost", "p1", "BUY", 10, 1, 2, t0, t0, 1L, 1L, 0L, 1L, "MANUAL", 1L),
            )
        }.exceptionOrNull()
        assertNotNull("foreign key must block an orphan trade", error)
    }

    // ---------- reopening ----------

    @Test
    fun `player data survives closing and reopening`() = runTest {
        val name = "reopen_player.db"
        val first = PlayerDatabase.create(ApplicationProvider.getApplicationContext(), name)
        first.playerDao().insertSeason(season())
        first.playerDao().putSetting(SettingEntity("theme", "DARK"))
        first.close()

        val second = PlayerDatabase.create(ApplicationProvider.getApplicationContext(), name)
        try {
            assertEquals(10_000_000_000L, second.playerDao().getSeason("s1")!!.balanceMicros)
            assertEquals("DARK", second.playerDao().getSetting("theme"))
        } finally {
            second.close()
        }
    }

    @Test
    fun `all twelve tables are created`() = runTest {
        val names = db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

        val expected = listOf(
            "Season", "TradeOrder", "Execution", "Position", "ClosedTrade",
            "DailyStats", "EquitySnapshot", "Drawing", "TradeJournal", "Badge",
            "ReplayCheckpoint", "Settings",
        )
        expected.forEach { assertTrue("missing table $it (found: $names)", it in names) }
    }
}
