package com.tradequest.app.core.chart.gestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 9.2 tests: fling inertia.
 *
 * The two that matter are the sign test — a fling must travel the way it was
 * thrown — and the termination test, because an exponential that never reaches
 * zero is a frame loop that never idles, which is a battery bug rather than a
 * visible one.
 */
class ChartFlingTest {

    private val dt = 1f / 60f

    /** Runs a fling to completion, returning the number of steps it took. */
    private fun runToRest(fling: ChartFling, maxSteps: Int = 10_000): Int {
        var steps = 0
        while (fling.isActive && steps < maxSteps) {
            fling.step(dt)
            steps++
        }
        return steps
    }

    @Test
    fun `a fling travels in the direction it was thrown`() {
        val forward = ChartFling(initialVelocityPxPerSec = 3_000f)
        val backward = ChartFling(initialVelocityPxPerSec = -3_000f)

        runToRest(forward)
        runToRest(backward)

        assertTrue("a positive throw must travel positively", forward.distancePx > 0f)
        assertTrue("a negative throw must travel negatively", backward.distancePx < 0f)
        assertEquals(
            "the two directions must travel the same distance",
            forward.distancePx,
            -backward.distancePx,
            0.01f,
        )
    }

    @Test
    fun `a fling eventually stops`() {
        val fling = ChartFling(initialVelocityPxPerSec = 5_000f)
        val steps = runToRest(fling)

        assertFalse("the fling never came to rest", fling.isActive)
        assertEquals("velocity must be exactly zero at rest", 0f, fling.velocityPxPerSec, 0f)
        // A sanity bound: at 60fps this is roughly a second, not thousands of
        // frames. If the floor were missing this would hit maxSteps.
        assertTrue("the fling took an implausible $steps steps", steps in 1..300)
    }

    @Test
    fun `a slower throw travels less far`() {
        val soft = ChartFling(initialVelocityPxPerSec = 1_000f)
        val hard = ChartFling(initialVelocityPxPerSec = 4_000f)

        runToRest(soft)
        runToRest(hard)

        assertTrue("a harder throw must travel further", hard.distancePx > soft.distancePx)
    }

    @Test
    fun `the travelled distance approaches the modelled asymptote`() {
        val velocity = 2_500f
        val fling = ChartFling(initialVelocityPxPerSec = velocity)
        runToRest(fling)

        val expected = ChartFling.totalDistancePx(velocity)
        // Stepping stops once below the floor, so the total is just short of the
        // asymptote; within a few percent is the assertion with teeth.
        assertEquals("stepped distance diverged from the model", expected, fling.distancePx, expected * 0.05f)
    }

    @Test
    fun `a step returns the distance it covered`() {
        val fling = ChartFling(initialVelocityPxPerSec = 1_000f)
        val first = fling.step(dt)
        assertTrue("the first step must move", first > 0f)
        assertEquals("the returned step must be the total travelled", first, fling.distancePx, 0.001f)

        val second = fling.step(dt)
        assertTrue("the second step must be shorter than the first", second < first)
    }

    @Test
    fun `a non-positive or non-finite step interval ends the fling`() {
        for (bad in listOf(0f, -0.016f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val fling = ChartFling(initialVelocityPxPerSec = 1_000f)
            val delta = fling.step(bad)
            assertEquals("a $bad interval must move nothing", 0f, delta, 0f)
            assertFalse("a $bad interval must end the fling", fling.isActive)
        }
    }

    @Test
    fun `a fling below the floor is not active and does not move`() {
        val fling = ChartFling(initialVelocityPxPerSec = ChartFling.MIN_VELOCITY_PX_PER_SEC / 2f)
        assertFalse(fling.isActive)
        assertEquals(0f, fling.step(dt), 0f)
    }

    @Test
    fun `cancelling stops the fling where it is`() {
        val fling = ChartFling(initialVelocityPxPerSec = 3_000f)
        fling.step(dt)
        val travelled = fling.distancePx

        fling.cancel()

        assertFalse(fling.isActive)
        assertEquals("cancelling must not undo the travel", travelled, fling.distancePx, 0f)
        assertEquals("cancelling must not add travel", 0f, fling.step(dt), 0f)
    }

    @Test
    fun `a non-finite initial velocity is treated as no fling`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val fling = ChartFling(initialVelocityPxPerSec = bad)
            assertFalse("a $bad velocity must not fling", fling.isActive)
        }
    }

    // ---------- isFling ----------

    @Test
    fun `a deliberate throw counts as a fling`() {
        assertTrue(ChartFling.isFling(1_000f))
        assertTrue(ChartFling.isFling(-1_000f))
    }

    @Test
    fun `a slow drag does not count as a fling`() {
        assertFalse(ChartFling.isFling(10f))
        assertFalse(ChartFling.isFling(0f))
    }

    @Test
    fun `a non-finite velocity does not count as a fling`() {
        assertFalse(ChartFling.isFling(Float.NaN))
        assertFalse(ChartFling.isFling(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `the travel asymptote scales linearly with velocity`() {
        val single = ChartFling.totalDistancePx(1_000f)
        val double = ChartFling.totalDistancePx(2_000f)
        assertEquals("travel must double when velocity doubles", single * 2f, double, 0.01f)
    }
}
