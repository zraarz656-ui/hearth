package com.tradequest.app.feature.news

import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.renderer.FlagSeverity
import com.tradequest.app.core.chart.renderer.NewsFlag
import com.tradequest.app.core.news.NewsImpact
import com.tradequest.app.core.news.NewsRelease

/**
 * Maps news releases onto the chart renderer's flag type (Steps 12.3, 12.4).
 *
 * # Why the mapping lives in the feature layer
 *
 * `core/chart` draws geometry and must not know what a release is, and
 * `core/news` must not depend on Compose. Neither can reference the other, so the
 * translation belongs where both are visible — the feature layer. Keeping it in
 * one object rather than inline in a ViewModel means it can be tested against real
 * [NewsRelease] values with no chart, which is what `NewsFlagMapperTest` does.
 *
 * # What is deliberately absent
 *
 * A [NewsFlag] carries an index, a severity and a headline. It cannot carry an
 * actual or a reaction note, so this mapper is structurally incapable of putting a
 * release outcome on the chart. That is the point: Step 12.4 hides those until the
 * release, and the surest way to honour that on the chart is for the chart's own
 * type to have no field for them.
 *
 * # Which events get a flag
 *
 * Every event in the visible window, whether or not it has happened yet. Steps
 * 12.2 and 12.4 make a scheduled release visible before it prints — a trader has
 * that from a real calendar — and only the outcome is withheld. So the mapper does
 * not filter by release state; it filters by whether the event has a bar to sit on
 * and whether its impact is one the calendar flags.
 */
object NewsFlagMapper {

    /**
     * One flag per flagged release that lands on a bar in [buffer].
     *
     * [releases] is the already-projected schedule, so this mapper never sees a
     * raw event and never decides visibility itself. An event whose bar is outside
     * the buffer is dropped: it has no position on the chart, and clamping it to the
     * nearest bar would put a flag on a bar where nothing happened.
     */
    fun flags(releases: List<NewsRelease>, buffer: CandleBuffer): List<NewsFlag> {
        if (buffer.isEmpty) return emptyList()
        return buildList {
            for (release in releases) {
                val severity = severityOf(release.impact) ?: continue
                val index = buffer.indexAtOrAfter(release.scheduledUtcMs)
                if (index < 0 || index >= buffer.size) continue
                // Two releases in the same minute would draw on top of each other;
                // the buffer is minute-resolution, so collapse them and keep the
                // first, which is the one the calendar lists first.
                if (any { it.index == index }) continue
                add(
                    NewsFlag(
                        index = index,
                        severity = severity,
                        headline = release.headline,
                    ),
                )
            }
        }
    }

    /**
     * The flag severity for an impact, or null when the event is not flagged.
     *
     * Red for high and amber for medium (Step 12.3). `LOW` and `UNKNOWN` get no
     * flag: an unknown impact could be anything, and drawing it as low would be a
     * claim the data does not support.
     */
    fun severityOf(impact: NewsImpact): FlagSeverity? = when (impact) {
        NewsImpact.HIGH -> FlagSeverity.HIGH
        NewsImpact.MEDIUM -> FlagSeverity.MEDIUM
        NewsImpact.LOW, NewsImpact.UNKNOWN -> null
    }
}
