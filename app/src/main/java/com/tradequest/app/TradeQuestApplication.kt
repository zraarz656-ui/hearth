package com.tradequest.app

import android.app.Application
import com.tradequest.app.di.AppContainer

/**
 * Application entry point.
 *
 * Owns the [AppContainer] composition root. Dependency injection is manual by
 * design: AGENTS.md section 4 requires that unnecessary third-party
 * dependencies are avoided, and Phase 0 has no graph complex enough to justify
 * a DI framework.
 */
class TradeQuestApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
