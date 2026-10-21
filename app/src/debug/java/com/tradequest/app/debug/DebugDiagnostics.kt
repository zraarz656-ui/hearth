package com.tradequest.app.debug

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
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

    private val lock = Any()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var started: Boolean = false

    /**
     * Single writer thread.
     *
     * [log] is called from the frame callback, which runs on the main thread.
     * Appending to a file there would stall the main thread for the duration of
     * a write, and — because the frame monitor is what calls it — the stall
     * would land in the very frame-interval samples being reported, inflating
     * p99/max with the cost of measuring. One dedicated thread keeps the main
     * thread clean and preserves line order, which a thread pool would not.
     */
    private val writer: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tradequest-debug-log").apply { isDaemon = true }
        }

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

    /** Appends one timestamped line. Safe from any thread; never blocks the caller. */
    fun log(category: String, message: String) {
        val line = "${timestamp()} [$category] $message\n"
        // logcat first: it is cheap and synchronous, so the line is visible even
        // if the process dies before the writer thread drains.
        Log.i(TAG, "[$category] $message")
        writer.execute {
            synchronized(lock) {
                logFile?.appendText(line)
            }
        }
    }

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

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

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
     * Records the chart's draw-relevant state once per published frame.
     *
     * Called from the chart's draw path, so it must stay cheap and must not
     * write to disk directly. Repeats are dropped: a static chart redraws every
     * frame with identical numbers, and logging each one would bury the
     * transitions that actually matter.
     */
    fun logChartState(detail: String) {
        if (detail == lastChartState) return
        lastChartState = detail
        log("chart", detail)
    }

    @Volatile
    private var lastChartState: String? = null

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
