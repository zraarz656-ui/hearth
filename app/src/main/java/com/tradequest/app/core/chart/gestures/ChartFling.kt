package com.tradequest.app.core.chart.gestures

import kotlin.math.abs
import kotlin.math.exp

/**
 * Fling inertia for a panned chart (Step 9.2).
 *
 * # The model
 *
 * Exponential decay: `v(t) = v0 * e^(-k t)`. Distance is the integral of that,
 * `v0 / k * (1 - e^(-k t))`, which converges on `v0 / k` — so a fling has a finite
 * travel distance that grows linearly with the throw. That is what makes a hard
 * flick go further than a soft one without ever running away.
 *
 * # Why it is stepped rather than pre-computed
 *
 * The chart must be able to stop a fling the moment it reaches the data edge, and
 * it can only know that per frame: the clamp is applied to the viewport after each
 * step. A pre-computed target distance would have to be clamped up front, which
 * would either overshoot the edge or cut the fling short of it. Stepping also
 * means the same code runs on every frame interval, so a dropped frame slows the
 * fling rather than teleporting it.
 *
 * # Units
 *
 * Velocity is in **pixels per second**, the unit the platform's `VelocityTracker`
 * reports, and distance is in pixels. The caller converts to bars using the candle
 * width, because that is the only place that knows the zoom. Keeping pixels here
 * means a fling travels the same physical distance on screen at any zoom, which is
 * what a finger expects: the chart was thrown, not the data.
 *
 * Not thread-safe; a fling belongs to the frame loop that drives it.
 */
class ChartFling(
    initialVelocityPxPerSec: Float,
    private val decayPerSecond: Float = DEFAULT_DECAY_PER_SECOND,
) {

    /**
     * Remaining velocity, in pixels per second.
     *
     * Reaches exactly `0f` when it falls below [MIN_VELOCITY_PX_PER_SEC], rather
     * than decaying forever. Without that floor an exponential never terminates and
     * the frame loop would never idle.
     */
    var velocityPxPerSec: Float =
        if (initialVelocityPxPerSec.isFinite()) initialVelocityPxPerSec else 0f
        private set

    /** Total distance travelled so far, in pixels, signed. */
    var distancePx: Float = 0f
        private set

    /** True while the fling still has velocity worth animating. */
    val isActive: Boolean get() = abs(velocityPxPerSec) >= MIN_VELOCITY_PX_PER_SEC

    /**
     * Advances by [dtSeconds] and returns the distance covered in that step, signed.
     *
     * A non-positive or non-finite [dtSeconds] ends the fling instead of producing a
     * zero-length step: a frame clock that reports a bad interval means the frame
     * loop cannot be trusted to continue, and continuing would be a spin.
     */
    fun step(dtSeconds: Float): Float {
        if (!isActive) return 0f
        if (!dtSeconds.isFinite() || dtSeconds <= 0f) {
            velocityPxPerSec = 0f
            return 0f
        }

        // Exact integral over the step, not `v * dt`: at a large dt the
        // instantaneous form overestimates the distance, because the velocity
        // decays across the interval.
        val decay = exp(-decayPerSecond * dtSeconds)
        val delta = velocityPxPerSec / decayPerSecond * (1f - decay)

        distancePx += delta
        velocityPxPerSec *= decay
        if (abs(velocityPxPerSec) < MIN_VELOCITY_PX_PER_SEC) velocityPxPerSec = 0f
        return delta
    }

    /** Ends the fling immediately, for a new touch or a clamp. */
    fun cancel() {
        velocityPxPerSec = 0f
    }

    companion object {
        /**
         * Friction, per second. The reciprocal is the fling's time constant, so
         * this is a throw that is essentially over in about 600ms — long enough to
         * read as momentum, short enough not to feel loose.
         */
        const val DEFAULT_DECAY_PER_SECOND: Float = 5f

        /**
         * Below this, the fling is over. In pixels per second, matching
         * `ViewConfiguration`'s sense of a deliberate movement rather than drift.
         */
        const val MIN_VELOCITY_PX_PER_SEC: Float = 50f

        /**
         * True when [velocityPxPerSec] is fast enough to be worth a fling.
         *
         * A drag that ends slowly should stop where the finger left it; coasting on
         * from a few pixels per second reads as the chart drifting on its own.
         */
        fun isFling(velocityPxPerSec: Float): Boolean =
            velocityPxPerSec.isFinite() && abs(velocityPxPerSec) >= MIN_VELOCITY_PX_PER_SEC

        /**
         * Total distance a fling at [velocityPxPerSec] would cover, in pixels.
         *
         * The asymptote of [step]'s integral. Used by tests and by anything that
         * needs the travel without simulating frames.
         */
        fun totalDistancePx(
            velocityPxPerSec: Float,
            decayPerSecond: Float = DEFAULT_DECAY_PER_SECOND,
        ): Float = if (velocityPxPerSec.isFinite()) velocityPxPerSec / decayPerSecond else 0f
    }
}
