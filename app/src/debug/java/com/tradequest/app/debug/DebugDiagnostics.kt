package com.tradequest.app.debug

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import android.view.WindowManager
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
     * How often the main-thread stall watchdog re-arms: 50 ms.
     *
     * Twenty wake-ups a second is far cheaper than a frame and is enough
     * resolution to catch a multi-hundred-millisecond block, which is the shape
     * the device log showed.
     */
    internal const val STALL_INTERVAL_MS = 50L

    /**
     * The `FrameMetrics` stages this log reports, with their short labels.
     *
     * Defined once and shared by the accumulator and the formatter, so the
     * index each value is stored under and the name it is printed with can never
     * drift apart.
     *
     * `GPU_DURATION` is API 31. On older devices the constant still resolves —
     * it is inlined at compile time — but the platform never populates it, so
     * including it would print a hard `0.00` that reads like a real measurement.
     * It is therefore omitted below 31 rather than shown as zero.
     *
     * `UNKNOWN_DELAY_DURATION` is included deliberately: it is the time a frame
     * spent in a state the platform cannot name, and on Android that is usually
     * where a blocked main thread shows up. Leaving it out would make a blocked
     * window look like a fast one.
     */
    internal val STAGE_LABELS: List<Pair<Int, String>> = buildList {
        add(FrameMetrics.INPUT_HANDLING_DURATION to "input")
        add(FrameMetrics.ANIMATION_DURATION to "anim")
        add(FrameMetrics.LAYOUT_MEASURE_DURATION to "layout")
        add(FrameMetrics.DRAW_DURATION to "draw")
        add(FrameMetrics.SYNC_DURATION to "sync")
        add(FrameMetrics.COMMAND_ISSUE_DURATION to "issue")
        add(FrameMetrics.SWAP_BUFFERS_DURATION to "swap")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(FrameMetrics.GPU_DURATION to "gpu")
        }
        add(FrameMetrics.UNKNOWN_DELAY_DURATION to "unknown")
    }

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

    @Volatile
    private var crashHandlerInstalled: Boolean = false

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
        logPreviousExit(context)
        installCrashHandler()
        installFrameMonitor(context as Application)
    }

    /**
     * Reports how the previous process died, if it did not exit cleanly.
     *
     * # Why this is here as well as [installCrashHandler]
     *
     * The uncaught-exception handler only sees a crash that unwinds through the
     * Java/Kotlin runtime. It cannot see a native crash — and although this app
     * ships no native code of its own, Compose and Skia are native, so a fault
     * inside the renderer kills the process without ever reaching Java. It also
     * cannot see an ANR or a low-memory kill, because in both cases the system
     * ends the process rather than the process throwing.
     *
     * `ApplicationExitInfo` is the platform's own record of *why* a process
     * ended, and it covers all of those cases. Because this build is currently
     * dying and relaunching, each new start can report the death that preceded
     * it, which is exactly the loop that has been invisible in the log.
     *
     * # Why the stack trace is read separately
     *
     * `getTraceInputStream` is populated for a crash or an ANR and empty
     * otherwise; reading it for a clean exit throws. It is therefore fetched only
     * for the reasons that carry one, and any failure to read it is recorded as a
     * gap rather than allowed to abort the device header.
     *
     * # Why only the most recent entry
     *
     * The interesting death is the one immediately before this launch. Listing
     * every retained exit would bury it under history on a device that has been
     * relaunching repeatedly.
     */
    private fun logPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager ?: return
            // `(null, 0, 1)`: this package (a null name means the caller's own),
            // any pid, most recent entry only. This three-argument overload is the
            // one present since API 30; the no-argument convenience form is newer.
            val info = manager
                .getHistoricalProcessExitReasons(null, 0, 1)
                .firstOrNull() ?: return

            val reason = exitReasonName(info.reason)
            log(
                "exit",
                "previous process: reason=$reason importance=${info.importance} " +
                    "status=${info.status} pss=${info.pss / 1024}KB rss=${info.rss / 1024}KB",
            )
            info.description?.let { log("exit", "description=$it") }

            if (info.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                info.reason == android.app.ApplicationExitInfo.REASON_ANR
            ) {
                try {
                    val trace = info.traceInputStream?.bufferedReader()?.use { it.readText() }
                    if (trace.isNullOrBlank()) {
                        log("exit", "trace: (empty)")
                    } else {
                        log("exit", "trace follows")
                        // Line by line so each entry carries a timestamp and can be
                        // scrolled to on the in-app log screen.
                        trace.lineSequence().filter { it.isNotBlank() }.forEach {
                            log("exit", "  $it")
                        }
                    }
                } catch (error: Exception) {
                    log("exit", "trace unavailable: ${error::class.java.simpleName}")
                }
            }
        } catch (error: Exception) {
            log("exit", "exit history unavailable: ${error::class.java.simpleName}")
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        android.app.ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    /**
     * Records an uncaught exception into the log file, then delegates.
     *
     * # Why this exists
     *
     * A device capture showed the app dying a few seconds after launch with no
     * line in this file explaining why. The log is the app's own diagnostics, and
     * nothing in any source set installs an uncaught-exception handler, so the
     * stack trace went only to logcat — which needs a cable to read. On a phone in
     * hand that is the same as losing it. This writes the throwable to the log so
     * the reason survives on the device, which is where the crash happens.
     *
     * # Why it writes synchronously, bypassing [writer]
     *
     * The process is about to die. An enqueued lambda may never run, and `log`
     * would hand the write to [writer]'s thread that is racing the teardown. The
     * write is done inline here, on the dying thread, and flushed before the
     * default handler runs. This is the one place in the class where blocking the
     * caller is correct: there is no next frame to protect.
     *
     * # Why the default handler still runs
     *
     * This only records. It then chains to the previous handler so the platform's
     * normal crash behaviour — the "app has stopped" dialog and the logcat entry —
     * is unchanged. Swallowing the exception here would hide the crash from the
     * system and leave the process in an undefined state.
     */
    private fun installCrashHandler() {
        synchronized(lock) {
            if (crashHandlerInstalled) return
            crashHandlerInstalled = true
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildString {
                    append("\n===== UNCAUGHT EXCEPTION =====\n")
                    append("thread=${thread.name}\n")
                    append("type=${error::class.java.name}\n")
                    append("message=${error.message}\n")
                    append(android.util.Log.getStackTraceString(error))
                    append("==============================\n\n")
                }
                Log.e(TAG, "uncaught exception", error)
                synchronized(lock) {
                    logFile?.appendText(text)
                }
            } catch (writeError: Throwable) {
                // A failure to record the crash must not replace the crash. The
                // default handler below still reports the original.
                Log.e(TAG, "could not write crash to log", writeError)
            }
            previous?.uncaughtException(thread, error)
        }
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
        // The thread name is read *here*, on the caller's thread, because
        // [line] runs on [writer] and would otherwise stamp every line with
        // "tradequest-debug-log". Attribution is the whole point of this
        // addition: a stall on the main thread and a stall on a worker are
        // different bugs.
        val thread = Thread.currentThread().name
        enqueue { line(category, message, thread) }
    }

    /**
     * The actual write, on [writer]'s thread.
     *
     * `Log.i` is kept — it is how a line survives if the process dies before the
     * file append lands — but it now runs here, off the main thread, so its IPC
     * cost cannot land in a measured frame interval.
     */
    private fun line(category: String, message: String, thread: String) {
        val text = "${timestamp()} [$category] ($thread) $message\n"
        Log.i(TAG, "[$category] ($thread) $message")
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

        // The panel's modes, and which one is active. The previous capture
        // reported `fps~61` and it was read as the panel's refresh rate; it is
        // actually `1000 / p50`, the app's own cadence. Without the panel's real
        // rate in the log there is no way to tell "rendering at the panel rate"
        // from "rendering at half of it", which is the difference between a
        // healthy chart and a dropped frame on every vsync.
        //
        // Resolved through `DisplayManager`, not `context.display`.
        //
        // `start` runs from `Application.onCreate`, and an `Application` context
        // has no display association: `context.display` returns null and the
        // deprecated `WindowManager.defaultDisplay` throws. A device capture
        // proved this the hard way — it reported "no display available (headless
        // context)" while the app was rendering at 61 fps on a 120 Hz panel, so
        // the block below never ran where it was most needed. The default display
        // is the panel the app's window is on, and `DisplayManager` can be asked
        // for it from any context.
        val display = try {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE)
                as? android.hardware.display.DisplayManager
            manager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        } catch (error: Exception) {
            null
        }
        if (display == null) {
            log("display", "no display available (headless context)")
        } else {
            try {
                val current = display.mode
                log(
                    "display",
                    diagFormat(
                        "refresh=%.2fHz mode=%dx%d@%.2fHz",
                        display.refreshRate, current.physicalWidth, current.physicalHeight,
                        current.refreshRate,
                    ),
                )
                val modes = display.supportedModes.joinToString(" ") {
                    diagFormat("${it.physicalWidth}x${it.physicalHeight}@%.2f", it.refreshRate)
                }
                log("display", "modes=$modes")
                log(
                    "display",
                    diagFormat("hdr=%b wideColor=%b", display.isHdr, display.isWideColorGamut),
                )
            } catch (error: Exception) {
                log("display", "modes unavailable: ${error::class.java.simpleName}")
            }
        }

        // A baseline for the per-window allocation delta. `art.gc.bytes-allocated`
        // is a monotonic counter, so a rate only exists relative to a previous
        // reading. Guarded for the same reason as the display block: a missing
        // statistic must not abort the rest of the device header.
        try {
            log(
                "heap",
                diagFormat(
                    "start allocated=%dKB limit=%dKB",
                    Debug.getNativeHeapAllocatedSize() / 1024,
                    Runtime.getRuntime().maxMemory() / 1024,
                ),
            )
        } catch (error: Exception) {
            log("heap", "unavailable: ${error::class.java.simpleName}")
        }
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
        // The thread here is always the main thread, and saying so explicitly is
        // the point: it proves the draw-path sample is attributed to the frame
        // callback rather than to a worker.
        val thread = Thread.currentThread().name
        enqueue { line("chart", key.describe(), thread) }
    }

    @Volatile
    private var lastChartLogNanos: Long = 0L

    @Volatile
    private var lastChartState: ChartStateKey? = null

    /**
     * Records a gesture boundary.
     *
     * Not rate-limited like [logChartState]: these are two lines per gesture, and
     * the whole point is that the start and end times are exact. Deduplicating
     * them would destroy the interval they exist to bracket.
     *
     * Called on the main thread from the chart's pointer loop, so it does nothing
     * but hand a string to [writer].
     */
    fun logGesture(name: String, started: Boolean) {
        val thread = Thread.currentThread().name
        val verb = if (started) "begin" else "end"
        enqueue { line("gesture", "$verb $name", thread) }
    }

    /**
     * The chart state fields that appear in a log line, as a comparable value.
     *
     * Exists so the draw-path dedup can compare primitives instead of formatted
     * text. `describe()` reproduces the column layout the device logs used, so
     * existing captures stay readable.
     */
    internal data class ChartStateKey(
        val timeframe: Any,
        val totalBars: Int,
        val barsVisible: Float,
        val firstVisibleIndex: Float,
        val rightPaddingBars: Float,
        val autoScale: Boolean,
        val forming: Boolean,
        val simulatedUtcMs: Long?,
    ) {
        fun describe(): String = describeChartState(
            timeframe, totalBars, barsVisible, firstVisibleIndex,
            rightPaddingBars, autoScale, forming, simulatedUtcMs,
        )
    }

    // ---------- frame timing ----------

    @Volatile
    private var frameMonitor: DebugFrameMonitor? = null

    /**
     * The background thread that receives `FrameMetrics` callbacks.
     *
     * `FrameMetrics` can be delivered on a handler of our choosing, and choosing
     * a background one keeps the per-frame listener off the main thread. The
     * callback only accumulates into arrays, but it runs 60–120 times a second
     * and there is no reason for it to share the thread it is measuring.
     */
    internal val frameHandler: Handler by lazy {
        val thread = HandlerThread("tradequest-frame-metrics")
        thread.start()
        Handler(thread.looper)
    }

    /**
     * Writes one window's stage attribution.
     *
     * `avg` and `worst` are indexed by [FrameMetrics] stage constant; this
     * formats them into the same `name=value` shape as the rest of the log, with
     * a `us` suffix so a reader can never mistake these for the millisecond
     * numbers on the `frames` line.
     */
    fun logFrameStages(
        frames: Int,
        drops: Int,
        worstTotalNanos: Long,
        avg: LongArray,
        worst: LongArray,
    ) {
        enqueue {
            line(
                "metrics",
                diagFormat(
                    "n=%d drops=%d worstTotal=%.2fms", frames, drops, worstTotalNanos / 1e6,
                ),
                "metrics",
            )
            line("metrics", "  avg  " + stageSummary(avg), "metrics")
            line("metrics", "  worst " + stageSummary(worst), "metrics")
        }
    }

    private fun stageSummary(values: LongArray): String =
        formatStageSummary(values, STAGE_LABELS)

    private fun installFrameMonitor(application: Application) {
        val monitor = DebugFrameMonitor()
        val metrics = DebugFrameMetrics()
        val watchdog = DebugStallWatchdog()

        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    monitor.start()
                    watchdog.start()
                    metrics.attach(activity.window)
                }

                override fun onActivityPaused(activity: Activity) {
                    // Stopping here keeps the monitor from measuring idle
                    // frames, which are meaningless and would drag the average
                    // down, and from waking the device in the background.
                    monitor.stop()
                    watchdog.stop()
                    metrics.detach()
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
 *
 * # What this can and cannot say
 *
 * The interval between two vsync callbacks is *elapsed wall time*, so it
 * measures a dropped frame but cannot attribute it: a 132 ms gap looks the
 * same whether the main thread was blocked, the GPU was slow, or the buffer
 * queue was full. It is also blind to work that blocks the main thread without
 * missing a frame.
 *
 * [DebugFrameMetrics] and [DebugStallWatchdog] exist to close exactly that gap.
 * This class stays as the cheap always-on timeline; the other two add
 * attribution.
 */
private class DebugFrameMonitor {

    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    private val samples = LongArray(DebugDiagnostics.FRAME_WINDOW)
    private var count = 0
    private var lastFrameNanos = 0L
    private var running = false

    /**
     * Allocation and GC counters carried between windows.
     *
     * `bytes-allocated` is monotonic, so the interesting number is the delta
     * across the window divided by its duration. Reading it per window rather
     * than per frame keeps it off the hot path.
     */
    private var lastAllocatedBytes = 0L
    private var lastBlockingGcMs = 0L
    private var windowStartNanos = 0L

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
        windowStartNanos = System.nanoTime()
        lastAllocatedBytes = readAllocatedBytes()
        lastBlockingGcMs = readBlockingGcMs()
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

    /**
     * `art.gc.bytes-allocated`, or 0 where the runtime does not expose it.
     *
     * Wrapped because a missing statistic must not take the monitor down; an
     * absent number is a gap in the log, not a crash.
     */
    private fun readAllocatedBytes(): Long = try {
        Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
    } catch (error: Exception) {
        0L
    }

    private fun readBlockingGcMs(): Long = try {
        Debug.getRuntimeStat("art.gc.blocking-gc-count").toLong() +
            Debug.getRuntimeStat("art.gc.gc-count").toLong()
    } catch (error: Exception) {
        0L
    }

    private fun flush() {
        if (count == 0) return

        // Everything below is instrumentation, and instrumentation must never take
        // the app down. This runs inside the `Choreographer` callback on the main
        // thread, so an exception here is an uncaught exception and the process
        // dies — which is exactly how a formatting bug in this method killed the
        // app on a device, every 300 frames. The monitor already treats a missing
        // runtime statistic as "a gap in the log, not a crash"; the summary is held
        // to the same rule. `finally` resets the window even on failure, so a
        // broken window cannot wedge the counter and re-throw on every frame.
        try {
            val sorted = samples.copyOf(count)
            sorted.sort()
            val summary = summarizeFrameWindow(sorted, count)

            val now = System.nanoTime()
            val windowSeconds = if (windowStartNanos == 0L) 0.0 else (now - windowStartNanos) / 1e9
            val allocated = readAllocatedBytes()
            val gcCount = readBlockingGcMs()
            val allocatedPerSecond =
                if (windowSeconds > 0 && lastAllocatedBytes > 0 && allocated >= lastAllocatedBytes) {
                    (allocated - lastAllocatedBytes) / windowSeconds
                } else {
                    -1.0
                }
            val gcDelta = if (gcCount >= lastBlockingGcMs) gcCount - lastBlockingGcMs else -1L

            lastAllocatedBytes = allocated
            lastBlockingGcMs = gcCount
            windowStartNanos = now

            DebugDiagnostics.log(
                "frames",
                formatFrameSummary(summary, allocatedPerSecond, gcDelta),
            )
        } catch (error: Exception) {
            DebugDiagnostics.log("frames", "window summary failed: ${error::class.java.simpleName}")
        } finally {
            count = 0
        }
    }
}

/**
 * Formats one window's `frames` line.
 *
 * # Why this is a separate function
 *
 * The frame-window arithmetic is pure and was tested; the *formatting* was not,
 * and that is where a real crash lived. The line was built as
 *
 *     "first half " +
 *         "second half".format(...)
 *
 * and `+` binds looser than `.format`, so the receiver was only the second
 * literal. The format string therefore began at `fps~`, and the first argument
 * — an `Int` count — was applied to `%.0f`. That throws
 * `IllegalFormatConversionException` from inside the `Choreographer` frame
 * callback, which is an uncaught exception on the main thread: it kills the
 * process. On a 120 Hz panel the flush fires at 300 frames, about 2.5 seconds
 * after launch, so the app appeared to crash a few seconds into every run and
 * the summary line could never be written. Pausing the activity hit the same
 * path through `stop()`, so it also crashed on the way out.
 *
 * Extracting the formatter makes the failure reachable from a unit test with no
 * device, a `Choreographer` or a log file — the same reason
 * [summarizeFrameWindow] is a top-level function. The parentheses around the
 * concatenation are load-bearing; without them the expression re-parses into the
 * broken shape above.
 */
internal fun formatFrameSummary(
    summary: FrameSummary,
    allocatedPerSecond: Double,
    gcDelta: Long,
): String =
    diagFormat(
        "n=%d avg=%.2fms p50=%.2f p95=%.2f p99=%.2f max=%.2f jank=%d(>%.1fms) " +
            "fps~%.0f drop2=%d drop4=%d drop8=%d alloc=%.0fKB/s gc=%d",
        summary.count, summary.avgMs, summary.p50Ms, summary.p95Ms,
        summary.p99Ms, summary.maxMs, summary.jank, summary.jankThresholdMs,
        summary.fps, summary.over2x, summary.over4x, summary.over8x,
        allocatedPerSecond / 1024.0, gcDelta,
    )

/**
 * Formats one log line with a fixed, non-locale-dependent number format.
 *
 * # Why this exists
 *
 * `String.format` without a `Locale` uses the *device's* default, so a phone set
 * to German renders `avg=16,67ms` where the diagnostics — and every capture
 * taken on an English phone — read `avg=16.67ms`. Two consequences, both real:
 *
 *  1. A capture cannot be compared against an earlier one, or against the
 *     thresholds in this file's own KDoc (`16.7 ms`, `p95`), because the decimal
 *     mark moved.
 *  2. `16,67` is ambiguous rather than merely different: a comma is a thousands
 *     separator in some locales and a decimal mark in others, so a reader cannot
 *     tell `16,67` ms from `1667` ms.
 *
 * The log is a machine-readable artefact that gets pasted into reports and diffed
 * between runs, so it must not vary with a setting unrelated to what it measures.
 * [timestampFormat] already pinned `Locale.US` for exactly this reason; this
 * applies the same rule to the numbers.
 *
 * Everything in the diagnostics goes through here so the rule cannot be applied
 * to one line and forgotten on the next — which is how the timestamp came to be
 * pinned while the numbers beside it were not.
 */
internal fun diagFormat(format: String, vararg args: Any?): String =
    java.lang.String.format(Locale.US, format, *args)

/**
 * Formats one `metrics` line's per-stage columns, e.g. `input=1.20 anim=0.40`.
 *
 * A top-level function for the same reason as [formatFrameSummary]: the stage
 * line is built by a `String.format` that a device crash proved can be wrong, and
 * a member of a private class is not reachable from a unit test. [labels] is
 * passed in rather than read from [DebugDiagnostics.STAGE_LABELS] so the test can
 * drive a fixed stage set.
 *
 * [values] is indexed by `FrameMetrics` stage constant; a stage the array does not
 * cover reads as `0.00` rather than throwing, because a missing attribution is a
 * gap in the log, not a reason to take the process down.
 */
internal fun formatStageSummary(
    values: LongArray,
    labels: List<Pair<Int, String>>,
): String =
    labels.joinToString(" ") { (stage, name) ->
        diagFormat("%s=%.2f", name, values.getOrElse(stage) { 0L } / 1e6)
    }

/**
 * Formats the chart-state log line, e.g. `tf=M1 bars=500 visible=120.0 ...`.
 *
 * Top-level so the line is reachable from a unit test without a chart, a
 * `Choreographer` or a log file. It is the highest-frequency line in the log —
 * one per accepted pan frame — and therefore the one whose formatting bug would
 * be both most likely to reach a device and hardest to spot in a capture.
 */
internal fun describeChartState(
    timeframe: Any?,
    totalBars: Int,
    barsVisible: Float,
    firstVisibleIndex: Float,
    rightPaddingBars: Float,
    autoScale: Boolean,
    forming: Boolean,
    simulatedUtcMs: Long?,
): String =
    diagFormat(
        "tf=%s bars=%d visible=%.1f first=%.2f pad=%.1f autoscale=%b forming=%b sim=%s",
        timeframe, totalBars, barsVisible, firstVisibleIndex,
        rightPaddingBars, autoScale, forming, simulatedUtcMs ?: "-",
    )

/**
 * Reduces one window's sorted frame intervals into the numbers the `frames` line
 * prints.
 *
 * A top-level function rather than a member of [DebugFrameMonitor] so it can be
 * tested without a `Choreographer`, a device or a log file. It is pure: given the
 * same intervals it produces the same summary, which is what makes a regression
 * in the jank threshold visible in a unit test rather than only on a phone.
 *
 * [sorted] must be ascending, and its first [count] entries the samples.
 */
internal fun summarizeFrameWindow(sorted: LongArray, count: Int): FrameSummary {
    var total = 0L
    for (i in 0 until count) total += sorted[i]
    val avgMs = total.toDouble() / count / 1_000_000.0

    fun pct(p: Double): Double =
        sorted[((count - 1) * p).toInt().coerceIn(0, count - 1)] / 1_000_000.0

    // Jank is measured against the panel's own cadence, not a fixed 16.7 ms. On a
    // 120 Hz screen a 16.7 ms frame is two dropped frames, so a fixed threshold
    // would report it as fine. The median interval approximates the refresh
    // period, and anything past 1.5x that is a frame the user saw late. This also
    // means the number is meaningful without knowing the device's refresh rate in
    // advance.
    val p50 = pct(0.50)
    val jankThreshold = p50 * 1.5
    var jank = 0
    var over2 = 0
    var over4 = 0
    var over8 = 0
    for (i in 0 until count) {
        val ms = sorted[i] / 1_000_000.0
        if (ms > jankThreshold) jank++
        if (ms > p50 * 2) over2++
        if (ms > p50 * 4) over4++
        if (ms > p50 * 8) over8++
    }

    return FrameSummary(
        count = count,
        avgMs = avgMs,
        p50Ms = p50,
        p95Ms = pct(0.95),
        p99Ms = pct(0.99),
        maxMs = sorted[count - 1] / 1_000_000.0,
        jank = jank,
        jankThresholdMs = jankThreshold,
        fps = if (p50 > 0) 1000.0 / p50 else 0.0,
        over2x = over2,
        over4x = over4,
        over8x = over8,
    )
}

/**
 * One window's frame-interval statistics, as printed on the `frames` line.
 *
 * A value type rather than a formatted string so the numbers survive a test
 * assertion; [summarizeFrameWindow] is the only producer.
 */
internal data class FrameSummary(
    val count: Int,
    val avgMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val jank: Int,
    val jankThresholdMs: Double,
    val fps: Double,
    val over2x: Int,
    val over4x: Int,
    val over8x: Int,
)

/**
 * Per-frame stage attribution, from the platform's own [FrameMetrics].
 *
 * # Why this exists
 *
 * [DebugFrameMonitor] says a frame took 132 ms. It cannot say where the time
 * went, and the four candidates — input, layout, draw, GPU — have completely
 * different fixes. This listener reports each stage separately, so a window's
 * slow frames can be attributed instead of guessed at.
 *
 * # Why the numbers are not added up here
 *
 * `FrameMetrics` reports nanoseconds per stage. They are summed per window into
 * fixed arrays and reduced on [DebugDiagnostics.writer]'s thread, so the
 * callback itself does one array write per stage and nothing else. It runs on
 * the main thread for every frame, so anything more would be the instrument
 * measuring itself — the failure `DebugDiagnostics` already documents.
 */
private class DebugFrameMetrics {

    private val window = DebugDiagnostics.FRAME_WINDOW

    /** Sums per stage, indexed by [FrameMetrics]' own stage constants. */
    private val sums = LongArray(STAGE_COUNT)
    private var frames = 0

    /**
     * The slowest single frame's per-stage split.
     *
     * A window's *average* stage cost hides the one frame that stalled; the
     * worst frame is the one the user felt, so its breakdown is kept whole.
     */
    private val worst = LongArray(STAGE_COUNT)
    private var worstTotal = 0L

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropCount ->
        // A non-zero drop count means frames were dropped before this one, which
        // is itself a finding: it means the buffer queue backed up.
        if (dropCount > 0) drops += dropCount

        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        for (stage in STAGES) {
            sums[stage] += metrics.getMetric(stage)
        }
        frames++
        if (total > worstTotal) {
            worstTotal = total
            for (stage in STAGES) worst[stage] = metrics.getMetric(stage)
        }
        if (frames >= window) flush()
    }

    private var drops = 0

    private var attachedWindow: Window? = null

    fun attach(window: Window) {
        if (attachedWindow === window) return
        detach()
        try {
            window.addOnFrameMetricsAvailableListener(listener, DebugDiagnostics.frameHandler)
            attachedWindow = window
        } catch (error: Exception) {
            // Not fatal: some devices reject the listener. The frame timeline
            // still works, and the log records that attribution is unavailable.
            DebugDiagnostics.log("metrics", "unavailable: ${error::class.java.simpleName}")
        }
    }

    fun detach() {
        attachedWindow?.removeOnFrameMetricsAvailableListener(listener)
        attachedWindow = null
    }

    private fun flush() {
        if (frames == 0) return
        val n = frames
        val dropTotal = drops
        val worstTotalLocal = worstTotal
        val avg = LongArray(STAGE_COUNT)
        val worstLocal = LongArray(STAGE_COUNT)
        for (stage in STAGES) {
            avg[stage] = sums[stage] / n
            worstLocal[stage] = worst[stage]
        }

        DebugDiagnostics.logFrameStages(n, dropTotal, worstTotalLocal, avg, worstLocal)

        for (stage in STAGES) sums[stage] = 0
        frames = 0
        drops = 0
        worstTotal = 0L
        for (stage in STAGES) worst[stage] = 0
    }

    private companion object {
        /**
         * The stages worth separating. Shared with the formatter so the two can
         * never disagree about which index means what.
         *
         * `GPU_DURATION` is API 31 and is excluded below it by
         * [DebugDiagnostics.STAGE_LABELS] rather than reported as a zero.
         */
        val STAGES = DebugDiagnostics.STAGE_LABELS.map { it.first }.toIntArray()
        const val STAGE_COUNT = 16
    }
}

