package com.tradequest.app.core.simulation.warp

import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
import com.tradequest.app.core.simulation.calendar.SessionClassifier
import com.tradequest.app.core.simulation.calendar.TradingSession
import com.tradequest.app.core.simulation.clock.DatasetBounds
import java.time.Instant

/**
 * A moment Time Warp can jump to (Step 12.6).
 *
 * # Why these are resolved targets, not offsets
 *
 * Each warp answers "the next time X happens", and the answer depends on the
 * trading calendar and the dataset's own closures. Resolving the destination
 * once, in one place, and handing the instant to the clock means the jump is a
 * single `seekTo` that cannot land mid-weekend, the target is inspectable
 * without a clock, and "there is no such moment" is representable rather than an
 * exception or a silent no-op.
 */
sealed interface WarpTarget {

    /** The simulated instant to jump to, or null when no such moment exists. */
    val atUtcMs: Long?

    /**
     * True when the target lies beyond the dataset's end.
     *
     * Distinct from `atUtcMs == null`: it tells the UI *why* there is nowhere to
     * go, so it can say "end of data" rather than a bare "unavailable".
     */
    val isBeyondDataset: Boolean

    /** The next moment the market is open, used to skip a weekend (Step 12.6). */
    data class SkipWeekend(
        override val atUtcMs: Long?,
        override val isBeyondDataset: Boolean,
    ) : WarpTarget

    /** The next London session open. */
    data class NextLondon(
        override val atUtcMs: Long?,
        override val isBeyondDataset: Boolean,
    ) : WarpTarget

    /** The next New York session open. */
    data class NextNewYork(
        override val atUtcMs: Long?,
        override val isBeyondDataset: Boolean,
    ) : WarpTarget

    /**
     * Five minutes before the next high-impact release (Step 12.6).
     *
     * [eventUtcMs] is the release itself, kept so the UI can name the event and a
     * test can assert the offset without re-deriving it.
     */
    data class NextHighImpactNews(
        override val atUtcMs: Long?,
        override val isBeyondDataset: Boolean,
        val eventUtcMs: Long?,
    ) : WarpTarget
}

/**
 * Resolves Time Warp destinations (Step 12.6).
 *
 * # Determinism
 *
 * Every method is a pure function of the current instant, the dataset bounds and
 * the injected news schedule. Nothing reads the wall clock, so a warp resolves
 * identically on a live season and on a replay — the plan's "Time Warp is
 * deterministic" acceptance criterion.
 *
 * # Why the search steps by day, not by minute
 *
 * "Is the market open?" asked once per minute across a ten-day closure is 14,400
 * iterations per warp. Asking it per day and then refining is proportional to the
 * days skipped instead. The refinement within a day is bounded by one session
 * window, because that is the only place the answer can change.
 */
