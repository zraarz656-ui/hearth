package com.tradequest.app.di

import android.content.Context

/**
 * Manual composition root for TradeQuest.
 *
 * Phase 0 intentionally holds only the application context. Repositories,
 * databases, clocks and engines are added here in the phase that implements
 * them. Nothing in this class may be a placeholder implementation.
 */
class AppContainer(
    val applicationContext: Context,
)
