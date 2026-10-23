package com.tradequest.app.core.simulation.clock

/**
 * Persistence port for the simulation clock (Step 3.5).
 *
 * Declared here, in `core.simulation`, and implemented by the database layer.
 * This is what keeps `core.simulation` free of any dependency on
 * `core.database`, as `docs/architecture.md` rule 1 requires. The dependency
 * points inwards: the database knows about the clock, not the reverse.
 *
 * Implementations must persist durably before returning, because the value
 * returned by [load] is what lets a season survive the app being closed.
 */
interface ClockStore {

    /** The stored anchor for [seasonId], or null when the season has none. */
    suspend fun load(seasonId: String): ClockAnchor?

    /** Writes the anchor for [seasonId], replacing any previous value. */
    suspend fun save(seasonId: String, anchor: ClockAnchor)

    suspend fun delete(seasonId: String)
}

/**
 * A [ClockStore] that keeps everything in memory.
 *
 * Used by tests, and by any code path that needs a clock before a season has
 * been persisted. It is not a stub standing in for unfinished work: it is a
 * complete, correct implementation of the port with process-lifetime scope.
 */
class InMemoryClockStore : ClockStore {

    private val anchors = mutableMapOf<String, ClockAnchor>()

    override suspend fun load(seasonId: String): ClockAnchor? = anchors[seasonId]

    override suspend fun save(seasonId: String, anchor: ClockAnchor) {
        anchors[seasonId] = anchor
    }

    override suspend fun delete(seasonId: String) {
        anchors.remove(seasonId)
    }
}

/**
 * The authoritative virtual time system (Steps 3.2 to 3.8).
 *
 * # Design
 *
 * The clock holds an immutable [ClockAnchor] and derives historical time from
 * it on demand via [ClockMath]. There is no background ticker and no mutable
 * "current time" field, which has three consequences that matter:
 *
 *  - **No drift.** Historical time is a pure function of the anchor and the
 *    real clock, so reading it twice at the same instant cannot disagree, and
 *    a ticker that is delayed by a busy main thread does not lose time.
 *  - **Restart-safe.** After a restart, the persisted anchor plus the current
 *    real time reconstruct historical time exactly, including the interval the
 *    app was closed (Step 3.3).
 *  - **Testable.** Time is injected, so every case below is exercised without
 *    sleeping.
 *
 * # What this class deliberately does not do
 *
 * It does not process the market. Moving historical time forward and *acting*
 * on the moved time are separate concerns; the replay processor owns the
 * latter. The clock's job is to say truthfully how much time has passed and
 * how much of it the pipeline still owes ([ClockReading.pendingSimMs]).
 */
