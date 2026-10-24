package com.tradequest.app.debug

import android.content.Context
import com.tradequest.app.core.challenge.ChallengeDashboardState
import com.tradequest.app.core.challenge.ChallengeLaunchResult
import com.tradequest.app.core.challenge.ChallengeOutcome
import com.tradequest.app.core.challenge.ChallengeRules
import com.tradequest.app.core.challenge.SeasonStatus
import com.tradequest.app.core.database.market.Candle1mEntity
import com.tradequest.app.core.database.market.MarketDatasetEntity
import com.tradequest.app.core.database.market.NewsEventEntity
import com.tradequest.app.core.news.NewsRelease
import com.tradequest.app.core.risk.RiskLimits
import com.tradequest.app.core.trading.AccountState
import com.tradequest.app.core.trading.CommandResult
import com.tradequest.app.core.simulation.warp.WarpPermission
import com.tradequest.app.feature.news.WarpKind
import com.tradequest.app.core.trading.OrderSide
import com.tradequest.app.core.trading.TradingState
import com.tradequest.app.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs the Phase 10, 11 and 12 flows through the real object graph on the
 * device, and reports what happened.
 *
 * # Why this exists
 *
 * The unit suite proves each class in isolation, but three claims in these two
 * phases are about the *whole app on a real device* and no Robolectric test can
 * settle them:
 *
 *  1. **The wiring is connected.** Starting a challenge must create the season
 *     *and* bind the clock; the Trading screen must get a tradable session from
 *     the same container. A missing call site is invisible to a test of either
 *     half, and this is exactly the bug Phase 11 shipped and then fixed.
 *  2. **Real time advances a challenge.** Every unit test injects a clock, so
 *     none of them can say whether a CHALLENGE at 1x actually moves when the
 *     wall clock does. That is the "season advances while the app is closed"
 *     claim, and it needs a real clock and a real wait.
 *  3. **Room behaves on a real file.** In-memory and Robolectric databases are
 *     not the same as a device's SQLite: schema validation, the migration and
 *     the `WITHOUT ROWID` callback all run for real here.
 *
 * # Each scenario runs on its own season
 *
 * A season's replay state accumulates — open positions, closed trades — so
 * scenarios that shared one season would interfere, and a failure in an early
 * step would cascade into later ones and obscure where the fault really is. Each
 * scenario therefore starts its own season on its own dataset, and starting a
 * season abandons the previous one, which also exercises Step 11.3 repeatedly.
 *
 * # It runs on scratch databases
 *
 * The container is built with `selfcheck-*` file names, so the check cannot read,
 * write or abandon the season the tester is playing. The files are deleted before
 * and after the run, so it is repeatable and leaves nothing behind.
 *
 * # It never throws
 *
 * Every step is wrapped: a failure is recorded as a `FAIL` line and the run
 * continues, because the useful output is the *set* of results, not the first
 * one. A crash here would also defeat the purpose — the report is the deliverable.
 */
internal object DebugSelfCheck {

    private const val MARKET_DB = "selfcheck-market.db"
    private const val PLAYER_DB = "selfcheck-player.db"

    private const val DS_RISING = "selfcheck-ds-rising"
    private const val DS_FLAT = "selfcheck-ds-flat"
    private const val DS_TRADE = "selfcheck-ds-trade"
    private const val DS_CHALLENGE = "selfcheck-ds-challenge"
    private const val DS_NEWS = "selfcheck-ds-news"

    /** The headline the news checks look for, so a rename cannot silently pass. */
    private const val NEWS_HEADLINE = "self-check non-farm payrolls"
    private const val DS_EMPTY = "selfcheck-ds-empty"

    private const val T0 = 1_705_276_800_000L
    private const val MINUTE = 60_000L
    private const val START_BALANCE_MICROS = 10_000L * 1_000_000L

    /**
     * Runs every check and returns the report as text.
     *
     * The caller appends this to the diagnostics log, so the tester can copy it.
     */
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        fun emit(line: String) {
            lines += line
            DebugDiagnostics.log("selfcheck", line)
        }

