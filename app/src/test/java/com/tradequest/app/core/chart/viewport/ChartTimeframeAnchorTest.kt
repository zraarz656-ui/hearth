package com.tradequest.app.core.chart.viewport

import com.tradequest.app.core.chart.buffer.CandleBuffer
import com.tradequest.app.core.market.HistoricalCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 9.9 tests: keeping the centre moment across a timeframe switch.
 *
 * The bug this guards against is silent: a wrong viewport still draws a perfectly
 * valid chart, just of the wrong period. Nothing but a timestamp assertion catches
 * it.
 */
class ChartTimeframeAnchorTest {

    private val minuteMs = 60_000L
    private val hourMs = 60 * minuteMs

    /** A one-minute series of [count] bars from [startUtcMs]. */
    private fun minuteBuffer(count: Int, startUtcMs: Long = 0L): CandleBuffer =
        CandleBuffer.of(
            (0 until count).map { i ->
                candle(startUtcMs + i * minuteMs, 2_050_000f + i)
            },
        )

    /** An hourly series, [count] bars, one hour apart from [startUtcMs]. */
    private fun hourBuffer(count: Int, startUtcMs: Long = 0L): CandleBuffer =
        CandleBuffer.of(
            (0 until count).map { i ->
                candle(startUtcMs + i * hourMs, 2_050_000f + i)
            },
        )

    private fun candle(ts: Long, price: Float) = HistoricalCandle(
        timestampUtcMs = ts,
        openUnits = price.toInt(),
        highUnits = (price + 10f).toInt(),
        lowUnits = (price - 10f).toInt(),
        closeUnits = price.toInt(),
        tickVolume = 1,
    )

    /** `rightPaddingBars` has no default on the data class, so tests supply one. */
    private fun vp(
        barsVisible: Float,
        firstVisibleIndex: Float,
        rightPaddingBars: Float = ChartViewport.DEFAULT_RIGHT_PADDING_BARS,
    ) = ChartViewport(
        barsVisible = barsVisible,
        firstVisibleIndex = firstVisibleIndex,
        rightPaddingBars = rightPaddingBars,
    )

    // ---------- centreTimestamp ----------

    @Test
    fun `the centre timestamp is the middle bar of the window`() {
        val buffer = minuteBuffer(count = 100)
        // Window shows bars 10..29 (20 bars), so the centre is bar 20.
        val viewport = vp(barsVisible = 20f, firstVisibleIndex = 10f)

        val centre = ChartTimeframeAnchor.centreTimestamp(viewport, buffer)

        assertEquals(20 * minuteMs, centre)
    }

    @Test
    fun `a fractional window centre uses the containing bar`() {
        val buffer = minuteBuffer(count = 100)
        // Centre is 10.4, which is inside bar 10 (bar 10 spans 10.0..11.0).
        val viewport = vp(barsVisible = 20f, firstVisibleIndex = 0.4f)

        assertEquals(10 * minuteMs, ChartTimeframeAnchor.centreTimestamp(viewport, buffer))
    }

    @Test
    fun `a window panned into empty space has no centre`() {
        val buffer = minuteBuffer(count = 10)
        // Entirely past the end of the data.
        val viewport = vp(barsVisible = 20f, firstVisibleIndex = 500f)

        assertNull(ChartTimeframeAnchor.centreTimestamp(viewport, buffer))
    }

    @Test
    fun `a window before the data has no centre`() {
        val buffer = minuteBuffer(count = 100)
        val viewport = vp(barsVisible = 20f, firstVisibleIndex = -50f)

        assertNull(ChartTimeframeAnchor.centreTimestamp(viewport, buffer))
    }

    @Test
    fun `an empty buffer has no centre`() {
        val viewport = vp(barsVisible = 20f, firstVisibleIndex = 0f)

        assertNull(ChartTimeframeAnchor.centreTimestamp(viewport, CandleBuffer.EMPTY))
    }

