package com.tradequest.app.core.risk

import com.tradequest.app.core.accounting.CheckedMath
import com.tradequest.app.core.accounting.CommissionSchedule
import com.tradequest.app.core.trading.ContractSpec

/**
 * The share of account equity a trade is allowed to risk (Step 10.6).
 *
 * # Why basis points and not a percentage
 *
 * The money invariant in `AGENTS.md` forbids floating-point money, and 0.25% has
 * no exact binary representation — `0.0025f` is 0.00249999994..., so a budget
 * computed from it is wrong in the last micro-dollar and the error compounds
 * across a challenge. Basis points are exact integers, and every preset the plan
 * names (0.25, 0.50, 1.00, 1.50, 2.00) is a whole number of them.
 *
 * # Why the presets are values and not an enum
 *
 * The plan also asks for a *custom* lock. An enum cannot carry an arbitrary
 * value, and widening it to a data class would make the presets unequal to
 * themselves. A value class keeps one type for both, so a caller that accepts a
 * lock accepts a custom one without a second code path.
 */
@JvmInline
value class RiskLockBps(val bps: Int) {

    init {
        require(bps in MIN_BPS..MAX_BPS) {
            "risk lock must be within $MIN_BPS..$MAX_BPS basis points, got $bps"
        }
    }

    /** This lock as a percentage string, for display. 25 -> "0.25%". */
    val label: String
        get() {
            val whole = bps / 100
            val frac = bps % 100
            return if (frac == 0) "$whole.00%" else "$whole.${frac.toString().padStart(2, '0')}%"
        }

    companion object {
        /** A hundredth of a percent is the smallest lock worth offering. */
        const val MIN_BPS: Int = 1

        /**
         * 100% — the whole account.
         *
         * Not a sane risk setting, but it is the point past which the value stops
         * meaning anything, so it is the bound rather than an arbitrary round
         * number. A caller wanting to refuse anything above the prop-firm cap
         * enforces that separately, because the cap is a policy and this is a
         * range.
         */
        const val MAX_BPS: Int = 10_000

        val QUARTER: RiskLockBps = RiskLockBps(25)
        val HALF: RiskLockBps = RiskLockBps(50)
        val ONE: RiskLockBps = RiskLockBps(100)
        val ONE_AND_HALF: RiskLockBps = RiskLockBps(150)
        val TWO: RiskLockBps = RiskLockBps(200)

        /** The presets Step 10.6 names, in the order the UI shows them. */
        val PRESETS: List<RiskLockBps> = listOf(QUARTER, HALF, ONE, ONE_AND_HALF, TWO)

        /** The lock a new season starts on. 1% is the plan's middle preset. */
        val DEFAULT: RiskLockBps = ONE

        /** A custom lock, validated by the constructor. */
        fun custom(bps: Int): RiskLockBps = RiskLockBps(bps)
    }
}

/**
 * Turns a risk lock into a lot size (Step 10.6).
 *
 * # Why this is a thin wrapper over [PositionSizing]
 *
 * The sizing arithmetic — spread, commission, rounding down, the margin cap —
 * already exists and is tested. This adds only the one thing the lock needs and
 * sizing does not: turning "1% of equity" into the money budget that sizing
 * takes. Duplicating the sizing here would create a second opinion about how big
 * a trade should be, and the two would drift.
 */
object RiskLock {

    /**
     * The money a lock allows at [equityMicros].
     *
     * Multiplies before dividing, so the result is exact for every lock up to
     * 100% and never truncates a micro-dollar that a divide-first shape would
     * lose.
     *
     * A non-positive equity yields a non-positive budget, which [PositionSizing]
     * then refuses as `TooSmall`. That is the right outcome — an account with no
     * equity cannot size a trade — and it happens without a special case here.
     */
    fun riskBudgetMicros(equityMicros: Long, lock: RiskLockBps): Long =
        CheckedMath.multiplyDivide(
            value = equityMicros,
            multiplier = lock.bps.toLong(),
            divisor = BPS_PER_UNIT,
        )

    /**
     * Sizes a position to [lock] of [equityMicros].
     *
     * [stopDistancePips] is the distance from entry to stop as it stands *right
     * now*. A caller recomputing while the user drags the stop passes the new
     * distance each time, which is what makes the lot size track the drag.
     */
    fun size(
        equityMicros: Long,
        lock: RiskLockBps,
        stopDistancePips: Int,
        entryPriceUnits: Int,
        spreadUnits: Int,
        slippageAllowanceUnits: Int,
        contract: ContractSpec,
        commission: CommissionSchedule,
        availableMarginMicros: Long?,
    ): PositionSizeResult = PositionSizing.size(
        request = PositionSizeRequest(
            riskBudgetMicros = riskBudgetMicros(equityMicros, lock),
            stopDistancePips = stopDistancePips,
            slippageAllowanceUnits = slippageAllowanceUnits,
        ),
        spreadUnits = spreadUnits,
        entryPriceUnits = entryPriceUnits,
        contract = contract,
        commission = commission,
        availableMarginMicros = availableMarginMicros,
    )

    /** Basis points in one whole unit, for the percentage conversion. */
    private const val BPS_PER_UNIT: Long = 10_000L
}