        emit(
            "=== TradeQuest self-check: Phase 10 (trading), Phase 11 (challenge) " +
                "and Phase 12 (news, blind mode, time warp) ===",
        )

        // A clean slate: a previous run's files would make the library non-empty
        // and change the results.
        deleteScratchDatabases(context)

        val container = AppContainer(context, MARKET_DB, PLAYER_DB)
        try {
            var passed = 0
            var failed = 0
            suspend fun check(name: String, block: suspend () -> String) {
                try {
                    val detail = block()
                    passed++
                    emit("PASS  $name${if (detail.isBlank()) "" else " :: $detail"}")
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    failed++
                    emit("FAIL  $name :: ${error::class.java.simpleName}: ${error.message}")
                }
            }

            runWiringChecks(container, ::check)
            runTradingChecks(container, ::check)
            runChallengeChecks(container, ::check)
            runNewsChecks(container, ::check)
            runRealTimeCheck(container, ::check)
            runPersistenceCheck(context, ::check)

            emit("=== self-check complete: $passed passed, $failed failed ===")
            if (failed > 0) {
                emit("NOTE  a FAIL above is a real defect; copy this whole block when reporting")
            }
        } finally {
            runCatching { container.playerDatabase.close() }
            runCatching { container.marketDatabase.close() }
            deleteScratchDatabases(context)
        }

        lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- Phase 11 wiring

    private suspend fun runWiringChecks(
        container: AppContainer,
        check: suspend (String, suspend () -> String) -> Unit,
    ) {
        var seasonId: String? = null

        check("11.1 start a challenge creates the season and binds the clock") {
            seedDataset(container, DS_RISING, "self-check rising", step = 1_000)
            val result = container.challengeLauncher.startChallenge(DS_RISING)
            require(result is ChallengeLaunchResult.Started) { "got $result" }
            seasonId = result.seasonId

            val season = container.playerRepository.activeSeason()
                ?: error("no active season after starting")
            require(season.seasonId == result.seasonId) { "active season is ${season.seasonId}" }
            require(season.balanceMicros == START_BALANCE_MICROS) { "balance ${season.balanceMicros}" }
            require(season.status == SeasonStatus.ACTIVE.name) { "status ${season.status}" }

            // The half that was missing before the fix: a season with no clock
            // can never advance, and the chart has no instant to follow.
            require(container.simulationClock.isAttached) { "clock not attached" }
            require(container.simulationClock.currentSeasonId == result.seasonId) {
                "clock bound to ${container.simulationClock.currentSeasonId}"
            }

            "season=${result.seasonId} clock=attached"
        }

        check("11.1 a challenge is refused on a dataset that does not exist") {
            val result = container.challengeLauncher.startChallenge("selfcheck-missing")
            require(result is ChallengeLaunchResult.DatasetNotFound) { "got $result" }
            "refused as DatasetNotFound"
        }

        check("11.1 a challenge is refused on a dataset with no candles") {
            seedEmptyDataset(container)
            val result = container.challengeLauncher.startChallenge(DS_EMPTY)
            require(result is ChallengeLaunchResult.DatasetNotReplayable) { "got $result" }
            // The refusal must not have consumed the single-active-season slot.
            require(container.playerRepository.activeSeason()?.seasonId == seasonId) {
                "the refusal changed the active season"
            }
            "refused as DatasetNotReplayable, active season untouched"
        }

        check("11.3 starting over a running challenge asks before abandoning") {
            seedDataset(container, DS_FLAT, "self-check flat", step = 0)
            val result = container.challengeLauncher.startChallenge(DS_FLAT)
            require(result is ChallengeLaunchResult.ConfirmationRequired) { "got $result" }
            require(result.existingSeasonId == seasonId) { "reported ${result.existingSeasonId}" }
            require(container.playerRepository.activeSeason()?.seasonId == seasonId) {
                "the running season was changed without confirmation"
            }
            "ConfirmationRequired, nothing written"
        }

        check("11.3 confirming abandons the old season, records it, and rebinds the clock") {
            val result = container.challengeLauncher
                .startChallenge(DS_FLAT, confirmAbandon = true)
            require(result is ChallengeLaunchResult.Started) { "got $result" }

            val abandoned = container.playerDatabase.playerDao().getSeason(seasonId!!)
                ?: error("the abandoned season row is gone")
            require(abandoned.status == SeasonStatus.ABANDONED.name) { "status ${abandoned.status}" }

            // The abandonment must be recorded, or the history silently loses an
            // attempt — the reason the write and the status change share a
            // transaction.
            val record = container.challengeHistoryRepository.recordForSeason(seasonId!!)
                ?: error("no history record written for the abandoned season")
            require(record.outcome == ChallengeOutcome.SUPERSEDED) { "outcome ${record.outcome}" }

            require(container.simulationClock.currentSeasonId == result.seasonId) {
                "the clock stayed on the old dataset"
            }
            "old season ABANDONED + recorded, clock rebound"
        }

        check("11.9 the history and personal bests read back what was recorded") {
            val history = container.challengeHistoryRepository.historyFor(DS_RISING)
            require(history.isNotEmpty()) { "the abandoned season was not recorded" }
            require(history.first().outcome == ChallengeOutcome.SUPERSEDED) {
                "unexpected outcome ${history.first().outcome}"
            }
            val best = container.challengeHistoryRepository.personalBest(DS_RISING)
            require(best.attemptCount >= 1) { "attemptCount ${best.attemptCount}" }
            "history=${history.size} attempts=${best.attemptCount}"
        }
    }

