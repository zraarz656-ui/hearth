package com.tradequest.app.core.risk

import com.tradequest.app.core.simulation.calendar.NewYorkTradingCalendar
import com.tradequest.app.core.trading.ContractSpec
import com.tradequest.app.core.trading.MarginMath
import com.tradequest.app.core.trading.RiskDecision
import com.tradequest.app.core.trading.RiskFailureReason
import com.tradequest.app.core.trading.RiskPolicy
import com.tradequest.app.core.trading.RiskSnapshot

/**
 * The prop-firm challenge's risk policy (Steps 6.5-6.10).
 *
 * # What it decides, in order
 *
 * 1. **Rollover.** If the trading date changed, the daily budget resets to the
 *    current equity and the day lock clears. Failure does not clear.
 * 2. **Static floor.** Equity at or below $9,000 fails the challenge outright
 *    (Step 6.10). This is checked before the daily floor because it is terminal:
 *    once failed, a daily strike would be a second, redundant reason.
 * 3. **Daily floor.** Equity at or below 3% of the day's reference equity records
 *    a strike and locks the day (Step 6.9). A third strike fails the challenge.
 * 4. **Stop-out.** Margin level at or below 50% requires liquidation (Step 6.8).
 * 5. **Warning.** Margin level at or below 100% raises a warning (Step 6.7).
 *
 * # Why it is a class over a [RiskLimits] rather than an object
 *
 * The limits are the policy's only configuration, and passing them in keeps the
 * rule arithmetic testable at any account size without a second implementation.
 *
 * # Why the rollover compares epoch days rather than times
 *
 * "Did the day change" is a question about the trading calendar, not about
 * elapsed time. Comparing `NewYorkTradingCalendar.tradingDateOf` values means a
 * dataset that skips a weekend — the normal case — still rolls the day exactly
 * once, and a dataset that spans 17:00 rolls at the right instant regardless of
 * whether the engine was advanced across it in one call or many.
 */
class PropFirmRiskPolicy(
    val limits: RiskLimits = RiskLimits.PROP_FIRM_STANDARD,
) : RiskPolicy {

    override fun evaluate(snapshot: RiskSnapshot): RiskDecision {
        val currentDay = NewYorkTradingCalendar.tradingDateOf(snapshot.atSimUtcMs).toEpochDay()

        // 1. Rollover. The day reference is the equity *at* the rollover, so the
        //    new budget is 3% of what the account was actually worth then.
        val rolled = currentDay != snapshot.dayEpochDay
        val dayStartEquity = if (rolled) snapshot.equityMicros else snapshot.dayStartEquityMicros
        val dayEpochDay = if (rolled) currentDay else snapshot.dayEpochDay
        val dayLockedAfterRoll = if (rolled) false else snapshot.dayLocked

        // A failed challenge never resumes, even at a new day.
        if (snapshot.challengeFailed) {
            return RiskDecision(
                dayStartEquityMicros = dayStartEquity,
                dayEpochDay = dayEpochDay,
                dailyLossStrikes = snapshot.dailyLossStrikes,
                dayLocked = true,
                stopOutRequired = false,
                stopOutOrder = emptyList(),
                marginWarning = false,
                newDay = rolled,
                dailyFloorBreached = false,
                dailyFloorMicros = dailyFloor(dayStartEquity),
            equityMicros = snapshot.equityMicros,
                failureReason = null,
            )
        }

        val stopOut = snapshot.marginLevelPercent != null &&
            snapshot.marginLevelPercent <= limits.stopOutPercent
        val warning = snapshot.marginLevelPercent != null &&
            snapshot.marginLevelPercent <= limits.marginWarningPercent
        val stopOutOrder = if (stopOut) stopOutOrderIds(snapshot) else emptyList()

        // 2. Static floor: terminal, checked before the daily budget.
        if (RiskRules.staticFloorBreached(snapshot.equityMicros, limits)) {
            return RiskDecision(
                dayStartEquityMicros = dayStartEquity,
                dayEpochDay = dayEpochDay,
                dailyLossStrikes = snapshot.dailyLossStrikes,
                dayLocked = true,
                stopOutRequired = stopOut,
                stopOutOrder = stopOutOrder,
                marginWarning = warning,
                newDay = rolled,
                dailyFloorBreached = false,
                dailyFloorMicros = dailyFloor(dayStartEquity),
            equityMicros = snapshot.equityMicros,
                failureReason = RiskFailureReason.STATIC_FLOOR_BREACHED,
            )
        }

        // 3. Daily floor. One strike per day: a day already locked does not strike
        //    again, or a single bad day would exhaust the allowance on its own.
        val dailyBreach = !dayLockedAfterRoll &&
            RiskRules.dailyFloorBreached(snapshot.equityMicros, dayStartEquity, limits)
        val strikes = if (dailyBreach) snapshot.dailyLossStrikes + 1 else snapshot.dailyLossStrikes
        val dayLocked = dayLockedAfterRoll || dailyBreach
        val strikesExhausted = strikes >= limits.maxDailyLossStrikes

        return RiskDecision(
            dayStartEquityMicros = dayStartEquity,
            dayEpochDay = dayEpochDay,
            dailyLossStrikes = strikes,
            dayLocked = dayLocked,
            stopOutRequired = stopOut,
            stopOutOrder = stopOutOrder,
            marginWarning = warning,
            newDay = rolled,
            dailyFloorBreached = dailyBreach,
            dailyFloorMicros = dailyFloor(dayStartEquity),
            equityMicros = snapshot.equityMicros,
            failureReason = if (strikesExhausted) {
                RiskFailureReason.DAILY_LOSS_STRIKES_EXHAUSTED
            } else {
                null
            },
        )
    }

    /** The day's floor, for the decision and for the event that reports it. */
    private fun dailyFloor(dayStartEquityMicros: Long): Long =
        RiskRules.dailyFloorMicros(dayStartEquityMicros, limits)

    /**
     * The open positions worst-first, as ids (Step 6.8).
     *
     * A position loss is only known once it is marked, and marking is a market
     * fact the engine owns, so the snapshot carries the mark for each open
     * position. A position with no mark falls back to its entry price, which is
     * the neutral choice and cannot invent a loss. The engine acts on this order
     * one position at a time, re-asking after each close.
     */
    private fun stopOutOrderIds(snapshot: RiskSnapshot): List<String> =
        snapshot.openPositions
            .sortedWith(
                compareBy(
                    {
                        MarginMath.unrealizedPnl(
                            position = it,
                            // The engine marks positions at the live exit price; the
                            // snapshot's margin level already reflects that mark, so
                            // the ordering here uses the same mark the engine passed.
                            exitPriceUnits = snapshot.positionMarks[it.positionId]
                                ?: it.openPriceUnits,
                            contract = snapshot.contract,
                        )
                    },
                    { it.positionId },
                ),
            )
            .map { it.positionId }
}
