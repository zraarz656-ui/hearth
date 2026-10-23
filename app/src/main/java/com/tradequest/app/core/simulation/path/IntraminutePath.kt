package com.tradequest.app.core.simulation.path

import com.tradequest.app.core.market.HistoricalCandle

/**
 * The deterministic intraminute path of one candle (Steps 4.1-4.5).
 *
 * # What this is
 *
 * A candle's OHLC fixes four prices but says nothing about the order in which
 * they occurred. The chart and the trading engine both need that order, and
 * product rule 2.4 requires them to use *the same* one. This class is that
 * order: a piecewise-linear path from the open, through the candle's low and
 * high, to the close.
 *
 * # Shape (Step 4.1)
 *
 * - Bullish (`close > open`): open → low → high → close
 * - Bearish (`close < open`): open → high → low → close
 * - Doji (`close == open`): open → low → high → close, by documented tie-break
 *
 * The doji rule is arbitrary by necessity: with `open == close`, OHLC contains
 * no information that could distinguish a dip-then-rally from a rally-then-dip.
 * The rule is fixed so that the chart and execution agree, not because it is
 * more likely to be true. See `docs/adr-0002-intraminute-path.md`.
 *
 * # Exactness (Step 4.3)
 *
 * All prices are integer thousandths of a USD and all arithmetic is integer.
 * [priceAt] rounds once, in one place, with one documented rule, so the chart
 * and execution cannot disagree about a price. [firstTouchElapsedMs] is solved
 * exactly rather than by sampling, so a level is never missed between ticks.
 *
 * This type is immutable and cheap to hold; it is a pure function of
 * [identity].
 */
