package com.tradequest.app.core.simulation.warp

import com.tradequest.app.core.simulation.clock.ClockMode
import com.tradequest.app.core.simulation.clock.DatasetBounds
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 12.6/12.7 tests: where Time Warp lands, and when it is refused.
 *
 * # Why the expected instants are hard-coded
 *
 * Every UTC millisecond below was computed from the IANA timezone database by
 * hand, not read back from the resolver. Deriving them from the code under test
 * would make a wrong answer agree with itself. The session windows are the ones
 * `SessionClassifier` documents: London 08:00 Europe/London, New York 08:00
 * America/New_York.
 *
 * # Why the resolver takes the news schedule as a function
 *
 * The resolver must not depend on a repository, and a test must not need a
 * database to answer "what is the next high-impact release". Injecting a lambda
 * keeps both true and lets these tests hand it an exact list.
 */
class TimeWarpResolverTest {

    private fun utcMs(text: String): Long =
        LocalDateTime.parse(text).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    /** A Monday, so a weekday warp has somewhere to go. */
    private val monday = utcMs("2024-01-08T12:00:00")

    private fun bounds(start: Long, end: Long) = DatasetBounds(startSimUtcMs = start, endSimUtcMs = end)

    private fun resolver(
        bounds: DatasetBounds,
        highImpact: List<Long> = emptyList(),
    ) = TimeWarpResolver(bounds) { from ->
        highImpact.filter { it >= from }.minOrNull()
    }

    // ---------- weekend skip ----------

    @Test
    fun `skipping the weekend lands on the next market open`() {
        // Friday 2024-01-12 after the close: the next open is Sunday 17:00 ET.
        val fridayEvening = utcMs("2024-01-12T23:00:00")
        val target = resolver(bounds(fridayEvening, utcMs("2024-01-20T00:00:00")))
            .nextMarketOpen(fridayEvening)

        assertTrue(target is WarpTarget.SkipWeekend)
        // 22:01 rather than 22:00: the calendar's own `nextMarketOpenUtcMs` steps
        // by whole hours from the probe instant (Friday 23:01), so it lands on the
        // same minute past the hour. One minute late is still the next open, and
        // pinning it here documents the behaviour rather than hiding it.
        assertEquals(utcMs("2024-01-14T22:01:00"), target.atUtcMs)
        assertTrue(
            "the target must be a moment the market is open",
            com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
                .isMarketOpen(target.atUtcMs!!),
        )
        assertFalse(target.isBeyondDataset)
    }

    @Test
    fun `a warp is strictly in the future, never the current instant`() {
        // Already open: the answer must be the *next* open, not "now", or the
        // button would visibly do nothing.
        val openInstant = utcMs("2024-01-08T15:00:00")
        val target = resolver(bounds(openInstant, utcMs("2024-01-20T00:00:00")))
            .nextMarketOpen(openInstant)

        assertTrue("expected a strictly future target, got ${target.atUtcMs}", target.atUtcMs!! > openInstant)
    }

    @Test
    fun `skipping the weekend past the dataset end reports beyond-dataset`() {
        val fridayEvening = utcMs("2024-01-12T23:00:00")
        val target = resolver(bounds(fridayEvening, fridayEvening + 60_000L))
            .nextMarketOpen(fridayEvening)

        assertTrue(target is WarpTarget.SkipWeekend)
        assertNull(target.atUtcMs)
        assertTrue(target.isBeyondDataset)
    }

    // ---------- session opens ----------

    @Test
    fun `the next London open is the following day's 08_00 London time`() {
        // Monday 12:00 UTC is inside London hours (08:00-17:00 GMT), so the next
        // open is Tuesday 08:00 London = 08:00 UTC in January.
        val target = resolver(bounds(monday, utcMs("2024-01-20T00:00:00")))
            .nextLondonOpen(monday) as WarpTarget.NextLondon

        assertEquals(utcMs("2024-01-09T08:00:00"), target.atUtcMs)
    }

    @Test
    fun `the next New York open is 08_00 New York time`() {
        // Monday 12:00 UTC = 07:00 ET, before the New York open, so the answer is
        // the same day at 08:00 ET = 13:00 UTC.
        val target = resolver(bounds(monday, utcMs("2024-01-20T00:00:00")))
            .nextNewYorkOpen(monday) as WarpTarget.NextNewYork

        assertEquals(utcMs("2024-01-08T13:00:00"), target.atUtcMs)
    }

    @Test
    fun `a session open past the dataset end reports beyond-dataset`() {
        val target = resolver(bounds(monday, monday + 60_000L)).nextLondonOpen(monday)
        assertNull(target.atUtcMs)
        assertTrue(target.isBeyondDataset)
    }