    // ---------------------------------------------------------------- Phase 10 trading

    private suspend fun runTradingChecks(
        container: AppContainer,
        check: suspend (String, suspend () -> String) -> Unit,
    ) {
        // Its own season, so no earlier scenario's positions are in the state.
        seedDataset(container, DS_TRADE, "self-check trade", step = 1_000)
        container.challengeLauncher.startChallenge(DS_TRADE, confirmAbandon = true)

        check("10.1 the app builds a tradable session for the active season") {
            val session = container.tradingSessionSource.createLiveSession()
                ?: error("no session for an active season")
            val state = session.open() ?: error("the session opened with no state")
            require(state.balanceMicros == START_BALANCE_MICROS) { "balance ${state.balanceMicros}" }
            "session opened at ${state.processedToSimUtcMs}, balance ${state.balanceMicros}"
        }

        check("10.1 a market buy through the app's session opens a real position") {
            val session = container.tradingSessionSource.createLiveSession()
                ?: error("no session")
            session.open()
            val at = T0 + 2 * MINUTE
            session.advanceTo(at)

            val result = session.placeMarket("sc-o1", OrderSide.BUY, 10, at)
            require(result is CommandResult.Accepted) { "rejected: $result" }
            require(session.openPositions().size == 1) { "positions ${session.openPositions().size}" }
            "accepted, 1 open position"
        }

        check("10.7 closing the position realises a result and moves the balance") {
            val session = container.tradingSessionSource.createLiveSession()
                ?: error("no session")
            session.open()
            // The previous step left one position open in this season's state, so
            // close whatever is open rather than assuming an empty book.
            val before = session.openPositions().size
            val exit = T0 + 8 * MINUTE
            session.advanceTo(exit)
            for (position in session.openPositions()) {
                val closed = session.closePosition(position.positionId, exit)
                require(closed is CommandResult.Accepted) { "close rejected: $closed" }
            }
            require(session.openPositions().isEmpty()) { "a position is still open" }
            require(session.closedTrades().isNotEmpty()) { "no closed trades recorded" }

            val pnl = session.closedTrades().sumOf { it.netPnlMicros }
            "closed $before position(s), net PnL $pnl micros"
        }

        check("10.10 the app's session and the replay path share one dataset") {
            val season = container.playerRepository.activeSeason() ?: error("no active season")
            val stored = container.replayStore.load(season.seasonId)
                ?: error("the live session committed no state")
            require(stored.seasonId == season.seasonId) { "state is for ${stored.seasonId}" }
            require(stored.processedToSimUtcMs > T0) {
                "the session did not advance past the dataset start"
            }
            "replay state committed at ${stored.processedToSimUtcMs}"
        }
    }