    // ---------- viewportAt ----------

    @Test
    fun `the requested moment ends up at the middle of the window`() {
        val buffer = hourBuffer(count = 200)
        val moment = 100 * hourMs

        val viewport = ChartTimeframeAnchor.viewportAt(
            centreUtcMs = moment,
            nextBuffer = buffer,
            barsVisible = 20f,
        )

        assertNotNull(viewport)
        // The centre index is bar 100, so the window starts 10 bars earlier.
        assertEquals(90.5f, viewport!!.firstVisibleIndex, 1e-3f)
    }

    @Test
    fun `a moment before the series starts yields no viewport`() {
        val buffer = hourBuffer(count = 50, startUtcMs = 1_000 * hourMs)

        val viewport = ChartTimeframeAnchor.viewportAt(
            centreUtcMs = 0L,
            nextBuffer = buffer,
            barsVisible = 20f,
        )

        assertNull(viewport)
    }

    @Test
    fun `a moment exactly on a bar uses that bar`() {
        val buffer = hourBuffer(count = 100)
        // `indexAtOrBefore` must pick the bar itself, not the next one, or a switch
        // would land one bucket late every time.
        val viewport = ChartTimeframeAnchor.viewportAt(
            centreUtcMs = 50 * hourMs,
            nextBuffer = buffer,
            barsVisible = 10f,
        )
        assertEquals(45.5f, viewport!!.firstVisibleIndex, 1e-3f)
    }

    @Test
    fun `a moment between bars uses the earlier bar`() {
        val buffer = hourBuffer(count = 100)
        val viewport = ChartTimeframeAnchor.viewportAt(
            centreUtcMs = 50 * hourMs + 30 * minuteMs,
            nextBuffer = buffer,
            barsVisible = 10f,
        )
        assertEquals(45.5f, viewport!!.firstVisibleIndex, 1e-3f)
    }

    @Test
    fun `the result is clamped to the new series`() {
        val buffer = hourBuffer(count = 5)
        // A moment at the very start would put the window left of the data.
        val viewport = ChartTimeframeAnchor.viewportAt(
            centreUtcMs = 0L,
            nextBuffer = buffer,
            barsVisible = 20f,
        )
        assertNotNull(viewport)
        // Pinned to the right bound because the series is narrower than the window.
        assertTrue(viewport!!.firstVisibleIndex <= viewport.maxFirstVisibleIndex(buffer.size) + 1e-3f)
    }

    @Test
    fun `an empty next buffer yields no viewport`() {
        assertNull(
            ChartTimeframeAnchor.viewportAt(0L, CandleBuffer.EMPTY, barsVisible = 20f),
        )
    }

    @Test
    fun `a non-positive bar count yields no viewport`() {
        val buffer = hourBuffer(count = 100)
        assertNull(ChartTimeframeAnchor.viewportAt(0L, buffer, barsVisible = 0f))
        assertNull(ChartTimeframeAnchor.viewportAt(0L, buffer, barsVisible = -5f))
    }

    // ---------- The whole conversion ----------

    @Test
    fun `switching timeframe keeps looking at the same moment`() {
        // Minute 3000 and hour 50 are the same instant. Both series are wide enough
        // that a 100-bar window fits, which is the case the conversion is for: when
        // the new series is narrower than the window there is nothing to centre on,
        // and the viewport pins to the live edge instead (asserted separately below).
        val minutes = minuteBuffer(count = 6_000)
        val hours = hourBuffer(count = 400)

        // M1 window centred on minute 3000.
        val m1 = vp(barsVisible = 100f, firstVisibleIndex = 2950f)
        val m1Centre = ChartTimeframeAnchor.centreTimestamp(m1, minutes)
        assertEquals(3000 * minuteMs, m1Centre)

        val h1 = ChartTimeframeAnchor.viewportPreservingCentre(
            previous = m1,
            previousBuffer = minutes,
            nextBuffer = hours,
        )

        assertNotNull(h1)
        val h1Centre = ChartTimeframeAnchor.centreTimestamp(h1!!, hours)
        assertEquals("the same instant must stay centred", m1Centre, h1Centre)
    }

