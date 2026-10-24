package com.tradequest.app.core.news

import com.tradequest.app.core.market.NewsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 12.4 tests: what an economic release reveals at one simulated instant.
 *
 * # Why these assertions are about the *type*, not just the values
 *
 * Product rule 2.2 says a future outcome must never be revealed. The projection
 * enforces that by giving the pre-release shape no `actual` field at all, so the
 * strongest assertion here is a cast: a `Pending` event cannot be asked for its
 * actual, and a test that tried would not compile. The value assertions below
 * (null, hidden, visible) cover the boundary behaviour the type alone cannot.
 */
class NewsReleaseProjectionTest {

    private val scheduled = 1_705_276_800_000L

    private fun item(
        actual: String? = "1.2%",
        reactionNote: String? = "Gold spiked 40 pips on the print.",
        reactionWindowMinutes: Int = 15,
    ) = NewsItem(
        newsId = 7L,
        datasetId = "ds",
        publishedAtUtcMs = scheduled,
        headline = "US Non-Farm Payrolls",
        body = "",
        impact = "HIGH",
        category = "EMPLOYMENT",
        forecast = "180K",
        previous = "175K",
        actual = actual,
        reactionNote = reactionNote,
        reactionWindowMinutes = reactionWindowMinutes,
    )

    @Test
    fun `before the release the event is pending and carries no actual`() {
        val release = NewsReleaseProjection.project(item(), simUtcMs = scheduled - 1)

        assertTrue(release is NewsRelease.Pending)
        assertFalse(release.isReleased)
        // The fields a trader legitimately has before the print.
        assertEquals("US Non-Farm Payrolls", release.headline)
        assertEquals(scheduled, release.scheduledUtcMs)
        assertEquals("180K", release.forecast)
        assertEquals("175K", release.previous)
        assertEquals(NewsImpact.HIGH, release.impact)
    }

    @Test
    fun `at the release instant the event is released, not pending`() {
        // The boundary is inclusive: an event scheduled for 14:30 prints at
        // 14:30:00.000, not one millisecond later.
        val release = NewsReleaseProjection.project(item(), simUtcMs = scheduled)

        assertTrue(release is NewsRelease.Released)
        assertTrue(release.isReleased)
        assertEquals("1.2%", (release as NewsRelease.Released).actual)
    }

    @Test
    fun `the reaction note is withheld until its window has elapsed`() {
        // One minute before the window closes: the actual is visible but the note
        // is not, which is the whole point of a separate reveal.
        val justBefore = NewsReleaseProjection.project(
            item(reactionWindowMinutes = 15),
            simUtcMs = scheduled + 14 * 60_000L,
        )
        assertTrue(justBefore is NewsRelease.Released)
        assertNull((justBefore as NewsRelease.Released).reactionNote)
        assertEquals("1.2%", justBefore.actual)

        // At the window boundary the note appears.
        val atBoundary = NewsReleaseProjection.project(
            item(reactionWindowMinutes = 15),
            simUtcMs = scheduled + 15 * 60_000L,
        )
        assertEquals(
            "Gold spiked 40 pips on the print.",
            (atBoundary as NewsRelease.Released).reactionNote,
        )
    }

    @Test
    fun `a zero-length reaction window reveals the note with the actual`() {
        val release = NewsReleaseProjection.project(
            item(reactionWindowMinutes = 0),
            simUtcMs = scheduled,
        )
        assertEquals(
            "Gold spiked 40 pips on the print.",
            (release as NewsRelease.Released).reactionNote,
        )
    }

    @Test
    fun `a negative reaction window is clamped, never revealing the note early`() {
        // External data can carry a nonsensical window. The worst case must be
        // "visible immediately", never "visible before the release".
        val before = NewsReleaseProjection.project(
            item(reactionWindowMinutes = -30),
            simUtcMs = scheduled - 1,
        )
        assertTrue(before is NewsRelease.Pending)

        val at = NewsReleaseProjection.project(
            item(reactionWindowMinutes = -30),
            simUtcMs = scheduled,
        )
        assertEquals(
            "Gold spiked 40 pips on the print.",
            (at as NewsRelease.Released).reactionNote,
        )
    }

    @Test
    fun `a release with no recorded figure is released but has a null actual`() {
        // Different from "not allowed to see it yet": the release happened, there
        // simply is no figure, and the type must not conflate the two.
        val release = NewsReleaseProjection.project(item(actual = null), simUtcMs = scheduled)
        assertTrue(release is NewsRelease.Released)
        assertNull((release as NewsRelease.Released).actual)
    }

    @Test
    fun `an unrecognised impact is parsed as unknown, not folded into low`() {
        val release = NewsReleaseProjection.project(
            item().copy(impact = "banana"),
            simUtcMs = scheduled,
        )
        assertEquals(NewsImpact.UNKNOWN, release.impact)
    }

    @Test
    fun `projectAll preserves order and projects each event independently`() {
        val early = item().copy(newsId = 1L, publishedAtUtcMs = scheduled)
        val late = item().copy(newsId = 2L, publishedAtUtcMs = scheduled + 60 * 60_000L)

        val releases = NewsReleaseProjection.projectAll(
            listOf(early, late),
            simUtcMs = scheduled + 60_000L,
        )

        assertEquals(listOf(1L, 2L), releases.map { it.newsId })
        assertTrue(releases[0].isReleased)
        assertFalse("an event an hour ahead must still be pending", releases[1].isReleased)
    }
}