    // ------------------------------------------------------- Phase 11 evaluation

    private suspend fun runChallengeChecks(
        container: AppContainer,
        check: suspend (String, suspend () -> String) -> Unit,
    ) {
        check("11.4 the rule set agrees with the engine's own loss floor") {
            require(
                ChallengeRules.STANDARD.agreesWithRiskLimits(
                    START_BALANCE_MICROS,
                    RiskLimits.PROP_FIRM_STANDARD.staticFloorMicros,
                ),
            ) { "the dashboard's floor and the engine's floor differ" }
            val floor = ChallengeRules.STANDARD.overallLossFloorMicros(START_BALANCE_MICROS)
            "both floors are $floor micros"
        }

        seedDataset(container, DS_CHALLENGE, "self-check challenge", step = 1_000)
        container.challengeLauncher.startChallenge(DS_CHALLENGE, confirmAbandon = true)

        check("11.5/11.9 syncChallenge evaluates the running season and persists it") {
            val season = container.playerRepository.activeSeason()
                ?: error("no active season")
            val session = container.tradingSessionSource.createLiveSession()
                ?: error("no session")
            session.open()
            val entry = T0 + 2 * MINUTE
            session.advanceTo(entry)
            val opened = session.placeMarket("sc-c1", OrderSide.BUY, 10, entry)
            require(opened is CommandResult.Accepted) { "open rejected: $opened" }
            val exit = T0 + 8 * MINUTE
            session.advanceTo(exit)
            session.closePosition(session.openPositions().first().positionId, exit)

            val state = container.replayStore.load(season.seasonId)
                ?: error("the session committed no state")
            val account = session.account()

            val evaluation = container.tradingSessionSource.syncChallenge(
                tradingState = state,
                account = account,
                equityMicros = account.equityMicros,
                atSimUtcMs = exit,
            ) ?: error("a running season did not evaluate")

            // The write must have landed: a dashboard showing one balance while the
            // row holds another is the failure this checks for.
            val stored = container.playerDatabase.playerDao().getSeason(season.seasonId)!!
            require(stored.balanceMicros == account.balanceMicros) {
                "stored balance ${stored.balanceMicros} != account ${account.balanceMicros}"
            }
            "score=${evaluation.score.total} title=${evaluation.score.title} " +
                "balance=${stored.balanceMicros}"
        }

        check("11.8 the dashboard reports the running season without advancing it") {
            val season = container.playerRepository.activeSeason() ?: error("no active season")
            val state = container.replayStore.load(season.seasonId)
            val before = state?.processedToSimUtcMs
            val dashboard: ChallengeDashboardState = container.challengeService
                .dashboard(season.seasonId, state)
                ?: error("no dashboard for an active season")

            require(dashboard.status == SeasonStatus.ACTIVE) { "status ${dashboard.status}" }
            require(dashboard.profitTargetMicros > dashboard.startingBalanceMicros) {
                "the profit target ${dashboard.profitTargetMicros} is not above the start"
            }
            require(dashboard.overallLossFloorMicros < dashboard.startingBalanceMicros) {
                "the loss floor ${dashboard.overallLossFloorMicros} is not below the start"
            }
            // Reading the dashboard must not move the season.
            val after = container.replayStore.load(season.seasonId)?.processedToSimUtcMs
            require(before == after) { "the dashboard read advanced the replay" }
            "status=${dashboard.status} equity=${dashboard.equityMicros} " +
                "target=${dashboard.profitTargetMicros} floor=${dashboard.overallLossFloorMicros}"
        }

        check("11.9 a terminal season is not re-evaluated") {
            val season = container.playerDatabase.playerDao().activeSeason()!!
            container.playerDatabase.playerDao().updateSeasonStatus(
                seasonId = season.seasonId,
                status = SeasonStatus.ABANDONED.name,
                reason = ChallengeOutcome.SUPERSEDED.name,
                completedAtSimUtcMs = null,
            )
            val evaluation = container.tradingSessionSource.syncChallenge(
                tradingState = TradingState(
                    seasonId = season.seasonId,
                    balanceMicros = START_BALANCE_MICROS,
                    orders = emptyList(),
                    positions = emptyList(),
                    executions = emptyList(),
                    closedTrades = emptyList(),
                    processedToSimUtcMs = T0,
                ),
                account = AccountState(
                    seasonId = season.seasonId,
                    balanceMicros = START_BALANCE_MICROS,
                    unrealizedPnlMicros = 0L,
                ),
                equityMicros = START_BALANCE_MICROS,
                atSimUtcMs = T0,
            )
            require(evaluation == null) { "a terminal season was evaluated: $evaluation" }
            "returned null as expected"
        }
    }

