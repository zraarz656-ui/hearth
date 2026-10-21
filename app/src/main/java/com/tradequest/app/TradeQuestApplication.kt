package com.tradequest.app

import android.app.Application
import com.tradequest.app.di.AppContainer
import com.tradequest.app.platform.ReplayCatchUpWorker

/**
 * Application entry point.
 *
 * Owns the [AppContainer] composition root. Dependency injection is manual by
 * design: AGENTS.md section 4 requires that unnecessary third-party
 * dependencies are avoided, and Phase 0 has no graph complex enough to justify
 * a DI framework.
 *
 * `open` so the debug variant can subclass it to add dataset seeding and frame
 * diagnostics (`app/src/debug/java/com/tradequest/app/debug/`). That code is
 * compiled only into debug builds; the release manifest still points here.
 */
open class TradeQuestApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Step 7.8: best-effort periodic catch-up, so a season keeps advancing
        // while the app is closed. Enqueued unconditionally: the worker is a
        // no-op when no season is active, and gating it here would mean a season
        // started later never gets scheduled.
        ReplayCatchUpWorker.enqueue(this)
    }
}
