package com.tradequest.app.core.simulation.clock

/**
 * The persisted anchoring state of the simulation clock (Step 3.5).
 *
 * # Why an anchor and not an offset
 *
 * Step 3.5 is explicit: do not depend solely on a fixed seven-day offset.
 * Instead the clock stores *two* anchors and derives historical time from them:
 *
 *  - [historicalAnchorSimUtcMs] — the simulated (historical) moment,
 *  - [realAnchorUtcMs] — the real wall-clock moment that corresponded to it.
 *
 * Historical time is then a function of the *difference* between the stored
 * real anchor and the current real time. This survives an app being closed for
 * any length of time, because the interval is measured rather than assumed, and
 * it survives a device-clock correction because the correction moves both
 * readings.
 *
 * This type is immutable. Every mutation of the clock re-anchors, which is what
 * makes playback-speed changes safe: see [withSpeed].
 */
data class ClockAnchor(
    val historicalAnchorSimUtcMs: Long,
    val realAnchorUtcMs: Long,
    val mode: ClockMode,
    val speed: PlaybackSpeed,
    val isPaused: Boolean,
    /**
     * The simulated time the replay/trading pipeline has fully processed.
     *
     * Historical time may legitimately run ahead of this while the app was
     * closed (Step 3.3). The gap is the work the pipeline still owes, not an
     * error.
     */
    val lastCommittedSimUtcMs: Long,
) {
    /** Re-anchors at [simUtcMs] without changing speed, mode or pause state. */
    fun anchoredAt(simUtcMs: Long, realUtcMs: Long): ClockAnchor =
        copy(historicalAnchorSimUtcMs = simUtcMs, realAnchorUtcMs = realUtcMs)

    /**
     * Changes playback speed, re-anchoring at [currentSimUtcMs] first.
     *
     * Re-anchoring is the whole point. Elapsed real time must not be rescaled
     * by a speed the user chose afterwards: with 10 minutes of real time at 1x
     * already behind us, switching to 60x must not retroactively credit 600
     * simulated minutes. Only the interval *after* the change is affected.
     */
    fun withSpeed(
        newSpeed: PlaybackSpeed,
        currentSimUtcMs: Long,
        realUtcMs: Long,
    ): ClockAnchor = copy(
        historicalAnchorSimUtcMs = currentSimUtcMs,
        realAnchorUtcMs = realUtcMs,
        speed = newSpeed,
    )

    /**
     * Freezes historical time at [currentSimUtcMs].
     *
     * The anchor is moved to the frozen moment, so a later [resume] measures
     * elapsed time from the resume, not from the original anchor. Time spent
     * paused is therefore not credited to the simulation.
     */
    fun pausedAt(currentSimUtcMs: Long, realUtcMs: Long): ClockAnchor =
        copy(
            historicalAnchorSimUtcMs = currentSimUtcMs,
            realAnchorUtcMs = realUtcMs,
            isPaused = true,
        )

    /** Unfreezes, measuring elapsed time from now. */
    fun resumedAt(currentSimUtcMs: Long, realUtcMs: Long): ClockAnchor =
        copy(
            historicalAnchorSimUtcMs = currentSimUtcMs,
            realAnchorUtcMs = realUtcMs,
            isPaused = false,
        )

    fun committedTo(simUtcMs: Long): ClockAnchor = copy(lastCommittedSimUtcMs = simUtcMs)
}

/**
 * How the simulation advances (Steps 3.3, 3.4).
 *
 * Defined here rather than reusing `core.database.player.SeasonMode`, because
 * `core.simulation` must not depend on `core.database` (`docs/architecture.md`
 * rule 1). The two are mapped at the persistence boundary, and that mapping is
 * covered by test so they cannot drift apart silently.
 */
enum class ClockMode {
    /** Advances at 1x real elapsed time, and keeps running while closed. */
    CHALLENGE,

    /** User-controlled speed, pausable. */
    PRACTICE,
}

/**
 * Allowed playback speeds (Step 3.4).
 *
 * An enum, not an `Int`, so an unsupported speed cannot be persisted or
 * restored. Challenge Mode is restricted to [X1] by [SimulationClock].
 */
enum class PlaybackSpeed(val multiplier: Int) {
    X1(1),
    X2(2),
    X5(5),
    X10(10),
    X30(30),
    X60(60),
    ;

