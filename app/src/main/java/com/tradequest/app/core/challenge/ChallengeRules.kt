package com.tradequest.app.core.challenge

import com.tradequest.app.core.accounting.AccountMath
import com.tradequest.app.core.accounting.CheckedMath
import com.tradequest.app.core.accounting.MoneyScale

/**
 * The prop-firm challenge's parameters (Step 11.4, `AGENTS.md` section 12).
 *
 * # Why these are a value, not constants
 *
 * Every figure here is a *rule*, and a rule is what a test wants to state when it
 * says "an account that reached its target on day 19 did not pass". Hard-coding
 * them would make the evaluator untestable at any other size without a second
 * implementation, which is exactly the mistake `RiskLimits` avoids one package
 * over.
 *
 * # The relationship to [com.tradequest.app.core.risk.RiskLimits]
 *
 * Phase 6 owns the *live* risk arithmetic — the daily floor, the stop-out, the
 * strike count — and its `RiskLimits.staticFloorMicros` is the authority the
 * engine enforces. This type restates the same rules as percentages so the
 * dashboard can show "maximum loss remaining" without recomputing a floor, and so
 * the challenge rules read the way the plan writes them. The two must agree: for
 * the plan's $10,000 start, 10% is $9,000, which is `staticFloorMicros` exactly.
 * [agreesWithRiskLimits] asserts that rather than leaving it to a reader to
 * notice, and a test fails if the two ever drift.
 */
data class ChallengeRules(
    /** Profit target, as a percentage of the starting balance. The plan: 15%. */
    val profitTargetPercent: Int = 15,

    /** Trading days required before a target-reaching season may pass. The plan: 20. */
    val minimumTradingDays: Int = 20,

    /** Daily loss limit, as a percentage of the day's starting equity. The plan: 3%. */
    val maxDailyLossPercent: Int = 3,

    /** Overall loss limit, as a percentage of the starting balance. The plan: 10%. */
    val maxOverallLossPercent: Int = 10,

    /** Daily-loss breaches allowed before the challenge fails. The plan: 3. */
    val maxDailyStrikes: Int = 3,
) {
    init {
        require(profitTargetPercent > 0) { "a profit target of zero would pass on day one" }
        require(minimumTradingDays >= 0) { "minimum trading days cannot be negative" }
        require(maxDailyLossPercent in 1..100) { "daily loss percent must be 1..100" }
        require(maxOverallLossPercent in 1..100) { "overall loss percent must be 1..100" }
        require(maxDailyStrikes > 0) { "there must be at least one allowed strike" }
    }

    /**
     * The equity at or above which the profit target is met.
     *
     * `starting * (100 + target) / 100`, multiply-then-divide so the intermediate
     * is never truncated. For the plan's $10,000 and 15% this is $11,500 exactly.
     */
    fun profitTargetMicros(startingBalanceMicros: Long): Long =
        CheckedMath.multiplyDivide(
            startingBalanceMicros,
            100L + profitTargetPercent,
            100L,
        )

    /**
     * The equity at or below which the overall loss limit is breached.
     *
     * `starting * (100 - limit) / 100`. For the plan's $10,000 and 10% this is
     * $9,000, matching the engine's static floor.
     */
    fun overallLossFloorMicros(startingBalanceMicros: Long): Long =
        CheckedMath.multiplyDivide(
            startingBalanceMicros,
            100L - maxOverallLossPercent,
            100L,
        )

    /**
     * The equity at or below which the *current day* is lost.
     *
     * Recomputed from the day's reference equity, not the season's start, so the
     * budget tracks a growing account exactly as `RiskRules.dailyFloorMicros`
     * does. The two are the same formula, deliberately: the dashboard and the
     * engine must not disagree about where the day's floor is.
     */
    fun dailyLossFloorMicros(dayStartEquityMicros: Long): Long =
        CheckedMath.multiplyDivide(
            dayStartEquityMicros,
            100L - maxDailyLossPercent,
            100L,
        )

    /** True when [equityMicros] has reached the profit target. */
    fun profitTargetReached(equityMicros: Long, startingBalanceMicros: Long): Boolean =
        equityMicros >= profitTargetMicros(startingBalanceMicros)

    /** True when [equityMicros] has reached the overall loss floor. */
    fun overallLossBreached(equityMicros: Long, startingBalanceMicros: Long): Boolean =
        AccountMath.atOrBelow(equityMicros, overallLossFloorMicros(startingBalanceMicros))

    /** True when enough trading days have been logged to allow a pass. */
    fun minimumDaysSatisfied(tradingDayCount: Int): Boolean =
        tradingDayCount >= minimumTradingDays

    /** True when the daily-strike allowance is exhausted. */
    fun strikesExhausted(dailyStrikeCount: Int): Boolean =
        dailyStrikeCount >= maxDailyStrikes

    /**
     * True when this rule set's overall-loss floor is the same figure the engine's
     * `RiskLimits` enforces.
     *
     * Stated as a method rather than a comment so a test can assert it. The two
     * values live in different packages and could be edited independently; if they
     * ever disagree, the dashboard would show a challenge failing at a level the
     * engine never reaches (or the reverse), which is precisely the class of bug a
     * reader cannot see by looking at either file alone.
     */
    fun agreesWithRiskLimits(
        startingBalanceMicros: Long,
        riskLimitsStaticFloorMicros: Long,
    ): Boolean = overallLossFloorMicros(startingBalanceMicros) == riskLimitsStaticFloorMicros

    companion object {
        /** The plan's challenge parameters. */
        val STANDARD: ChallengeRules = ChallengeRules()
    }
}

/**
 * Money formatting for challenge figures.
 *
 * Thin wrapper over [MoneyScale] so the challenge package has one place that turns
 * micro-dollars into text, and so a screen never formats a floor itself.
 */
object ChallengeFormat {

    /** Signed 2-decimal USD, e.g. `$11,500.00` or `-$250.00`. */
    fun money(micros: Long): String {
        val formatted = MoneyScale.formatMicros(micros)
        val negative = formatted.startsWith("-")
        val digits = if (negative) formatted.substring(1) else formatted
        val grouped = groupThousands(digits)
        return if (negative) "-$$grouped" else "$$grouped"
    }

    private fun groupThousands(value: String): String {
        val dot = value.indexOf('.')
        val whole = if (dot >= 0) value.substring(0, dot) else value
        val rest = if (dot >= 0) value.substring(dot) else ""
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        return grouped + rest
    }
}