class SimulationClock(
    private val store: ClockStore,
    private val wallClock: WallClock,
    private val monotonicClock: MonotonicClock,
) {

    private var anchor: ClockAnchor? = null
    private var bounds: DatasetBounds? = null
    private var seasonId: String? = null

    /**
     * The furthest historical time this clock has ever reported.
     *
     * Historical time must never move backwards. [ClockMath.simTimeAt] alone
     * cannot guarantee that, because a device-clock correction backwards makes
     * the elapsed interval negative and the derived time would fall back to the
     * anchor. This high-water mark is the floor that prevents the rewind.
     *
     * It is process-scoped rather than persisted: on restart the simulation is
     * reconstructed from the anchor, and any real time that passed is credited
     * forward. Nothing needs to be stored to keep the invariant across a
     * restart, because a restart cannot produce a negative interval.
     */
    private var highWaterSimUtcMs: Long? = null

    /** The season this clock is driving, or null before [attach]. */
    val currentSeasonId: String? get() = seasonId

    val isAttached: Boolean get() = anchor != null

    /**
     * Binds the clock to [seasonId] and its dataset bounds.
     *
     * When a stored anchor exists it is restored, subject to [bounds]: a stored
     * position outside the current dataset is clamped, because a dataset must
     * never be entered part-way through from another dataset's coordinates
     * (Step 3.8).
     *
     * [startSimUtcMs] is used only when no anchor is stored, so a restored
     * season keeps its own position rather than being reset to the dataset
     * start.
     */
    suspend fun attach(
        seasonId: String,
        bounds: DatasetBounds,
        startSimUtcMs: Long,
        mode: ClockMode,
        speed: PlaybackSpeed = PlaybackSpeed.DEFAULT,
    ) {
        this.seasonId = seasonId
        this.bounds = bounds

        val stored = store.load(seasonId)
        anchor = if (stored == null) {
            val sim = bounds.clamp(startSimUtcMs)
            val now = wallClock.nowUtcMs()
            ClockAnchor(
                historicalAnchorSimUtcMs = sim,
                realAnchorUtcMs = now,
                mode = mode,
                speed = effectiveSpeed(mode, speed),
                isPaused = false,
                lastCommittedSimUtcMs = sim,
            ).also { store.save(seasonId, it) }
        } else {
            val sim = bounds.clamp(stored.historicalAnchorSimUtcMs)
            stored.copy(
                historicalAnchorSimUtcMs = sim,
                lastCommittedSimUtcMs = bounds.clamp(stored.lastCommittedSimUtcMs),
                speed = effectiveSpeed(stored.mode, stored.speed),
            )
        }

        // A new season starts a new timeline. Carrying the previous season's
        // high-water mark across would pin the new season at the old one's
        // furthest point.
        highWaterSimUtcMs = anchor?.historicalAnchorSimUtcMs
    }

    /** Detaches, so no further reads are served. Persisted state is untouched. */
    fun detach() {
        anchor = null
        bounds = null
        seasonId = null
        highWaterSimUtcMs = null
    }

    /** The current reading, or null when not attached. */
    fun read(): ClockReading? {
        val current = anchor ?: return null
        val limits = bounds ?: return null
        val derived = ClockMath.simTimeAt(current, wallClock.nowUtcMs(), limits)
        val sim = observe(derived, limits)
        return ClockReading(
            simUtcMs = sim,
            lastCommittedSimUtcMs = current.lastCommittedSimUtcMs,
            speed = current.speed,
            mode = current.mode,
            isPaused = current.isPaused,
            stopReason = ClockMath.stopReason(current, sim, limits),
        )
    }

    /**
     * Applies the monotonic floor to [derived] and records the new high-water
     * mark.
     *
     * Every path that reports historical time goes through here, so the
     * "never rewinds" invariant has exactly one implementation.
     */
    private fun observe(derived: Long, limits: DatasetBounds): Long {
        val floored = maxOf(derived, highWaterSimUtcMs ?: derived)
        val clamped = limits.clamp(floored)
        highWaterSimUtcMs = maxOf(highWaterSimUtcMs ?: clamped, clamped)
        return clamped
    }

    /** Historical time now, clamped to the dataset, or null when detached. */
    fun currentSimUtcMs(): Long? = read()?.simUtcMs

    // ---------- Controls (Step 3.2) ----------

    /**
     * Pauses. The anchor is moved to the frozen moment so the time spent paused
     * is not credited later.
     */
    suspend fun pause() = reanchor { current, realNow, _ ->
        current.pausedAt(currentSimOrAnchor(current, realNow), realNow)
    }

    /** Resumes from the frozen moment, measuring elapsed time from now. */
    suspend fun resume() = reanchor { current, realNow, _ ->
        current.resumedAt(currentSimOrAnchor(current, realNow), realNow)
    }

    /**
     * Sets playback speed, re-anchoring first (Step 3.4).
     *
     * Refuses a speed other than 1x in [ClockMode.CHALLENGE], because Step 3.3
     * fixes Challenge Mode at 1x real elapsed time. Silently accepting 60x in a
     * challenge would let a user finish a 20-trading-day challenge in minutes.
     */
    suspend fun setSpeed(speed: PlaybackSpeed): Boolean = reanchor { current, realNow, limits ->
        if (current.mode == ClockMode.CHALLENGE && speed != PlaybackSpeed.X1) {
            return@reanchor null
        }
        current.withSpeed(speed, observedNow(current, realNow, limits), realNow)
    }

    /**
     * Advances historical time by [simMs] of simulated time, ignoring real time.
     *
     * Used by Time Warp (Step 12.6) and by tests. The result is clamped to the
     * dataset, so a warp past the endpoint stops at the endpoint rather than
     * failing (Step 3.8).
     */
    suspend fun advanceBySimMs(simMs: Long) = reanchor { current, realNow, limits ->
        require(simMs >= 0) { "advanceBySimMs requires a nonnegative interval, was $simMs" }
        val from = observedNow(current, realNow, limits)
        val target = limits.clamp(from + simMs)
        current.anchoredAt(target, realNow)
    }

    /** Jumps to an absolute historical moment, clamped to the dataset. */
    suspend fun seekTo(simUtcMs: Long) = reanchor { current, realNow, limits ->
        current.anchoredAt(limits.clamp(simUtcMs), realNow)
    }

    /**
     * Records that the pipeline has fully processed up to [simUtcMs].
     *
     * The clock does not advance here; this only narrows
     * [ClockReading.pendingSimMs]. Committing a time beyond the current
     * historical time is refused, because the pipeline cannot have processed
     * the future.
     */
    suspend fun commit(simUtcMs: Long): Boolean {
        val current = anchor ?: return false
        val limits = bounds ?: return false
        val now = observedNow(current, wallClock.nowUtcMs(), limits)
        if (simUtcMs > now) return false
        val updated = current.committedTo(maxOf(simUtcMs, current.lastCommittedSimUtcMs))
        anchor = updated
        seasonId?.let { store.save(it, updated) }
        return true
    }

    /**
     * Recomputes the reading using monotonic time within this process.
     *
     * Provided for callers that measure a short real interval and must not be
     * affected by a device-clock correction mid-interval. It does not change
     * stored state; it reports the interval that monotonic time implies.
     */
    fun elapsedRealMsSince(startMonotonicMs: Long): Long =
        (monotonicClock.elapsedMs() - startMonotonicMs).coerceAtLeast(0L)

    private fun currentSimOrAnchor(current: ClockAnchor, realNow: Long): Long {
        val limits = bounds ?: return current.historicalAnchorSimUtcMs
        return observedNow(current, realNow, limits)
    }

    /** Historical time now, with the monotonic floor applied. */
    private fun observedNow(current: ClockAnchor, realNow: Long, limits: DatasetBounds): Long =
        observe(ClockMath.simTimeAt(current, realNow, limits), limits)

    /**
     * Applies [transform] to the anchor and persists the result.
     *
     * Returns false when detached or when [transform] declines by returning
     * null. Every control funnels through here so persistence and the
     * not-attached case are handled in exactly one place.
     */
    private suspend fun reanchor(
        transform: (ClockAnchor, Long, DatasetBounds) -> ClockAnchor?,
    ): Boolean {
        val current = anchor ?: return false
        val limits = bounds ?: return false
        val now = wallClock.nowUtcMs()
        val updated = transform(current, now, limits) ?: return false
        anchor = updated
        seasonId?.let { store.save(it, updated) }
        return true
    }

    /** Challenge Mode is always 1x, regardless of what was stored (Step 3.3). */
    private fun effectiveSpeed(mode: ClockMode, requested: PlaybackSpeed): PlaybackSpeed =
        if (mode == ClockMode.CHALLENGE) PlaybackSpeed.X1 else requested
}