/**
 * Reports when the main thread was blocked long enough to matter.
 *
 * # Why the frame monitor is not enough
 *
 * A blocked main thread only shows up as jank if a frame was due. Work that
 * runs between frames — the dataset load and path build on resume are exactly
 * this — can block for hundreds of milliseconds and leave the frame cadence
 * looking clean, because no vsync was missed. The device log's resume-shaped
 * spikes are the other side of the same coin: the frame monitor sees them but
 * cannot name the thread.
 *
 * This watchdog posts to the main looper every [DebugDiagnostics.STALL_INTERVAL_MS]
 * and measures how late each post actually ran. A post that arrives 300 ms late
 * means the main thread was blocked for 300 ms, whatever the frame cadence was
 * doing. It names no cause, but it turns "a frame was late" into "the main
 * thread was blocked, and here is when".
 *
 * The measured latency is the gap between the intended and actual run time, so
 * the watchdog's own wake-up cost is included in its own reading. That bias is
 * fixed and small — a Handler wake-up is far below the tens of milliseconds
 * this is looking for — and is preferable to trying to subtract a correction
 * that would drift.
 */
private class DebugStallWatchdog {

    private val handler = Handler(Looper.getMainLooper())

    /** Longest block seen since the last report. */
    private var worstMs = 0L