    // ------------------------------------------------- Phase 12 news and warps

    /**
     * Exercises the news calendar, Blind Mode and Time Warp through the real
     * container.
     *
     * # Why these are device checks and not unit tests
     *
     * The unit suite pins the projection, the resolver and the permission rule in
     * isolation. What it cannot pin is that the *app's* `newsScheduleSource` is
     * assembled correctly: that the season's blind flag survives the migration and
     * the launcher, that the high-impact schedule the resolver searches is the one
     * the database holds, and that a warp moves the clock *and* leaves the replay
     * caught up. Those are joins between parts, which is exactly the class of bug
     * Phase 11 shipped.
     */
    private suspend fun runNewsChecks(
        container: AppContainer,
        check: suspend (String, suspend () -> String) -> Unit,
    ) {
        // A dataset with a high-impact release ten minutes in, carrying an actual
        // and a reaction note so the projection has something to withhold.
        seedDataset(container, DS_NEWS, "self-check news", step = 1_000)
        seedNews(container, DS_NEWS)

        // Blind Mode is chosen at launch (Step 12.5) and stored on the season.
        container.challengeLauncher.startChallenge(
            DS_NEWS,
            confirmAbandon = true,
            blindMode = true,
        )

        check("12.5 Blind Mode survives the launch path and is read back from the season") {
            val season = container.playerRepository.activeSeason()
                ?: error("no active season after starting")
            require(season.blindMode) { "the stored season is not blind" }
            require(container.newsScheduleSource.isBlind()) {
                "the source reports non-blind for a blind season"
            }
            "season stored blind, source reports blind"
        }

        check("12.4 before the release the source returns an event with no actual") {
            val releases = container.newsScheduleSource.releasesAt(
                datasetId = DS_NEWS,
                fromUtcMs = T0,
                toUtcMs = T0 + 30 * MINUTE,
                simUtcMs = T0,
            )
            val event = releases.firstOrNull { it.headline == NEWS_HEADLINE }
                ?: error("the seeded release was not returned")
            require(event is NewsRelease.Pending) { "before the release the event is $event" }
            require(!event.isReleased) { "the event reports itself released at T0" }
            require(event.forecast == "180K") { "forecast ${event.forecast}" }
            "pending, forecast=${event.forecast}, previous=${event.previous}, no actual"
        }

        check("12.4 after the release the same event carries the actual") {
            val releases = container.newsScheduleSource.releasesAt(
                datasetId = DS_NEWS,
                fromUtcMs = T0,
                toUtcMs = T0 + 30 * MINUTE,
                simUtcMs = T0 + 11 * MINUTE,
            )
            val event = releases.firstOrNull { it.headline == NEWS_HEADLINE }
                ?: error("the seeded release was not returned")
            val released = event as? NewsRelease.Released
                ?: error("after the release the event is still $event")
            require(released.actual == "1.2%") { "actual ${released.actual}" }
            // One minute past the release is inside the 15-minute reaction window,
            // so the note must still be withheld.
            require(released.reactionNote == null) {
                "the reaction note was revealed before its window: ${released.reactionNote}"
            }
            "released, actual=${released.actual}, reaction withheld"
        }

        check("12.6 the news warp resolves to five minutes before the release") {
            val target = container.newsScheduleSource.resolveWarp(WarpKind.NEXT_HIGH_IMPACT_NEWS)
                ?: error("no warp target for an active season")
            val expected = T0 + 10 * MINUTE - 5 * MINUTE
            require(target.atUtcMs == expected) {
                "warp target ${target.atUtcMs}, expected $expected"
            }
            "resolved to ${target.atUtcMs} (T-5m)"
        }

        check("12.7 performing the warp moves the clock and catches the replay up") {
            val target = container.newsScheduleSource.resolveWarp(WarpKind.NEXT_HIGH_IMPACT_NEWS)
                ?: error("no warp target")
            val reached = container.newsScheduleSource.performWarp(target)
                ?: error("the warp did not move the clock")
            val expected = target.atUtcMs!!
            require(reached >= expected) { "the clock reached $reached, short of $expected" }
            // Real time keeps moving at 1x, so allow a small overshoot rather than
            // asserting an exact instant.
            require(reached - expected < 5 * MINUTE) { "the clock overshot to $reached" }

            val season = container.playerRepository.activeSeason() ?: error("no active season")
            val stored = container.replayStore.load(season.seasonId)
                ?: error("the warp left no replay state")
            require(stored.processedToSimUtcMs >= expected) {
                "the replay stopped at ${stored.processedToSimUtcMs}, behind the warp"
            }
            "clock at $reached, replay committed to ${stored.processedToSimUtcMs}"
        }

        check("12.7 a challenge with an open position refuses the warp") {
            // Rebind to a flat dataset, open a position, then ask for permission:
            // the counts must come from the live session, not a stale snapshot.
            container.challengeLauncher.startChallenge(DS_NEWS, confirmAbandon = true)
            val session = container.tradingSessionSource.createLiveSession()
                ?: error("no session for the fresh season")
            session.open()
            val entry = T0 + 2 * MINUTE
            session.advanceTo(entry)
            val placed = session.placeMarket("sc-news-o1", OrderSide.BUY, 10, entry)
            require(placed is CommandResult.Accepted) { "open rejected: $placed" }

            val permission = container.newsScheduleSource.warpPermission()
            val refused = permission as? WarpPermission.RefusedExposure
                ?: error("the warp was not refused: $permission")
            require(refused.openPositionCount == 1) {
                "reported ${refused.openPositionCount} open positions"
            }
            refused.reason
        }
    }

