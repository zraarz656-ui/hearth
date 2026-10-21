package com.tradequest.app.di

import android.content.Context
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.RoomMarketRepository
import com.tradequest.app.core.database.player.PlayerDatabase
import com.tradequest.app.core.database.player.PlayerRepository
import com.tradequest.app.core.database.player.RoomClockStore
import com.tradequest.app.core.database.player.RoomReplayStore
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.replay.ReplayCoordinator
import com.tradequest.app.core.replay.ReplayNotifier
import com.tradequest.app.core.replay.ReplayProcessor
import com.tradequest.app.core.replay.ReplayStore
import com.tradequest.app.core.replay.ResumeResult
import com.tradequest.app.core.risk.PropFirmRiskPolicy
import com.tradequest.app.core.simulation.clock.ClockStore
import com.tradequest.app.core.simulation.clock.SimulationClock
import com.tradequest.app.core.simulation.clock.SystemMonotonicClock
import com.tradequest.app.core.simulation.clock.SystemWallClock
import com.tradequest.app.core.trading.TradingContext
import com.tradequest.app.platform.AndroidReplayNotifier

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

    /** Durable storage for a season's resumable replay state (Steps 7.3-7.4). */
    val replayStore: ReplayStore by lazy {
        RoomReplayStore(playerDatabase)
    }

    /** Delivers trade notifications (Step 7.9). */
    val replayNotifier: ReplayNotifier by lazy {
        AndroidReplayNotifier(applicationContext)
    }

    /**
     * Catches the active season up to wherever the simulation clock now stands
     * (Steps 7.7-7.8).
     *
     * Returns null when there is no active season, when the clock is not attached
     * to one, or when the season's dataset has no candles. All three mean
     * "nothing to replay", and both callers — the app's resume path and the
     * periodic worker — treat them the same way.
     */
    suspend fun catchUpActiveSeason(): ResumeResult? {
        val season = playerRepository.activeSeason() ?: return null
        val processor = replayProcessorFor(
            seasonId = season.seasonId,
            datasetId = season.datasetId,
            startingBalanceMicros = season.startingBalanceMicros,
        ) ?: return null
        return ReplayCoordinator(simulationClock, processor).resume(season.seasonId)
    }

    /**
     * A processor for one season, or null when the dataset cannot be replayed.
     *
     * # Why the context is built here rather than inside the processor
     *
     * The news instants and the risk policy are *inputs* to a replay, and the
     * processor must not know how to look them up — that would give `core.replay`
     * a dependency on the dataset's stored shape. Building the context here, at
     * the composition root, is the one place that knows both the season and its
     * dataset, and it is where the rule "one season replays one dataset under one
     * rule set" is enforced.
     */
    suspend fun replayProcessorFor(
        seasonId: String,
        datasetId: String,
        startingBalanceMicros: Long,
    ): ReplayProcessor? {
        val bounds = marketRepository.timeBounds(datasetId) ?: return null
        val news = marketRepository.newsInRange(
            datasetId = datasetId,
            fromUtcMs = bounds.first,
            toUtcMs = bounds.second + ONE_MINUTE_MS,
        )
        val context = TradingContext(
            seasonId = seasonId,
            newsReleaseTimesUtcMs = news.map { it.publishedAtUtcMs },
            riskPolicy = PropFirmRiskPolicy(),
        )
        return ReplayProcessor(
            marketRepository = marketRepository,
            store = replayStore,
            datasetId = datasetId,
            tradingContext = context,
            startingBalanceMicros = startingBalanceMicros,
            notifier = replayNotifier,
        )
    }

    private companion object {
        /** The exclusive end of the dataset's last candle. */
        const val ONE_MINUTE_MS: Long = 60_000L
    }
}
