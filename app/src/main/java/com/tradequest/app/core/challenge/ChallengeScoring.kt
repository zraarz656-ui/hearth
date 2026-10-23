package com.tradequest.app.core.challenge

import com.tradequest.app.core.accounting.CheckedMath

/**
 * The four scored dimensions and their weights (Step 11.6).
 *
 * The weights sum to 100, and [TOTAL_POINTS] asserts it, so a future edit that
 * changes one weight without the others fails at class-initialisation rather than
 * silently producing a score out of 105.
 */
object ChallengeScoreWeights {
    const val PROFIT = 40
    const val RISK_CONTROL = 30
    const val CONSISTENCY = 20
    const val DISCIPLINE = 10

    /** The maximum attainable score. */
    const val TOTAL_POINTS: Int = PROFIT + RISK_CONTROL + CONSISTENCY + DISCIPLINE

    init {
        check(TOTAL_POINTS == 100) { "the weights must sum to 100, got $TOTAL_POINTS" }
    }
}

/**
 * A season's challenge score, out of 100 (Step 11.6).
 *
 * Kept as its four components rather than a single number so the dashboard can
 * show *why* a season scored what it did, and so a test asserts a dimension
 * instead of a total that several changes could each have shifted.
 */
data class ChallengeScore(
    val profitPoints: Int,
    val riskControlPoints: Int,
    val consistencyPoints: Int,
    val disciplinePoints: Int,
) {
    /** The total, clamped to the plan's maximum. */
    val total: Int
        get() = (profitPoints + riskControlPoints + consistencyPoints + disciplinePoints)
            .coerceIn(0, ChallengeScoreWeights.TOTAL_POINTS)

    /** The title this score earns (Step 11.7). */
    val title: ChallengeTitle get() = ChallengeTitle.forScore(total)

    companion object {
        /** The score of a season that has done nothing yet. */
        val ZERO = ChallengeScore(0, 0, 0, 0)
    }
}

/** The rank a score earns (Step 11.7). */
enum class ChallengeTitle {
    ROOKIE,
    TRADER,
    PRO,
    GOLD_MASTER,
    ;

    companion object {
        /**
         * The title for [score].
         *
         * The plan's bands are 0-39, 40-64, 65-84 and 85-100, written as a
         * boundary comparison rather than a range check so a score of exactly 40 is
         * unambiguous. Scores outside 0..100 cannot occur — [ChallengeScore.total]
         * clamps — but the bands are ordered so that a future rule set scoring
         * higher still lands somewhere sensible.
         */
        fun forScore(score: Int): ChallengeTitle = when {
            score >= 85 -> GOLD_MASTER
            score >= 65 -> PRO
            score >= 40 -> TRADER
            else -> ROOKIE
        }
    }
}

/**
 * The inputs the score is computed from (Step 11.6).
 *
 * All ratios are expressed as the counts they are made of, never as a `Double`, so
 * the score is exact and reproducible: two seasons with the same trades always
 * score the same, and a test can assert the arithmetic rather than a tolerance.
 */
data class ChallengeScoreInput(
    /** Starting balance in micro-dollars. */
    val startingBalanceMicros: Long,

    /** Peak equity reached, in micro-dollars. */
    val peakEquityMicros: Long,

    /** Equity now. */
    val currentEquityMicros: Long,

    /** Days logged. */
    val tradingDayCount: Int,

    /** Days the daily loss limit was breached. */
    val dailyStrikeCount: Int,

    /**
     * The single best trading day's net PnL, in micro-dollars.
     *
     * Used for the consistency dimension: the plan scores a season whose profit
     * came from one day below one whose profit was spread out.
     */
    val bestDayPnlMicros: Long,

    /** The season's total net profit, in micro-dollars. */
    val totalPnlMicros: Long,

    /** Trades that closed at a loss. */
    val losingTradeCount: Int,

    /** Every closed trade, winning and losing. */
    val closedTradeCount: Int,
)

