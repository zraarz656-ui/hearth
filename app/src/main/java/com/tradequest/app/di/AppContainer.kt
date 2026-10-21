package com.tradequest.app.di

import android.content.Context
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.RoomMarketRepository
import com.tradequest.app.core.database.player.PlayerDatabase
import com.tradequest.app.core.database.player.PlayerRepository
import com.tradequest.app.core.market.MarketRepository

/**
 * Manual composition root for TradeQuest.
 *
 * Dependency injection is hand-written by design: `AGENTS.md` section 4 asks
 * that unnecessary third-party dependencies be avoided, and the object graph is
 * small enough that a framework would add more indirection than it removes.
 * Nothing in this class may be a placeholder implementation.
 *
 * Databases are created lazily, so starting the app does not open either
 * database file until something reads from it. They are never closed: they live
 * for the lifetime of the process, and closing them on an Activity teardown
 * would break the process-scoped instance.
 */
class AppContainer(
    val applicationContext: Context,
) {

    val marketDatabase: MarketDatabase by lazy {
        MarketDatabase.create(applicationContext)
    }

    val playerDatabase: PlayerDatabase by lazy {
        PlayerDatabase.create(applicationContext)
    }

    val marketRepository: MarketRepository by lazy {
        RoomMarketRepository(marketDatabase)
    }

    val playerRepository: PlayerRepository by lazy {
        PlayerRepository(playerDatabase, marketRepository)
    }
}