class TimeWarpResolver(
    private val bounds: DatasetBounds,
    /**
     * The next high-impact release at or after an instant, or null when none
     * remains. Injected so this class does not depend on the repository, and so a
     * test can supply a schedule directly instead of seeding a database.
     */
    private val nextHighImpactAtOrAfter: (Long) -> Long?,
) {

    /**
     * The next open after [fromUtcMs], for skipping a weekend.
     *
     * Returns null when the dataset ends first. The minimum advance is one minute
     * so the result is strictly in the future: a warp to the current instant
     * would be a button that visibly does nothing.
     */
    fun nextMarketOpen(fromUtcMs: Long): WarpTarget {
        val at = findNextOpen(fromUtcMs + ONE_MINUTE_MS)
        return WarpTarget.SkipWeekend(at, isBeyondDataset = at == null)
    }

    /**
     * The next London session open after [fromUtcMs].
     *
     * A session open is the first minute at which the session is open. If the
     * clock is already inside the London window, the next open is the following
     * day's, because jumping to "now" is a warp that does nothing.
     */
    fun nextLondonOpen(fromUtcMs: Long): WarpTarget {
        val at = findNextSessionOpen(fromUtcMs, TradingSession.LONDON)
        return WarpTarget.NextLondon(at, isBeyondDataset = at == null)
    }

    /** The next New York session open after [fromUtcMs]. */
    fun nextNewYorkOpen(fromUtcMs: Long): WarpTarget {
        val at = findNextSessionOpen(fromUtcMs, TradingSession.NEW_YORK)
        return WarpTarget.NextNewYork(at, isBeyondDataset = at == null)
    }

    /**
     * Five minutes before the next high-impact release after [fromUtcMs].
     *
     * # Why T-5m rather than the release
     *
     * The point is to let the player be *positioned* for a release, which means
     * arriving before it prints; jumping to the release instant would put them on
     * the wrong side of the move. Five minutes is the plan's figure.
     *
     * # Why a release nearer than five minutes is skipped
     *
     * If the next release is four minutes away, T-5m is already in the past and
     * jumping there would move historical time backwards, which the clock forbids
     * and the simulation must never do. That event is passed over and the search
     * continues, so the warp either lands strictly in the future or reports that
     * there is nothing to reach.
     */
    fun nextHighImpactNews(fromUtcMs: Long): WarpTarget {
        var cursor = fromUtcMs
        repeat(MAX_NEWS_PROBES) {
            val eventAt = nextHighImpactAtOrAfter(cursor + 1) ?: return NO_NEWS
            if (eventAt > bounds.endSimUtcMs) {
                return WarpTarget.NextHighImpactNews(
                    atUtcMs = null,
                    isBeyondDataset = true,
                    eventUtcMs = eventAt,
                )
            }
            val target = eventAt - NEWS_LEAD_MS
            if (target > fromUtcMs) {
                return WarpTarget.NextHighImpactNews(
                    atUtcMs = target,
                    isBeyondDataset = false,
                    eventUtcMs = eventAt,
                )
            }
            cursor = eventAt
        }
        return NO_NEWS
    }

    /**
     * The first open minute at or after [startUtcMs], or null past the dataset end.
     *
     * The day-step uses the calendar's own `nextMarketOpenUtcMs` to leap a whole
     * closure at once, which is what makes a two-day weekend one iteration rather
     * than 2,880.
     */
    private fun findNextOpen(startUtcMs: Long): Long? {
        if (startUtcMs > bounds.endSimUtcMs) return null
        var probe = startUtcMs
        var days = 0L
        while (probe <= bounds.endSimUtcMs && days <= MAX_LOOKAHEAD_DAYS) {
            if (NewYorkTradingCalendar.isMarketOpen(probe)) return probe
            val jumped = NewYorkTradingCalendar.nextMarketOpenUtcMs(probe)
            probe = if (jumped > probe) jumped else probe + ONE_MINUTE_MS
            days++
        }
        return null
    }

    /**
     * The first minute at or after [fromUtcMs] + one minute at which [session]
     * is open *and the dataset is trading*, or null past the dataset end.
     *
     * # Why this computes the window start instead of scanning minutes
     *
     * A session opens once per day at a fixed local time, so the candidate
     * instants are one per day, not 1,440. Scanning minutes across a 30-day
     * lookahead would be 43,200 iterations of a timezone conversion to answer a
     * question with 30 possible answers. Each day is instead asked directly:
     * when does this session open on that date, and is that instant in the
     * future?
     *
     * A candidate must also be a moment the dataset actually trades. A London
     * open that falls inside a detected dataset closure is not somewhere the
     * clock can usefully land, so it is skipped rather than returned.
     */
    private fun findNextSessionOpen(fromUtcMs: Long, session: TradingSession): Long? {
        val window = when (session) {
            TradingSession.LONDON -> SessionClassifier.LONDON_WINDOW
            TradingSession.NEW_YORK -> SessionClassifier.NEW_YORK_WINDOW
            else -> return null
        }
        val earliest = fromUtcMs + ONE_MINUTE_MS
        if (earliest > bounds.endSimUtcMs) return null

        var date = Instant.ofEpochMilli(earliest).atZone(window.zoneId).toLocalDate()
        repeat(MAX_LOOKAHEAD_DAYS.toInt()) {
            val candidate = date.atTime(window.start).atZone(window.zoneId).toInstant().toEpochMilli()
            if (candidate > bounds.endSimUtcMs) return null
            if (candidate >= earliest && NewYorkTradingCalendar.isMarketOpen(candidate)) {
                return candidate
            }
            date = date.plusDays(1)
        }
        return null
    }

    companion object {
        /** One minute: the dataset's native resolution. */
        const val ONE_MINUTE_MS = 60_000L

        /** How long before a release the warp lands (Step 12.6). */
        const val NEWS_LEAD_MS = 5 * ONE_MINUTE_MS

        /**
         * A bound on the day walk. Thirty days is longer than any closure this
         * app's datasets contain, so hitting it means the caller passed a
         * mismatched instant rather than that the market is shut for a month.
         */
        private const val MAX_LOOKAHEAD_DAYS = 30L

        /**
         * A bound on how many releases are examined before giving up. Each probe
         * advances past one release, so this bounds the work on a dataset whose
         * remaining schedule is dense with already-past events.
         */
        private const val MAX_NEWS_PROBES = 256

        private val NO_NEWS = WarpTarget.NextHighImpactNews(
            atUtcMs = null,
            isBeyondDataset = false,
            eventUtcMs = null,
        )
    }
}