/**
 * Scores a season out of 100 (Step 11.6).
 *
 * # How each dimension is computed, and why
 *
 * Every dimension is a proportion of a denominator that is itself a plan figure, so
 * the maximum is reached by exactly meeting the plan's rule and cannot be exceeded
 * by overshooting it. That is deliberate: a season that risks 1% per trade is not
 * "more risk-controlled" than one that risks exactly the limit, it is the same
 * score, because the rule is a limit rather than a target to maximise.
 *
 * - **Profit (40).** Progress toward the 15% target, capped at 100%. Computed from
 *   *peak* equity, not current: a season that reached +15% and gave it back still
 *   demonstrated it could make the money, and scoring current equity would make the
 *   score fall as the season went badly, which is the opposite of what a
 *   performance record should do.
 * - **Risk control (30).** Surviving without breaching a limit. A season with no
 *   strikes scores full marks; each strike costs a third of the dimension, so the
 *   third strike — which fails the challenge anyway — scores zero. The overall
 *   floor is not scored here because breaching it ends the season; it is reflected
 *   in the profit dimension falling back to zero.
 * - **Consistency (20).** The share of the season's profit that did *not* come
 *   from its single best day. `1 - bestDayPnl / totalPnl`, so a season that made
 *   its whole profit in one day scores zero and one that spread it evenly scores
 *   full marks. A season with no profit scores zero, since there is no consistency
 *   in nothing.
 * - **Discipline (10).** The share of trades that did not lose. `1 - losses /
 *   trades`. A season with no closed trades scores zero rather than a free pass.
 *
 * # Why integer arithmetic throughout
 *
 * `AGENTS.md` section 11 and rule 2 forbid `Double` in the domain, and a score is
 * domain state — it is persisted. Every ratio is computed by multiply-then-divide
 * on `Long`s, so the result is exact and the same on every device.
 */
object ChallengeScorer {

    fun score(
        input: ChallengeScoreInput,
        rules: ChallengeRules = ChallengeRules.STANDARD,
    ): ChallengeScore = ChallengeScore(
        profitPoints = profitPoints(input, rules),
        riskControlPoints = riskControlPoints(input, rules),
        consistencyPoints = consistencyPoints(input),
        disciplinePoints = disciplinePoints(input),
    )

    /**
     * Profit: progress to the target, measured on peak equity.
     *
     * `progress = (peak - start) / (target - start)`, clamped to 0..1. A season
     * below its starting balance scores zero rather than negative.
     */
    private fun profitPoints(input: ChallengeScoreInput, rules: ChallengeRules): Int {
        val start = input.startingBalanceMicros
        val target = rules.profitTargetMicros(start)
        val span = target - start
        if (span <= 0L) return ChallengeScoreWeights.PROFIT
        val gained = input.peakEquityMicros - start
        if (gained <= 0L) return 0
        val progress = CheckedMath.multiplyDivide(gained, 10_000L, span).coerceIn(0L, 10_000L)
        return (progress * ChallengeScoreWeights.PROFIT / 10_000L).toInt()
    }

    /**
     * Risk control: the strike allowance not yet used.
     *
     * `1 - strikes / maxStrikes`, so the plan's third strike scores zero.
     */
    private fun riskControlPoints(input: ChallengeScoreInput, rules: ChallengeRules): Int {
        val allowed = rules.maxDailyStrikes
        if (allowed <= 0) return ChallengeScoreWeights.RISK_CONTROL
        val remaining = (allowed - input.dailyStrikeCount).coerceAtLeast(0)
        return remaining * ChallengeScoreWeights.RISK_CONTROL / allowed
    }

    /**
     * Consistency: the share of profit that came from days other than the best one.
     *
     * Zero when there is no profit: a season that has not made money has not
     * demonstrated that it can make it consistently.
     */
    private fun consistencyPoints(input: ChallengeScoreInput): Int {
        val total = input.totalPnlMicros
        if (total <= 0L) return 0
        val fromBestDay = input.bestDayPnlMicros.coerceIn(0L, total)
        val rest = total - fromBestDay
        val share = CheckedMath.multiplyDivide(rest, 10_000L, total).coerceIn(0L, 10_000L)
        return (share * ChallengeScoreWeights.CONSISTENCY / 10_000L).toInt()
    }

    /**
     * Discipline: the share of closed trades that did not lose.
     *
     * Zero when nothing has been traded, so an idle season cannot out-score one
     * that traded and lost: both score zero on this dimension, which is the honest
     * reading. The dimension measures how well the trader handled their trades, and
     * no trades means the question does not yet have an answer.
     */
    private fun disciplinePoints(input: ChallengeScoreInput): Int {
        val trades = input.closedTradeCount
        if (trades <= 0) return 0
        val wins = (trades - input.losingTradeCount).coerceIn(0, trades)
        return wins * ChallengeScoreWeights.DISCIPLINE / trades
    }
}
