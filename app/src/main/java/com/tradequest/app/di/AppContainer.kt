package com.tradequest.app.di

import android.content.Context
import com.tradequest.app.core.challenge.ChallengeDashboardState
import com.tradequest.app.core.challenge.ChallengeEvaluation
import com.tradequest.app.core.challenge.ChallengeHistoryEntry
import com.tradequest.app.core.challenge.ChallengeHistoryRepository
import com.tradequest.app.core.challenge.ChallengeLaunchResult
import com.tradequest.app.core.challenge.ChallengeLauncher
import com.tradequest.app.core.challenge.ChallengeProgressStore
import com.tradequest.app.core.challenge.ChallengeService
import com.tradequest.app.core.challenge.SeasonMode
import com.tradequest.app.core.database.market.MarketDatabase
import com.tradequest.app.core.database.market.RoomMarketRepository
import com.tradequest.app.core.database.player.PlayerDatabase
import com.tradequest.app.core.database.player.PlayerRepository
import com.tradequest.app.core.database.player.RoomChallengeHistoryRepository
import com.tradequest.app.core.database.player.RoomChallengeProgressStore
import com.tradequest.app.core.database.player.RoomClockStore
import com.tradequest.app.core.database.player.RoomReplayStore
import com.tradequest.app.core.database.player.SeasonStartResult
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.core.news.NewsImpact
import com.tradequest.app.core.news.NewsRelease
import com.tradequest.app.core.news.NewsReleaseProjection
import com.tradequest.app.core.replay.ReplayCoordinator
import com.tradequest.app.core.replay.ReplayNotifier
import com.tradequest.app.core.replay.ReplayProcessor
import com.tradequest.app.core.replay.ReplayStore
import com.tradequest.app.core.replay.ResumeResult
import com.tradequest.app.core.risk.PropFirmRiskPolicy
import com.tradequest.app.core.simulation.clock.ClockMode
import com.tradequest.app.core.simulation.clock.ClockStore
import com.tradequest.app.core.simulation.clock.DatasetBounds
import com.tradequest.app.core.simulation.clock.DatasetBoundsResolver
import com.tradequest.app.core.simulation.clock.SimulationClock
import com.tradequest.app.core.simulation.clock.SystemMonotonicClock
import com.tradequest.app.core.simulation.clock.SystemWallClock
import com.tradequest.app.core.simulation.warp.TimeWarpResolver
import com.tradequest.app.core.simulation.warp.WarpPermission
import com.tradequest.app.core.simulation.warp.WarpTarget
import com.tradequest.app.core.trading.AccountState
import com.tradequest.app.core.trading.TradingContext
import com.tradequest.app.core.trading.TradingState
import com.tradequest.app.feature.news.NewsScheduleSource
import com.tradequest.app.feature.news.WarpKind
import com.tradequest.app.feature.trading.LiveTradingSession
import com.tradequest.app.feature.trading.SeasonBinding
import com.tradequest.app.feature.challenge.ChallengeOverviewSource
import com.tradequest.app.feature.challenge.DatasetBest
import com.tradequest.app.feature.trading.TradingSessionSource
import com.tradequest.app.platform.AndroidReplayNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

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
    /**
     * Overrides the database file names, for a container that must not touch the
     * player's real data.
     *
     * Defaults are the app's own names, so production is unaffected. The debug
     * self-check passes scratch names and runs the *real* object graph — the same
     * launcher, session source and challenge service the app uses — against
     * throwaway files, which is the only way to exercise the real wiring on a
     * device without risking the season being played.
     */
    marketDatabaseName: String = MarketDatabase.NAME,
    playerDatabaseName: String = PlayerDatabase.NAME,
) {

    val marketDatabase: MarketDatabase by lazy {
        MarketDatabase.create(applicationContext, marketDatabaseName)
    }

    val playerDatabase: PlayerDatabase by lazy {
        PlayerDatabase.create(applicationContext, playerDatabaseName)
    }

    val marketRepository: MarketRepository by lazy {
        RoomMarketRepository(marketDatabase)
    }

    val playerRepository: PlayerRepository by lazy {
        PlayerRepository(playerDatabase, marketRepository)
    }

    /** Challenge history and personal bests (Steps 11.9, 11.11). */
    val challengeHistoryRepository: ChallengeHistoryRepository by lazy {
        RoomChallengeHistoryRepository(playerDatabase)
    }

    /** Persists a season's challenge progress and its terminal record (Steps 11.5, 11.8). */
    val challengeProgressStore: ChallengeProgressStore by lazy {
        RoomChallengeProgressStore(playerDatabase)
    }

    /** Runs the challenge lifecycle for a season (Steps 11.5-11.9). */
    val challengeService: ChallengeService by lazy {
        ChallengeService(challengeProgressStore)
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
     * The chart's view of the running season (Step 8.10).
     *
     * # Why this is defined here rather than in the Trading feature
     *
     * It is the only place that knows both halves of the answer: the season, which
     * lives in the player database, and the clock, which is process-scoped. The
     * Trading feature sees only the [TradingSessionSource] interface, so it cannot
     * reach a database or a season directly.
     *
     * # Why the instant is not read from the database
     *
     * `SimulationClock.currentSimUtcMs()` derives the instant from an in-memory
     * anchor and the wall clock, so the per-frame read the chart performs costs no
     * IO. Only [TradingSessionSource.currentSeason], which runs once, touches the
     * database.
     *
     * # Why the ticker lives here
     *
     * The clock has no ticker, so [simInstants] provides one. It polls at a
     * fixed cadence and emits only when the instant has actually moved, which
     * keeps a paused clock from producing a stream of identical frames. Putting
     * the loop here rather than in the ViewModel is what keeps the ViewModel
     * testable — see the port's documentation.
     */
    val tradingSessionSource: TradingSessionSource by lazy {
        object : TradingSessionSource {
            override suspend fun currentSeason(): SeasonBinding? {
                val season = playerRepository.activeSeason() ?: return null
                return SeasonBinding(
                    seasonId = season.seasonId,
                    datasetId = season.datasetId,
                )
            }

            override fun currentSimUtcMs(): Long? {
                // A clock attached to a different season, or to none, has no
                // instant for this chart.
                if (!simulationClock.isAttached) return null
                return simulationClock.currentSimUtcMs()
            }

            override suspend fun startingBalanceMicros(): Long =
                playerRepository.activeSeason()?.startingBalanceMicros
                    ?: playerRepository.startingBalanceMicros

            override suspend fun createLiveSession(): LiveTradingSession? {
                val season = playerRepository.activeSeason() ?: return null
                val bounds = marketRepository.timeBounds(season.datasetId) ?: return null
                val news = marketRepository.newsInRange(
                    datasetId = season.datasetId,
                    fromUtcMs = bounds.first,
                    toUtcMs = bounds.second + ONE_MINUTE_MS,
                )
                // The same context shape the replay path builds, so a live trade and
                // a replayed one are priced by the same spread and rule set.
                val context = TradingContext(
                    seasonId = season.seasonId,
                    newsReleaseTimesUtcMs = news.map { it.publishedAtUtcMs },
                    riskPolicy = PropFirmRiskPolicy(),
                )
                return LiveTradingSession(
                    seasonId = season.seasonId,
                    marketRepository = marketRepository,
                    datasetId = season.datasetId,
                    tradingContext = context,
                    startingBalanceMicros = season.startingBalanceMicros,
                    store = replayStore,
                )
            }

            override fun simInstants(): Flow<Long> = flow {
                var last: Long? = null
                while (true) {
                    val now = currentSimUtcMs()
                    if (now == null) {
                        // The season ended, so there is nothing left to follow.
                        if (last != null) return@flow
                    } else if (now != last) {
                        last = now
                        emit(now)
                    }
                    delay(CHART_TICK_INTERVAL_MS)
                }
            }

            override suspend fun syncChallenge(
                tradingState: TradingState,
                account: AccountState,
                equityMicros: Long,
                atSimUtcMs: Long,
            ): ChallengeEvaluation? {
                val seasonId = playerRepository.activeSeason()?.seasonId ?: return null
                return challengeService.sync(
                    seasonId = seasonId,
                    state = tradingState,
                    account = account,
                    equityMicros = equityMicros,
                    atSimUtcMs = atSimUtcMs,
                    atRealUtcMs = SystemWallClock.nowUtcMs(),
                )
            }
        }
    }

    /**
     * The News calendar's view of the schedule, the clock and the warp rules
     * (Steps 12.2-12.7).
     *
     * # Why the projection happens here
     *
     * [NewsScheduleSource.releasesAt] returns already-projected [NewsRelease]
     * values. `NewsReleaseProjection.project` is the single place that decides
     * what an event reveals at a simulated instant (Step 12.4), and it lives on
     * the domain side of the boundary, so a screen that never receives an actual
     * cannot leak one. The screen is handed the *projected* event, not the raw
     * `NewsItem` plus an instruction to hide a field.
     *
     * # Why the warp processes the interval here
     *
     * Step 12.7 requires every intervening market and trading event to be handled
     * during a warp, which is exactly what the app's catch-up path already does:
     * seek the clock to the destination and let the replay processor walk from
     * its committed cursor to the new instant. Reusing [catchUpActiveSeason]
     * rather than advancing the clock alone is what makes a warp and a resume the
     * same operation, so a position carried across the jump is priced and settled
     * by the same engine.
     */
    val newsScheduleSource: NewsScheduleSource by lazy {
        object : NewsScheduleSource {
            override suspend fun releasesAt(
                datasetId: String,
                fromUtcMs: Long,
                toUtcMs: Long,
                simUtcMs: Long,
            ): List<NewsRelease> {
                val items = marketRepository.newsInRange(
                    datasetId = datasetId,
                    fromUtcMs = fromUtcMs,
                    toUtcMs = toUtcMs,
                )
                return NewsReleaseProjection.projectAll(items, simUtcMs)
            }

            override suspend fun activeDatasetId(): String? =
                playerRepository.activeSeason()?.datasetId

            override fun currentSimUtcMs(): Long? {
                if (!simulationClock.isAttached) return null
                return simulationClock.currentSimUtcMs()
            }

            override suspend fun isBlind(): Boolean =
                playerRepository.activeSeason()?.blindMode == true

            override suspend fun datasetBounds(datasetId: String): Pair<Long, Long>? =
                marketRepository.timeBounds(datasetId)

            override suspend fun warpPermission(): WarpPermission {
                val season = playerRepository.activeSeason()
                    ?: return WarpPermission.Allowed
                // The counts come from a freshly-opened session rather than a
                // snapshot, so a position opened since the screen loaded is seen.
                val session = tradingSessionSource.createLiveSession()
                session?.open()
                return WarpPermission.evaluate(
                    mode = RoomClockStore.clockModeOf(season.mode),
                    openPositionCount = session?.openPositions()?.size ?: 0,
                    pendingOrderCount = session?.pendingOrders()?.size ?: 0,
                )
            }

            override suspend fun resolveWarp(kind: WarpKind): WarpTarget? {
                val season = playerRepository.activeSeason() ?: return null
                val bounds = DatasetBoundsResolver.resolve(marketRepository, season.datasetId)
                    ?: return null
                val now = currentSimUtcMs() ?: bounds.startSimUtcMs
                // The resolver's probe is a plain lambda, not a suspend one, so the
                // high-impact schedule is read once here and searched in memory.
                // Re-querying per probe would also be the wrong shape: the resolver
                // may probe many times and the schedule does not change mid-warp.
                val highImpactTimes = highImpactReleaseTimes(season.datasetId, bounds)
                val resolver = TimeWarpResolver(
                    bounds = bounds,
                    nextHighImpactAtOrAfter = { after ->
                        highImpactTimes.firstOrNull { it >= after }
                    },
                )
                return when (kind) {
                    WarpKind.SKIP_WEEKEND -> resolver.nextMarketOpen(now)
                    WarpKind.NEXT_LONDON -> resolver.nextLondonOpen(now)
                    WarpKind.NEXT_NEW_YORK -> resolver.nextNewYorkOpen(now)
                    WarpKind.NEXT_HIGH_IMPACT_NEWS -> resolver.nextHighImpactNews(now)
                }
            }

            override suspend fun performWarp(target: WarpTarget): Long? {
                val destination = target.atUtcMs ?: return null
                if (!simulationClock.isAttached) return null
                simulationClock.seekTo(destination)
                // Process everything between the old instant and the destination,
                // so positions, stops and margin events across the jump are real.
                catchUpActiveSeason()
                return simulationClock.currentSimUtcMs()
            }

            override fun simInstants(): Flow<Long> = flow {
                var last: Long? = null
                while (true) {
                    val now = currentSimUtcMs()
                    if (now == null) {
                        if (last != null) return@flow
                    } else if (now != last) {
                        last = now
                        emit(now)
                    }
                    delay(CHART_TICK_INTERVAL_MS)
                }
            }
        }
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
        // The worker runs in a process that may never have opened the Trading
        // screen, so the clock may not be bound yet. Binding here is what makes the
        // background catch-up work at all: without it `ReplayCoordinator.resume`
        // finds no reading and reports nothing owed.
        val season = playerRepository.activeSeason() ?: return null
        val bounds = DatasetBoundsResolver.resolve(marketRepository, season.datasetId)
            ?: return null
        attachClockTo(season.seasonId, bounds)
        val processor = replayProcessorFor(
            seasonId = season.seasonId,
            datasetId = season.datasetId,
            startingBalanceMicros = season.startingBalanceMicros,
        ) ?: return null
        val result = ReplayCoordinator(simulationClock, processor).resume(season.seasonId)
            ?: return null

        // The catch-up is the other place the engine advances — the app's resume
        // path and the periodic worker both come through here — so the challenge is
        // evaluated here too. Without this a season that breached a limit while the
        // app was closed would not be recorded as failed until the trader opened
        // the trading screen, which is exactly the state the plan says must not
        // survive: the season's outcome would depend on whether the screen was
        // visited.
        syncChallengeAfterCatchUp(season.seasonId, result)
        return result
    }

    /**
     * Evaluates the challenge after a catch-up, using the state the replay left.
     *
     * # Why the state is re-read rather than taken from the outcome
     *
     * The replay outcome carries the account, but the challenge needs the engine's
     * whole state — the closed trades that define the day count, the strike count
     * and the failure flag. The processor commits each batch, so the store holds
     * exactly that state; reading it back is the same source of truth the live path
     * uses, and avoids widening [ReplayOutcome] with fields only the challenge
     * reads.
     *
     * # Why a failure here is swallowed
     *
     * The replay itself has already succeeded and committed, and it is the worker's
     * job to report on that. A challenge write failing afterwards must not turn a
     * successful catch-up into a retry, which would replay nothing and simply fail
     * again. The next resume re-evaluates from the same committed state, so a missed
     * write is recovered rather than lost.
     */
    private suspend fun syncChallengeAfterCatchUp(seasonId: String, result: ResumeResult) {
        val state = try {
            replayStore.load(seasonId)
        } catch (error: Exception) {
            null
        } ?: return

        try {
            challengeService.sync(
                seasonId = seasonId,
                state = state,
                account = result.account,
                equityMicros = result.account.equityMicros,
                atSimUtcMs = state.processedToSimUtcMs,
                atRealUtcMs = SystemWallClock.nowUtcMs(),
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // See above: the catch-up stands, and the next resume re-evaluates.
        }
    }

    /**
     * The Challenge screen's view of the running season and past attempts
     * (Steps 11.8-11.11).
     *
     * # Why the dashboard is re-derived rather than read from a column
     *
     * The season row stores the balance, equity and peak, but the day count, the
     * strikes and the score are derived from the closed-trade ledger, which lives in
     * the replay state. This observes the season row — the thing that changes when
     * the trading path writes progress — and re-derives the dashboard from the same
     * reader and coordinator the live path uses, so the two cannot disagree.
     *
     * # Why the replay state is read on each emission
     *
     * It is one row keyed by the season, read on a change of the season rather than
     * per frame, so the cost is bounded by how often the season actually moves.
     */
    val challengeOverviewSource: ChallengeOverviewSource by lazy {
        object : ChallengeOverviewSource {
            override fun observeDashboard(): Flow<ChallengeDashboardState?> =
                playerRepository.observeActiveSeason()
                    .map { season ->
                        if (season == null) return@map null
                        challengeService.dashboard(
                            seasonId = season.seasonId,
                            state = replayStore.load(season.seasonId),
                        )
                    }

            override fun observeHistory(): Flow<List<ChallengeHistoryEntry>> =
                challengeHistoryRepository.observeHistory()

            override fun observePersonalBests(): Flow<List<DatasetBest>> =
                challengeHistoryRepository.observeHistory().map(::bestPerDataset)
        }
    }

    /**
     * Starts a challenge and makes it live (Steps 11.1-11.3).
     *
     * # Why the season and the clock are bound together here
     *
     * A season row with no attached clock is a season that can never advance: the
     * chart has no instant to follow and the replay owes nothing, so no rule is ever
     * evaluated. Creating the season and attaching the clock is therefore one
     * operation, and this is the only place that can perform it — it knows both the
     * player database and the process-scoped clock.
     *
     * # Why the clock is attached after the season exists
     *
     * The clock persists its anchor keyed by season id, so it needs the id the
     * season was created with. Attaching first would need an id chosen before the
     * transaction that validates the dataset, which is the wrong order: a refusal
     * (a missing dataset, a declined confirmation) must leave no clock anchor behind.
     *
     * # Why the dataset's bounds are read before the season is created
     *
     * A season on a dataset with no candles could never advance, and it would still
     * hold the "one ACTIVE season" slot — so the trader would be stuck with a
     * challenge that can neither run nor be replaced without confirmation. The
     * bounds are therefore resolved first, and an empty dataset is refused before
     * anything is written. The bounds are also what the season records as its start
     * instant, so the read is needed regardless.
     */
    val challengeLauncher: ChallengeLauncher by lazy {
        object : ChallengeLauncher {
            override suspend fun startChallenge(
                datasetId: String,
                confirmAbandon: Boolean,
                blindMode: Boolean,
            ): ChallengeLaunchResult {
                val bounds = DatasetBoundsResolver.resolve(marketRepository, datasetId)
                    ?: return if (marketRepository.getDataset(datasetId) == null) {
                        ChallengeLaunchResult.DatasetNotFound(datasetId)
                    } else {
                        ChallengeLaunchResult.DatasetNotReplayable(datasetId)
                    }

                val seasonId = newSeasonId(datasetId)
                val result = playerRepository.startSeason(
                    seasonId = seasonId,
                    datasetId = datasetId,
                    mode = SeasonMode.CHALLENGE,
                    startedAtSimUtcMs = bounds.startSimUtcMs,
                    createdAtRealUtcMs = SystemWallClock.nowUtcMs(),
                    confirmAbandon = confirmAbandon,
                    blindMode = blindMode,
                )

                return when (result) {
                    is SeasonStartResult.Started -> {
                        attachClockTo(seasonId, bounds)
                        ChallengeLaunchResult.Started(seasonId, result.datasetDisplayName)
                    }

                    is SeasonStartResult.ConfirmationRequired ->
                        ChallengeLaunchResult.ConfirmationRequired(result.existingSeasonId)

                    is SeasonStartResult.DatasetNotFound ->
                        ChallengeLaunchResult.DatasetNotFound(datasetId)
                }
            }
        }
    }

    /**
     * The scheduled instants of every high-impact release in [bounds], ascending.
     *
     * Read once per warp resolution rather than per probe: `TimeWarpResolver`'s
     * probe is a plain lambda and may be called many times, while the schedule is
     * fixed for the dataset. Impact is matched through [NewsImpact.fromStored] so
     * the same tolerant parsing the calendar uses also decides what Time Warp
     * treats as high-impact; a second spelling table here could disagree with it.
     */
    private suspend fun highImpactReleaseTimes(
        datasetId: String,
        bounds: DatasetBounds,
    ): List<Long> =
        marketRepository.newsInRange(
            datasetId = datasetId,
            fromUtcMs = bounds.startSimUtcMs,
            toUtcMs = bounds.endSimUtcMs + ONE_MINUTE_MS,
        )
            .filter { NewsImpact.fromStored(it.impact) == NewsImpact.HIGH }
            .map { it.publishedAtUtcMs }
            .sorted()

    /**
     * Binds the clock to a season, if it is not already bound to that season.
     *
     * Idempotent by design: `SimulationClock.attach` restores a stored anchor rather
     * than resetting it, so calling this when the clock is already attached to the
     * same season is a no-op in effect and cannot rewind a running challenge. The
     * season check is still made first, so a switch from one season to another
     * re-attaches rather than leaving the clock on the old dataset.
     */
    private suspend fun attachClockTo(seasonId: String, bounds: DatasetBounds) {
        if (simulationClock.currentSeasonId == seasonId) return
        simulationClock.attach(
            seasonId = seasonId,
            bounds = bounds,
            startSimUtcMs = bounds.startSimUtcMs,
            mode = ClockMode.CHALLENGE,
        )
    }

    /**
     * Makes the stored active season live, if there is one.
     *
     * Called on app start and before a catch-up. A season outlives the process, but
     * the clock does not — it is process-scoped and rebuilt from its stored anchor —
     * so without this a season that survived a restart would be inert until a new
     * one was started, and the periodic worker would find nothing to catch up.
     *
     * Returns the season id now bound, or null when there is no season or its
     * dataset can no longer be replayed.
     */
    suspend fun ensureClockAttached(): String? {
        val season = playerRepository.activeSeason() ?: return null
        val bounds = DatasetBoundsResolver.resolve(marketRepository, season.datasetId)
            ?: return null
        attachClockTo(season.seasonId, bounds)
        return season.seasonId
    }

    /**
     * The app-start hook: binds the clock to the active season, if any.
     *
     * Deliberately does not catch up. The catch-up is a separate, potentially long
     * operation that the Trading screen drives with progress (Step 7.7) and the
     * worker drives in the background (Step 7.8); folding it into app start would
     * put an unbounded replay on the main-thread-adjacent path.
     */
    suspend fun onAppStart() {
        try {
            ensureClockAttached()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // An unreadable season must not stop the app from starting. The Trading
            // screen's own load will surface the failure where it is actionable.
        }
    }

    /**
     * A season id unique to this launch.
     *
     * Derived from the dataset and the real instant rather than random, so a season
     * id is meaningful in a log or a database dump and two launches in the same
     * millisecond on the same dataset cannot collide. The `Season` primary key is
     * what actually enforces uniqueness; this only has to be unlikely to repeat.
     */
    private fun newSeasonId(datasetId: String): String =
        "season-$datasetId-${SystemWallClock.nowUtcMs()}"

    /**
     * Groups a history into one best-result row per dataset (Step 11.11).
     *
     * The best overall is the highest-scoring attempt of any ordinal; the
     * first-attempt best is the highest-scoring attempt that *was* the dataset's
     * first. Both are reported, because a trader comparing against their own first
     * try wants the second and a personal-best headline wants the first — the same
     * distinction `ChallengeHistoryRepository.personalBest` draws, applied here in
     * one pass over the history the screen already has.
     */
    private fun bestPerDataset(history: List<ChallengeHistoryEntry>): List<DatasetBest> =
        history.groupBy { it.datasetId }
            .map { (datasetId, attempts) ->
                DatasetBest(
                    datasetId = datasetId,
                    datasetDisplayName = attempts.first().datasetDisplayName,
                    bestOverall = attempts.maxWithOrNull(BEST_ATTEMPT_ORDER)!!,
                    bestFirstAttempt = attempts.filter { it.isFirstAttempt }
                        .maxWithOrNull(BEST_ATTEMPT_ORDER),
                    attemptCount = attempts.size,
                )
            }
            .sortedWith(compareByDescending<DatasetBest> { it.bestOverall.score.total }
                .thenBy { it.datasetDisplayName })

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
        /**
         * Best-first ordering for a dataset's attempts, matching the history
         * repository's SQL: score, then profit, then record id so the order is total.
         */
        val BEST_ATTEMPT_ORDER: Comparator<ChallengeHistoryEntry> =
            compareByDescending<ChallengeHistoryEntry> { it.score.total }
                .thenByDescending { it.profitMicros }
                .thenBy { it.recordId }

        /** The exclusive end of the dataset's last candle. */
        const val ONE_MINUTE_MS: Long = 60_000L

        /**
         * How often the chart looks for a new simulated instant.
         *
         * Four times a second. The forming candle's high and low move at the
         * replay's own granularity, so a faster poll would only redraw identical
         * frames — and the flow suppresses those anyway — while a slower one would
         * visibly lag the clock.
         */
        const val CHART_TICK_INTERVAL_MS: Long = 250L
    }
}
