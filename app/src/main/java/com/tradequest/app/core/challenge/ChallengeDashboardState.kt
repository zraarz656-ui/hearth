package com.tradequest.app.core.challenge

/**
 * Everything the challenge dashboard shows (Step 11.8).
 *
 * # Why this is a domain type rather than a UI model
 *
 * Every field is a rule-derived figure — a floor, a remaining budget, a progress
 * fraction — and each must be computed by the same arithmetic the evaluator uses,
 * or the dashboard would show a challenge as passing that the evaluator would
 * fail. Deriving them in the composable would put a second copy of that arithmetic
 * on the wrong side of the architecture boundary (`docs/architecture.md` rule 1).
 * So the figures are computed here, in pure Kotlin, and the screen only formats
 * them.
 */
data class ChallengeDashboardState(
    val seasonId: String,
    val datasetId: String,
    val status: SeasonStatus,

    /** What the season began with. */
    val startingBalanceMicros: Long,

    /** Realised balance. */
    val balanceMicros: Long,

    /** Balance plus unrealised PnL. */
    val equityMicros: Long,

    /** Highest equity reached, which is what the profit score is measured on. */
    val peakEquityMicros: Long,

    /** Equity at or above which the season may pass. */
    val profitTargetMicros: Long,

    /** Equity at or below which the season fails outright. */
    val overallLossFloorMicros: Long,

    /** Equity at or below which the current day is lost. */
    val dailyLossFloorMicros: Long,

    val tradingDayCount: Int,
    val minimumTradingDays: Int,

    val dailyStrikeCount: Int,
    val maxDailyStrikes: Int,

    val score: ChallengeScore,

    /** Why the season ended, when it has. Null while it is running. */
    val outcome: ChallengeOutcome?,
) {
    /** Profit or loss against the starting balance. Negative when losing. */
    val profitMicros: Long get() = equityMicros - startingBalanceMicros

    /**
     * Progress toward the target, as an exact integer fraction in 0..1.
     *
     * A `Float` is permitted here because it is a rendering ratio and never holds
     * money (`docs/architecture.md`): it drives a progress bar's width, and the
     * authoritative figures beside it are the micro-dollar values.
     */
    val targetProgress: Float
        get() {
            val span = profitTargetMicros - startingBalanceMicros
            if (span <= 0L) return 1f
            val gained = peakEquityMicros - startingBalanceMicros
            if (gained <= 0L) return 0f
            return (gained.toDouble() / span.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }

    /** True once the profit target level has been reached. */
    val targetReached: Boolean get() = equityMicros >= profitTargetMicros

    /** Equity the account may still lose before the overall floor is reached. */
    val overallLossRemainingMicros: Long
        get() = (equityMicros - overallLossFloorMicros).coerceAtLeast(0L)

    /** Equity the account may still lose today before the daily floor is reached. */
    val dailyLossRemainingMicros: Long
        get() = (equityMicros - dailyLossFloorMicros).coerceAtLeast(0L)

    /** Strikes still available before the challenge fails. */
    val strikesRemaining: Int
        get() = (maxDailyStrikes - dailyStrikeCount).coerceAtLeast(0)

    /** Trading days still owed before a target-reaching season could pass. */
    val tradingDaysRemaining: Int
        get() = (minimumTradingDays - tradingDayCount).coerceAtLeast(0)

    /** True while the season can still be traded. */
    val isActive: Boolean get() = status == SeasonStatus.ACTIVE
}
