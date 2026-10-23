package com.tradequest.app.core.chart.renderer

/**
 * Which drawn line a touch landed on (Step 10.4).
 *
 * [priceUnits] is the line's current price, so a caller that only needs "what is
 * being dragged" does not have to look the group up again.
 */
data class OverlayLineHit(
    val overlayId: String,
    val kind: OverlayLineKind,
    val priceUnits: Int,
)

/**
 * Finds the draggable overlay line nearest a touch (Step 10.4).
 *
 * # Why this lives in `core/chart`
 *
 * It operates on [OverlayGroup] — the renderer's own type — so it belongs beside
 * them. Putting it in the trading domain would need that domain to know the
 * renderer's types, and the whole point of [OverlayGroup] is that the renderer never
 * learns what an order is. Here, the hit test is pure geometry over drawn lines and
 * is exercised with hand-built overlays and no engine.
 *
 * # Why the tolerance is in price units, not pixels
 *
 * A touch tolerance is naturally a pixel distance — a finger is a few millimetres
 * wide. But this object must not know about a screen: it is pure Kotlin, testable
 * without a Compose runtime. So the caller converts its pixel tolerance into price
 * units using the chart's own scale (`ChartPriceScale.toleranceUnits`) and passes
 * that. The conversion needs the visible price range, which the chart has and this
 * object must not.
 *
 * # Why the nearest line wins rather than the first within tolerance
 *
 * Two brackets can be close together — a stop just above another position's entry,
 * or a stop and a target bracketing a narrow range. Taking the first within
 * tolerance would make the drag depend on list order, which is insertion order and
 * therefore arbitrary from the trader's point of view. Nearest is what the finger
 * meant.
 *
 * # The tie-break, and why it is a stop
 *
 * When two lines are equidistant, the stop is chosen. A stop is the line that bounds
 * the loss, so if a drag is ambiguous the safer reading is the one that lets the
 * trader tighten protection rather than the one that moves the target. It is also a
 * stable choice: without a tie-break the winner would depend on `minByOrNull` and
 * list order, and the same touch could pick different lines on different frames.
 */
object OverlayLineHitTester {

    /** Lower ranks win a tie. A stop outranks a target, which outranks an entry. */
    private fun tieRank(kind: OverlayLineKind): Int = when (kind) {
        OverlayLineKind.STOP -> 0
        OverlayLineKind.TARGET -> 1
        // An entry line is never draggable on a position, and on a pending order it
        // is the only line, so it never actually competes.
        OverlayLineKind.ENTRY -> 2
    }

    /**
     * The draggable line nearest [tapPriceUnits] within [toleranceUnits], or null.
     *
     * A non-positive tolerance matches nothing: a drag must have a target the trader
     * could plausibly have aimed at, and a zero-width target is a coincidence, not an
     * intention.
     */
    fun hit(
        overlays: List<OverlayGroup>,
        tapPriceUnits: Int,
        toleranceUnits: Int,
    ): OverlayLineHit? {
        if (toleranceUnits <= 0) return null
        var best: OverlayLineHit? = null
        var bestDistance = Int.MAX_VALUE
        var bestRank = Int.MAX_VALUE

        for (overlay in overlays) {
            // A preview bracket is not a live order, so nothing on it is grabbable.
            if (overlay.isPreview) continue
            for (line in overlay.lines) {
                if (!line.isDraggable) continue
                val distance = kotlin.math.abs(line.priceUnits - tapPriceUnits)
                if (distance > toleranceUnits) continue
                val rank = tieRank(line.kind)
                val better = distance < bestDistance ||
                    (distance == bestDistance && rank < bestRank)
                if (better) {
                    best = OverlayLineHit(overlay.id, line.kind, line.priceUnits)
                    bestDistance = distance
                    bestRank = rank
                }
            }
        }
        return best
    }
}
