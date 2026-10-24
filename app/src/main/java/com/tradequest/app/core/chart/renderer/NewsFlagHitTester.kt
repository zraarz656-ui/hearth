package com.tradequest.app.core.chart.renderer

import kotlin.math.abs

/**
 * Where news flags are drawn, and which one a touch landed on (Steps 12.3, 12.4).
 *
 * [headline] is carried so the caller can open the event detail without looking the
 * flag up again — the same value that was drawn, rather than a second read that
 * could disagree with what the user tapped.
 */
data class NewsFlagHit(
    val index: Int,
    val headline: String,
)

/**
 * Finds the news flag nearest a touch (Step 12.3: "allow tapping markers").
 *
 * # Why this lives in `core/chart`
 *
 * It operates on [NewsFlag] — the renderer's own type — so it belongs beside it, for
 * the same reason [OverlayLineHitTester] does: the renderer must not learn what a
 * release is, and the news domain must not learn about pixels. Here the test is pure
 * arithmetic over drawn flags, exercised with hand-built flags and no database.
 *
 * # Why the strip geometry lives here and not in the renderer
 *
 * The renderer draws the flags and this decides which one a finger hit, so the two
 * must agree on where the strip is. If the top offset and the size were constants on
 * [ChartRenderer], a change there would move the drawn flag and leave the hit region
 * behind — a flag you can see but cannot tap, with no test failing. Keeping the
 * numbers here, where the hit test uses them, means the renderer has to read them
 * from the same place and the two cannot drift apart.
 *
 * # Why the tolerance is in pixels here, unlike the line hit test
 *
 * A trade line's position is a *price*, so its tolerance has to be expressed in
 * price units and converted by the caller. A flag's position is a fixed pixel column
 * derived from the bar index, and the flag strip has a fixed pixel height, so the
 * tolerance is naturally pixels and no conversion is needed. The caller passes the
 * chart's own scale so a finger-sized target stays finger-sized.
 */
object NewsFlagHitTester {

    /** The top of the flag strip, in pixels from the top of the plot. */
    const val STRIP_TOP_PX: Float = 6f

    /** The height of one flag, in pixels. The strip is exactly this tall. */
    const val STRIP_HEIGHT_PX: Float = 11f

    /**
     * The bottom of the flag strip, in pixels from the top of the plot.
     *
     * A touch below this is on the candles, not the flags, and must fall through to
     * the chart's own gestures.
     */
    const val STRIP_BOTTOM_PX: Float = STRIP_TOP_PX + STRIP_HEIGHT_PX

    /**
     * The flag within [tolerancePx] of [touchX] that is closest to it, or null.
     *
     * [flagX] maps a flag to its pixel column; the caller supplies it because only
     * the chart knows the current viewport. Returning null rather than the nearest
     * flag regardless of distance is deliberate: a tap on empty chart space must not
     * open an unrelated event.
     */
    fun hitTest(
        touchX: Float,
        flags: List<NewsFlag>,
        flagX: (NewsFlag) -> Float,
        tolerancePx: Float,
    ): NewsFlagHit? {
        var best: NewsFlag? = null
        var bestDistance = Float.MAX_VALUE
        for (flag in flags) {
            val distance = abs(flagX(flag) - touchX)
            if (distance > tolerancePx) continue
            if (distance < bestDistance) {
                bestDistance = distance
                best = flag
            }
        }
        return best?.let { NewsFlagHit(index = it.index, headline = it.headline) }
    }

    /**
     * The flag under a full touch point, or null when the touch is not on the strip.
     *
     * # Why the y-axis is checked here and not left to the caller
     *
     * Every flag sits on one horizontal line, so once the strip is identified the
     * x-axis alone decides which flag was hit. But a tap *below* the strip is on the
     * candles, and treating it as a flag would open a release detail when the trader
     * meant to read a bar. Testing the strip here means the caller cannot forget to,
     * and the same constants that place the drawn flag also bound the tap.
     */
    fun hitTestAt(
        touchX: Float,
        touchY: Float,
        flags: List<NewsFlag>,
        flagX: (NewsFlag) -> Float,
        tolerancePx: Float,
    ): NewsFlagHit? {
        if (touchY < STRIP_TOP_PX || touchY > STRIP_BOTTOM_PX) return null
        return hitTest(touchX, flags, flagX, tolerancePx)
    }
}
