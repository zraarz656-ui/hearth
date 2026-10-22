package com.tradequest.app.debug

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import com.tradequest.app.core.chart.renderer.ChartRenderState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only diagnostics: a text log of device information, seed results and
 * real frame timing, written to a file that can be pulled off the device.
 *
 * # Why frame timing lives here and not in a test
 *
 * `ChartRendererTest` asserts that a draw pass stays fast, but it runs on the
 * JVM through Robolectric's CPU rasteriser. That can prove the renderer is not
 * walking the whole series; it cannot say anything about frame delivery on a
 * real GPU. [DebugFrameMonitor] uses [Choreographer], which reports the actual
 * vsync cadence, so a chart that misses frames on a real device shows up as a
 * p95/max that dwarfs the 16.7 ms budget.
 *
 * # Why there is no hook in `app/src/main`
 *
 * The monitor attaches from the debug application class via
 * [Application.ActivityLifecycleCallbacks] and samples the whole process while
 * an activity is resumed. Nothing in the shipping source has to know it exists,
 * so no seam is left behind in `main`.
 *
 * # Removal
 *
 * Debug instrumentation, not a Phase 8 deliverable. Delete
 * `app/src/debug/java/com/tradequest/app/debug/` and the debug manifest once
 * Phase 15's import UI makes it unnecessary.
 */
internal object DebugDiagnostics {

    private const val TAG = "TradeQuestDiag"
    private const val FILE_NAME = "tradequest-debug.log"

    /** Trim the log once it passes this size, keeping [KEEP_BYTES] of the tail. */
    private const val MAX_BYTES = 512L * 1024L
    private const val KEEP_BYTES = 256 * 1024

    /** Frames per summary block. At 60 Hz this is roughly every 5 seconds. */
    internal const val FRAME_WINDOW = 300

    /**
     * Minimum gap between chart-state log lines: 100 ms, so at most 10 lines/s.
     *
     * A pan redraws every frame, and before this cap the log carried one line per
     * frame — about 60/s. Even with the formatting moved off the draw thread,
     * that is a lot of queue traffic and a lot of output to read. Ten per second
     * is still dense enough to reconstruct a pan's shape and its endpoints, which
     * is what the capture is for.
     *
     * Dedup runs in addition to this: a static chart produces no lines at all.
     */
    private const val CHART_MIN_INTERVAL_NANOS = 100_000_000L

    private val lock = Any()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var started: Boolean = false

