package com.tradequest.app.core.simulation.warp

import com.tradequest.app.core.simulation.clock.ClockMode

/**
 * Whether Time Warp is allowed right now (Step 12.7).
 *
 * # The rule
 *
 * In **Challenge Mode** a warp is refused while any position is open or any
 * order is pending. In **Practice Mode** it is always allowed, and the
 * intervening market and trading events are processed either way.
 *
 * # Why the restriction exists at all
 *
 * A challenge is a test of trading, and jumping the clock is not trading. If a
 * warp were allowed with a position open, the position would be carried across
 * the skipped interval, which is exactly the situation the rule prevents: the
 * player would have skipped the part where they were supposed to manage the
 * trade. Practice Mode has no such stake, so it permits the warp and the engine
 * simply processes what happened while time moved.
 *
 * # Why this returns a reason rather than a boolean
 *
 * The UI must explain *why* the button is disabled, and "you have an open
 * position" and "you have a pending order" are different messages. A boolean
 * would force the caller to re-derive the reason, and a re-derived reason is a
 * second implementation that can disagree with the decision.
 */
sealed interface WarpPermission {

    /** The warp may proceed. */
    data object Allowed : WarpPermission

    /**
     * Refused: Challenge Mode with exposure.
     *
     * [openPositionCount] and [pendingOrderCount] are reported separately so the
     * message can name what is actually blocking.
     */
    data class RefusedExposure(
        val openPositionCount: Int,
        val pendingOrderCount: Int,
    ) : WarpPermission {
        /** A sentence naming what must be closed before warping. */
        val reason: String
            get() {
                val parts = buildList {
                    if (openPositionCount > 0) {
                        add(
                            if (openPositionCount == 1) {
                                "1 open position"
                            } else {
                                "$openPositionCount open positions"
                            },
                        )
                    }
                    if (pendingOrderCount > 0) {
                        add(
                            if (pendingOrderCount == 1) {
                                "1 pending order"
                            } else {
                                "$pendingOrderCount pending orders"
                            },
                        )
                    }
                }
                return "Close ${parts.joinToString(" and ")} before warping in a challenge."
            }
    }

    companion object {
        /**
         * Decides whether a warp is permitted.
         *
         * [mode] is the season's mode; [openPositionCount] and
         * [pendingOrderCount] are read from the live session at the moment the
         * user asks. Both counts are taken rather than a single "is flat" flag so
         * the refusal can say which one applies.
         */
        fun evaluate(
            mode: ClockMode,
            openPositionCount: Int,
            pendingOrderCount: Int,
        ): WarpPermission =
            if (mode != ClockMode.CHALLENGE) {
                Allowed
            } else if (openPositionCount > 0 || pendingOrderCount > 0) {
                RefusedExposure(
                    openPositionCount = openPositionCount.coerceAtLeast(0),
                    pendingOrderCount = pendingOrderCount.coerceAtLeast(0),
                )
            } else {
                Allowed
            }
    }
}
