package com.tradequest.app.core.simulation.path

/**
 * Where the waypoints of a minute sit in time (Step 4.2).
 *
 * # The choice, and what it costs
 *
 * Waypoints are placed at even quarter-minute intervals — 0, 15,000, 30,000 and
 * 60,000 ms — which is the shape the plan suggests. It is deterministic,
 * trivial to test, and needs no floating point.
 *
 * The honest limitation: even spacing means the high of a bullish minute is
 * *always* reached at 30,000 ms and the low at 15,000 ms, whatever the candle's
 * shape. Real intraminute timing is not like that. This matters, because the
 * relative timing of the high and the low decides which of a stop-loss and a
 * take-profit is hit first, and that is a trade outcome, not a cosmetic detail.
 *
 * The choice is therefore recorded as a model assumption in
 * `docs/adr-0002-intraminute-path.md`, and it is the main reason
 * [PathModelVersion] exists: a later model may weight waypoint times by price
 * distance travelled, and bumping the version is how that change is kept
 * distinguishable from this one.
 */
object WaypointTiming {

    /** Even quarter-minute spacing: 0, 15s, 30s, 60s. */
    private const val QUARTER_MS: Int = 15_000

    /**
     * The four waypoints of [identity], in path order (Step 4.1).
     *
     * The order is the whole model. OHLC fixes *which* prices occurred, not
     * *when*, and this function is the single place that decides.
     */
    fun waypointsFor(identity: PathIdentity): List<PathWaypoint> {
        val (second, third) = intermediatePrices(identity)
        return listOf(
            PathWaypoint(elapsedMs = 0, priceUnits = identity.openUnits),
            PathWaypoint(elapsedMs = QUARTER_MS, priceUnits = second),
            PathWaypoint(elapsedMs = QUARTER_MS * 2, priceUnits = third),
            PathWaypoint(elapsedMs = CANDLE_DURATION_MS.toInt(), priceUnits = identity.closeUnits),
        )
    }

    /**
     * The two interior waypoints, in order, for [identity].
     *
     * Split out because it is the pure statement of Step 4.1 and is worth
     * testing on its own, without timing in the way.
     */
    fun intermediatePrices(identity: PathIdentity): Pair<Int, Int> =
        when {
            // Bullish: dip to the low, then rally to the high.
            identity.closeUnits > identity.openUnits -> identity.lowUnits to identity.highUnits

            // Bearish: spike to the high, then fall to the low.
            identity.closeUnits < identity.openUnits -> identity.highUnits to identity.lowUnits

            // Doji: documented tie-break, low first then high.
            //
            // With open == close, OHLC cannot tell a dip-then-rally from a
            // rally-then-dip, so no rule can be *correct*. This one is fixed so
            // the chart and execution agree. It also matches the bullish shape,
            // which keeps a doji from being a special case in the renderer.
            else -> identity.lowUnits to identity.highUnits
        }
}
