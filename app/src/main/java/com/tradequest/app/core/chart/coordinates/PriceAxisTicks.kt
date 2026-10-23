package com.tradequest.app.core.chart.coordinates

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Chooses price-axis gridlines (Step 8.7).
 *
 * # The problem
 *
 * Given a visible price window and a rough number of labels that will fit, pick
 * a step that produces labels a human reads without effort. A step of 137.42
 * units is arithmetically fine and useless. The step must be a "nice" number —
 * 1, 2, 5 or 10 times a power of ten — and the gridlines must land on round
 * prices, not on whatever the window's edge happens to be.
 *
 * # Units, not dollars
 *
 * Prices are integer thousandths of a USD. The natural unit for a label here is
 * a pip, which is 100 units ([com.tradequest.app.core.market.PriceScale.UNITS_PER_PIP]),
 * because XAUUSD moves in pips and traders read it that way. A 1,000-unit step
 * is ten pips, and that is how it is chosen and reported.
 */
object PriceAxisTicks {

    /**
     * Gridline prices covering [range], aiming for about [targetCount] lines.
     *
     * The returned prices ascend. Each is an exact multiple of the chosen step,
     * so labels read as round numbers. A range too small or a target count too
     * low to produce any line returns an empty list rather than a single
     * arbitrary line, because one unlabelled gridline is noise.
     */
    fun ticks(range: PriceAxisRange, targetCount: Int): List<Float> {
        if (targetCount <= 0 || !range.spanUnits.isFinite() || range.spanUnits <= 0f) {
            return emptyList()
        }

        val step = niceStep(range.spanUnits / targetCount)

        val first = ceil(range.minUnits / step) * step
        if (first > range.maxUnits) return emptyList()

        val out = ArrayList<Float>(targetCount + 2)
        var value = first
        // Bounded by the range rather than by a count, but the count bound is a
        // belt-and-braces guard against a pathological step producing a huge
        // list and stalling the frame.
        var guard = 0
        while (value <= range.maxUnits && guard < MAX_TICKS) {
            out += value
            value += step
            guard++
        }
        return out
    }

    /**
     * The smallest "nice" step at or above [rawStep].
     *
     * Nice means 1, 2 or 5 times a power of ten — the classic choice, because
     * those divide evenly into 10, 100 and 1,000 and so produce labels a person
     * recognises. Rounds *up*, so the result never packs more labels onto the
     * axis than asked for.
     */
    fun niceStep(rawStep: Float): Float {
        if (rawStep <= 0f || !rawStep.isFinite()) return MINIMUM_STEP_UNITS

        val magnitude = 10f.pow(floor(log10(rawStep.toDouble())).toFloat())
        val normalised = rawStep / magnitude

        val multiplier = when {
            normalised <= 1f -> 1f
            normalised <= 2f -> 2f
            normalised <= 5f -> 5f
            else -> 10f
        }

        val step = multiplier * magnitude
        // Never propose a step finer than one pip: sub-pip gridlines are below
        // the precision the data is stored at, so the lines would not correspond
        // to distinguishable prices.
        return if (step < MINIMUM_STEP_UNITS) MINIMUM_STEP_UNITS else step
    }

    /**
     * How many pips [stepUnits] represents.
     *
     * Used for the axis label, so a 1,000-unit step reads as "10 pips" rather
     * than an unexplained number.
     */
    fun stepPips(stepUnits: Float): Float = stepUnits / PIP_UNITS

    /**
     * The step actually used for [range] and [targetCount].
     *
     * Exposed so a caller drawing a label can describe the spacing without
     * recomputing it from two adjacent ticks, which would be wrong when only one
     * tick fits.
     */
    fun stepFor(range: PriceAxisRange, targetCount: Int): Float =
        if (targetCount <= 0) MINIMUM_STEP_UNITS else niceStep(range.spanUnits / targetCount)

    /** Price units in one pip, mirroring `PriceScale.UNITS_PER_PIP`. */
    private const val PIP_UNITS: Float = 100f

    /**
     * Finest gridline spacing: one pip.
     *
     * Below one pip the labels would differ only in the third decimal, which is
     * noise on a phone-width axis.
     */
    private const val MINIMUM_STEP_UNITS: Float = PIP_UNITS

    /**
     * Hard ceiling on gridlines produced, regardless of the arithmetic.
     *
     * A degenerate range and a large target count cannot then produce a list
     * long enough to stall a frame.
     */
    private const val MAX_TICKS: Int = 512

    /** Formats [priceUnits] for an axis label, using the app's price scale. */
    fun formatPrice(priceUnits: Float): String =
        com.tradequest.app.core.market.PriceScale.format(
            kotlin.math.round(priceUnits).toInt(),
        )
}