    /**
     * Single writer thread.
     *
     * This thread owns *everything* expensive: timestamp formatting, string
     * building, the `logcat` write, and the file append. Callers only hand over
     * raw values.
     *
     * # Why logcat is not written on the caller's thread
     *
     * An earlier version called `Log.i` inline, on the theory that it is "cheap
     * and synchronous". The second half was true and the first was not: `Log.i`
     * writes to `logd` over a socket, so it is a synchronous IPC on whichever
     * thread calls it. The hottest caller is the chart's draw block, which emits
     * a line per pan frame — roughly 60 per second. That put 60 blocking IPC
     * writes per second inside the frame callback, and the resulting jank was
     * then measured by the frame monitor and reported as the *chart's*
     * performance. The instrument was measuring itself.
     *
     * Device logs confirmed it: jank appeared only in windows containing a pan
     * (15, 9, 6, 2) and was exactly zero in eight consecutive idle windows.
     *
     * So the main thread now only enqueues. The cost that remains is one small
     * lambda allocation per accepted sample, bounded by [CHART_MIN_INTERVAL_NANOS].
     */
    private val writer: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tradequest-debug-log").apply { isDaemon = true }
        }

    /**
     * Formatter for the log's timestamp column.
     *
     * Not thread-safe by contract, which is fine: only [writer]'s thread touches
     * it. The previous implementation built a fresh `SimpleDateFormat` for every
     * line, which is a comparatively expensive allocation to make 60 times a
     * second on the draw thread.
     */
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Opens the log and writes the device header. Idempotent per process. */
    fun start(context: Context) {
        synchronized(lock) {
            if (started) return
            started = true

            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            logFile = file

            // Appended, not truncated. Truncating here would wipe the log every
            // time the process restarts — including when the tester taps the
            // separate debug-log icon to *read* the log, which starts a fresh
            // process if the app had been killed. A session marker separates runs
            // instead, so the seed line from the first launch survives alongside
            // the frame numbers from a later one. Trimming bounds the file.
            trimIfOversized(file)
            Log.i(TAG, "diagnostics log: ${file.absolutePath}")
        }
        log("session", "start (pid=${android.os.Process.myPid()})")
        logDevice(context)
        installFrameMonitor(context as Application)
    }

    /**
     * Keeps the tail of the log when it grows past [MAX_BYTES].
     *
     * The frame monitor appends roughly a line every five seconds, so a long
     * session on a real device would otherwise grow without bound. The tail is
     * what matters: the newest frames and the most recent interaction.
     */
    private fun trimIfOversized(file: File) {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        try {
            val text = file.readText()
            val tail = text.takeLast(KEEP_BYTES)
            // Start at a line boundary so the first line is not a fragment.
            val firstNewline = tail.indexOf('\n')
            file.writeText(
                "[trimmed: earlier log discarded]\n" +
                    if (firstNewline >= 0) tail.substring(firstNewline + 1) else tail,
            )
        } catch (error: Exception) {
            Log.w(TAG, "could not trim log", error)
        }
    }

    /**
     * Appends one timestamped line.
     *
     * The caller's thread does no formatting, no logcat write and no file I/O —
     * it enqueues a lambda and returns. Everything else happens on [writer]. See
     * the note on [writer] for why this matters: this method is called from the
     * chart's draw block.
     */
    fun log(category: String, message: String) {
        enqueue { line(category, message) }
    }

    /**
     * The actual write, on [writer]'s thread.
     *
     * `Log.i` is kept — it is how a line survives if the process dies before the
     * file append lands — but it now runs here, off the main thread, so its IPC
     * cost cannot land in a measured frame interval.
     */
    private fun line(category: String, message: String) {
        val text = "${timestamp()} [$category] $message\n"
        Log.i(TAG, "[$category] $message")
        synchronized(lock) {
            logFile?.appendText(text)
        }
    }

    /**
     * Hands [work] to [writer].
     *
     * The lambda captures its arguments, so a small allocation is unavoidable
     * without a ring buffer; that is the one cost still paid by the caller. It is
     * an allocation, not a syscall, and the hot path bounds how often it happens.
     */
    private inline fun enqueue(crossinline work: () -> Unit) {
        writer.execute { work() }
    }

    private fun timestamp(): String = timestampFormat.format(Date())

    /** Absolute path of the log file, shown on the debug log screen. */
    fun logPath(): String = logFile?.absolutePath ?: "(diagnostics not started)"

    /**
     * The log file's current contents.
     *
     * The writer thread appends asynchronously, so a read that races an append
     * may miss the last line or two. That is acceptable for a screen that
     * refreshes once a second, and it is why the reader does not try to take the
     * same lock — a read must never block the frame callback's enqueue.
     */
    fun readLog(): String {
        val file = logFile ?: return "(diagnostics not started)"
        return try {
            when {
                !file.exists() -> "Log file does not exist yet:\n${file.absolutePath}"
                file.length() == 0L -> "(log is empty)"
                else -> file.readText()
            }
        } catch (error: Exception) {
            "Could not read log: ${error::class.java.name}: ${error.message}"
        }
    }

    /** The log file, for sharing. Null until [start] has run. */
    fun logFileOrNull(): File? = logFile

    /** Truncates the log so a fresh capture starts from a known point. */
    fun clear() {
        val file = logFile ?: return
        writer.execute {
            synchronized(lock) {
                file.writeText("")
                lastChartState = null
            }
        }
        log("session", "log cleared")
    }

    private fun logDevice(context: Context) {
        val metrics = context.resources.displayMetrics
        log("device", "model=${Build.MANUFACTURER} ${Build.MODEL}")
        log("device", "android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("device", "abi=${Build.SUPPORTED_ABIS.joinToString(",")}")
        log(
            "device",
            "screen=${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.density} " +
                "densityDpi=${metrics.densityDpi}",
        )
    }

    // ---------- chart state ----------

    /**
     * Records the chart's draw-relevant state.
     *
     * Called from the chart's draw path on every frame, so this method is the one
     * that must stay nearly free. It does three things and no more:
     *
     * 1. Rate-limits to [CHART_MIN_INTERVAL_NANOS].
     * 2. Compares the raw values against the last logged ones and drops repeats.
     * 3. Enqueues the formatting onto [writer].
     *
     * The previous version took an already-formatted `String`, which meant the
     * caller built a `String.format` — the expensive part — on the draw thread
     * *before* the dedup check could throw it away. Passing the state itself
     * instead lets the format run on [writer]'s thread, and only for a sample that
     * survived both filters.
     *
     * The dedup key is the rendered text's inputs rather than the text, because
     * the text does not exist yet. Comparing the floats directly is what the
     * `visible=`/`first=` columns were already showing.
     */
    fun logChartState(state: ChartRenderState) {
        val now = System.nanoTime()
        if (now - lastChartLogNanos < CHART_MIN_INTERVAL_NANOS) return

        val viewport = state.viewport
        val key = ChartStateKey(
            timeframe = state.timeframe,
            totalBars = state.totalBars,
            barsVisible = viewport.barsVisible,
            firstVisibleIndex = viewport.firstVisibleIndex,
            rightPaddingBars = viewport.rightPaddingBars,
            autoScale = viewport.autoScale,
            forming = state.formingCandle != null,
            simulatedUtcMs = state.simulatedUtcMs,
        )
        if (key == lastChartState) return

        lastChartLogNanos = now
        lastChartState = key
        enqueue { line("chart", key.describe()) }
    }

    @Volatile
    private var lastChartLogNanos: Long = 0L

    @Volatile
    private var lastChartState: ChartStateKey? = null

    /**
     * The chart state fields that appear in a log line, as a comparable value.
     *
     * Exists so the draw-path dedup can compare primitives instead of formatted
     * text. `describe()` reproduces the column layout the device logs used, so
     * existing captures stay readable.
     */
    private data class ChartStateKey(
        val timeframe: Any,
        val totalBars: Int,
        val barsVisible: Float,
        val firstVisibleIndex: Float,
        val rightPaddingBars: Float,
        val autoScale: Boolean,
        val forming: Boolean,
        val simulatedUtcMs: Long?,
    ) {
        fun describe(): String =
            "tf=%s bars=%d visible=%.1f first=%.2f pad=%.1f autoscale=%b forming=%b sim=%s"
                .format(
                    timeframe, totalBars, barsVisible, firstVisibleIndex,
                    rightPaddingBars, autoScale, forming, simulatedUtcMs ?: "-",
                )
    }

    // ---------- frame timing ----------

    @Volatile
    private var frameMonitor: DebugFrameMonitor? = null

    private fun installFrameMonitor(application: Application) {
        val monitor = DebugFrameMonitor()

        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    monitor.start()
                }

                override fun onActivityPaused(activity: Activity) {
                    // Stopping here keeps the monitor from measuring idle
                    // frames, which are meaningless and would drag the average
                    // down, and from waking the device in the background.
                    monitor.stop()
                }

                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )

        frameMonitor = monitor
    }
}

