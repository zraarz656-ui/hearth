package com.tradequest.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the frame-window arithmetic that the `frames` log line reports.
 *
 * This is the part of the diagnostics that makes a claim — "this window was
 * janky" — so it is the part worth pinning down. The monitoring itself needs a
 * device, but the reduction is pure, and a silent change to the jank threshold
 * would otherwise only be visible by reading two captures weeks apart.
 */
class FrameSummaryTest {

    private fun ms(value: Double): Long = (value * 1_000_000.0).toLong()

    private fun summarize(vararg values: Double): FrameSummary {
        val sorted = LongArray(values.size) { ms(values[it]) }
        sorted.sort()
        return summarizeFrameWindow(sorted, sorted.size)
    }

    @Test
    fun `a steady 60hz window reports no jank`() {
        val summary = summarize(*DoubleArray(300) { 16.67 })

        assertEquals(0, summary.jank)
        assertEquals(16.67, summary.p50Ms, 0.01)
        assertEquals(60.0, summary.fps, 0.5)
        assertEquals(0, summary.over2x)
    }

    @Test
    fun `jank threshold scales with the panel rather than a fixed budget`() {
        // The same 16.7 ms interval is one dropped frame at 120 Hz and a healthy
        // frame at 60 Hz. The threshold must follow the panel, so a 120 Hz
        // window of 16.7 ms frames is janky and a 60 Hz one is not.
        val at120 = summarize(*DoubleArray(300) { 16.67 }, *DoubleArray(300) { 8.33 })
        val at60 = summarize(*DoubleArray(300) { 16.67 })

        assertTrue(
            "16.7 ms frames beside an 8.3 ms median are two dropped frames",
            at120.jank > 0,
        )
        assertEquals(0, at60.jank)
    }

    @Test
    fun `a single long stall is counted once but does not move the median`() {
        val frames = DoubleArray(299) { 16.67 } + 200.0
        val summary = summarize(*frames)

        assertEquals(1, summary.jank)
        assertEquals(200.0, summary.maxMs, 0.01)
        assertEquals(16.67, summary.p50Ms, 0.01)
        assertEquals(1, summary.over8x)
    }

    @Test
    fun `drop buckets separate one big stall from many small ones`() {
        // A window with one huge freeze and a window with a burst of mildly late
        // frames are different bugs. The buckets are what separate them: the
        // single freeze reaches the 8x bucket, the burst only reaches 2x.
        val oneBig = summarize(*(DoubleArray(299) { 16.67 } + 200.0))
        val manySmall = summarize(*(DoubleArray(290) { 16.67 } + DoubleArray(10) { 40.0 }))

        assertEquals(1, oneBig.over2x)
        assertEquals(10, manySmall.over2x)
        assertTrue(oneBig.over8x > 0)
        assertEquals(0, manySmall.over8x)
    }

    @Test
    fun `percentiles are taken from the sorted samples`() {
        // 1..100 ms, so the answers are checkable by hand: p50 is the 50th
        // sample, p95 the 95th, and the maximum the last.
        val summary = summarize(*DoubleArray(100) { (it + 1).toDouble() })

        assertEquals(50.0, summary.p50Ms, 0.01)
        assertEquals(95.0, summary.p95Ms, 0.01)
        assertEquals(99.0, summary.p99Ms, 0.01)
        assertEquals(100.0, summary.maxMs, 0.01)
    }

    @Test
    fun `a single frame window does not divide by zero`() {
        val summary = summarize(16.67)

        assertEquals(1, summary.count)
        assertEquals(16.67, summary.p50Ms, 0.01)
        assertEquals(16.67, summary.avgMs, 0.01)
        assertEquals(0, summary.jank)
    }

    // ---------- the formatted line ----------

    /**
     * The `frames` line must format at all.
     *
     * This is the regression test for a crash that shipped to a device. The line
     * was built as `"first " + "second".format(args)`, and because `+` binds
     * looser than `.format` the receiver was only the second literal. The format
     * string began at `fps~`, so the `Int` count met `%.0f` and threw
     * `IllegalFormatConversionException` — from inside the `Choreographer`
     * callback, on the main thread, which kills the process. Every 300-frame
     * window crashed the app about 2.5 s after launch on a 120 Hz panel.
     *
     * Asserting only the arithmetic, as the tests above do, cannot see that: the
     * numbers were right, and the line that printed them was not. So this asserts
     * on the produced string.
     */
    @Test
    fun `the frames line formats without throwing`() {
        val summary = summarize(*DoubleArray(300) { 16.67 })

        val line = formatFrameSummary(summary, allocatedPerSecond = 1_048_576.0, gcDelta = 3L)

        // Every field must be filled in, not just the first two. A truncated
        // format string is precisely the shape of the original bug.
        assertTrue("missing count: $line", line.contains("n=300"))
        assertTrue("missing avg: $line", line.contains("avg=16.67ms"))
        assertTrue("missing jank: $line", line.contains("jank=0"))
        assertTrue("missing fps: $line", line.contains("fps~60"))
        assertTrue("missing alloc: $line", line.contains("alloc=1024KB/s"))
        assertTrue("missing gc: $line", line.contains("gc=3"))
    }

    @Test
    fun `the frames line renders an unknown allocation rate without throwing`() {
        // `allocatedPerSecond` is -1.0 when the runtime stat is unavailable, which
        // is the branch a device with a missing `art.gc.bytes-allocated` takes.
        val summary = summarize(*DoubleArray(300) { 16.67 })

        val line = formatFrameSummary(summary, allocatedPerSecond = -1.0, gcDelta = -1L)

        assertTrue("missing unknown alloc: $line", line.contains("alloc=-0KB/s"))
        assertTrue("missing unknown gc: $line", line.contains("gc=-1"))
    }
}
