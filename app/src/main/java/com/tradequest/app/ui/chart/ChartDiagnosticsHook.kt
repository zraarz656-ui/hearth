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
}
