package com.tradequest.app.core.challenge

import com.tradequest.app.core.trading.AccountState
import com.tradequest.app.core.trading.TradingState

/**
 * Runs the challenge lifecycle for a season: read, evaluate, persist (Steps
 * 11.5-11.9).
 *
 * # Why this exists as a service
 *
 * The evaluation must happen wherever a season's state changes — the replay worker
 * after a catch-up, the live session after a tick — and each of those call sites
 * must do the *same* four things: read the season's facts, read the engine's state,
 * evaluate, and persist the result. Putting that sequence in one method means a
 * call site cannot persist progress without evaluating, or evaluate without
 * persisting, which are exactly the ways the dashboard and the stored season could
 * come to disagree.
 *
 * # Why a terminal season is not re-recorded
 *
 * [sync] returns early when the season is already terminal. A season can be seen as
 * ended by more than one caller — the dashboard polls, and the worker ticks — and
 * without this guard each would write a history record, so a season that failed
 * once would appear several times in history. The guard is on the *stored* status,
 * not on the in-memory verdict, so it holds across a process restart.
 *
 * # Practice mode
 *
 * A PRACTICE season is evaluated and scored like a challenge — the figures are
 * useful to a free-play trader — but it never passes or fails: [sync] persists its
 * progress and returns an InProgress verdict. The plan's pass/fail rules are the
 * challenge's, and applying them to practice would end a free-play session at a
 * loss limit, which is not what practice is for.
 */