    // -------------------------------------------------------- real elapsed time

    private suspend fun runRealTimeCheck(
        container: AppContainer,
        check: suspend (String, suspend () -> String) -> Unit,
    ) {
        check("3.3/7.7 a real 1x challenge advances with real elapsed time") {
            // A fresh challenge so the clock is in CHALLENGE mode at 1x.
            container.challengeLauncher.startChallenge(DS_RISING, confirmAbandon = true)

            val before = container.simulationClock.currentSimUtcMs()
                ?: error("the clock has no instant")
            // The one thing a unit test cannot do: wait on the real wall clock.
            delay(1_500)
            val after = container.simulationClock.currentSimUtcMs()
                ?: error("the clock stopped reporting")

            val advanced = after - before
            require(advanced >= 1_200) {
                "1x advanced only ${advanced}ms over a 1500ms wait — the clock is not following real time"
            }
            "advanced ${advanced}ms over a 1500ms wait (1x)"
        }

        check("7.7 catch-up replays what the clock owes after an advance") {
            val season = container.playerRepository.activeSeason() ?: error("no active season")
            // A deterministic jump rather than a wait: the point is the catch-up
            // path, not the rate. Ten minutes of simulated time is owed.
            container.simulationClock.advanceBySimMs(10 * MINUTE)

            container.catchUpActiveSeason()
                ?: error("catch-up returned null for an active season with an attached clock")

            val stored = container.replayStore.load(season.seasonId)
                ?: error("the catch-up stored no state")
            require(stored.processedToSimUtcMs > T0) {
                "the replay did not move past the dataset start (${stored.processedToSimUtcMs})"
            }
            "replayed to ${stored.processedToSimUtcMs}"
        }
    }

    // ------------------------------------------------- persistence across restart