/**
 * Samples the real frame cadence with [Choreographer] and writes a percentile
 * summary every [DebugDiagnostics.FRAME_WINDOW] frames.
 *
 * A frame interval is the time between two vsync callbacks. On a 60 Hz panel
 * the budget is 16.7 ms; anything well above it is a frame the user saw late.
 * The samples are kept in a `LongArray` sized once, so monitoring does not
 * allocate per frame — doing so would perturb the very measurement it takes.
 */
private class DebugFrameMonitor {

    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    private val samples = LongArray(DebugDiagnostics.FRAME_WINDOW)
    private var count = 0
    private var lastFrameNanos = 0L
    private var running = false

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) {
                samples[count++] = frameTimeNanos - lastFrameNanos
                if (count == samples.size) flush()
            }
            lastFrameNanos = frameTimeNanos
            choreographer.postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        count = 0
        lastFrameNanos = 0L
        choreographer.postFrameCallback(callback)
        DebugDiagnostics.log("frames", "sampling started")
    }

    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(callback)
        if (count > 0) flush()
        DebugDiagnostics.log("frames", "sampling stopped")
    }

    private fun flush() {
        if (count == 0) return

        val sorted = samples.copyOf(count)
        sorted.sort()

        var total = 0L
        for (i in 0 until count) total += sorted[i]
        val avgMs = total.toDouble() / count / 1_000_000.0

        fun pct(p: Double): Double =
            sorted[((count - 1) * p).toInt().coerceIn(0, count - 1)] / 1_000_000.0

        val maxMs = sorted[count - 1] / 1_000_000.0

        // Jank is measured against the panel's own cadence, not a fixed 16.7 ms.
        // On a 120 Hz screen a 16.7 ms frame is two dropped frames, so a fixed
        // threshold would report it as fine. The median interval approximates
        // the refresh period, and anything past 1.5x that is a frame the user
        // saw late. This also means the number is meaningful without knowing the
        // device's refresh rate in advance.
        val baselineMs = pct(0.50)
        val jankThreshold = baselineMs * 1.5
        val jank = sorted.count { it / 1_000_000.0 > jankThreshold }

        DebugDiagnostics.log(
            "frames",
            "n=%d avg=%.2fms p50=%.2f p95=%.2f p99=%.2f max=%.2f jank=%d(>%.1fms) fps~%.0f"
                .format(
                    count, avgMs, baselineMs, pct(0.95), pct(0.99), maxMs,
                    jank, jankThreshold, if (baselineMs > 0) 1000.0 / baselineMs else 0.0,
                ),
        )

        count = 0
    }
}
