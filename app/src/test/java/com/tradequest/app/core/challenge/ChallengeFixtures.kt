package com.tradequest.app.core.challenge

import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.risk.RiskLimits

/**
 * Fixtures for the Phase 11 challenge tests (Step 11.12).
 *
 * # Why a builder rather than a constructor call per test
 *
 * `ChallengeProgress` has eighteen fields and a test usually cares about two or
 * three of them. Naming every field at each call site would bury the one value the
 * test is about in fifteen that are noise, and a reader could not tell which of
 * them the assertion depends on. The default is the plan's *starting state* — the
 * figures Step 11.1 says a season begins with — so a test that changes nothing
 * gets a running season that has done nothing, and a test that changes one field is
 * plainly about that field.
 *
 * All money is micro-dollars. $10,000 is 10,000 * 1,000,000.
 */
internal object ChallengeFixtures {

    /** The plan's starting balance (Step 11.1). */
    const val START_MICROS: Long = 10_000L * MoneyScale.MICROS_PER_USD

    /** 2024-01-01T00:00:00Z, a Monday. DST-free, matching the other fixtures. */
    const val T0: Long = 1_704_067_200_000L

    const val DAY_MS: Long = 86_400_000L

    const val DATASET_ID: String = "ds_gold"
    const val DATASET_NAME: String = "Gold 2024"
    const val SEASON_ID: String = "season_1"

    /**
     * A running season at its starting state: $10,000, no trades, no days, no
     * strikes.
     *
     * This is the value Step 11.12's "starting state" test asserts against, so it is
     * the default rather than a special case.
     */
    fun progress(
        seasonId: String = SEASON_ID,
        datasetId: String = DATASET_ID,
        datasetDisplayName: String = DATASET_NAME,
        mode: SeasonMode = SeasonMode.CHALLENGE,
        startingBalanceMicros: Long = START_MICROS,
        balanceMicros: Long = START_MICROS,
        equityMicros: Long = START_MICROS,
        peakEquityMicros: Long = START_MICROS,
        tradingDayCount: Int = 0,
        dailyStrikeCount: Int = 0,
        engineFailed: Boolean = false,
        bestDayPnlMicros: Long = 0L,
        dailyStartEquityMicros: Long = START_MICROS,
        totalPnlMicros: Long = 0L,
        losingTradeCount: Int = 0,
        closedTradeCount: Int = 0,
        startedAtSimUtcMs: Long = T0,
        atSimUtcMs: Long = T0 + DAY_MS,
        atRealUtcMs: Long = T0 + DAY_MS,
    ): ChallengeProgress = ChallengeProgress(
        seasonId = seasonId,
        datasetId = datasetId,
        datasetDisplayName = datasetDisplayName,
        mode = mode,
        startingBalanceMicros = startingBalanceMicros,
        balanceMicros = balanceMicros,
        equityMicros = equityMicros,
        peakEquityMicros = peakEquityMicros,
        tradingDayCount = tradingDayCount,
        dailyStrikeCount = dailyStrikeCount,
        engineFailed = engineFailed,
        bestDayPnlMicros = bestDayPnlMicros,
        dailyStartEquityMicros = dailyStartEquityMicros,
        totalPnlMicros = totalPnlMicros,
        losingTradeCount = losingTradeCount,
        closedTradeCount = closedTradeCount,
        startedAtSimUtcMs = startedAtSimUtcMs,
        atSimUtcMs = atSimUtcMs,
        atRealUtcMs = atRealUtcMs,
    )

    /** The plan's $9,000 static floor, as the engine's `RiskLimits` states it. */
    val ENGINE_STATIC_FLOOR: Long = RiskLimits.PROP_FIRM_STANDARD.staticFloorMicros
}
