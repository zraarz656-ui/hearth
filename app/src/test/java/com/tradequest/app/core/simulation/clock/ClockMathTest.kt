package com.tradequest.app.core.simulation.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct tests of [ClockMath], the pure derivation of historical time.
 *
 * [SimulationClock] is tested through its controls in `SimulationClockTest`.
 * These tests pin the arithmetic itself, including the cases that are awkward
 * to reach through the public API.
 */
class ClockMathTest {

    private val start = 1_700_000_000_000L
    private val end = start + 10L * 24 * 60 * 60 * 1000
    private val bounds = DatasetBounds(start, end)

    private fun anchor(
        sim: Long = start,
        real: Long = 1_800_000_000_000L,
        speed: PlaybackSpeed = PlaybackSpeed.X1,
        paused: Boolean = false,
    ) = ClockAnchor(
        historicalAnchorSimUtcMs = sim,
        realAnchorUtcMs = real,
        mode = ClockMode.PRACTICE,
        speed = speed,
        isPaused = paused,
        lastCommittedSimUtcMs = sim,
    )

    @Test
    fun `no real time passing leaves historical time at the anchor`() {
        assertEquals(start, ClockMath.simTimeAt(anchor(), 1_800_000_000_000L, bounds))
    }

    @Test
    fun `real time advances historical time one for one at 1x`() {
        assertEquals(
            start + 90_000L,
            ClockMath.simTimeAt(anchor(), 1_800_000_000_000L + 90_000L, bounds),
        )
    }

    @Test
    fun `speed scales the advance`() {
        for (speed in PlaybackSpeed.entries) {
            assertEquals(
                "speed $speed",
                start + 1_000L * speed.multiplier,
                ClockMath.simTimeAt(anchor(speed = speed), 1_800_000_000_000L + 1_000L, bounds),
            )
        }
    }

    @Test
    fun `a paused anchor never moves`() {
        assertEquals(
            start,
            ClockMath.simTimeAt(anchor(paused = true), 1_800_000_000_000L + 86_400_000L, bounds),
        )
    }

    @Test
    fun `a real clock that moves backwards does not move historical time back`() {
        // The interval is negative. Treated as zero rather than as a rewind.
        assertEquals(
            start,
            ClockMath.simTimeAt(anchor(), 1_800_000_000_000L - 500_000L, bounds),
        )
    }

    @Test
    fun `the result is clamped to the dataset end`() {
        assertEquals(
            end,
            ClockMath.simTimeAt(anchor(), 1_800_000_000_000L + 1_000_000_000L, bounds),
        )
    }

    @Test
    fun `a very large elapsed interval does not overflow`() {
        val far = ClockAnchor(
            historicalAnchorSimUtcMs = start,
            realAnchorUtcMs = Long.MIN_VALUE / 2,
            mode = ClockMode.PRACTICE,
            speed = PlaybackSpeed.X60,
            isPaused = false,
            lastCommittedSimUtcMs = start,
        )
        // Must clamp, not wrap around to a negative or arbitrary value.
        assertEquals(end, ClockMath.simTimeAt(far, Long.MAX_VALUE / 2, bounds))
    }

    @Test
    fun `a long elapsed interval at 60x still lands exactly on the endpoint`() {
        val a = anchor(speed = PlaybackSpeed.X60)
        val neededRealMs = (end - start) / 60
        assertEquals(end, ClockMath.simTimeAt(a, 1_800_000_000_000L + neededRealMs, bounds))
    }

    @Test
    fun `stop reason is NONE while running`() {
        assertEquals(
            ClockStopReason.NONE,
            ClockMath.stopReason(anchor(), start + 1_000L, bounds),
        )
    }

    @Test
    fun `stop reason is PAUSED when paused`() {
        assertEquals(
            ClockStopReason.PAUSED,
            ClockMath.stopReason(anchor(paused = true), start, bounds),
        )
    }

    @Test
    fun `stop reason is DATASET_END at the endpoint`() {
        assertEquals(
            ClockStopReason.DATASET_END,
            ClockMath.stopReason(anchor(), end, bounds),
        )
    }

    @Test
    fun `a paused clock at the endpoint reports PAUSED, not DATASET_END`() {
        // Pause is the more specific fact: the clock is not advancing because
        // the user paused it, and resuming is a meaningful action.
        assertEquals(
            ClockStopReason.PAUSED,
            ClockMath.stopReason(anchor(paused = true), end, bounds),
        )
    }

    @Test
    fun `dataset bounds reject an inverted range`() {
        val error = runCatching { DatasetBounds(100L, 50L) }.exceptionOrNull()
        assertTrue("an inverted range must be rejected", error is IllegalArgumentException)
    }

    @Test
    fun `dataset bounds accept a single instant`() {
        val single = DatasetBounds(start, start)
        assertEquals(start, single.clamp(start + 1_000L))
        assertTrue(single.contains(start))
    }

    @Test
    fun `changing speed re-anchors without rescaling the past`() {
        val a = anchor(sim = start, real = 1_800_000_000_000L)
        val tenMinutesLater = 1_800_000_000_000L + 600_000L
        val atSwitch = ClockMath.simTimeAt(a, tenMinutesLater, bounds)
        assertEquals(start + 600_000L, atSwitch)

        val switched = a.withSpeed(PlaybackSpeed.X60, atSwitch, tenMinutesLater)

        // The elapsed ten minutes stay ten minutes.
        assertEquals(atSwitch, ClockMath.simTimeAt(switched, tenMinutesLater, bounds))
        // Only time after the switch is accelerated.
        assertEquals(
            atSwitch + 60_000L,
            ClockMath.simTimeAt(switched, tenMinutesLater + 1_000L, bounds),
        )
    }
}
