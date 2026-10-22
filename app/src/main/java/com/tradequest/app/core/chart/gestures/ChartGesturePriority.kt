package com.tradequest.app.core.chart.gestures

/**
 * Which gesture a touch begins, decided by hit testing in priority order
 * (Step 9.10).
 *
 * # Why priority has to be explicit
 *
 * Several gestures want the same finger. A drag that starts on the price gutter
 * could scale the axis or pan the chart; a long press could open a crosshair or
 * start a drawing. Whichever rule is checked first wins, so the order is a
 * behavioural decision, not an implementation detail — and it has to be the same
 * decision every frame, or a gesture would change identity halfway through.
 *
 * The spec's order is, most specific first:
 *
 * 1. Price axis — a narrow strip with one meaning
 * 2. Order lines — a few thin, precisely-targeted objects
 * 3. Drawing handles — small and easy to miss, so they beat everything larger
 * 4. Active drawing tool — the mode the user deliberately entered
 * 5. Pinch zoom — needs two fingers, so it is unambiguous when it applies
 * 6. Crosshair — a long press, which is a duration rather than a position
 * 7. Pan — the default, and last because it is what everything else falls back to
 *
 * # Why this lives in core
 *
 * The order is the kind of thing that is wrong silently: get it wrong and a
 * gesture still works, just not the one the user meant. Keeping it here means the
 * order can be asserted directly, without a Compose runtime, and the composable
 * only has to ask "what did this touch start?".
 *
 * # Reserved layers
 *
 * Order lines, drawing handles and the active drawing tool do not exist yet —
 * they arrive with the order and drawing phases. They are in the enum now so the
 * order is fixed before anything competes for a finger, and so adding them later
 * is filling in a case rather than re-deciding the order.
 */
enum class ChartGesture {
    PRICE_AXIS_SCALE,
    ORDER_LINE_DRAG,
    DRAWING_HANDLE_DRAG,
    DRAWING_TOOL,
    PINCH_ZOOM,
    CROSSHAIR,
    PAN,
}

/**
 * The inputs a touch presents to the priority rule.
 *
 * A plain data class rather than a parameter list because the rule has seven
 * inputs and a call site with seven positional booleans is unreadable — and
 * mis-ordering them would type-check.
 */
data class ChartHitTest(
    /** True when the down event landed in the price gutter, right of the plot. */
    val onPriceAxis: Boolean = false,
    /** True when the down event landed on an order line's hit region. */
    val onOrderLine: Boolean = false,
    /** True when the down event landed on a drawing handle. */
    val onDrawingHandle: Boolean = false,
    /** True when a drawing tool is armed and the touch is inside the plot. */
    val drawingToolArmed: Boolean = false,
    /** True when two or more fingers are down. */
    val isMultiTouch: Boolean = false,
    /**
     * True when a single finger has been held past the crosshair threshold without
     * travelling. Computed by the caller, because the threshold needs the event
     * timestamps and the touch slop.
     */
    val longPressSatisfied: Boolean = false,
)

/**
 * Resolves the priority order of Step 9.10 into the one gesture a touch means.
 *
 * # Why this is a pure function of the *current* event
 *
 * It is called per event, not once per touch, so a gesture can be re-evaluated as
 * the touch develops — a stationary finger becomes a crosshair after the
 * threshold, and a second finger turns a pan into a pinch. The caller keeps the
 * decision sticky once a gesture is under way; this function only answers "what
 * does the touch mean right now".
 */
object ChartGesturePriority {

    /**
     * The gesture [hit] selects.
     *
     * # Why multi-touch outranks the crosshair
     *
     * A crosshair is a one-finger reading. Adding a second finger means the user
     * wants to zoom, and a crosshair that ignored the second finger would leave
     * them pinching with no effect. It does *not* outrank the price axis or a
     * drawing handle: those are precise targets, and a stray second finger should
     * not abandon a drag the user deliberately started.
     */
    fun resolve(hit: ChartHitTest): ChartGesture = when {
        hit.onPriceAxis -> ChartGesture.PRICE_AXIS_SCALE
        hit.onOrderLine -> ChartGesture.ORDER_LINE_DRAG
        hit.onDrawingHandle -> ChartGesture.DRAWING_HANDLE_DRAG
        hit.drawingToolArmed -> ChartGesture.DRAWING_TOOL
        hit.isMultiTouch -> ChartGesture.PINCH_ZOOM
        hit.longPressSatisfied -> ChartGesture.CROSSHAIR
        else -> ChartGesture.PAN
    }

    /**
     * True when [gesture] may still turn into a different one later in the same
     * touch.
     *
     * A pan may become a crosshair or a pinch; a crosshair may become a pinch. An
     * axis drag may not become anything — it was aimed at a specific strip — and a
     * drawing handle, order line or armed tool is likewise committed, because
     * silently abandoning one mid-drag would leave the drawing half-edited.
     *
     * This is what stops a pan that has already moved from being promoted to a
     * crosshair by the long-press timer: the caller checks
     * [ChartHitTest.longPressSatisfied] only while the gesture is still
     * [PAN] and [isPromotable] says so.
     */
    fun isPromotable(gesture: ChartGesture): Boolean = when (gesture) {
        ChartGesture.PAN, ChartGesture.CROSSHAIR, ChartGesture.PINCH_ZOOM -> true
        ChartGesture.PRICE_AXIS_SCALE,
        ChartGesture.ORDER_LINE_DRAG,
        ChartGesture.DRAWING_HANDLE_DRAG,
        ChartGesture.DRAWING_TOOL,
        -> false
    }

    /**
     * The gesture a touch is promoted to, given what it already is.
     *
     * # Why this is not just [resolve]
     *
     * Resolving from scratch on every event is wrong once a gesture is under way,
     * because the inputs keep changing. A crosshair is the clearest case: the user
     * holds a finger still, the crosshair activates, then they drag it to read a
     * different candle. The finger has now travelled past the touch slop, so a
     * fresh [resolve] would say "pan" and snatch the reading away mid-drag.
     *
     * Promotion is therefore one-way for a given touch, except where a new finger
     * genuinely changes the intent — a crosshair may still become a pinch, because
     * two fingers can only mean zoom. Committed gestures ([isPromotable] false) are
     * returned unchanged: a stray second finger must not abandon an axis drag.
     */
    fun promote(current: ChartGesture, hit: ChartHitTest): ChartGesture = when {
        !isPromotable(current) -> current
        current == ChartGesture.CROSSHAIR ->
            if (hit.isMultiTouch) ChartGesture.PINCH_ZOOM else ChartGesture.CROSSHAIR
        current == ChartGesture.PINCH_ZOOM ->
            // Dropping back to one finger must not resume a pan: the touch was
            // started as a zoom, and half a pinch is not a drag.
            if (hit.isMultiTouch) ChartGesture.PINCH_ZOOM else current
        else -> resolve(hit)
    }

    /**
     * True when [gesture] consumes horizontal drags, which is what the fling and
     * the pan share.
     *
     * Stated once here rather than as an `if` in the gesture loop, so adding a
     * gesture that also pans cannot forget to opt in.
     */
    fun pansHorizontally(gesture: ChartGesture): Boolean = gesture == ChartGesture.PAN
}
