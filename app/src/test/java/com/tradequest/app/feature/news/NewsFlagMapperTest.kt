package com.tradequest.app.feature.news

import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.renderer.FlagSeverity
import com.tradequest.app.core.chart.renderer.NewsFlag
import com.tradequest.app.core.chart.renderer.NewsFlagHit
import com.tradequest.app.core.chart.renderer.NewsFlagHitTester
import com.tradequest.app.core.news.NewsCategory
import com.tradequest.app.core.news.NewsImpact
import com.tradequest.app.core.news.NewsRelease
import com.tradequest.app.core.chart.ChartFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 12.3/12.4 tests: which releases become chart flags, and how a tap finds one.
 *
 * # Why this is a feature-layer test
 *
 * The mapper is the join between `core/news` and `core/chart`, and the plan's rule
 * is that the two must not know about each other. The mapper is the only place
 * both types are visible, so it is the only place the mapping can be wrong in a way
 * neither side's own tests would catch: a high-impact release that never reaches
 * the chart, or a flag drawn where no bar exists.
 */
class NewsFlagMapperTest {

    private val t0 = ChartFixtures.MONDAY_OPEN_UTC_MS

    private fun buffer(count: Int = 30): CandleBuffer =
        CandleBuffer.of(ChartFixtures.rising(count))

    private fun release(
        at: Long,
        impact: NewsImpact = NewsImpact.HIGH,
        headline: String = "US CPI",
    ): NewsRelease = NewsRelease.Pending(
        newsId = 1L,
        datasetId = "ds",
        scheduledUtcMs = at,
        headline = headline,
        category = NewsCategory.INFLATION,
        impact = impact,
        forecast = "3.1%",
        previous = "3.0%",
    )

    @Test
    fun `a high-impact release becomes a red flag on its bar`() {
        val flags = NewsFlagMapper.flags(listOf(release(t0 + 5 * 60_000L)), buffer())

        assertEquals(1, flags.size)
        assertEquals(5, flags[0].index)
        assertEquals(FlagSeverity.HIGH, flags[0].severity)
        assertEquals("US CPI", flags[0].headline)
    }

    @Test
    fun `a medium-impact release becomes an amber flag`() {
        val flags = NewsFlagMapper.flags(
            listOf(release(t0 + 3 * 60_000L, impact = NewsImpact.MEDIUM)),
            buffer(),
        )
        assertEquals(FlagSeverity.MEDIUM, flags[0].severity)
    }

    @Test
    fun `low and unknown impacts get no flag`() {
        val flags = NewsFlagMapper.flags(
            listOf(
                release(t0, impact = NewsImpact.LOW),
                release(t0 + 60_000L, impact = NewsImpact.UNKNOWN),
            ),
            buffer(),
        )
        assertTrue("low/unknown must not be flagged: $flags", flags.isEmpty())
    }

    @Test
    fun `an event with no bar in the buffer is dropped, not clamped`() {
        // Past the last candle: there is no bar to sit on, and clamping would put a
        // flag on a bar where nothing happened.
        val flags = NewsFlagMapper.flags(
            listOf(release(t0 + 5 * 60 * 60_000L)),
            buffer(30),
        )
        assertTrue(flags.isEmpty())
    }

    @Test
    fun `two releases in the same minute collapse to one flag`() {
        val sameMinute = t0 + 4 * 60_000L
        val flags = NewsFlagMapper.flags(
            listOf(
                release(sameMinute, headline = "First"),
                release(sameMinute, headline = "Second"),
            ),
            buffer(),
        )
        assertEquals(1, flags.size)
        assertEquals("the calendar's first event should win", "First", flags[0].headline)
    }

    @Test
    fun `an empty buffer produces no flags rather than throwing`() {
        assertTrue(NewsFlagMapper.flags(listOf(release(t0)), CandleBuffer.of(emptyList())).isEmpty())
    }

    @Test
    fun `a released event still gets a flag, because the flag never carried the outcome`() {
        // Step 12.4: the outcome is what is hidden, not the event's existence. A
        // flag for a past release is legitimate — a trader looking back sees it.
        val released = NewsRelease.Released(
            newsId = 2L,
            datasetId = "ds",
            scheduledUtcMs = t0 + 2 * 60_000L,
            headline = "FOMC",
            category = NewsCategory.INTEREST_RATE,
            impact = NewsImpact.HIGH,
            forecast = null,
            previous = null,
            actual = "5.50%",
            reactionNote = "Gold fell 200 pips.",
        )
        val flags = NewsFlagMapper.flags(listOf(released), buffer())

        assertEquals(1, flags.size)
        assertEquals("FOMC", flags[0].headline)
    }
}

