package com.tradequest.app.di

import android.content.Context
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.RoomMarketRepository
import com.tradequest.app.core.database.player.PlayerDatabase
import com.tradequest.app.core.database.player.PlayerRepository
import com.tradequest.app.core.database.player.RoomClockStore
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.simulation.clock.ClockStore
import com.tradequest.app.core.simulation.clock.SimulationClock
import com.tradequest.app.core.simulation.clock.SystemMonotonicClock
import com.tradequest.app.core.simulation.clock.SystemWallClock

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

    /**
     * Persistence for the simulation clock (Step 3.5).
     *
     * The clock owns no background thread and no ticker, so a single
     * process-scoped instance is correct: its state is the persisted anchor,
     * and every read derives historical time from the real clock.
     */
    val clockStore: ClockStore by lazy {
        RoomClockStore(playerDatabase)
    }

    /**
     * The authoritative virtual time system (Steps 3.2 to 3.8).
     *
     * Not attached to a season until `attach` is called, so constructing the
     * container does not open a database or commit to a season.
     */
    val simulationClock: SimulationClock by lazy {
        SimulationClock(
            store = clockStore,
            wallClock = SystemWallClock,
            monotonicClock = SystemMonotonicClock,
        )
    }
}
