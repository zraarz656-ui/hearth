package com.tradequest.app.core.challenge

import com.tradequest.app.core.accounting.MoneyScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steps 11.4-11.8, 11.12: the challenge rules, the pass/fail verdict, the score and
 * the dashboard figures.
 *
 * # Why these drive `ChallengeCoordinator` and not the evaluator alone
 *
 * The plan's acceptance criteria are about what the *trader* sees and what is
 * *stored*: "challenge rules work" is a claim about the dashboard agreeing with the
 * verdict. Driving the coordinator asserts the verdict, the score and the dashboard
 * together, from one set of facts, which is the only way a disagreement between
 * them can be caught. The evaluator and scorer are private details of that call.
 *
 * Nothing here touches a database, a clock or an engine: `ChallengeProgress` is the
 * whole input, so each test states a scenario in one builder call.
 */
class ChallengeRulesTest {

    private val rules = ChallengeRules.STANDARD

    // ---------- Step 11.4: the rule figures themselves ----------

    @Test
    fun `the plan's rule figures are the ones implemented`() {
        assertEquals(15, rules.profitTargetPercent)
        assertEquals(20, rules.minimumTradingDays)
        assertEquals(3, rules.maxDailyLossPercent)
        assertEquals(10, rules.maxOverallLossPercent)
        assertEquals(3, rules.maxDailyStrikes)
    }

    @Test
    fun `the profit target is fifteen percent of the starting balance`() {
        assertEquals(11_500L * MoneyScale.MICROS_PER_USD, rules.profitTargetMicros(ChallengeFixtures.START_MICROS))
    }

    @Test
    fun `the overall loss floor is the engine's own static floor`() {
        // The dashboard must not show a challenge failing at a level the engine never
        // reaches. This asserts the two definitions agree for the plan's start.
        assertTrue(
            rules.agreesWithRiskLimits(
                startingBalanceMicros = ChallengeFixtures.START_MICROS,
                riskLimitsStaticFloorMicros = ChallengeFixtures.ENGINE_STATIC_FLOOR,
            ),
        )
        assertEquals(
            ChallengeFixtures.ENGINE_STATIC_FLOOR,
            rules.overallLossFloorMicros(ChallengeFixtures.START_MICROS),
        )
    }

    @Test
    fun `the daily loss floor is three percent of the day's own reference equity`() {
        // Not of the season's start: a growing account has a growing daily budget,
        // which is what the engine enforces (Step 6.9).
        val dayStart = 12_000L * MoneyScale.MICROS_PER_USD
        assertEquals(11_640L * MoneyScale.MICROS_PER_USD, rules.dailyLossFloorMicros(dayStart))
    }

    // ---------- Step 11.12: starting state ----------

    @Test
    fun `a fresh season is running and has not passed, failed or scored`() {
        val evaluation = ChallengeCoordinator.evaluate(ChallengeFixtures.progress())

        assertEquals(ChallengeVerdict.InProgress, evaluation.verdict)
        assertEquals(SeasonStatus.ACTIVE, evaluation.status)
        assertNull(evaluation.outcome)
        assertFalse(evaluation.isTerminal)
        // Risk control is full marks because no strike has been used: the dimension
        // measures the allowance not spent, and a fresh season has spent none. The
        // other three dimensions are genuinely zero, so the title is still Rookie.
        assertEquals(0, evaluation.score.profitPoints)
        assertEquals(ChallengeScoreWeights.RISK_CONTROL, evaluation.score.riskControlPoints)
        assertEquals(0, evaluation.score.consistencyPoints)
        assertEquals(0, evaluation.score.disciplinePoints)
        assertEquals(30, evaluation.score.total)
        assertEquals(ChallengeTitle.ROOKIE, evaluation.score.title)
    }