    @Test
    fun `the zoom is preserved across the switch`() {
        val minutes = minuteBuffer(count = 6_000)
        val hours = hourBuffer(count = 400)
        val m1 = vp(barsVisible = 100f, firstVisibleIndex = 2950f)

        val h1 = ChartTimeframeAnchor.viewportPreservingCentre(m1, minutes, hours)

        assertEquals(100f, h1!!.barsVisible, 1e-3f)
    }

    @Test
    fun `the right padding is preserved across the switch`() {
        val minutes = minuteBuffer(count = 6_000)
        val hours = hourBuffer(count = 400)
        val m1 = vp(barsVisible = 100f, firstVisibleIndex = 2950f, rightPaddingBars = 12f)

        val h1 = ChartTimeframeAnchor.viewportPreservingCentre(m1, minutes, hours)

        assertEquals(12f, h1!!.rightPaddingBars, 1e-3f)
    }

    @Test
    fun `a switch with an empty old buffer falls back`() {
        val hours = hourBuffer(count = 400)
        val m1 = vp(barsVisible = 100f, firstVisibleIndex = 10f)

        assertNull(
            ChartTimeframeAnchor.viewportPreservingCentre(m1, CandleBuffer.EMPTY, hours),
        )
    }

    @Test
    fun `a switch with an empty new buffer falls back`() {
        val minutes = minuteBuffer(count = 100)
        val m1 = vp(barsVisible = 20f, firstVisibleIndex = 10f)

        assertNull(
            ChartTimeframeAnchor.viewportPreservingCentre(m1, minutes, CandleBuffer.EMPTY),
        )
    }

    @Test
    fun `a new series narrower than the window pins to the live edge`() {
        // 40 hourly bars cannot be centred on a 100-bar window. Pinning to the live
        // edge is the documented fallback, and it must not produce a viewport
        // pointing off the end of the data.
        val minutes = minuteBuffer(count = 6_000)
        val hours = hourBuffer(count = 40)
        val m1 = vp(barsVisible = 100f, firstVisibleIndex = 2950f)

        val h1 = ChartTimeframeAnchor.viewportPreservingCentre(m1, minutes, hours)

        assertNotNull(h1)
        assertEquals(hours.size, 40)
        assertTrue(h1!!.firstVisibleIndex <= h1.maxFirstVisibleIndex(hours.size) + 1e-3f)
    }

    @Test
    fun `the automatic scale flag survives the switch`() {
        val minutes = minuteBuffer(count = 6_000)
        val hours = hourBuffer(count = 400)
        val m1 = vp(barsVisible = 100f, firstVisibleIndex = 2950f)

        val h1 = ChartTimeframeAnchor.viewportPreservingCentre(m1, minutes, hours)

        // The default is auto-scale, and a switch is not a reason to change it.
        assertTrue(h1!!.autoScale)
    }

    @Test
    fun `switching back and forth does not drift`() {
        // The round trip is the case a rounding error would compound: each switch
        // would land one bar late, and ten switches would move the view ten bars.
        val minutes = minuteBuffer(count = 6_000)
        val hours = hourBuffer(count = 400)
        val original = vp(barsVisible = 100f, firstVisibleIndex = 2950f)
        val originalCentre = ChartTimeframeAnchor.centreTimestamp(original, minutes)

        var current = original
        var buffer = minutes
        repeat(10) {
            val other = if (buffer === minutes) hours else minutes
            current = ChartTimeframeAnchor.viewportPreservingCentre(current, buffer, other)!!
            buffer = other
        }

        assertEquals("ten round trips must not drift", originalCentre, ChartTimeframeAnchor.centreTimestamp(current, buffer))
    }
}
