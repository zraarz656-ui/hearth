package com.tradequest.app.core.challenge

import com.tradequest.app.core.accounting.AccountMath

/**
 * The live facts a challenge evaluation needs, gathered from one source.
 *
 * Assembled by whoever holds the authoritative state — the replay worker or the
 * live session — so the coordinator takes one value rather than reaching for a
 * database, an engine or a clock.
 */
data class ChallengeProgress(
    val seasonId: String,
    val datasetId: String,
    val datasetDisplayName: String,
    val mode: SeasonMode,

    val startingBalanceMicros: Long,
    val balanceMicros: Long,
    val equityMicros: Long,
    val peakEquityMicros: Long,

    /** Distinct trading days, from the closed-trade ledger (Step 11.4). */
    val tradingDayCount: Int,

    /** Daily-loss breaches, as the engine counted them (Step 11.4). */
    val dailyStrikeCount: Int,

    /** True when the engine already failed the season at a loss limit. */
    val engineFailed: Boolean,

    /** The best single day's net PnL, in micro-dollars, floored at zero. */
    val bestDayPnlMicros: Long,

    /**
     * The equity at the current trading day's rollover.
     *
     * Supplied by the caller because it is the engine's own reference — the equity
     * it froze when the day rolled at 17:00 New York — and the dashboard's daily
     * floor must be computed from the same figure the engine enforces. Falling back
     * to the season's starting balance would show a budget that does not track the
     * account.
     */
    val dailyStartEquityMicros: Long,

    /** Total net realised PnL, in micro-dollars. */
    val totalPnlMicros: Long,

    val losingTradeCount: Int,
    val closedTradeCount: Int,

    /** The season's start in simulated time, carried into the history record. */
    val startedAtSimUtcMs: Long,

    /** The current simulated instant, recorded as the completion time. */
    val atSimUtcMs: Long,

    /** The current real instant, recorded as the completion time. */
    val atRealUtcMs: Long,
) {
    /** The snapshot the evaluator reads. */
    fun snapshot(): ChallengeSnapshot = ChallengeSnapshot(
        startingBalanceMicros = startingBalanceMicros,
        equityMicros = equityMicros,
        tradingDayCount = tradingDayCount,
        dailyStrikeCount = dailyStrikeCount,
        engineFailed = engineFailed,
    )

    /** The score inputs, taken from the same facts as the snapshot. */
    fun scoreInput(): ChallengeScoreInput = ChallengeScoreInput(
        startingBalanceMicros = startingBalanceMicros,
        peakEquityMicros = peakEquityMicros,
        currentEquityMicros = equityMicros,
        tradingDayCount = tradingDayCount,
        dailyStrikeCount = dailyStrikeCount,
        bestDayPnlMicros = bestDayPnlMicros,
        totalPnlMicros = totalPnlMicros,
        losingTradeCount = losingTradeCount,
        closedTradeCount = closedTradeCount,
    )
}

/**
 * What a coordinator evaluation concluded, ready to persist and to display.
 */
data class ChallengeEvaluation(
    val progress: ChallengeProgress,
    val verdict: ChallengeVerdict,
    val score: ChallengeScore,
    val dashboard: ChallengeDashboardState,
) {
    /** True when the season has ended and must be recorded. */
    val isTerminal: Boolean get() = verdict != ChallengeVerdict.InProgress

    /** The status to persist, or ACTIVE while the season runs. */
    val status: SeasonStatus
        get() = when (verdict) {
            ChallengeVerdict.Passed -> SeasonStatus.PASSED
            is ChallengeVerdict.Failed -> SeasonStatus.FAILED
            ChallengeVerdict.InProgress -> SeasonStatus.ACTIVE
        }

    /** Why the season ended, when it has. */
    val outcome: ChallengeOutcome?
        get() = when (verdict) {
            is ChallengeVerdict.Failed -> verdict.outcome
            ChallengeVerdict.Passed -> ChallengeOutcome.TARGET_REACHED
            ChallengeVerdict.InProgress -> null
        }

    /**
     * The history entry to write, or null while the season is still running.
     *
     * [attemptOrdinal] is passed in rather than carried on [progress] because it is
     * a property of the *dataset's* history, not of this season: it is the count of
     * attempts that came before, which only the store can answer. Reading it at the
     * one moment it is needed keeps it off the per-tick path, where it would be a
     * `COUNT(*)` per frame for a figure that changes once a season.
     */
    fun historyEntry(recordId: String, attemptOrdinal: Int): ChallengeHistoryEntry? {
        val outcome = outcome ?: return null
        return ChallengeHistoryEntry(
            recordId = recordId,
            seasonId = progress.seasonId,
            datasetId = progress.datasetId,
            datasetDisplayName = progress.datasetDisplayName,
            mode = progress.mode,
            status = status,
            outcome = outcome,
            startingBalanceMicros = progress.startingBalanceMicros,
            finalBalanceMicros = progress.balanceMicros,
            finalEquityMicros = progress.equityMicros,
            peakEquityMicros = progress.peakEquityMicros,
            tradingDayCount = progress.tradingDayCount,
            dailyStrikeCount = progress.dailyStrikeCount,
            score = score,
            title = score.title,
            attemptOrdinal = attemptOrdinal,
            closedTradeCount = progress.closedTradeCount,
            startedAtSimUtcMs = progress.startedAtSimUtcMs,
            completedAtSimUtcMs = progress.atSimUtcMs,
            completedAtRealUtcMs = progress.atRealUtcMs,
        )
    }
}

