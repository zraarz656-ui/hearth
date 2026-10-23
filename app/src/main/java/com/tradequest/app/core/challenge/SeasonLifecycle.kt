package com.tradequest.app.core.challenge

/**
 * Which rule set a season runs under.
 *
 * # Why this moved out of `core.database.player`
 *
 * `AGENTS.md` section 3.4 and `docs/architecture.md` rule 2 make the challenge
 * domain pure Kotlin, and the season's *status* is a challenge concept — it is
 * what a challenge history is a list of. Declaring it in the database layer made
 * `core.challenge` unable to name its own subject without importing Room, which
 * inverts the dependency the layering exists to enforce. The database layer now
 * imports these instead, which is the correct direction.
 */
enum class SeasonMode {
    /** Prop-firm rules apply: target, daily loss limit, strikes, scoring. */
    CHALLENGE,

    /** Free play. The same engine, but no pass/fail and no score. */
    PRACTICE,
}

/**
 * A season's lifecycle state.
 *
 * `ACTIVE` is the only state a season can trade in, and at most one season may be
 * `ACTIVE` at a time (Step 11.2). The other three are terminal and are what the
 * history screen lists.
 */
enum class SeasonStatus {
    ACTIVE,

    /** The profit target was reached with the minimum trading days satisfied. */
    PASSED,

    /** A loss limit was breached: the overall floor, or the third daily strike. */
    FAILED,

    /** Superseded by a new challenge before it finished (Step 11.3). */
    ABANDONED,
    ;

    /** True when the season will never trade again. */
    val isTerminal: Boolean get() = this != ACTIVE
}