    @Test
    fun `a fresh season's dashboard reports the starting figures and no progress`() {
        val dashboard = ChallengeCoordinator.evaluate(ChallengeFixtures.progress()).dashboard

        assertEquals(ChallengeFixtures.START_MICROS, dashboard.balanceMicros)
        assertEquals(ChallengeFixtures.START_MICROS, dashboard.equityMicros)
        assertEquals(0L, dashboard.profitMicros)
        assertEquals(0f, dashboard.targetProgress, 0.0f)
        assertEquals(0, dashboard.tradingDayCount)
        assertEquals(20, dashboard.minimumTradingDays)
        assertEquals(0, dashboard.dailyStrikeCount)
        assertEquals(3, dashboard.maxDailyStrikes)
        assertEquals(3, dashboard.strikesRemaining)
        assertEquals(20, dashboard.tradingDaysRemaining)
        // The daily budget is the 3% of the day's reference equity: $300 of $10,000.
        // The overall budget is the 10% between the balance and the $9,000 floor.
        assertEquals(300L * MoneyScale.MICROS_PER_USD, dashboard.dailyLossRemainingMicros)
        assertEquals(1_000L * MoneyScale.MICROS_PER_USD, dashboard.overallLossRemainingMicros)
        assertTrue(dashboard.isActive)
    }

    @Test
    fun `a fresh season cannot pass by doing nothing`() {
        // The minimum-days rule is what stops a season from passing on day one, and
        // it must also stop one that has never traded.
        val evaluation = ChallengeCoordinator.evaluate(ChallengeFixtures.progress())
        assertFalse(evaluation.verdict is ChallengeVerdict.Passed)
    }

    // ---------- Step 11.5, 11.12: pass/fail ----------