class IntraminutePath private constructor(
    val identity: PathIdentity,
    /** The path's waypoints, ascending by time, first at `0`, last at `CANDLE_DURATION_MS`. */
    val waypoints: List<PathWaypoint>,
) {

    val timestampUtcMs: Long get() = identity.timestampUtcMs
    val openUnits: Int get() = identity.openUnits
    val highUnits: Int get() = identity.highUnits
    val lowUnits: Int get() = identity.lowUnits
    val closeUnits: Int get() = identity.closeUnits

    /**
     * The price at [elapsedMs] into the minute, by linear interpolation.
     *
     * [elapsedMs] is clamped to `[0, CANDLE_DURATION_MS]`, so callers past the
     * end of the minute get the close rather than an extrapolation.
     *
     * Rounding is half away from zero, applied to the exact rational
     * intermediate. Both the chart and execution call this function, so they
     * agree by construction rather than by two implementations agreeing.
     */
    fun priceAt(elapsedMs: Int): Int {
        val elapsed = elapsedMs.coerceIn(0, CANDLE_DURATION_MS.toInt())
        val segment = segmentContaining(elapsed)
        val from = waypoints[segment]
        val to = waypoints[segment + 1]
        return interpolate(from, to, elapsed)
    }

    /**
     * The candle as it looks at [elapsedMs], using only traversed movement
     * (Step 4.4).
     *
     * The returned high and low are the extremes *so far*. Before the high is
     * reached the high is not yet the candle's high, which is the point: a
     * caller reading this cannot see the rest of the minute.
     */
    fun formingCandleAt(elapsedMs: Int): FormingCandle {
        val elapsed = elapsedMs.coerceIn(0, CANDLE_DURATION_MS.toInt())

        // Price is linear between waypoints, so its extreme on any traversed
        // segment is at a segment endpoint. Scanning the reached waypoints plus
        // the interpolated point is therefore exact, not an approximation.
        var high = Int.MIN_VALUE
        var low = Int.MAX_VALUE
        for (waypoint in waypoints) {
            if (waypoint.elapsedMs > elapsed) break
            if (waypoint.priceUnits > high) high = waypoint.priceUnits
            if (waypoint.priceUnits < low) low = waypoint.priceUnits
        }
        val current = priceAt(elapsed)
        if (current > high) high = current
        if (current < low) low = current

        return FormingCandle(
            timestampUtcMs = timestampUtcMs,
            openUnits = openUnits,
            highUnits = high,
            lowUnits = low,
            closeUnits = current,
            elapsedMs = elapsed,
        )
    }

    /**
     * The earliest integer millisecond at which the continuous path reaches
     * exactly [thresholdUnits], or null if it never does (Step 4.5).
     *
     * # Why this is solved and not sampled
     *
     * The plan forbids relying on displayed samples: a level can be touched and
     * left between two 1 Hz ticks, and a stop-loss that missed its trigger would
     * be a correctness bug, not a cosmetic one. So for each segment this solves
     *
     * ```
     * t* = t0 + (t1 - t0) * (T - p0) / (p1 - p0)
     * ```
     *
     * exactly, in integers, and rounds the crossing *up* to the first whole
     * millisecond at which the path has arrived. A threshold outside the
     * segment's price range is not touched by that segment and is skipped.
     *
     * # Touch, not "beyond"
     *
     * This reports a *touch*, which is what triggers a stop or a limit order:
     * the market reaching a level. Direction is deliberately not part of the
     * question, because a stop triggers from either side. A caller wanting
     * "strictly beyond" builds that on top.
     */
    fun firstTouchElapsedMs(thresholdUnits: Int): Int? =
        firstTouchFrom(0, thresholdUnits)

    /**
     * The earliest millisecond in `[fromElapsedMs, toElapsedMs)` at which the path
     * reaches exactly [thresholdUnits], or null.
     *
     * Used by the execution engine, which must not resolve a trigger beyond the
     * price it has already walked to: a trailing stop that saw a later price would
     * be reading the future.
     */
    fun firstTouchInRange(fromElapsedMs: Int, toElapsedMs: Int, thresholdUnits: Int): Int? {
        val found = firstTouchFrom(fromElapsedMs, thresholdUnits) ?: return null
        return if (found < toElapsedMs) found else null
    }

    /**
     * The earliest millisecond at or after [fromElapsedMs] at which the path
     * reaches exactly [thresholdUnits], or null.
     *
     * Used by the replay cursor: an order placed part-way through a minute must
     * not be filled by a crossing that already happened before it existed.
     */
    fun firstTouchFrom(fromElapsedMs: Int, thresholdUnits: Int): Int? {
        val from = fromElapsedMs.coerceIn(0, CANDLE_DURATION_MS.toInt())

        // Already at the level: a stop resting exactly at the current price has
        // been reached, so it triggers now rather than being missed.
        if (priceAt(from) == thresholdUnits) return from

        for (index in 0 until waypoints.size - 1) {
            val start = waypoints[index]
            val end = waypoints[index + 1]

            // A segment that ended at or before the start cannot contain the
            // first crossing at or after it.
            if (end.elapsedMs <= from) continue

            val lower = minOf(start.priceUnits, end.priceUnits)
            val upper = maxOf(start.priceUnits, end.priceUnits)
            if (thresholdUnits < lower || thresholdUnits > upper) continue

            // A flat segment already at the threshold. Reached only when [from]
            // is at or before its start, because the equality check above would
            // otherwise have returned already.
            if (start.priceUnits == end.priceUnits) {
                return maxOf(start.elapsedMs, from)
            }

            // Exact rational offset. Numerator and denominator share a sign
            // because the threshold lies between the endpoints, so the ratio is
            // in [0, 1] and no sign correction is needed.
            val numerator = (thresholdUnits - start.priceUnits).toLong() *
                (end.elapsedMs - start.elapsedMs).toLong()
            val denominator = (end.priceUnits - start.priceUnits).toLong()

            val offsetMs = ceilDivNonNegative(
                numerator = kotlin.math.abs(numerator),
                denominator = kotlin.math.abs(denominator),
            )

            val crossing = start.elapsedMs.toLong() + offsetMs
            if (crossing < from) continue
            return crossing.coerceAtMost(end.elapsedMs.toLong()).toInt()
        }
        return null
    }

    /**
     * True when the path touches [thresholdUnits] at any point.
     *
     * Equivalent to the path's price range covering the threshold, which is
     * cheaper than searching and is what [firstTouchElapsedMs] would find.
     */
    fun touches(thresholdUnits: Int): Boolean =
        thresholdUnits in lowUnits..highUnits

    /**
     * The lowest and highest price on `[fromElapsedMs, toElapsedMs]`.
     *
     * Price is linear between waypoints, so an extreme over an interval is at a
     * waypoint inside it or at one of its endpoints. Scanning those is therefore
     * exact, not a sample — which matters because a trailing stop's best price
     * must not be approximated.
     */
    fun priceRangeIn(fromElapsedMs: Int, toElapsedMs: Int): Pair<Int, Int> {
        val from = fromElapsedMs.coerceIn(0, CANDLE_DURATION_MS.toInt())
        val to = toElapsedMs.coerceIn(from, CANDLE_DURATION_MS.toInt())

        var min = priceAt(from)
        var max = min
        for (waypoint in waypoints) {
            if (waypoint.elapsedMs < from || waypoint.elapsedMs > to) continue
            if (waypoint.priceUnits < min) min = waypoint.priceUnits
            if (waypoint.priceUnits > max) max = waypoint.priceUnits
        }
        val end = priceAt(to)
        if (end < min) min = end
        if (end > max) max = end
        return min to max
    }

    /** The waypoint times of this path, ascending. Used to split a minute into linear pieces. */
    fun waypointTimes(): List<Int> = waypoints.map { it.elapsedMs }

    /** The segment index whose span contains [elapsedMs]; the last segment at the close. */
    private fun segmentContaining(elapsedMs: Int): Int {
        for (index in 0 until waypoints.size - 1) {
            if (elapsedMs <= waypoints[index + 1].elapsedMs) return index
        }
        return waypoints.size - 2
    }

    private fun interpolate(from: PathWaypoint, to: PathWaypoint, elapsedMs: Int): Int {
        val span = to.elapsedMs - from.elapsedMs
        if (span <= 0) return to.priceUnits
        val priceDelta = (to.priceUnits - from.priceUnits).toLong()
        val timeDelta = (elapsedMs - from.elapsedMs).toLong()
        val scaled = priceDelta * timeDelta
        // Round half away from zero, on the exact product. Integer division
        // truncates toward zero, so the negative branch mirrors the positive.
        val rounded = if (scaled >= 0) {
            (scaled + span / 2) / span
        } else {
            -((-scaled + span / 2) / span)
        }
        return (from.priceUnits + rounded).toInt()
    }

    private fun ceilDivNonNegative(numerator: Long, denominator: Long): Long {
        if (denominator == 0L) return 0L
        return (numerator + denominator - 1L) / denominator
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is IntraminutePath && identity == other.identity)

    override fun hashCode(): Int = identity.hashCode()

    override fun toString(): String =
        "IntraminutePath(t=$timestampUtcMs, o=$openUnits, h=$highUnits, l=$lowUnits, " +
            "c=$closeUnits, v=${identity.pathModelVersion})"

    companion object {

        /**
         * Builds the path for [candle] (Steps 4.1, 4.2, 4.7).
         *
         * Pure: the same candle and dataset always produce an equal path.
         */
        fun of(datasetId: String, candle: HistoricalCandle): IntraminutePath =
            of(
                PathIdentity(
                    datasetId = datasetId,
                    timestampUtcMs = candle.timestampUtcMs,
                    openUnits = candle.openUnits,
                    highUnits = candle.highUnits,
                    lowUnits = candle.lowUnits,
                    closeUnits = candle.closeUnits,
                ),
            )

        fun of(identity: PathIdentity): IntraminutePath {
            require(identity.highUnits >= identity.openUnits && identity.highUnits >= identity.closeUnits) {
                "high ${identity.highUnits} must not be below open/close"
            }
            require(identity.lowUnits <= identity.openUnits && identity.lowUnits <= identity.closeUnits) {
                "low ${identity.lowUnits} must not be above open/close"
            }
            require(identity.highUnits >= identity.lowUnits) {
                "high ${identity.highUnits} must not be below low ${identity.lowUnits}"
            }
            return IntraminutePath(identity, WaypointTiming.waypointsFor(identity))
        }
    }
}
