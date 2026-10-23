package com.tradequest.app.ui.chart

import com.tradequest.app.core.chart.renderer.ChartRenderState

/**
 * A single optional observer on the chart's draw path.
 *
 * The chart is composed by `main` source, so a debug build cannot reach it by
 * subclassing or by overriding the manifest. This is the seam: `main` calls the
 * hook, and the debug source set installs a logger into it.
 *
 * Null in release — the debug source set is the only thing that assigns it — so
 * the shipping cost is one reference comparison per frame, and no debug code is
 * compiled into the release APK at all.
 *
 * This exists only to diagnose Phase 8 on a real device. It is temporary: see
 * the "DEBUG-ONLY DEVICE SCAFFOLDING" section of AGENTS.md.
 */
object ChartDiagnosticsHook {

    /**
     * Invoked on the draw thread for every chart frame, with the state that was
     * actually drawn. Must not block: it runs inside `Canvas`'s draw block.
     */
    @Volatile
    var onDraw: ((ChartRenderState) -> Unit)? = null

    /**
     * Invoked when a gesture begins or ends, with a label naming it.
     *
     * # Why the viewport log is not enough
     *
     * The `chart` line records the viewport, so a pan is visible after the fact
     * as a changing `first=`. But it cannot say *which gesture* moved it — a
     * pan, a pinch, a fling and a programmatic jump all look identical — and it
     * cannot say when the finger was down. Correlating a slow frame with "the
     * user was dragging" is exactly what a performance capture needs, and it is
     * the difference between blaming the renderer and blaming a fling.
     *
     * Called with `true` at gesture start and `false` at its end, on the main
     * thread. A gesture that is replaced by a promotion — a pan that becomes a
     * crosshair — reports the old one's `false` before the new one's `true`, so
     * the calls always pair up. Null in release.
     */
    @Volatile
    var onGesture: ((String, Boolean) -> Unit)? = null
}