/**
 * Ties the challenge's three pure pieces together: evaluate, score, and build the
 * dashboard (Steps 11.5-11.8).
 *
 * # Why one coordinator rather than three call sites
 *
 * The evaluator, the scorer and the dashboard all read the same facts, and the plan
 * requires them to agree: a dashboard that showed "running" for a season the
 * evaluator had failed would be worse than no dashboard. Deriving all three from
 * one [ChallengeProgress] in one call is the only shape in which they cannot
 * disagree, and it gives the caller a single object to persist and to display.
 *
 * It is stateless and pure, so it is also what the tests drive directly.
 */
object ChallengeCoordinator {

    fun evaluate(
        progress: ChallengeProgress,
        rules: ChallengeRules = ChallengeRules.STANDARD,
    ): ChallengeEvaluation {
        val verdict = ChallengeEvaluator.evaluate(progress.snapshot(), rules)
        val score = ChallengeScorer.score(progress.scoreInput(), rules)
        val status = when (verdict) {
            ChallengeVerdict.Passed -> SeasonStatus.PASSED
            is ChallengeVerdict.Failed -> SeasonStatus.FAILED
            ChallengeVerdict.InProgress -> SeasonStatus.ACTIVE
        }
        val outcome = when (verdict) {
            is ChallengeVerdict.Failed -> verdict.outcome
            ChallengeVerdict.Passed -> ChallengeOutcome.TARGET_REACHED
            ChallengeVerdict.InProgress -> null
        }
        return ChallengeEvaluation(
            progress = progress,
            verdict = verdict,
            score = score,
            dashboard = dashboard(progress, rules, status, outcome, score),
        )
    }

    /**
     * The dashboard figures, from the same facts the verdict came from.
     *
     * [dailyLossFloorMicros] is derived from the *current* day's reference equity.
     * The engine's reference is the equity at the last rollover, which the caller
     * supplies through [ChallengeProgress.dailyStartEquityMicros] — see the note on
     * that field. Using the season's starting balance instead would show a floor
     * that does not move as the account grows, and a trader who was up 10% would
     * see a daily budget three times larger than the one the engine enforces.
     */
    private fun dashboard(
        progress: ChallengeProgress,
        rules: ChallengeRules,
        status: SeasonStatus,
        outcome: ChallengeOutcome?,
        score: ChallengeScore,
    ): ChallengeDashboardState = ChallengeDashboardState(
        seasonId = progress.seasonId,
        datasetId = progress.datasetId,
        status = status,
        startingBalanceMicros = progress.startingBalanceMicros,
        balanceMicros = progress.balanceMicros,
        equityMicros = progress.equityMicros,
        peakEquityMicros = progress.peakEquityMicros,
        profitTargetMicros = rules.profitTargetMicros(progress.startingBalanceMicros),
        overallLossFloorMicros = rules.overallLossFloorMicros(progress.startingBalanceMicros),
        dailyLossFloorMicros = rules.dailyLossFloorMicros(progress.dailyStartEquityMicros),
        tradingDayCount = progress.tradingDayCount,
        minimumTradingDays = rules.minimumTradingDays,
        dailyStrikeCount = progress.dailyStrikeCount,
        maxDailyStrikes = rules.maxDailyStrikes,
        score = score,
        outcome = outcome,
    )

    /** Equity, from a balance and an already-computed unrealised figure. */
    fun equityMicros(balanceMicros: Long, unrealizedPnlMicros: Long): Long =
        AccountMath.equityMicros(balanceMicros, unrealizedPnlMicros)
}