    companion object {
        /** The speed used when none is stored. */
        val DEFAULT: PlaybackSpeed = X1

        /** Resolves a persisted multiplier, or null when it is not a known speed. */
        fun fromMultiplier(multiplier: Int): PlaybackSpeed? =
            entries.firstOrNull { it.multiplier == multiplier }
    }
}

/**
 * The dataset bounds the clock may move within (Step 3.8).
 *
 * The simulation must stop at the dataset endpoint and must never advance into
 * another dataset, so the bounds are part of the clock's own state rather than
 * a check the caller is trusted to perform.
 */
data class DatasetBounds(
    val startSimUtcMs: Long,
    val endSimUtcMs: Long,
) {
    init {
        require(endSimUtcMs >= startSimUtcMs) {
            "dataset end $endSimUtcMs precedes start $startSimUtcMs"
        }
    }

    fun clamp(simUtcMs: Long): Long = simUtcMs.coerceIn(startSimUtcMs, endSimUtcMs)

    fun contains(simUtcMs: Long): Boolean = simUtcMs in startSimUtcMs..endSimUtcMs
}

/** Why the clock stopped advancing, when it did. */
enum class ClockStopReason {
    /** Running normally. */
    NONE,

    /** Paused by the user. */
    PAUSED,

    /** The dataset endpoint has been reached (Step 3.8). */
    DATASET_END,
}

/**
 * A computed reading of the clock. Returned by [SimulationClock.read].
 *
 * [pendingSimMs] is how far historical time has run ahead of what the pipeline
 * has processed. It is expected to be nonzero after the app has been closed.
 */
data class ClockReading(
    val simUtcMs: Long,
    val lastCommittedSimUtcMs: Long,
    val speed: PlaybackSpeed,
    val mode: ClockMode,
    val isPaused: Boolean,
    val stopReason: ClockStopReason,
) {
    val pendingSimMs: Long get() = (simUtcMs - lastCommittedSimUtcMs).coerceAtLeast(0L)

    val atDatasetEnd: Boolean get() = stopReason == ClockStopReason.DATASET_END

    val isAdvancing: Boolean get() = stopReason == ClockStopReason.NONE
}

/**
 * Pure historical-time arithmetic.
 *
 * Separated from [SimulationClock] so the derivation can be tested without a
 * clock instance, a coroutine, or a time source.
 */
object ClockMath {

    /**
     * Historical time implied by [anchor] at real time [realNowUtcMs].
     *
     * Three deliberate properties:
     *
     * 1. **Monotonic.** A device clock that jumps backwards must not rewind the
     *    simulation, so a negative elapsed interval is treated as zero. The
     *    simulation freezes until real time catches up rather than replaying.
     * 2. **Bounded.** The result is clamped to [bounds], so it can never leave
     *    the dataset (Step 3.8).
     * 3. **Overflow-safe.** Elapsed time is capped at what is needed to reach
     *    the endpoint before multiplying by speed, so a device clock set to the
     *    year 3000 cannot overflow the multiplication.
     */
    fun simTimeAt(
        anchor: ClockAnchor,
        realNowUtcMs: Long,
        bounds: DatasetBounds,
    ): Long {
        if (anchor.isPaused) return bounds.clamp(anchor.historicalAnchorSimUtcMs)

        val elapsedRealMs = (realNowUtcMs - anchor.realAnchorUtcMs).coerceAtLeast(0L)
        val speed = anchor.speed.multiplier.toLong()

        // Real milliseconds still needed to reach the endpoint. Capping elapsed
        // time at this value bounds the multiplication below.
        val remainingSimMs = (bounds.endSimUtcMs - anchor.historicalAnchorSimUtcMs).coerceAtLeast(0L)
        val neededRealMs = remainingSimMs / speed
        val cappedElapsedMs = elapsedRealMs.coerceAtMost(neededRealMs + 1L)

        val advancedSimMs = cappedElapsedMs * speed
        val raw = anchor.historicalAnchorSimUtcMs + advancedSimMs
        return bounds.clamp(raw)
    }

    /** Why the clock is not advancing, given a [reading] at [simUtcMs]. */
    fun stopReason(
        anchor: ClockAnchor,
        simUtcMs: Long,
        bounds: DatasetBounds,
    ): ClockStopReason = when {
        anchor.isPaused -> ClockStopReason.PAUSED
        simUtcMs >= bounds.endSimUtcMs -> ClockStopReason.DATASET_END
        else -> ClockStopReason.NONE
    }
}