class ChallengeService(
    private val store: ChallengeProgressStore,
    private val rules: ChallengeRules = ChallengeRules.STANDARD,
) {

    /**
     * Evaluates [state] against the season's stored facts and persists the result.
     *
     * Returns the evaluation, or null when the season is unknown or already
     * terminal — both of which mean there is nothing to do, not that something went
     * wrong.
     */
    suspend fun sync(
        seasonId: String,
        state: TradingState,
        account: AccountState,
        equityMicros: Long,
        atSimUtcMs: Long,
        atRealUtcMs: Long,
    ): ChallengeEvaluation? {
        val facts = store.seasonFacts(seasonId) ?: return null
        if (facts.status.isTerminal) return null

        val progress = ChallengeProgressReader.read(
            state = state,
            facts = facts,
            account = account,
            equityMicros = equityMicros,
            datasetDisplayName = facts.datasetDisplayName,
            atSimUtcMs = atSimUtcMs,
            atRealUtcMs = atRealUtcMs,
        )
        val evaluation = ChallengeCoordinator.evaluate(progress, rules)

        // Practice never ends on a rule: its progress is recorded, but its verdict
        // is forced back to running so nothing downstream terminates it.
        if (facts.mode == SeasonMode.PRACTICE) {
            persistIfChanged(seasonId, progress, evaluation.score.total, facts)
            return evaluation.copy(
                verdict = ChallengeVerdict.InProgress,
                dashboard = evaluation.dashboard.copy(status = SeasonStatus.ACTIVE, outcome = null),
            )
        }

        if (evaluation.isTerminal) {
            store.complete(evaluation)
        } else {
            persistIfChanged(seasonId, progress, evaluation.score.total, facts)
        }
        return evaluation
    }

    /**
     * The stored dashboard for [seasonId], without writing anything (Step 11.8).
     *
     * # Why this exists alongside [sync]
     *
     * The Challenge screen is a different destination from the chart, and it must
     * be able to show a season's figures without advancing anything: the screen has
     * no engine, and evaluating there would mean a second place that could decide a
     * verdict. So this reads what the trading path already persisted and derives
     * the display figures from it, using the *same* [ChallengeProgressReader] and
     * [ChallengeCoordinator] the live path uses. It never writes, which is why it is
     * safe to call as often as the season row changes.
     *
     * # Why [state] is optional, and what its absence means
     *
     * The season row carries the balance, equity and peak, but not the closed-trade
     * ledger or the day's reference equity — those live in the replay state. When
     * that state cannot be read (a season never traded, or the store is empty) the
     * day count, the strikes and the score are genuinely unknown, and this reports
     * them as zero rather than inventing them. A dashboard that showed a score the
     * evaluator would not produce is worse than one that shows a floor of zero while
     * the season has not traded, because the second is visibly "not yet" and the
     * first is a plausible lie.
     */
    suspend fun dashboard(seasonId: String, state: TradingState?): ChallengeDashboardState? {
        val facts = store.seasonFacts(seasonId) ?: return null

        // The account as the last write recorded it: the balance is the row's, and
        // the unrealised part is whatever made the stored equity differ from it.
        // Reconstructing it this way keeps the dashboard's equity identical to the
        // figure the trading path published, rather than a second, unmarked guess.
        val account = AccountState(
            seasonId = seasonId,
            balanceMicros = facts.balanceMicros,
            unrealizedPnlMicros = facts.equityMicros - facts.balanceMicros,
        )
        val progress = ChallengeProgressReader.read(
            state = state ?: emptyState(seasonId, facts),
            facts = facts,
            account = account,
            equityMicros = facts.equityMicros,
            datasetDisplayName = facts.datasetDisplayName,
            atSimUtcMs = state?.processedToSimUtcMs ?: 0L,
            atRealUtcMs = 0L,
        )
        val evaluation = ChallengeCoordinator.evaluate(progress, rules)
        // The stored status is the authority: an ABANDONED season is not a verdict
        // the evaluator can reach, and a terminal season must keep the outcome it
        // was recorded with rather than be re-decided from a later snapshot.
        return evaluation.dashboard.copy(
            status = facts.status,
            outcome = facts.outcome ?: evaluation.dashboard.outcome,
        )
    }

    /**
     * A season state with no trades, for a season that has never been replayed.
     *
     * Only the fields the reader consults matter; everything else is empty, which is
     * exactly what a season with no engine state has done.
     */
    private fun emptyState(seasonId: String, facts: ChallengeSeasonFacts): TradingState = TradingState(
        seasonId = seasonId,
        balanceMicros = facts.balanceMicros,
        orders = emptyList(),
        positions = emptyList(),
        executions = emptyList(),
        closedTrades = emptyList(),
        processedToSimUtcMs = facts.startedAtSimUtcMs,
    )

    /**
     * Writes progress only when a stored figure would change.
     *
     * # Why the guard is here and not in the store
     *
     * The chart ticks several times a second, and almost every tick changes nothing
     * the season stores: the equity moves within the day, but the balance, the day
     * count, the strikes and the score are all unchanged. A write per tick would be
     * hundreds of database writes a minute for no new information, and on a device
     * that is a real cost. The comparison is against the *stored* values, so it is
     * correct across a process restart: a resumed season writes once, then stays
     * quiet until something genuinely moves.
     *
     * The peak equity is not compared, because it is monotonic and a change to it is
     * always accompanied by a change to equity, which is compared.
     */
    private suspend fun persistIfChanged(
        seasonId: String,
        progress: ChallengeProgress,
        scorePoints: Int,
        facts: ChallengeSeasonFacts,
    ) {
        val unchanged = progress.balanceMicros == facts.balanceMicros &&
            progress.equityMicros == facts.equityMicros &&
            progress.tradingDayCount == facts.tradingDayCount &&
            progress.dailyStrikeCount == facts.dailyStrikeCount &&
            scorePoints == facts.scorePoints
        if (unchanged) return

        store.saveProgress(
            seasonId = seasonId,
            balanceMicros = progress.balanceMicros,
            equityMicros = progress.equityMicros,
            peakEquityMicros = progress.peakEquityMicros,
            tradingDayCount = progress.tradingDayCount,
            dailyStrikeCount = progress.dailyStrikeCount,
            scorePoints = scorePoints,
        )
    }
}