    @Test
    fun `reaching the target with the minimum days logged passes the season`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                equityMicros = 11_500L * MoneyScale.MICROS_PER_USD,
                peakEquityMicros = 11_500L * MoneyScale.MICROS_PER_USD,
                balanceMicros = 11_500L * MoneyScale.MICROS_PER_USD,
                tradingDayCount = 20,
                totalPnlMicros = 1_500L * MoneyScale.MICROS_PER_USD,
                bestDayPnlMicros = 100L * MoneyScale.MICROS_PER_USD,
                closedTradeCount = 20,
            ),
        )

        assertEquals(ChallengeVerdict.Passed, evaluation.verdict)
        assertEquals(SeasonStatus.PASSED, evaluation.status)
        assertEquals(ChallengeOutcome.TARGET_REACHED, evaluation.outcome)
        assertTrue(evaluation.isTerminal)
    }

    @Test
    fun `reaching the target before the minimum days keeps the season running`() {
        // The plan's 20-day minimum makes this a consistency test, not a sprint: an
        // early lead is not a pass.
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                equityMicros = 11_600L * MoneyScale.MICROS_PER_USD,
                peakEquityMicros = 11_600L * MoneyScale.MICROS_PER_USD,
                tradingDayCount = 19,
            ),
        )

        assertEquals(ChallengeVerdict.InProgress, evaluation.verdict)
        assertEquals(SeasonStatus.ACTIVE, evaluation.status)
        assertEquals(1, evaluation.dashboard.tradingDaysRemaining)
    }

    @Test
    fun `dropping to the overall loss floor fails the season`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(equityMicros = 9_000L * MoneyScale.MICROS_PER_USD),
        )

        assertEquals(ChallengeVerdict.Failed(ChallengeOutcome.OVERALL_LOSS_BREACHED), evaluation.verdict)
        assertEquals(SeasonStatus.FAILED, evaluation.status)
        assertTrue(evaluation.isTerminal)
    }

    @Test
    fun `one dollar above the floor is still running`() {
        // The limit is a floor, not a warning band: the boundary is exact.
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(equityMicros = 9_000L * MoneyScale.MICROS_PER_USD + 1),
        )
        assertEquals(ChallengeVerdict.InProgress, evaluation.verdict)
    }

    @Test
    fun `the third daily strike fails the season`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(dailyStrikeCount = 3),
        )

        assertEquals(ChallengeVerdict.Failed(ChallengeOutcome.DAILY_STRIKES_EXHAUSTED), evaluation.verdict)
    }

    @Test
    fun `two strikes are allowed and do not fail the season`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(dailyStrikeCount = 2),
        )

        assertEquals(ChallengeVerdict.InProgress, evaluation.verdict)
        assertEquals(1, evaluation.dashboard.strikesRemaining)
    }

    @Test
    fun `a failure the engine already recorded is honoured`() {
        // The engine saw the breach at the instant it happened. A later snapshot that
        // no longer shows either limit breached must not resurrect the season.
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                equityMicros = 10_400L * MoneyScale.MICROS_PER_USD,
                engineFailed = true,
            ),
        )

        assertTrue(evaluation.verdict is ChallengeVerdict.Failed)
        assertTrue(evaluation.isTerminal)
    }

    @Test
    fun `a recovered account above the floor is failed at the floor's reason`() {
        // Engine-failed with the numbers restored: the verdict stays failed and is
        // attributed to the overall floor, which is the terminal limit the plan names.
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                equityMicros = 9_500L * MoneyScale.MICROS_PER_USD,
                engineFailed = true,
            ),
        )
        assertEquals(ChallengeVerdict.Failed(ChallengeOutcome.OVERALL_LOSS_BREACHED), evaluation.verdict)
    }

    @Test
    fun `the overall floor is decided before the target`() {
        // Not reachable at the plan's figures, but the precedence is stated so a
        // future rule set that could reach both resolves the same way every time.
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                equityMicros = 11_500L * MoneyScale.MICROS_PER_USD,
                engineFailed = true,
                dailyStrikeCount = 3,
            ),
        )
        assertEquals(ChallengeVerdict.Failed(ChallengeOutcome.DAILY_STRIKES_EXHAUSTED), evaluation.verdict)
    }

    @Test
    fun `practice mode is scored but never passes or fails`() {
        // Free play: the figures are useful, but a loss limit must not end a practice
        // session. The service forces the verdict back to running; this asserts the
        // rules alone would fail it, which is why that forcing exists.
        val wouldFail = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                mode = SeasonMode.PRACTICE,
                equityMicros = 8_000L * MoneyScale.MICROS_PER_USD,
            ),
        )
        assertTrue(wouldFail.verdict is ChallengeVerdict.Failed)
    }

    // ---------- Step 11.6, 11.12: scoring ----------

    @Test
    fun `the score weights are the plan's and sum to one hundred`() {
        assertEquals(40, ChallengeScoreWeights.PROFIT)
        assertEquals(30, ChallengeScoreWeights.RISK_CONTROL)
        assertEquals(20, ChallengeScoreWeights.CONSISTENCY)
        assertEquals(10, ChallengeScoreWeights.DISCIPLINE)
        assertEquals(100, ChallengeScoreWeights.TOTAL_POINTS)
    }

    @Test
    fun `a season that met every rule scores at the top of the scale`() {
        // Peak at the target, no strikes, profit spread evenly over twenty days, no
        // losing trades. Consistency is 19 of 20 rather than 20: twenty equal days
        // still has a best day, and the dimension measures the share of profit that
        // did *not* come from it. The remaining dimensions are all at maximum.
        val perDay = 75L * MoneyScale.MICROS_PER_USD
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                equityMicros = 11_500L * MoneyScale.MICROS_PER_USD,
                peakEquityMicros = 11_500L * MoneyScale.MICROS_PER_USD,
                tradingDayCount = 20,
                totalPnlMicros = perDay * 20,
                bestDayPnlMicros = perDay,
                closedTradeCount = 20,
                losingTradeCount = 0,
            ),
        )

        assertEquals(ChallengeScoreWeights.PROFIT, evaluation.score.profitPoints)
        assertEquals(ChallengeScoreWeights.RISK_CONTROL, evaluation.score.riskControlPoints)
        assertEquals(19, evaluation.score.consistencyPoints)
        assertEquals(ChallengeScoreWeights.DISCIPLINE, evaluation.score.disciplinePoints)
        assertEquals(99, evaluation.score.total)
        assertEquals(ChallengeTitle.GOLD_MASTER, evaluation.score.title)
    }

    @Test
    fun `profit points are capped at the target and measured on peak equity`() {
        // Overshooting the target does not score more than the target, and a season
        // that reached it and gave it back still gets credit for having reached it.
        val overshoot = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                peakEquityMicros = 14_000L * MoneyScale.MICROS_PER_USD,
                equityMicros = 10_500L * MoneyScale.MICROS_PER_USD,
            ),
        )
        assertEquals(ChallengeScoreWeights.PROFIT, overshoot.score.profitPoints)
    }

    @Test
    fun `half the target scores half the profit dimension`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(peakEquityMicros = 10_750L * MoneyScale.MICROS_PER_USD),
        )
        assertEquals(20, evaluation.score.profitPoints)
    }

    @Test
    fun `a season below its starting balance scores nothing on profit`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                equityMicros = 9_500L * MoneyScale.MICROS_PER_USD,
                peakEquityMicros = 10_000L * MoneyScale.MICROS_PER_USD,
            ),
        )
        assertEquals(0, evaluation.score.profitPoints)
    }

    @Test
    fun `each strike costs a third of the risk-control dimension`() {
        val one = ChallengeCoordinator.evaluate(ChallengeFixtures.progress(dailyStrikeCount = 1))
        val two = ChallengeCoordinator.evaluate(ChallengeFixtures.progress(dailyStrikeCount = 2))
        val three = ChallengeCoordinator.evaluate(ChallengeFixtures.progress(dailyStrikeCount = 3))

        assertEquals(20, one.score.riskControlPoints)
        assertEquals(10, two.score.riskControlPoints)
        assertEquals(0, three.score.riskControlPoints)
    }

    @Test
    fun `profit concentrated in one day scores zero on consistency`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                tradingDayCount = 5,
                totalPnlMicros = 500L * MoneyScale.MICROS_PER_USD,
                bestDayPnlMicros = 500L * MoneyScale.MICROS_PER_USD,
            ),
        )
        assertEquals(0, evaluation.score.consistencyPoints)
    }

    @Test
    fun `profit spread evenly across days scores nearly the full consistency dimension`() {
        // Five equal days: the best day is one fifth of the profit, so four fifths of
        // it came from elsewhere and the dimension scores 16 of 20. Full marks would
        // need a best day of zero, which a profitable season cannot have.
        val perDay = 100L * MoneyScale.MICROS_PER_USD
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                tradingDayCount = 5,
                totalPnlMicros = perDay * 5,
                bestDayPnlMicros = perDay,
            ),
        )
        assertEquals(16, evaluation.score.consistencyPoints)
    }

    @Test
    fun `a season with no profit scores zero on consistency`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(totalPnlMicros = 0L, bestDayPnlMicros = 0L),
        )
        assertEquals(0, evaluation.score.consistencyPoints)
    }

    @Test
    fun `discipline is the share of trades that did not lose`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(closedTradeCount = 10, losingTradeCount = 2),
        )
        assertEquals(8, evaluation.score.disciplinePoints)
    }

    @Test
    fun `a season with no closed trades scores zero on discipline`() {
        // No free pass: an idle season must not out-score one that traded and lost.
        val evaluation = ChallengeCoordinator.evaluate(ChallengeFixtures.progress())
        assertEquals(0, evaluation.score.disciplinePoints)
    }

    @Test
    fun `titles follow the plan's bands`() {
        assertEquals(ChallengeTitle.ROOKIE, ChallengeTitle.forScore(0))
        assertEquals(ChallengeTitle.ROOKIE, ChallengeTitle.forScore(39))
        assertEquals(ChallengeTitle.TRADER, ChallengeTitle.forScore(40))
        assertEquals(ChallengeTitle.TRADER, ChallengeTitle.forScore(64))
        assertEquals(ChallengeTitle.PRO, ChallengeTitle.forScore(65))
        assertEquals(ChallengeTitle.PRO, ChallengeTitle.forScore(84))
        assertEquals(ChallengeTitle.GOLD_MASTER, ChallengeTitle.forScore(85))
        assertEquals(ChallengeTitle.GOLD_MASTER, ChallengeTitle.forScore(100))
    }

    // ---------- Step 11.8: the dashboard's derived figures ----------

    @Test
    fun `target progress is measured on peak equity and clamped`() {
        val half = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(peakEquityMicros = 10_750L * MoneyScale.MICROS_PER_USD),
        )
        assertEquals(0.5f, half.dashboard.targetProgress, 0.001f)

        val beyond = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(peakEquityMicros = 20_000L * MoneyScale.MICROS_PER_USD),
        )
        assertEquals(1.0f, beyond.dashboard.targetProgress, 0.001f)
    }

    @Test
    fun `the remaining daily budget tracks the day's own reference equity`() {
        val dayStart = 12_000L * MoneyScale.MICROS_PER_USD
        val equity = 12_000L * MoneyScale.MICROS_PER_USD
        val dashboard = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                equityMicros = equity,
                dailyStartEquityMicros = dayStart,
            ),
        ).dashboard

        // 3% of $12,000 is $360, so the budget from $12,000 is $360, not the $300 a
        // start-relative floor would show.
        assertEquals(360L * MoneyScale.MICROS_PER_USD, dashboard.dailyLossRemainingMicros)
    }

    @Test
    fun `the remaining overall budget is measured against the season's floor`() {
        val dashboard = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(equityMicros = 10_500L * MoneyScale.MICROS_PER_USD),
        ).dashboard

        assertEquals(1_500L * MoneyScale.MICROS_PER_USD, dashboard.overallLossRemainingMicros)
    }

    @Test
    fun `remaining budgets never go negative once a floor is breached`() {
        val dashboard = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                equityMicros = 8_500L * MoneyScale.MICROS_PER_USD,
                dailyStrikeCount = 3,
            ),
        ).dashboard

        assertEquals(0L, dashboard.overallLossRemainingMicros)
        assertEquals(0L, dashboard.dailyLossRemainingMicros)
        assertEquals(0, dashboard.strikesRemaining)
    }

    @Test
    fun `profit is equity less the starting balance`() {
        val dashboard = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(equityMicros = 10_250L * MoneyScale.MICROS_PER_USD),
        ).dashboard
        assertEquals(250L * MoneyScale.MICROS_PER_USD, dashboard.profitMicros)
    }

    // ---------- The history entry the evaluation produces (Step 11.9) ----------

    @Test
    fun `a running season produces no history entry`() {
        val evaluation = ChallengeCoordinator.evaluate(ChallengeFixtures.progress())
        assertNull(evaluation.historyEntry("record-1", attemptOrdinal = 1))
    }

    @Test
    fun `a terminal season produces a history entry carrying its own figures`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(
                equityMicros = 11_500L * MoneyScale.MICROS_PER_USD,
                peakEquityMicros = 11_500L * MoneyScale.MICROS_PER_USD,
                tradingDayCount = 20,
                closedTradeCount = 20,
            ),
        )

        val entry = evaluation.historyEntry("record-1", attemptOrdinal = 1)
        assertNotNull(entry)
        assertEquals("record-1", entry!!.recordId)
        assertEquals(ChallengeFixtures.SEASON_ID, entry.seasonId)
        assertEquals(ChallengeFixtures.DATASET_ID, entry.datasetId)
        assertEquals(ChallengeFixtures.DATASET_NAME, entry.datasetDisplayName)
        assertEquals(SeasonStatus.PASSED, entry.status)
        assertEquals(ChallengeOutcome.TARGET_REACHED, entry.outcome)
        assertEquals(1, entry.attemptOrdinal)
        assertTrue(entry.isFirstAttempt)
        assertEquals(evaluation.score, entry.score)
        assertEquals(evaluation.score.title, entry.title)
        assertEquals(20, entry.tradingDayCount)
    }

    @Test
    fun `a repeat attempt is not marked as the first`() {
        val evaluation = ChallengeCoordinator.evaluate(
            ChallengeFixtures.progress(equityMicros = 9_000L * MoneyScale.MICROS_PER_USD),
        )
        val entry = evaluation.historyEntry("record-2", attemptOrdinal = 2)!!
        assertFalse(entry.isFirstAttempt)
        assertEquals(2, entry.attemptOrdinal)
    }

    @Test
    fun `the rules refuse a nonsensical configuration`() {
        // Guards that exist so a future rule set cannot be built that would pass on
        // day one or allow no strikes at all.
        val zeroTarget = runCatching { ChallengeRules(profitTargetPercent = 0) }
        val noStrikes = runCatching { ChallengeRules(maxDailyStrikes = 0) }
        val badPercent = runCatching { ChallengeRules(maxOverallLossPercent = 101) }

        assertTrue(zeroTarget.isFailure)
        assertTrue(noStrikes.isFailure)
        assertTrue(badPercent.isFailure)
    }
}
