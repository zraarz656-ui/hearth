package com.tradequest.app.core.challenge

import com.tradequest.app.core.trading.AccountState
import com.tradequest.app.core.trading.ClosedTrade
import com.tradequest.app.core.trading.TradingState

/**
 * Builds a [ChallengeProgress] from the engine's state and a season's facts.
 *
 * # Why this is separate from [ChallengeCoordinator]
 *
 * The coordinator is pure arithmetic over a value it is handed. This is the
 * *reading* — turning a `TradingState` and a `ChallengeSeasonFacts` into the one
 * value the coordinator understands. Keeping them apart means the rules can be
 * tested with a hand-written progress, and this adapter can be tested against a
 * real engine state, without either test needing the other's fixtures.
 *
 * # Why the day ledger is derived here and not stored
 *
 * See [TradingDayLedger]: the day count is a pure function of the closed trades, so
 * there is no counter to keep in step and nothing to migrate. The cost is that this
 * re-derives the aggregate on each call, which is bounded by the season's own trade
 * count — a figure a user traded by hand, not an unbounded log.
 */
object ChallengeProgressReader {

    fun read(
        state: TradingState,
        facts: ChallengeSeasonFacts,
        account: AccountState,
        equityMicros: Long,
        datasetDisplayName: String,
        atSimUtcMs: Long,
        atRealUtcMs: Long,
    ): ChallengeProgress {
        val closed = state.closedTrades
        val summaries = TradingDayLedger.summarize(
            closedTradeTimesUtcMs = closed.map { it.closeTimeSimUtcMs },
            netPnlMicros = closed.map { it.netPnlMicros },
        )
        return ChallengeProgress(
            seasonId = facts.seasonId,
            datasetId = facts.datasetId,
            datasetDisplayName = datasetDisplayName,
            mode = facts.mode,
            startingBalanceMicros = facts.startingBalanceMicros,
            balanceMicros = account.balanceMicros,
            equityMicros = equityMicros,
            peakEquityMicros = maxOf(facts.peakEquityMicros, equityMicros),
            tradingDayCount = TradingDayLedger.tradingDayCount(summaries),
            dailyStrikeCount = state.dailyLossStrikes,
            engineFailed = state.challengeFailed,
            bestDayPnlMicros = TradingDayLedger.bestDayPnlMicros(summaries),
            dailyStartEquityMicros = state.dayStartEquityMicros,
            totalPnlMicros = TradingDayLedger.totalPnlMicros(summaries),
            losingTradeCount = closed.count { it.netPnlMicros < 0L },
            closedTradeCount = closed.size,
            startedAtSimUtcMs = facts.startedAtSimUtcMs,
            atSimUtcMs = atSimUtcMs,
            atRealUtcMs = atRealUtcMs,
        )
    }

    /** True when [trade] lost money, net of commission. */
    fun isLoss(trade: ClosedTrade): Boolean = trade.netPnlMicros < 0L
}
