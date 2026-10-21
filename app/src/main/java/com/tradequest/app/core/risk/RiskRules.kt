package com.tradequest.app.core.risk

import com.tradequest.app.core.accounting.AccountMath
import com.tradequest.app.core.accounting.CheckedMath
import com.tradequest.app.core.accounting.MoneyScale

/**
 * The challenge's risk parameters (Steps 6.5-6.10, AGENTS.md section 12).
 *
 * Held as a value rather than as constants so a test can state the account it is
 * describing, and so a future challenge tier can differ without editing the
 * rules. The defaults are the plan's figures and are the only ones the app uses.
 */
data class RiskLimits(
    /** Daily loss limit as a percentage of the day's starting equity. The plan: 3%. */
    val dailyLossPercent: Int = 3,

    /** Absolute equity floor for the account. The plan: $9,000. */
    val staticFloorMicros: Long = 9_000L * MoneyScale.MICROS_PER_USD,

    /** Margin level at which the user is warned. The plan: 100%. */
    val marginWarningPercent: Int = 100,

    /** Margin level at which positions are liquidated. The plan: 50%. */
    val stopOutPercent: Int = 50,

    /** Daily-loss breaches allowed before the challenge fails. The plan: 3. */
    val maxDailyLossStrikes: Int = 3,
) {
    init {
        require(dailyLossPercent in 0..100) { "daily loss percent must be 0..100" }
        require(marginWarningPercent > 0) { "margin warning percent must be positive" }
        require(stopOutPercent in 1 until marginWarningPercent) {
            "stop-out must sit below the warning, otherwise the warning can never precede it"
        }
        require(maxDailyLossStrikes > 0) { "there must be at least one allowed strike" }
    }

    companion object {
        /** The plan's challenge parameters (AGENTS.md section 12). */
        val PROP_FIRM_STANDARD: RiskLimits = RiskLimits()
    }
}

object RiskRules {

    /**
     * The equity below which the day is lost (Step 6.9).
     *
     * `dayStartEquity * (100 - percent) / 100`, by multiply-then-divide so the
     * intermediate is never truncated.
     */
    fun dailyFloorMicros(dayStartEquityMicros: Long, limits: RiskLimits): Long =
        CheckedMath.multiplyDivide(
            dayStartEquityMicros,
            100L - limits.dailyLossPercent,
            100L,
        )

    /** True when [equityMicros] has reached the day's floor. */
    fun dailyFloorBreached(
        equityMicros: Long,
        dayStartEquityMicros: Long,
        limits: RiskLimits,
    ): Boolean = AccountMath.atOrBelow(
        equityMicros,
        dailyFloorMicros(dayStartEquityMicros, limits),
    )

    /**
     * True when [equityMicros] has reached the account's static floor (Step 6.10).
     *
     * The plan states a flat $9,000 rather than a percentage, and
     * [RiskLimits.staticFloorMicros] holds that figure directly. Keeping the
     * absolute floor as the authority means the account fails at $9,000 whatever
     * the starting balance was.
     */
    fun staticFloorBreached(equityMicros: Long, limits: RiskLimits): Boolean =
        AccountMath.atOrBelow(equityMicros, limits.staticFloorMicros)
}
