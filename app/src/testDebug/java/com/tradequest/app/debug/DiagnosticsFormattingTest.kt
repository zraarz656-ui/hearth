package com.tradequest.app.debug

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests every log line the diagnostics can print.
 *
 * # Why this file exists beside [FrameSummaryTest]
 *
 * `FrameSummaryTest` pins the frame-window *arithmetic*. A device crash proved
 * that the arithmetic being right is not enough: the numbers were correct and the
 * line that printed them threw `IllegalFormatConversionException` from inside the
 * `Choreographer` callback, killing the process every 300 frames. That fix
 * extracted one formatter and left the others untested, so the same class of bug
 * could still reach a device through `describeChartState` or `formatStageSummary`.
 *
 * This file closes that gap: every formatter is a top-level function reachable
 * without a device, a `Choreographer` or a log file, and every one is asserted on
 * the *string it produces* — the artefact that reaches the capture.
 *
 * # Why the locale is set and restored
 *
 * The diagnostics run on whatever phone the tester holds. A German phone formats
 * `16.67` as `16,67`, which is not merely a cosmetic difference: a comma is a
 * thousands separator in some locales and a decimal mark in others, so `16,67 ms`
 * cannot be told from `1667 ms`, and no capture can be diffed against an earlier
 * one. These tests set a comma-decimal default locale and assert the output is
 * unchanged, which is the only way to catch a formatter that forgot its `Locale`
 * without owning a German phone.
 */
class DiagnosticsFormattingTest {

    private val original: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    /** A fixed stage set, so the assertions do not move when `STAGE_LABELS` does. */
    private val stages = listOf(0 to "input", 1 to "anim", 2 to "draw")

    private fun stagesOf(vararg micros: Long): String =
        formatStageSummary(LongArray(micros.size) { micros[it] * 1000 }, stages)

    // ---------- the locale rule ----------

    @Test
    fun `a comma-decimal locale does not change the frames line`() {
        // de-DE is the concrete case that was reachable: `String.format` with no
        // `Locale` uses the default, so `%.2f` renders `16,67` on such a phone.
        Locale.setDefault(Locale.GERMANY)

        val summary = summarizeFrameSummaryForTest()

        val line = formatFrameSummary(summary, allocatedPerSecond = 1_048_576.0, gcDelta = 3L)

        assertTrue("decimal mark moved under a German locale: $line", line.contains("avg=16.67ms"))
        assertTrue("fps decimal mark moved: $line", line.contains("fps~60"))
        assertFalse("a comma decimal reached the capture: $line", line.contains("16,67"))
    }

    @Test
    fun `a comma-decimal locale does not change the chart-state line`() {
        Locale.setDefault(Locale.FRANCE)

        val line = describeChartState(
            timeframe = "M1",
            totalBars = 500,
            barsVisible = 120.0f,
            firstVisibleIndex = 3.25f,
            rightPaddingBars = 12.0f,
            autoScale = true,
            forming = false,
            simulatedUtcMs = 1_705_276_800_000L,
        )

        assertTrue("visible moved under a French locale: $line", line.contains("visible=120.0"))
        assertTrue("first moved: $line", line.contains("first=3.25"))
        assertFalse("a comma decimal reached the capture: $line", line.contains("3,25"))
    }

    @Test
    fun `a comma-decimal locale does not change the stage line`() {
        Locale.setDefault(Locale.GERMANY)

        val line = stagesOf(1_200, 400, 33_000)

        assertTrue("input moved under a German locale: $line", line.contains("input=1.20"))
        assertTrue("draw moved: $line", line.contains("draw=33.00"))
        assertFalse("a comma decimal reached the capture: $line", line.contains("1,20"))
    }

    // ---------- the stage line ----------

    @Test
    fun `the stage line names every stage and carries a us-scaled value`() {
        // Nanoseconds in, milliseconds out: 1_200_000 ns is 1.20 ms. The suffix
        // convention (milliseconds here, `ms` on the frames line) only holds if
        // the scale is right, so it is asserted directly.
        val line = stagesOf(1_200, 400, 33_000)

        assertEquals("input=1.20 anim=0.40 draw=33.00", line)
    }

    @Test
    fun `a stage the array does not cover reads as zero rather than throwing`() {
        // `FrameMetrics` populates stages the accumulator may not have sized for.
        // A missing attribution is a gap in the log, not a reason to kill the
        // process inside a frame callback.
        val line = formatStageSummary(LongArray(1) { 1_000_000L }, stages)

        assertEquals("input=1.00 anim=0.00 draw=0.00", line)
    }

    @Test
    fun `an empty stage set formats as an empty string`() {
        assertEquals("", formatStageSummary(LongArray(0), emptyList()))
    }

    // ---------- the chart-state line ----------

    @Test
    fun `the chart-state line formats every column`() {
        // A truncated format string is the exact shape of the shipped crash, so
        // each field is asserted rather than just the first.
        val line = describeChartState(
            timeframe = "M1",
            totalBars = 500,
            barsVisible = 120.0f,
            firstVisibleIndex = 3.25f,
            rightPaddingBars = 12.0f,
            autoScale = true,
            forming = false,
            simulatedUtcMs = 1_705_276_800_000L,
        )

        assertTrue("missing tf: $line", line.contains("tf=M1"))
        assertTrue("missing bars: $line", line.contains("bars=500"))
        assertTrue("missing visible: $line", line.contains("visible=120.0"))
        assertTrue("missing first: $line", line.contains("first=3.25"))
        assertTrue("missing pad: $line", line.contains("pad=12.0"))
        assertTrue("missing autoscale: $line", line.contains("autoscale=true"))
        assertTrue("missing forming: $line", line.contains("forming=false"))
        assertTrue("missing sim: $line", line.contains("sim=1705276800000"))
    }

    @Test
    fun `a chart with no simulated clock prints a dash rather than null`() {
        // A live chart has no warp clock. `null` in the column would read as a
        // real value that failed to parse; `-` reads as absent, which it is.
        val line = describeChartState(
            timeframe = "M5",
            totalBars = 10,
            barsVisible = 10.0f,
            firstVisibleIndex = 0.0f,
            rightPaddingBars = 0.0f,
            autoScale = false,
            forming = true,
            simulatedUtcMs = null,
        )

        assertTrue("missing sim dash: $line", line.contains("sim=-"))
        assertFalse("a literal null reached the capture: $line", line.contains("null"))
    }

    // ---------- the frames line, under the locale rule ----------

    private fun summarizeFrameSummaryForTest(): FrameSummary {
        val sorted = LongArray(300) { (16.67 * 1_000_000.0).toLong() }
        sorted.sort()
        return summarizeFrameWindow(sorted, sorted.size)
    }
}
