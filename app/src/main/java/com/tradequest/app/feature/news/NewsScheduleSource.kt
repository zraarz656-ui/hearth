package com.tradequest.app.feature.news

import com.tradequest.app.core.news.NewsRelease
import com.tradequest.app.core.simulation.warp.WarpPermission
import com.tradequest.app.core.simulation.warp.WarpTarget
import kotlinx.coroutines.flow.Flow

/**
 * What the News feature needs from the rest of the app (Steps 12.2-12.7).
 *
 * # Why a port rather than the repository
 *
 * Architecture rule 4 says a feature must not reach into `core/database`, and the
 * News screen needs more than one source: the release schedule, the *simulated*
 * instant to project it against, and the current season's mode and exposure to
 * decide whether a warp is allowed. Those live in different places, and only the
 * composition root knows all of them. So the screen asks one port for exactly what
 * it needs and never learns where any of it came from.
 *
 * # Why the projection happens behind this port
 *
 * [releasesAt] returns already-projected [NewsRelease] values, not raw events. That
 * keeps the point-in-time rule (Step 12.4) on the domain side of the boundary: the
 * screen is handed events that already cannot reveal a future outcome, rather than
 * being trusted to hide one. A screen that never receives an actual cannot leak it.
 *
 * # Why the instant is a parameter, not a flow
 *
 * The screen is viewing a *historical* schedule, not following the clock in real
 * time. It passes the instant it is showing and gets the projection for that
 * instant, which makes the screen's content a pure function of its own state and
 * keeps the polling loop out of the ViewModel, for the same reason
 * `TradingSessionSource.simInstants` is a flow owned by the container.
 */
interface NewsScheduleSource {

    /**
     * The releases in `[fromUtcMs, toUtcMs)`, projected as known at [simUtcMs].
     *
     * Ordered by scheduled instant. An event scheduled after [simUtcMs] comes back
     * as a `NewsRelease.Pending` carrying no actual, whatever is stored.
     */
    suspend fun releasesAt(
        datasetId: String,
        fromUtcMs: Long,
        toUtcMs: Long,
        simUtcMs: Long,
    ): List<NewsRelease>

    /**
     * The dataset the running season trades, or null when no season is running.
     *
     * The calendar needs a dataset to show, and "the one the player is currently
     * trading" is the only sensible default; with no season there is nothing to
     * project against a simulated instant.
     */
    suspend fun activeDatasetId(): String?

    /** The simulated instant the season is at, or null when none is running. */
    fun currentSimUtcMs(): Long?

    /**
     * Whether the running season is in Blind Mode (Step 12.5).
     *
     * Read here rather than passed by the screen because Blind Mode is a property of
     * the season's mode, which the screen must not have to know how to derive. A
     * screen that guessed would show absolute dates for a challenge, which is the
     * leak the mode exists to prevent.
     */
    suspend fun isBlind(): Boolean

    /** The first and last stored minute for [datasetId], or null when empty. */
    suspend fun datasetBounds(datasetId: String): Pair<Long, Long>?

    /**
     * Whether a Time Warp is permitted right now (Step 12.7).
     *
     * Read from the live session, so the counts are the ones the trading engine
     * would act on rather than a snapshot the screen took earlier.
     */
    suspend fun warpPermission(): WarpPermission

    /** Resolves a warp destination (Step 12.6). Null when no season is running. */
    suspend fun resolveWarp(kind: WarpKind): WarpTarget?

    /**
     * Performs a warp, processing every intervening market and trading event
     * (Step 12.7).
     *
     * Returns the instant reached, or null when nothing happened. The processing is
     * part of this call and not the caller's job: Step 12.7 requires the events
     * between here and the destination to be handled, and a screen that advanced the
     * clock without them would leave the account wrong.
     */
    suspend fun performWarp(target: WarpTarget): Long?

    /**
     * Emits the simulated instant whenever it changes, so the calendar can follow
     * the clock after a warp.
     *
     * Empty when no season is running.
     */
    fun simInstants(): Flow<Long>
}

/** The four warps Step 12.6 specifies. */
enum class WarpKind {
    SKIP_WEEKEND,
    NEXT_LONDON,
    NEXT_NEW_YORK,
    NEXT_HIGH_IMPACT_NEWS,
}
