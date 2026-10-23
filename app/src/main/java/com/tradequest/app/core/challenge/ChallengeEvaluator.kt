package com.tradequest.app.core.challenge

/**
 * The facts a pass/fail decision is made from (Step 11.5).
 *
 * A plain value, computed by the caller from the season's stored progress and the
 * engine's live state. The evaluator takes no database, no clock and no engine, so
 * a test states a scenario in one constructor call and reads the verdict directly.
 */
data class ChallengeSnapshot(
    /** What the season began with, in micro-dollars. */
    val startingBalanceMicros: Long,

    /** Current equity: balance plus unrealised PnL. */
    val equityMicros: Long,

    /** Distinct trading days logged so far (Step 11.4: at least 20 to pass). */
    val tradingDayCount: Int,

    /** Daily-loss breaches accumulated across the season (Step 11.4: 3 fails). */
    val dailyStrikeCount: Int,

    /**
     * True when the engine has already failed the season at a loss limit.
     *
     * The engine owns the chronological enforcement (Step 6.9-6.10) and sets this
     * on the state. The evaluator honours it rather than re-deriving it, because
     * the engine saw the equity at the instant it breached, and the evaluator only
     * sees a later snapshot.
     */
    val engineFailed: Boolean = false,
)

/**
 * Why a season ended, for the history list.
 *
 * Carried alongside the status rather than folded into it, because the plan
 * distinguishes a failure at the overall floor from a failure on strikes and the
 * history screen reports the difference. A null reason on a PASSED season is
 * correct: nothing went wrong.
 */
enum class ChallengeOutcome {
    /** The profit target was reached with the minimum trading days satisfied. */
    TARGET_REACHED,

    /** Equity reached the overall loss floor. */
    OVERALL_LOSS_BREACHED,

    /** The third daily strike was recorded. */
    DAILY_STRIKES_EXHAUSTED,

    /** A new challenge was started over this one (Step 11.3). */
    SUPERSEDED,
}

/** What a pass/fail evaluation concluded. */
sealed interface ChallengeVerdict {

    /** The season is still running. */
    data object InProgress : ChallengeVerdict

    /** The season passed: the target was met and the minimum days were logged. */
    data object Passed : ChallengeVerdict

    /** The season failed at [outcome]. */
    data class Failed(val outcome: ChallengeOutcome) : ChallengeVerdict
}

/**
 * Deterministic pass/fail evaluation (Step 11.5).
 *
 * # Why this is a pure object over a snapshot
 *
 * "Pass or fail" must be a function of the season's own numbers, not of the order
 * in which a UI happened to poll it. If the dashboard and the replay worker each
 * decided, they could disagree — the dashboard showing a running challenge while
 * the worker had already failed it. One pure function, called by whoever holds
 * the authoritative state, is the only shape in which they cannot.
 *
 * # The precedence, and why it is this order
 *
 * 1. **A failure the engine already recorded wins outright.** The engine saw the
 *    breach at the instant it happened; a later snapshot cannot un-see it, and a
 *    season that dipped to $8,900 and recovered must not be shown as passing.
 * 2. **The overall loss floor fails the season.** Terminal, and checked before
 *    the target: at the plan's figures a $10,000 account cannot be simultaneously
 *    at $11,500 and $9,000, but the ordering is stated so a future rule set that
 *    could reach both resolves the same way every time.
 * 3. **Exhausted strikes fail the season.**
 * 4. **The target passes it, but only with the minimum trading days logged.**
 *    This is the rule that makes the challenge a *consistency* test rather than a
 *    speed test: reaching +15% on day three is not a pass, it is an early lead,
 *    and the season stays running until the days are logged. A season that meets
 *    the target and then loses it falls back to running, which is correct — the
 *    target is a level to hold, not a flag to set.
 * 5. Otherwise, running.
 */
object ChallengeEvaluator {

    fun evaluate(
        snapshot: ChallengeSnapshot,
        rules: ChallengeRules = ChallengeRules.STANDARD,
    ): ChallengeVerdict {
        if (snapshot.engineFailed) {
            return ChallengeVerdict.Failed(outcomeForEngineFailure(snapshot, rules))
        }

        if (rules.overallLossBreached(snapshot.equityMicros, snapshot.startingBalanceMicros)) {
            return ChallengeVerdict.Failed(ChallengeOutcome.OVERALL_LOSS_BREACHED)
        }

        if (rules.strikesExhausted(snapshot.dailyStrikeCount)) {
            return ChallengeVerdict.Failed(ChallengeOutcome.DAILY_STRIKES_EXHAUSTED)
        }

        if (rules.profitTargetReached(snapshot.equityMicros, snapshot.startingBalanceMicros) &&
            rules.minimumDaysSatisfied(snapshot.tradingDayCount)
        ) {
            return ChallengeVerdict.Passed
        }

        return ChallengeVerdict.InProgress
    }

    /**
     * The reason to attribute to a failure the engine recorded.
     *
     * The engine's own flag does not say *which* limit was hit, and the snapshot
     * may have moved since. The floor is checked first because it is the more
     * severe of the two, and a season that breached both is reported by the one
     * that would have ended it regardless of the other.
     */
    private fun outcomeForEngineFailure(
        snapshot: ChallengeSnapshot,
        rules: ChallengeRules,
    ): ChallengeOutcome =
        if (rules.overallLossBreached(snapshot.equityMicros, snapshot.startingBalanceMicros)) {
            ChallengeOutcome.OVERALL_LOSS_BREACHED
        } else if (rules.strikesExhausted(snapshot.dailyStrikeCount)) {
            ChallengeOutcome.DAILY_STRIKES_EXHAUSTED
        } else {
            // The engine failed the season but the snapshot no longer shows either
            // limit breached — for example the flag was set and the numbers were
            // then restored from an older commit. Attribute the failure to the
            // overall floor rather than inventing a third reason: the season *is*
            // failed, and that is the terminal limit the plan names.
            ChallengeOutcome.OVERALL_LOSS_BREACHED
        }
}