    /** How many wake-ups were late enough to be worth naming. */
    private var stalls = 0

    private var running = false
    private var expectedNanos = 0L
    private var lastReportNanos = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            if (expectedNanos != 0L) {
                val lateMs = (now - expectedNanos) / 1_000_000L
                if (lateMs > worstMs) worstMs = lateMs
                // 100 ms is well past a frame but short enough that a genuine
                // stall is never missed. Below it, scheduler jitter would
                // otherwise report constantly.
                if (lateMs >= STALL_REPORT_MS) {
                    stalls++
                    DebugDiagnostics.log("stall", "main thread blocked ${lateMs}ms")
                }
            }
            if (lastReportNanos == 0L) lastReportNanos = now
            if (now - lastReportNanos >= REPORT_INTERVAL_NANOS) {
                DebugDiagnostics.log(
                    "stall",
                    diagFormat("window worst=%dms count=%d", worstMs, stalls),
                )
                worstMs = 0
                stalls = 0
                lastReportNanos = now
            }
            expectedNanos = System.nanoTime() + STALL_INTERVAL_NANOS
            handler.postDelayed(this, DebugDiagnostics.STALL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        expectedNanos = 0L
        lastReportNanos = 0L
        handler.postDelayed(tick, DebugDiagnostics.STALL_INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private companion object {
        const val STALL_REPORT_MS = 100L
        const val STALL_INTERVAL_NANOS = DebugDiagnostics.STALL_INTERVAL_MS * 1_000_000L
        const val REPORT_INTERVAL_NANOS = 5_000_000_000L
    }
}
