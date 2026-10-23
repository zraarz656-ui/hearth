package com.tradequest.app.core.trading

import com.tradequest.app.core.accounting.CheckedMath
import com.tradequest.app.core.accounting.MoneyScale

/**
 * A planned trade's three levels, in price units (Step 10.5).
 *
 * # Why entry is required and the others are not
 *
 * A bracket with no entry has no anchor, so nothing can be placed. A bracket with
 * an entry and no stop is a real state — the trader is still deciding — and it
 * must still be drawable, so the stop and target are nullable rather than forced.
 */
data class BracketPlan(
    val side: OrderSide,
    val entryPriceUnits: Int,
    val stopPriceUnits: Int? = null,
    val targetPriceUnits: Int? = null,
)

/**
 * What a bracket's levels mean in money (Step 10.5).
 *
 * Every field is `Long` micro-dollars because the money invariant forbids
 * floating-point money and a reward on a large position exceeds `Int32`. The
 * ratios are the one exception, and they are exact integer pairs rather than a
 * `Float`, for the reason given on [RiskReward].
 */
data class BracketEconomics(
    /** Money lost if the stop is hit. Null when no stop is set. */
    val riskMicros: Long?,
    /** Money gained if the target is hit. Null when no target is set. */
    val rewardMicros: Long?,
    /** The ratio of reward to risk, or null when either leg is missing. */
    val riskReward: RiskReward?,
    /** The pip distance from entry to stop, when a stop exists and is valid. */
    val stopDistancePips: Int?,
    /** The pip distance from entry to target, when a target exists and is valid. */
    val targetDistancePips: Int?,
) {
    /** True when the bracket can actually be submitted: entry, stop and target, all valid. */
    val isComplete: Boolean
        get() = riskMicros != null && rewardMicros != null && riskReward != null
}

/**
 * A reward-to-risk ratio as an exact integer pair (Step 10.5).
 *
 * # Why not a Float
 *
 * The plan asks for "R:R ratio" and a naive `reward / risk` gives 2.9999998 for
 * what is really 3:1, because neither figure is exactly representable. A trader
 * reading "3.0" and a trader reading "3.0" must mean the same trade. Reducing to
 * lowest terms and formatting the pair is exact, so 300:100 and 3:1 print
 * identically and no rounding is involved anywhere.
 *
 * A zero [risk] is refused at construction: an infinite ratio is not a number to
 * show.
 */
data class RiskReward(val reward: Int, val risk: Int) {

    init {
        require(risk > 0) { "a risk/reward ratio needs a positive risk, got $risk" }
        require(reward >= 0) { "a reward cannot be negative, got $reward" }
    }

    /** The reduced pair as a display string, e.g. `"3.0 : 1"` or `"0.5 : 1"`. */
    val label: String
        get() {
            // Normalise to "x : 1" when the risk divides the reward cleanly, which
            // is the form a trader reads. Otherwise show the reduced pair to two
            // decimals, so no information is lost to rounding.
            if (reward % risk == 0) {
                val whole = reward / risk
                return "$whole.0 : 1"
            }
            val g = gcd(reward.toLong(), risk.toLong())
            val r = reward.toLong() / g
            val k = risk.toLong() / g
            return "%.2f : 1".format(r.toDouble() / k.toDouble())
        }

    companion object {

        /**
         * The ratio of [rewardMicros] to [riskMicros], reduced to lowest terms.
         *
         * Both inputs must be positive. A non-positive risk has no ratio, and a
         * non-positive reward is a losing plan rather than a ratio, so both are
         * rejected as null and the caller decides what to show.
         */
        fun of(rewardMicros: Long, riskMicros: Long): RiskReward? {
            if (riskMicros <= 0L || rewardMicros < 0L) return null
            // Reduce before narrowing, so a large money figure cannot overflow Int
            // on the way in.
            val g = gcd(rewardMicros, riskMicros)
            val reward = rewardMicros / g
            val risk = riskMicros / g
            if (reward > Int.MAX_VALUE || risk > Int.MAX_VALUE) return null
            return RiskReward(reward.toInt(), risk.toInt())
        }

        private fun gcd(a: Long, b: Long): Long {
            var x = if (a < 0) -a else a
            var y = if (b < 0) -b else b
            while (y != 0L) {
                val t = y
                y = x % y
                x = t
            }
            return if (x == 0L) 1L else x
        }
    }
}

/**
 * Prices a three-line bracket (Step 10.5).
 *
 * # What "risk" and "reward" mean here
 *
 * The risk is what the position loses if the stop fills and the reward is what it
 * gains if the target fills, both at [lotUnits]. They are computed from the price
 * distance, so they move as the lines are dragged — which is what makes the
 * displayed figures track the bracket rather than being set once.
 *
 * # Why a stop on the wrong side is null rather than negative
 *
 * A buy whose stop is above its entry is not a small stop, it is an invalid one —
 * the engine would fill it immediately. Reporting `riskMicros = null` lets the UI
 * show "—" instead of a negative risk, so a trader never sees "-$120 risk" and
 * has to work out what that means.
 */
object BracketMath {

    /**
     * The economics of [plan] at [lotUnits].
     *
     * [lotUnits] is hundredths of a lot, matching [Position.volumeHundredths].
     */
    fun economics(
        plan: BracketPlan,
        lotUnits: Int,
        contract: ContractSpec,
    ): BracketEconomics {
        val stopDistancePips = plan.stopPriceUnits?.let { stop ->
            TradeOverlay.pipDistanceFor(
                side = plan.side,
                entryPriceUnits = plan.entryPriceUnits,
                levelPriceUnits = stop,
                contract = contract,
                // A stop sits on the losing side, so a correctly-placed one is a
                // positive adverse distance and a stop dragged through entry is
                // negative and dropped below.
                direction = TradeDirection.ADVERSE,
            ).takeIf { it > 0 }
        }
        val targetDistancePips = plan.targetPriceUnits?.let { target ->
            TradeOverlay.pipDistanceFor(
                side = plan.side,
                entryPriceUnits = plan.entryPriceUnits,
                levelPriceUnits = target,
                contract = contract,
                direction = TradeDirection.FAVOURABLE,
            ).takeIf { it > 0 }
        }

        val risk = stopDistancePips?.let {
            moneyForDistance(it, lotUnits, contract)
        }
        val reward = targetDistancePips?.let {
            moneyForDistance(it, lotUnits, contract)
        }

        return BracketEconomics(
            riskMicros = risk,
            rewardMicros = reward,
            riskReward = if (risk != null && reward != null) {
                RiskReward.of(rewardMicros = reward, riskMicros = risk)
            } else {
                null
            },
            stopDistancePips = stopDistancePips,
            targetDistancePips = targetDistancePips,
        )
    }

    /**
     * The money a price move of [distancePips] is worth at [lotUnits].
     *
     * Wraps the price-delta helper rather than re-deriving it, so the bracket and
     * the engine cannot disagree about what a pip is worth.
     */
    fun moneyForDistance(distancePips: Int, lotUnits: Int, contract: ContractSpec): Long =
        MoneyScale.pnlMicrosForPriceDelta(
            deltaUnits = CheckedMath.multiply(
                distancePips.toLong(),
                contract.unitsPerPip.toLong(),
            ).toInt(),
            lotUnits = lotUnits,
            ouncesPerLot = contract.ouncesPerLot,
        )
}
