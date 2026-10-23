package com.tradequest.app.core.chart.crosshair

import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.chart.renderer.FormingCandleView
import com.tradequest.app.core.chart.timeframe.Timeframe
import com.tradequest.app.core.market.PriceScale
import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the crosshair is pointing at (Step 9.5).
 *
 * # Why this is a value, not a pair of coordinates
 *
 * The crosshair is the only place a trader reads a candle's exact OHLC, so the
 * reading has to be authoritative rather than a rendering of a pixel position: the
 * index is resolved once, against the buffer, and every displayed field comes from
 * that index. Formatting is done here rather than in the renderer so the strings can
 * be asserted without a Compose runtime — the renderer is left with placement only.
 *
 * # Why the change is in units, not percent
 *
 * `changeUnits` is close minus open in price units, which is what the app's price
 * scale is for ([PriceScale]). A percentage would need a division and would be a
 * second, disagreeing representation of the same movement.
 */
data class CrosshairReadout(
    /** Index into the buffer, or the forming slot when [isForming]. */
    val index: Int,
    /** True when the crosshair sits on the still-forming candle. */
    val isForming: Boolean,
    val timestampUtcMs: Long,
    val openUnits: Int,
    val highUnits: Int,
    val lowUnits: Int,
    val closeUnits: Int,
) {
    /** Close minus open, in price units. Positive when the candle closed up. */
    val changeUnits: Int get() = closeUnits - openUnits

    /** The candle's timestamp, formatted for the header. */
    val timestampLabel: String
        get() = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampUtcMs).atZone(ZONE))

    /** The OHLC values as display strings, in the order a trader reads them. */
    val ohlcLabels: List<String>
        get() = listOf(openUnits, highUnits, lowUnits, closeUnits).map { PriceScale.format(it) }

    /**
     * The change as a signed display string, e.g. `+1.23`.
     *
     * The sign is explicit because a bare `1.23` does not say which way the candle
     * went, and the crosshair is often read without the candle visible behind it.
     */
    val changeLabel: String
        get() {
            val sign = if (changeUnits >= 0) "+" else "-"
            return sign + PriceScale.format(abs(changeUnits))
        }

    companion object {
        private val ZONE: ZoneId = NewYorkTradingCalendar.ZONE
        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")

        /**
         * The readout at [index], or null when there is nothing to read.
         *
         * [index] is clamped into range first, because a crosshair can be dragged
         * past the end of the series — into the right gutter, where the live candle
         * is going to be. Clamping means the crosshair keeps reading the newest
         * candle there rather than blinking off, which is what a terminal does.
         *
         * The forming candle occupies the slot one past the last closed bar, so an
         * index at or beyond that reads the forming candle.
         */
        fun at(
            buffer: CandleBuffer,
            forming: FormingCandleView?,
            index: Int,
        ): CrosshairReadout? {
            if (buffer.isEmpty && forming == null) return null

            val formingIndex = buffer.size
            val lastReadable = formingIndex - if (forming == null) 1 else 0
            val clamped = index.coerceIn(0, maxOf(0, lastReadable))

            if (forming != null && clamped >= formingIndex) {
                return CrosshairReadout(
                    index = formingIndex,
                    isForming = true,
                    timestampUtcMs = forming.timestampUtcMs,
                    openUnits = forming.openUnits,
                    highUnits = forming.highUnits,
                    lowUnits = forming.lowUnits,
                    closeUnits = forming.closeUnits,
                )
            }

            if (buffer.isEmpty) {
                // Only a forming candle exists and it was not selected above, so
                // there is no candle at this index at all.
                return null
            }

            val safe = clamped.coerceIn(0, buffer.size - 1)
            return CrosshairReadout(
                index = safe,
                isForming = false,
                timestampUtcMs = buffer.timestamps[safe],
                openUnits = buffer.opens[safe].roundToInt(),
                highUnits = buffer.highs[safe].roundToInt(),
                lowUnits = buffer.lows[safe].roundToInt(),
                closeUnits = buffer.closes[safe].roundToInt(),
            )
        }

        /**
         * The index a crosshair at screen x should read, from a resolved bar index.
         *
         * Rounding, not truncation: truncation biases every reading half a bar to
         * the left, which is visible as a crosshair that disagrees with the candle
         * under it at high zoom.
         */
        fun indexFor(indexAtX: Float): Int = indexAtX.roundToInt()

        /** True when [timeframe] labels should include the date rather than only the time. */
        fun needsDate(timeframe: Timeframe): Boolean = !timeframe.isIntraday
    }
}
