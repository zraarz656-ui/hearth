package com.tradequest.app.core.risk

import com.tradequest.app.core.accounting.CheckedMath
import com.tradequest.app.core.accounting.CommissionSchedule
import com.tradequest.app.core.accounting.MoneyScale
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.MarginMath

/** What a sizing calculation was given, and what it produced (Step 6.11). */
data class PositionSizeRequest(
    /** Money the user is willing to lose on this trade, in micro-dollars. */
    val riskBudgetMicros: Long,
    /** Stop distance from entry, in pips. */
    val stopDistancePips: Int,
    /**
     * Allowance for adverse slippage on entry and exit, in price units.
     *
     * The plan asks for a planned slippage allowance. Making it explicit rather
     * than assuming zero means the sizing is honest about news: a trade planned
     * to risk $100 will not actually risk $140 because it slipped.
     */
    val slippageAllowanceUnits: Int = 0,
)

/** The outcome of a sizing calculation. */
sealed interface PositionSizeResult {
    /** A size the contract accepts. */
    data class Sized(
        val lotUnits: Int,
        /** The money actually at risk at that size, which is at or below the budget. */
        val riskMicros: Long,
        /** True when the ideal size was cut to fit the available margin. */
        val limitedByMargin: Boolean,
        /** True when the ideal size was cut to a whole number of lot steps. */
        val roundedDown: Boolean,
    ) : PositionSizeResult

    /** No valid size exists within the budget. */
    data class TooSmall(
        val reason: String,
        /** The smallest tradable size's risk, so the caller can say how far off it is. */
        val minimumRiskMicros: Long,
    ) : PositionSizeResult
}

/**
 * Position sizing from a planned risk (Step 6.11).
 *
 * # The rule
 *
 * ```
 * risk per lot = price risk over the stop distance
 *              + spread paid on the round trip
 *              + commission on the round trip
 * ```
 *
 * then `lots = riskBudget / riskPerLot`, rounded **down** to a whole lot step,
 * and finally capped by the margin the account can actually support.
 *
 * # Why spread and commission are inside the risk figure
 *
 * A stop at 50 pips does not cost 50 pips of money. The position is entered at
 * the ask and stopped out at the bid, so a long pays the spread as well, and the
 * round trip costs $7 a lot whatever happens. Sizing on the stop distance alone
 * would put every trade over budget by exactly those two amounts — a small,
 * systematic, always-in-the-same-direction error, which is the kind that looks
 * fine in testing and loses money in aggregate.
 *
 * # Why rounding is always down
 *
 * The plan requires that the size never silently exceeds the planned risk.
 * Rounding to nearest would exceed it half the time. Rounding down means the
 * realised risk is at or below the budget, and the shortfall is reported rather
 * than hidden.
 *
 * # Why this never returns a size below the minimum
 *
 * If the budget cannot cover the minimum tradable size, this returns [TooSmall]
 * rather than rounding up to something tradable. Rounding up would exceed the
 * stated risk, which is the one thing the plan forbids. The caller decides
 * whether to refuse the trade or tell the user to widen the stop.
 */
object PositionSizing {

    /**
     * The money at risk for one lot at the given stop, including the round-trip
     * spread and commission.
     *
     * The stop distance is measured on the **exit side**, which is where the stop
     * actually executes, so the spread between the entry and the exit price is
     * added once here rather than being folded into the distance.
     */
    fun riskPerLotMicros(
        stopDistancePips: Int,
        spreadUnits: Int,
        slippageAllowanceUnits: Int,
        contract: ContractSpec,
        commission: CommissionSchedule,
    ): Long {
        val stopUnits = contract.pipsToUnits(stopDistancePips)
        val adverseUnits = CheckedMath.add(
            stopUnits.toLong(),
            CheckedMath.add(spreadUnits.toLong(), slippageAllowanceUnits.toLong()),
        )
        val priceRisk = MoneyScale.pnlMicrosForPriceDelta(
            // The delta is Int in the money helper; the adverse distance is well
            // within Int range for any real stop, and the Long product happens
            // inside the helper.
            deltaUnits = adverseUnits.toInt(),
            lotUnits = MoneyScale.LOT_UNITS_PER_LOT.toInt(),
            ouncesPerLot = contract.ouncesPerLot,
        )
        val commissionRisk = commission.roundTripMicros(
            MoneyScale.LOT_UNITS_PER_LOT.toInt(),
        )
        return CheckedMath.add(priceRisk, commissionRisk)
    }

