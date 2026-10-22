package com.tradequest.app.debug

import com.tradequest.app.TradeQuestApplication
import com.tradequest.app.ui.chart.ChartDiagnosticsHook

/**
 * Debug application: starts diagnostics and seeds a dataset, then behaves
 * exactly like [TradeQuestApplication].
 *
 * # Why this is a subclass rather than a flag in `main`
 *
 * This class and everything it calls live only in `app/src/debug/`, which the
 * Android build never compiles into a release variant. That is a stronger
 * guarantee than a runtime `BuildConfig.DEBUG` branch, which would still ship
 * the code and leave a seam in `main` for someone to flip by accident. The
 * debug manifest points `android:name` here; the release manifest keeps
 * [TradeQuestApplication].
 */
class DebugTradeQuestApplication : TradeQuestApplication() {

    override fun onCreate() {
        super.onCreate()

        // Started first so the log file is open before the seeder reports into
        // it. The seeder runs off the main thread and could otherwise finish its
        // logging before the file existed, losing the one line that says whether
        // the seed worked.
        DebugDiagnostics.start(this)

        // Seeding writes thousands of rows, so it must not block onCreate; the
        // seeder owns its own background scope and reports through the log.
        DebugDatasetSeeder.seedIfEmpty(this, container.marketDatabase)

        // Reports what the chart actually drew, which is what pan/zoom debugging
        // needs. The chart lives in `main`, so this hook is the only way for a
        // debug build to observe it.
        //
        // The state is handed over unformatted: formatting on this thread would
        // mean `String.format` running inside the draw block, which is the cost
        // this hook was fixed to avoid.
        ChartDiagnosticsHook.onDraw = { state ->
            DebugDiagnostics.logChartState(state)
        }
    }
}
