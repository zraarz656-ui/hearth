package com.tradequest.app.core.simulation.clock

/**
 * Injectable time sources (Step 3.1).
 *
 * The plan requires three concepts to be kept **separate**:
 *
 *  - wall-clock time — the real calendar, which can move backwards when the
 *    user or NTP corrects the device clock,
 *  - monotonic elapsed time — a counter that never decreases and is unaffected
 *    by clock corrections, but resets when the process restarts,
 *  - test-controlled time — advanced explicitly by a test.
 *
 * They are separate interfaces rather than one type with a mode flag, because
 * a caller that needs "real elapsed time" and a caller that needs "the current
 * date" have different failure modes, and a single type would let either one
 * silently receive the wrong answer.
 *
 * Nothing here reads the Android framework, so the whole clock subsystem runs
 * under a plain JVM test.
 */

/**
 * The real calendar clock, in UTC epoch milliseconds.
 *
 * Used for the simulation's real-time anchor, because Challenge Mode must keep
 * advancing while the app is closed (Step 3.3) and only a persisted wall-clock
 * reading can express that.
 */
fun interface WallClock {
    fun nowUtcMs(): Long
}

/**
 * A counter of elapsed time that never decreases.
 *
 * Used to measure real elapsed time *within* one process, where a wall-clock
 * correction would otherwise produce a negative or inflated interval.
 * It has no defined epoch, so it must never be interpreted as a date.
 */
fun interface MonotonicClock {
    fun elapsedMs(): Long
}

/** [WallClock] backed by [System.currentTimeMillis]. */
object SystemWallClock : WallClock {
    override fun nowUtcMs(): Long = System.currentTimeMillis()
}

/** [MonotonicClock] backed by [System.nanoTime], reported in milliseconds. */
object SystemMonotonicClock : MonotonicClock {
    override fun elapsedMs(): Long = System.nanoTime() / 1_000_000L
}

/**
 * A time source a test drives by hand.
 *
 * Deliberately mutable and deliberately not thread-safe: a test that shares one
 * instance across threads is a test whose timing is not reproducible, which
 * defeats the point of having this class at all.
 *
 * [wallUtcMs] and [elapsedMs] move independently so a test can express the case
 * that matters most — the wall clock jumping backwards while monotonic time
 * keeps rising.
 */
class TestTimeSource(
    startWallUtcMs: Long,
    startElapsedMs: Long = 0L,
) : WallClock, MonotonicClock {

    var wallUtcMs: Long = startWallUtcMs
        private set

    var elapsedMs: Long = startElapsedMs
        private set

    override fun nowUtcMs(): Long = wallUtcMs

    override fun elapsedMs(): Long = elapsedMs

    /** Moves both clocks forward together, the normal case. */
    fun advance(ms: Long) {
        require(ms >= 0) { "advance requires a nonnegative interval, was $ms" }
        wallUtcMs += ms
        elapsedMs += ms
    }

    /**
     * Moves only the wall clock, leaving monotonic time alone.
     *
     * A negative [ms] models a device-clock correction backwards.
     */
    fun shiftWallClock(ms: Long) {
        wallUtcMs += ms
    }

    fun setWallClock(utcMs: Long) {
        wallUtcMs = utcMs
    }
}