    private suspend fun runPersistenceCheck(
        context: Context,
        check: suspend (String, suspend () -> String) -> Unit,
    ) {
        check("11.1/3.3 a season and its clock binding survive a container restart") {
            // A new container over the same files is what a process restart looks
            // like: the rows survive, the clock's in-memory binding does not.
            val restarted = AppContainer(context, MARKET_DB, PLAYER_DB)
            try {
                require(restarted.playerRepository.activeSeason() != null) {
                    "the active season did not survive"
                }
                require(!restarted.simulationClock.isAttached) {
                    "a fresh clock should start unattached"
                }

                restarted.onAppStart()

                require(restarted.simulationClock.isAttached) {
                    "onAppStart did not rebind the clock, so a resumed season would be inert"
                }
                "season survived, onAppStart rebound the clock"
            } finally {
                runCatching { restarted.playerDatabase.close() }
                runCatching { restarted.marketDatabase.close() }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Inserts a synthetic dataset directly through the DAO.
     *
     * Deliberately not the bundled CSV: this check is about the wiring and the
     * clock, and a deterministic 30-candle series makes the prices and the day
     * boundaries predictable. The CSV import path is covered separately by
     * [DebugDatasetSeeder] and `DebugSeederRoomTest`.
     */
    private suspend fun seedDataset(
        container: AppContainer,
        datasetId: String,
        displayName: String,
        step: Int,
        minutes: Int = 30,
    ) {
        container.marketDatabase.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = datasetId,
                displayName = displayName,
                instrument = "XAUUSD",
                startTimestampUtcMs = T0,
                endTimestampUtcMs = T0 + (minutes - 1) * MINUTE,
                candleCount = minutes,
                contentHash = "selfcheck-hash-$datasetId",
                validationStatus = "VALID",
                sourceDescription = "debug self-check",
                importedAtUtcMs = T0,
            ),
        )
        container.marketDatabase.marketDao().insertCandles(
            (0 until minutes).map { index ->
                val open = 2_000_000 + index * step
                Candle1mEntity(
                    datasetId = datasetId,
                    timestampUtcMs = T0 + index * MINUTE,
                    openUnits = open,
                    highUnits = open + 500,
                    lowUnits = open - 500,
                    closeUnits = open + 400,
                    tickVolume = 100,
                )
            },
        )
    }

    /**
     * Seeds one high-impact release with an outcome, ten minutes into the dataset.
     *
     * The actual and the reaction note are present in the row on purpose: the point
     * of Step 12.4 is that the projection withholds them, not that the database is
     * missing them.
     */
    private suspend fun seedNews(container: AppContainer, datasetId: String) {
        container.marketDatabase.marketDao().insertNews(
            listOf(
                NewsEventEntity(
                    datasetId = datasetId,
                    publishedAtUtcMs = T0 + 10 * MINUTE,
                    headline = NEWS_HEADLINE,
                    body = "self-check release",
                    category = "EMPLOYMENT",
                    impact = "HIGH",
                    forecast = "180K",
                    previous = "175K",
                    actual = "1.2%",
                    reactionNote = "Gold spiked on the print.",
                    reactionWindowMinutes = 15,
                ),
                NewsEventEntity(
                    datasetId = datasetId,
                    publishedAtUtcMs = T0 + 4 * MINUTE,
                    headline = "self-check survey",
                    impact = "MEDIUM",
                ),
            ),
        )
    }

    private suspend fun seedEmptyDataset(container: AppContainer) {
        container.marketDatabase.marketDao().insertDataset(
            MarketDatasetEntity(
                datasetId = DS_EMPTY, displayName = "self-check empty", instrument = "XAUUSD",
                startTimestampUtcMs = T0, endTimestampUtcMs = T0, candleCount = 0,
                contentHash = "selfcheck-hash-empty", validationStatus = "VALID",
                sourceDescription = "debug self-check", importedAtUtcMs = T0,
            ),
        )
    }

    /**
     * Removes the scratch database files.
     *
     * Called before the run so a previous run cannot change the result, and after
     * so the check leaves nothing on the device. Deleting is best-effort: on a
     * locked file the next run's clean-up will get it.
     */
    private fun deleteScratchDatabases(context: Context) {
        for (name in listOf(MARKET_DB, PLAYER_DB)) {
            runCatching { context.getDatabasePath(name).delete() }
        }
    }
}