    /**
     * Sizes a position to a planned risk.
     *
     * @param availableMarginMicros margin headroom, or null when unconstrained.
     */
    fun size(
        request: PositionSizeRequest,
        spreadUnits: Int,
        entryPriceUnits: Int,
        contract: ContractSpec,
        commission: CommissionSchedule,
        availableMarginMicros: Long?,
    ): PositionSizeResult {
        if (request.riskBudgetMicros <= 0) {
            return PositionSizeResult.TooSmall(
                reason = "risk budget must be positive",
                minimumRiskMicros = 0L,
            )
        }
        if (request.stopDistancePips <= 0) {
            return PositionSizeResult.TooSmall(
                reason = "a stop distance is required to size a position",
                minimumRiskMicros = 0L,
            )
        }

        val riskPerLot = riskPerLotMicros(
            stopDistancePips = request.stopDistancePips,
            spreadUnits = spreadUnits,
            slippageAllowanceUnits = request.slippageAllowanceUnits,
            contract = contract,
            commission = commission,
        )
        if (riskPerLot <= 0L) {
            return PositionSizeResult.TooSmall(
                reason = "risk per lot computed as non-positive",
                minimumRiskMicros = 0L,
            )
        }

        val minimumLotUnits = contract.minLotUnits
        val minimumRisk = riskPerLot * minimumLotUnits.toLong() /
            MoneyScale.LOT_UNITS_PER_LOT
        if (minimumRisk > request.riskBudgetMicros) {
            return PositionSizeResult.TooSmall(
                reason = "the minimum size risks more than the budget",
                minimumRiskMicros = minimumRisk,
            )
        }

        // Ideal size in hundredths, then rounded down to a lot step.
        val numerator = request.riskBudgetMicros * MoneyScale.LOT_UNITS_PER_LOT
        val idealLotUnits = numerator / riskPerLot
        val stepped = idealLotUnits - (idealLotUnits % contract.lotStepUnits)
        val rounded = stepped.toInt()
        // Rounding happened when the division left a remainder, or when the lot
        // step cut further. Testing `ideal != stepped` alone would miss the
        // remainder, because `ideal` is already an integer division.
        val wasRounded = numerator % riskPerLot != 0L || idealLotUnits != stepped

        if (rounded < minimumLotUnits) {
            return PositionSizeResult.TooSmall(
                reason = "the budget does not reach the minimum size",
                minimumRiskMicros = minimumRisk,
            )
        }

        var lotUnits = rounded
        var limitedByMargin = false
        if (availableMarginMicros != null) {
            val affordable = affordableLotUnits(
                availableMarginMicros = availableMarginMicros,
                entryPriceUnits = entryPriceUnits,
                contract = contract,
            )
            if (affordable < lotUnits) {
                lotUnits = affordable
                limitedByMargin = true
            }
        }

        if (lotUnits < minimumLotUnits) {
            return PositionSizeResult.TooSmall(
                reason = "available margin does not support the minimum size",
                minimumRiskMicros = minimumRisk,
            )
        }

        val actualRisk = riskPerLot * lotUnits.toLong() / MoneyScale.LOT_UNITS_PER_LOT
        return PositionSizeResult.Sized(
            lotUnits = lotUnits,
            riskMicros = actualRisk,
            limitedByMargin = limitedByMargin,
            roundedDown = wasRounded,
        )
    }

    /**
     * The largest size the available margin supports, rounded down to a lot step.
     *
     * The margin formula is linear in size, so this divides rather than searching.
     * Dividing is exact and cannot loop forever on a pathological input.
     */
    fun affordableLotUnits(
        availableMarginMicros: Long,
        entryPriceUnits: Int,
        contract: ContractSpec,
    ): Int {
        if (availableMarginMicros <= 0L) return 0
        val marginPerLotUnit = MarginMath.marginMicrosFor(
            entryPriceUnits = entryPriceUnits,
            lotUnits = 1,
            ouncesPerLot = contract.ouncesPerLot,
            leverage = contract.leverage,
        )
        // A one-unit margin can round to zero at a very low price, in which case
        // every size is affordable and the cap is the contract maximum.
        if (marginPerLotUnit <= 0L) return contract.maxLotUnits

        val raw = availableMarginMicros / marginPerLotUnit
        val stepped = raw - (raw % contract.lotStepUnits)
        return stepped.coerceAtMost(contract.maxLotUnits.toLong()).toInt()
    }
}