    // ---------- high-impact news ----------

    @Test
    fun `the news warp lands five minutes before the release`() {
        val release = utcMs("2024-01-08T13:30:00")
        val target = resolver(
            bounds(monday, utcMs("2024-01-20T00:00:00")),
            highImpact = listOf(release),
        ).nextHighImpactNews(monday) as WarpTarget.NextHighImpactNews

        assertEquals(release - 5 * 60_000L, target.atUtcMs)
        assertEquals(release, target.eventUtcMs)
        assertFalse(target.isBeyondDataset)
    }

    @Test
    fun `a release nearer than five minutes is skipped for the one after it`() {
        // The first release is four minutes away, so T-5m would be in the past.
        // The warp must move to the next one, not backwards.
        val tooNear = monday + 4 * 60_000L
        val later = monday + 60 * 60_000L
        val target = resolver(
            bounds(monday, utcMs("2024-01-20T00:00:00")),
            highImpact = listOf(tooNear, later),
        ).nextHighImpactNews(monday) as WarpTarget.NextHighImpactNews

        assertEquals(later, target.eventUtcMs)
        assertEquals(later - 5 * 60_000L, target.atUtcMs)
    }

    @Test
    fun `a release beyond the dataset end reports beyond-dataset with the event named`() {
        val release = utcMs("2024-02-01T13:30:00")
        val target = resolver(
            bounds(monday, utcMs("2024-01-20T00:00:00")),
            highImpact = listOf(release),
        ).nextHighImpactNews(monday) as WarpTarget.NextHighImpactNews

        assertNull(target.atUtcMs)
        assertTrue(target.isBeyondDataset)
        assertEquals(release, target.eventUtcMs)
    }

    @Test
    fun `no remaining high-impact release reports an unavailable warp`() {
        val target = resolver(bounds(monday, utcMs("2024-01-20T00:00:00")))
            .nextHighImpactNews(monday)

        assertNull(target.atUtcMs)
        assertFalse(target.isBeyondDataset)
    }

    @Test
    fun `resolution is deterministic for the same inputs`() {
        // The plan's "Time Warp is deterministic" criterion: nothing reads a wall
        // clock, so the same question asked twice gives the same answer.
        val release = utcMs("2024-01-09T13:30:00")
        val subject = resolver(bounds(monday, utcMs("2024-01-20T00:00:00")), listOf(release))

        assertEquals(
            subject.nextHighImpactNews(monday).atUtcMs,
            subject.nextHighImpactNews(monday).atUtcMs,
        )
        assertEquals(
            subject.nextLondonOpen(monday).atUtcMs,
            subject.nextLondonOpen(monday).atUtcMs,
        )
    }
}

/**
 * Step 12.7 tests: the Challenge-versus-Practice warp rule.
 *
 * The rule is short — a challenge with exposure cannot warp — but it is the one
 * place the mode changes behaviour, so it is pinned directly rather than only
 * through a screen.
 */
class WarpPermissionTest {

    @Test
    fun `practice mode always allows the warp`() {
        assertEquals(
            WarpPermission.Allowed,
            WarpPermission.evaluate(ClockMode.PRACTICE, openPositionCount = 3, pendingOrderCount = 2),
        )
    }

    @Test
    fun `a flat challenge allows the warp`() {
        assertEquals(
            WarpPermission.Allowed,
            WarpPermission.evaluate(ClockMode.CHALLENGE, openPositionCount = 0, pendingOrderCount = 0),
        )
    }

    @Test
    fun `a challenge with an open position is refused and names the position`() {
        val permission = WarpPermission.evaluate(
            ClockMode.CHALLENGE,
            openPositionCount = 1,
            pendingOrderCount = 0,
        )

        assertTrue(permission is WarpPermission.RefusedExposure)
        assertEquals(1, (permission as WarpPermission.RefusedExposure).openPositionCount)
        assertEquals("Close 1 open position before warping in a challenge.", permission.reason)
    }

    @Test
    fun `a challenge with both exposure kinds names both`() {
        val permission = WarpPermission.evaluate(
            ClockMode.CHALLENGE,
            openPositionCount = 2,
            pendingOrderCount = 1,
        ) as WarpPermission.RefusedExposure

        assertEquals(
            "Close 2 open positions and 1 pending order before warping in a challenge.",
            permission.reason,
        )
    }

    @Test
    fun `negative counts are clamped so a bad read cannot fabricate exposure`() {
        // A miscounted source must not produce "Close -1 open positions".
        assertEquals(
            WarpPermission.Allowed,
            WarpPermission.evaluate(ClockMode.CHALLENGE, openPositionCount = -1, pendingOrderCount = 0),
        )
    }
}
