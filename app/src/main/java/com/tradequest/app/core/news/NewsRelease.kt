package com.tradequest.app.core.news

import com.tradequest.app.core.market.NewsItem

/**
 * An economic release as it is known at one simulated instant (Steps 12.2-12.4).
 *
 * # Why this type exists instead of a nullable `actual` on the read model
 *
 * Product rule 2.2 says future historical news outcomes must never be revealed,
 * and Step 12.4 splits each event into what is known before the release and what
 * becomes known after it. The tempting implementation is to fetch the whole row
 * and have the UI decide what to draw. That is unsafe here for three reasons:
 *
 * 1. **A hidden field can leak through a path nobody remembered to check.** The
 *    calendar, the chart flag, the trading screen's event list and the debug log
 *    all render news. Every one of them would have to independently remember to
 *    hide [actual] before release, and one that forgets is a spoiler.
 * 2. **A log line is a UI.** `DebugDiagnostics` writes news to a file the user
 *    can copy out. A "hidden" value that is present in the object still gets
 *    logged.
 * 3. **It cannot be tested by inspection.** A nullable field makes "was this
 *    revealed too early" a question about every call site. Removing the field
 *    from the type makes it a question about one function.
 *
 * So the projection has two shapes, and the pre-release shape simply has no
 * field to leak. [Released] carries [actual]; [Pending] does not, and neither
 * does the shared base. Code that wants the released value cannot compile
 * against a [Pending] event, which is the strongest guarantee available.
 *
 * # The seam
 *
 * [NewsReleaseProjection.project] is the single place that decides visibility. It
 * takes the event and the *simulated* instant, never the wall clock, so a
 * replayed season sees the same reveals on every run.
 */
sealed interface NewsRelease {

    /** Fields known before and after the release alike. */
    val newsId: Long
    val datasetId: String

    /** The scheduled release instant, in simulated UTC. */
    val scheduledUtcMs: Long

    val headline: String
    val category: NewsCategory
    val impact: NewsImpact

    /** Consensus forecast, or null when the source has none. */
    val forecast: String?

    /** Previous reading, or null when the source has none. */
    val previous: String?

    /** True once the release instant has passed at the projected instant. */
    val isReleased: Boolean

    /**
     * The event as known *before* its release (Step 12.4).
     *
     * [actual] and [reactionNote] are absent by construction.
     */
    data class Pending(
        override val newsId: Long,
        override val datasetId: String,
        override val scheduledUtcMs: Long,
        override val headline: String,
        override val category: NewsCategory,
        override val impact: NewsImpact,
        override val forecast: String?,
        override val previous: String?,
    ) : NewsRelease {
        override val isReleased: Boolean get() = false
    }

    /**
     * The event once its release instant has passed (Step 12.4).
     *
     * [actual] is present but nullable: a release can genuinely have no recorded
     * figure, and that is different from not being allowed to see it yet.
     */
    data class Released(
        override val newsId: Long,
        override val datasetId: String,
        override val scheduledUtcMs: Long,
        override val headline: String,
        override val category: NewsCategory,
        override val impact: NewsImpact,
        override val forecast: String?,
        override val previous: String?,
        val actual: String?,
        /**
         * How the market reacted, or null while its price window is still open.
         *
         * The window is measured from the release instant, not from the moment
         * the user happens to look, so the note appears at a fixed simulated time
         * and stays visible afterwards.
         */
        val reactionNote: String?,
    ) : NewsRelease {
        override val isReleased: Boolean get() = true
    }
}

/**
 * Decides what an economic release reveals at one simulated instant (Step 12.4).
 *
 * Pure and stateless. It reads no clock: the caller passes the simulated instant,
 * so the same event projects identically on a live run and on a replay.
 */
object NewsReleaseProjection {

    private const val MINUTE_MS = 60_000L

    /**
     * Projects [item] as known at [simUtcMs].
     *
     * Before the release instant the result is [NewsRelease.Pending] and carries
     * no actual. At or after it the result is [NewsRelease.Released], with
     * [NewsRelease.Released.reactionNote] withheld until its window elapses.
     *
     * The boundary is inclusive at the release instant: an event scheduled for
     * 14:30 is released at 14:30:00.000, not one millisecond later.
     */
    fun project(item: NewsItem, simUtcMs: Long): NewsRelease {
        val scheduled = item.publishedAtUtcMs
        val category = NewsCategory.fromStored(item.category)
        val impact = NewsImpact.fromStored(item.impact)

        if (simUtcMs < scheduled) {
            return NewsRelease.Pending(
                newsId = item.newsId,
                datasetId = item.datasetId,
                scheduledUtcMs = scheduled,
                headline = item.headline,
                category = category,
                impact = impact,
                forecast = item.forecast,
                previous = item.previous,
            )
        }

        // `reactionWindowMinutes` is external data. A negative value would put the
        // reveal *before* the release, which would leak the reaction early; clamp
        // to zero so the worst case is "visible immediately", never "visible
        // before the release".
        val windowMs = item.reactionWindowMinutes.coerceAtLeast(0).toLong() * MINUTE_MS
        val reactionVisibleAt = scheduled + windowMs
        val note = if (simUtcMs >= reactionVisibleAt) item.reactionNote else null

        return NewsRelease.Released(
            newsId = item.newsId,
            datasetId = item.datasetId,
            scheduledUtcMs = scheduled,
            headline = item.headline,
            category = category,
            impact = impact,
            forecast = item.forecast,
            previous = item.previous,
            actual = item.actual,
            reactionNote = note,
        )
    }

    /** Projects a list, preserving order. */
    fun projectAll(items: List<NewsItem>, simUtcMs: Long): List<NewsRelease> =
        items.map { project(it, simUtcMs) }
}
