package com.tradequest.app.core.trading

/**
 * The port the engine uses to enforce account-level risk (Phase 6).
 *
 * # Why this is an interface in `core.trading` rather than a call into `core.risk`
 *
 * The risk rules need to read positions and a contract, so `core.risk` depends on
 * `core.trading`. If the engine called `core.risk` directly, the two packages
 * would depend on each other. Defining the contract here and implementing it
 * there keeps the dependency one-way: `risk -> trading`, never back.
 *
 * It also puts the boundary in the right place. The engine owns *mechanics* —
 * closing a position at a price, charging commission, updating the balance. The
 * policy owns *decisions* — is the margin level too low, has the daily floor
 * been reached, has the challenge failed. A test can drive the engine with a
 * stub policy, and the risk rules can be tested without a market path at all.
 *
 * A null policy means no risk enforcement: the engine behaves as it did in
 * Phase 5, which is what the Phase 5 tests continue to exercise.
 */
interface RiskPolicy {

    /**
     * Decides what the account requires at [snapshot]'s instant.
     *
     * Must be pure. The engine calls it once per price segment, so a policy that
     * mutated state or read a clock would make a replay non-reproducible.
     */
    fun evaluate(snapshot: RiskSnapshot): RiskDecision
}

/**
 * Everything a risk policy needs to decide, taken at one instant.
 *
 * [equityMicros] is `balance + unrealised`, computed by the engine at the
 * current executable prices. It is passed in rather than recomputed by the
 * policy so the engine and the policy cannot disagree about what equity is.
 */
data class RiskSnapshot(
    val atSimUtcMs: Long,
    val equityMicros: Long,
    /** Open positions, at their current remaining size. */
    val openPositions: List<Position>,
    /**
     * The exit-side price each open position is currently marked at, by position id.
     *
     * Passed in so the policy can rank losers without the engine having to hand
     * over the market path. A position missing from the map is marked at its entry
     * price, which is the neutral choice and cannot invent a loss or a gain.
     */
    val positionMarks: Map<String, Int>,
    /** The contract the positions are priced against. */
    val contract: ContractSpec,
    /**
     * Equity over used margin times 100, or null when nothing is open.
     *
     * The margin itself is not carried: a policy decides from the *level*, and
     * passing the raw figure as well would invite a second, divergent opinion
     * about what the margin is.
     */
    val marginLevelPercent: Long?,
    /** Equity at the current day's rollover. */
    val dayStartEquityMicros: Long,
    /** The trading date the current budget belongs to, as an epoch day. */
    val dayEpochDay: Long,
    val dailyLossStrikes: Int,
    val dayLocked: Boolean,
    val challengeFailed: Boolean,
)

/**
 * What the policy decided, and the day state it decided it in.
 *
 * The policy returns the day fields rather than mutating them, because the
 * engine owns the state and must be able to record a new value only when it has
 * actually acted on the decision. Returning them also makes the rollover
 * explicit: the engine can see that the day changed in the same call that
 * produced the new reference equity.
 */
data class RiskDecision(
    val dayStartEquityMicros: Long,
    val dayEpochDay: Long,
    val dailyLossStrikes: Int,
    val dayLocked: Boolean,
    /** True when the account must be liquidated, worst loser first (Step 6.8). */
    val stopOutRequired: Boolean,
    /**
     * Position ids to liquidate, worst unrealised loser first.
     *
     * The policy owns the ordering because "largest unrealised dollar loser" is a
     * risk rule; the engine owns the mechanics of closing one at a market price.
     * The engine closes the first, then asks again, so the repeat-until-restored
     * behaviour of Step 6.8 falls out of re-evaluation rather than a loop that
     * the policy would have to predict the end of.
     */
    val stopOutOrder: List<String>,
    /** True when a margin warning should be shown (Step 6.7). */
    val marginWarning: Boolean,
    /** True when the day rolled over on this evaluation. */
    val newDay: Boolean,
    /** True when the daily floor was reached on this evaluation (Step 6.9). */
    val dailyFloorBreached: Boolean,
    /** The floor that was compared against, so an event can report it without recomputing. */
    val dailyFloorMicros: Long,
    /** The equity this decision was made from, so an event reports the breaching figure. */
    val equityMicros: Long,
    /** Non-null when the challenge has failed (Steps 6.9-6.10). */
    val failureReason: RiskFailureReason?,
) {
    companion object {
        /** The decision for a policy that does nothing, used when none is set. */
        fun inactive(snapshot: RiskSnapshot): RiskDecision = RiskDecision(
            dayStartEquityMicros = snapshot.dayStartEquityMicros,
            dayEpochDay = snapshot.dayEpochDay,
            dailyLossStrikes = snapshot.dailyLossStrikes,
            dayLocked = snapshot.dayLocked,
            stopOutRequired = false,
            stopOutOrder = emptyList(),
            marginWarning = false,
            newDay = false,
            dailyFloorBreached = false,
            dailyFloorMicros = snapshot.dayStartEquityMicros,
            equityMicros = snapshot.equityMicros,
            failureReason = null,
        )
    }
}

/** Why the challenge ended (Steps 6.9-6.10). */
enum class RiskFailureReason {
    /** Three daily-loss breaches (Step 6.9). */
    DAILY_LOSS_STRIKES_EXHAUSTED,

    /** Equity reached the static floor (Step 6.10). */
    STATIC_FLOOR_BREACHED,
}

/** Why the engine refused a command on risk grounds. */
enum class RiskBlockReason {
    /** The daily floor was hit; trading resumes at the next rollover (Step 6.9). */
    DAY_LOCKED,

    /** The challenge has failed (Steps 6.9-6.10). */
    CHALLENGE_FAILED,
}