/**
 * Step 12.3 tests: tapping a news flag.
 *
 * # Why only the x-axis is compared
 *
 * Every flag sits on the same horizontal strip, so a touch that reaches the strip
 * is unambiguous in y. The interesting cases are therefore: the nearest flag wins,
 * a touch between two flags picks one, and a touch on empty space opens nothing.
 */
class NewsFlagHitTesterTest {

    private val flags = listOf(
        NewsFlag(index = 10, severity = FlagSeverity.HIGH, headline = "A"),
        NewsFlag(index = 20, severity = FlagSeverity.MEDIUM, headline = "B"),
    )

    /** Flag index maps to pixels directly, so the arithmetic is readable. */
    private val flagX: (NewsFlag) -> Float = { it.index.toFloat() }

    @Test
    fun `a touch on a flag reports it`() {
        val hit = NewsFlagHitTester.hitTest(10.2f, flags, flagX, tolerancePx = 6f)
        assertEquals(NewsFlagHit(index = 10, headline = "A"), hit)
    }

    @Test
    fun `the nearest flag wins when two are in range`() {
        val hit = NewsFlagHitTester.hitTest(13f, flags, flagX, tolerancePx = 8f)
        assertEquals("the closer of the two must win", "A", hit?.headline)
    }

    @Test
    fun `a touch on empty chart space opens nothing`() {
        assertNull(NewsFlagHitTester.hitTest(15f, flags, flagX, tolerancePx = 2f))
    }

    @Test
    fun `an empty flag list yields no hit`() {
        assertNull(NewsFlagHitTester.hitTest(10f, emptyList(), flagX, tolerancePx = 6f))
    }

    @Test
    fun `the tolerance boundary is inclusive`() {
        // 16 is exactly 6px from flag A and 4px from flag B, so B is in range and
        // nearer: the boundary admits the flag, and proximity still decides.
        // 5.0 is 5px from A and 15px from B, so a 4px tolerance reaches neither.
        assertNull(NewsFlagHitTester.hitTest(5f, flags, flagX, tolerancePx = 4f))
        assertEquals(
            "a touch exactly at the tolerance should still hit something",
            NewsFlagHit(index = 20, headline = "B"),
            NewsFlagHitTester.hitTest(10f + 6f, flags, flagX, tolerancePx = 6f),
        )
    }

    // ---------------- Step 12.3: the strip bounds the tap ----------------

    @Test
    fun `a touch below the strip is on the candles, not a flag`() {
        // Every flag is drawn on one line at the top of the plot. A tap that lands
        // on a candle below the strip must not open a release, or reading a bar
        // would pop up an unrelated event.
        assertNull(
            "a tap on the candles must not open a release",
            NewsFlagHitTester.hitTestAt(
                touchX = 10f,
                touchY = NewsFlagHitTester.STRIP_BOTTOM_PX + 1f,
                flags = flags,
                flagX = flagX,
                tolerancePx = 6f,
            ),
        )
    }

    @Test
    fun `a touch inside the strip hits the flag`() {
        val hit = NewsFlagHitTester.hitTestAt(
            touchX = 10f,
            touchY = NewsFlagHitTester.STRIP_TOP_PX + 1f,
            flags = flags,
            flagX = flagX,
            tolerancePx = 6f,
        )
        assertEquals(NewsFlagHit(index = 10, headline = "A"), hit)
    }

    @Test
    fun `the strip bounds are inclusive at both edges`() {
        // The top and bottom edges of the strip belong to the flag, so a tap on the
        // exact edge is not lost to a half-pixel rounding.
        for (y in listOf(NewsFlagHitTester.STRIP_TOP_PX, NewsFlagHitTester.STRIP_BOTTOM_PX)) {
            assertEquals(
                "y=$y is on the strip",
                NewsFlagHit(index = 10, headline = "A"),
                NewsFlagHitTester.hitTestAt(10f, y, flags, flagX, tolerancePx = 6f),
            )
        }
    }
}
